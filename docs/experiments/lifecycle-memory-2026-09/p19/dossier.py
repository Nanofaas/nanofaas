"""Freeze and validate a content-addressed P19 dossier after collection finishes."""
import argparse
import gzip
import hashlib
import json
import pathlib
import shutil
import statistics
import subprocess
import tarfile
import xml.etree.ElementTree as ET
import zipfile
from contract import summarize, validate_pairs, percentiles

HERE = pathlib.Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def write(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, sort_keys=True, indent=2) + '\n')


def verify(folder):
    manifest_path = folder / 'manifest.json'
    manifest = json.loads(manifest_path.read_text())
    if manifest['schema'] not in ['nanofaas-p19-dossier-v1', 'nanofaas-p19-dossier-v2']:
        raise ValueError('unknown dossier schema')
    if digest(manifest_path) != folder.name:
        raise ValueError('manifest digest/path mismatch')
    expected_sums = ''.join(f"{info['sha256']}  {name}\n" for name, info in manifest['files'].items()) + f'{digest(manifest_path)}  manifest.json\n'
    if (folder / 'SHA256SUMS').read_text() != expected_sums:
        raise ValueError('checksum list mismatch')
    for name, identity in manifest['files'].items():
        path = folder / name
        if '..' in pathlib.PurePosixPath(name).parts or pathlib.PurePosixPath(name).is_absolute():
            raise ValueError('unsafe payload path')
        if path.stat().st_size != identity['bytes'] or digest(path) != identity['sha256']:
            raise ValueError('payload mismatch: ' + name)
    expected = set(manifest['files']) | {'manifest.json', 'SHA256SUMS'}
    if {str(p.relative_to(folder)) for p in folder.rglob('*') if p.is_file()} != expected:
        raise ValueError('unmanifested or missing payload')
    runs = [json.loads((folder / 'runs' / (side + str(i)) / 'run.json').read_text()) for i in range(1, 4) for side in 'AB']
    builds = json.loads((folder/'build-identities.json').read_text())
    validate_pairs(runs, {side: builds[side+'-none.jar'] for side in 'AB'})
    for run in runs:
        if run['schema'] != 'p19-http-run-v1' or not run['valid'] or run['success_latency_ns']['count'] != run['unique_successes']:
            raise ValueError('missing successful latency samples')
        with gzip.open(folder / 'runs' / run['label'] / 'requests.jsonl.gz', 'rt') as stream:
            rows = [json.loads(line) for line in stream]
        rebuilt = summarize(rows, run['offered'], run['elapsed_ns'])
        for field in ['admitted', 'unique_successes', 'unresolved', 'success_latency_ns', 'refused']:
            if rebuilt[field] != run[field]:
                raise ValueError('raw accounting mismatch: ' + field)
        if manifest['schema'] == 'nanofaas-p19-dossier-v2':
            directory = folder/'runs'/run['label']
            if json.loads((directory/'workload.json').read_text()) != run['workload_document']:
                raise ValueError('actual workload file differs')
            config = run['config_document']
            if json.loads((directory/'function.json').read_text()) != config['function']:
                raise ValueError('actual configured function differs')
            java = run['java_command']
            application = [arg if not arg.startswith('--nanofaas.registry.path=') else '--nanofaas.registry.path=<isolated>'
                           for arg in java if arg.startswith('--')]
            if java[1:6] != config['java_options'] or application != config['application_options']:
                raise ValueError('actual Java configuration differs')
            jar_path = pathlib.PurePosixPath(java[11])
            if (len(java) != 13 + len(application) or jar_path.name != run['label'][0]+'-none.jar'
                    or java[7:10] != ['-Dp19.probePort=18082', '-Dloader.main=P19Probe', '-Dloader.path=' + str(jar_path.parent.parent/'probe')]
                    or java[10] != '-cp' or java[12] != 'org.springframework.boot.loader.launch.PropertiesLauncher'):
                raise ValueError('unexpected JVM launcher configuration')
            if digest(folder/'probe/P19Probe.class') != config['environment']['probe_sha256']:
                raise ValueError('compiled observer identity differs')
            for row in rows:
                if not run['start_ns'] <= row['sent_ns'] <= row['done_ns'] <= run['end_ns']:
                    raise ValueError('raw request outside measured chronology')
                if row['latency_ns'] != row['done_ns']-row['sent_ns']:
                    raise ValueError('raw latency differs from observed timestamps')
            for index, row in enumerate(rows):
                if row['intended_ns'] != run['start_ns'] + int(index*1e9/run['workload_document']['rate_per_s']):
                    raise ValueError('actual offered schedule differs')
            points = json.loads((directory/'checkpoints.json').read_text())
            if any(point['pid'] != run['processes']['server']['pid'] or not run['process_start_ns'] <= point['monotonic_ns'] <= run['process_end_ns'] for point in points):
                raise ValueError('checkpoint process or lifetime differs')
            for role in ['server', 'backend']:
                if run[role+'_pid'] != run['processes'][role]['pid']:
                    raise ValueError('process PID binding differs')
            supervision = json.loads((folder/'supervision'/(run['label']+'.json')).read_text())
            if supervision['status'] != 'exited' or supervision['exit_code'] != 0 or supervision['remaining_live_pids'] or supervision['pid'] != run['generator_pid']:
                raise ValueError('supervisor process exit binding differs')
    print('VERIFIED', digest(manifest_path), len(manifest['files']), 'payload files')


def freeze(work):
    review = json.loads((work/'fix-round.json').read_text()) if (work/'fix-round.json').exists() else {}
    staging = work / 'dossier'
    staging.mkdir(exist_ok=False)
    for name in ['runs', 'verification', 'external', 'smoke', 'graph', 'aborted', 'auxiliary', 'profiles', 'supervision', 'probe', 'red-native', 'native-container-cli-unavailable']:
        source = work / name
        if source.exists():
            shutil.copytree(source, staging / name)
    # Native-specific parsers supplement the general Java command recorder.
    verification = []
    for file in sorted((staging / 'verification').glob('*/result.json')):
        record = json.loads(file.read_text())
        label = record['label']
        output = (file.parent / 'output.log').read_text()
        if label == 'python-sdk':
            suite = ET.parse(work / 'verification/python-junit.xml').getroot().find('testsuite')
            record['counts'] = dict(passed=int(suite.get('tests'))-int(suite.get('failures'))-int(suite.get('errors'))-int(suite.get('skipped')),
                                    failed=int(suite.get('failures'))+int(suite.get('errors')), skipped=int(suite.get('skipped')))
        elif label in ['go-sdk', 'go-race']:
            events = [json.loads(line) for line in output.splitlines() if line.startswith('{')]
            record['counts'] = {key: sum(e.get('Action') == action and 'Test' in e for e in events)
                                for key, action in [('passed', 'pass'), ('failed', 'fail'), ('skipped', 'skip')]}
        elif label == 'javascript-sdk':
            record['counts'] = {key: int(next(line.split()[-1] for line in output.splitlines() if line.startswith('# ' + word + ' ')))
                                for key, word in [('passed', 'pass'), ('failed', 'fail'), ('skipped', 'skipped')]}
        elif label == 'wire-validator':
            import re
            record['counts'] = dict(passed=int(re.search(r'Ran (\d+) tests', output)[1]), failed=0, skipped=0)
            assert '\nOK\n' in output
        elif label == 'selector-tests':
            record['cases'] = []
            for xml in sorted((ROOT / 'platform/gradle-plugin/build/test-results/test').glob('TEST-*.xml')):
                shutil.copyfile(xml, file.parent / xml.name)
                for case in ET.parse(xml).getroot().findall('testcase'):
                    state = 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
                    record['cases'].append(dict(classname=case.get('classname'), name=case.get('name'), state=state))
            record['counts'] = {state: sum(c['state'] == state for c in record['cases']) for state in ['passed', 'failed', 'skipped']}
            assert record['counts']['passed'] == 30
        elif not record['cases']:
            record['counts'] = None
        record['counts_status'] = 'observed' if record['counts'] is not None else 'not_applicable: command has no test cases'
        assert record['exit_code'] == record['expected_exit_code'], label
        assert record['counts'] is None or record['counts']['failed'] == 0, label
        if label.startswith('negative-'):
            assert 'FAILURE: Build failed' in output and 'Could not resolve' not in output, label
        write(file, record)
        verification.append({k: record[k] for k in ['label', 'exit_code', 'expected_exit_code', 'counts', 'counts_status']})
    write(staging / 'verification-summary.json', verification)
    for pilot in work.glob('pilot-*'):
        shutil.copytree(pilot, staging / 'pilots' / pilot.name)
    for pattern in ['*.log', '*.json', '*.sha256', '*.out', '*.tar.gz']:
        for source in work.glob(pattern):
            if source.is_file():
                shutil.copyfile(source, staging / source.name)
    with gzip.GzipFile(filename=str(staging / 'harness.tar.gz'), mode='wb', mtime=0) as compressed:
        with tarfile.open(fileobj=compressed, mode='w|') as archive:
            for source in sorted(HERE.iterdir()):
                if not source.is_file() or source.name == 'baseline.json':
                    continue
                info = archive.gettarinfo(str(source), arcname=source.name)
                info.mtime = 0
                info.uid = info.gid = 0
                info.uname = info.gname = ''
                with source.open('rb') as stream:
                    archive.addfile(info, stream)
    shutil.copyfile(HERE / 'observer-dictionary.json', staging / 'observer-dictionary.json')
    shutil.copyfile(HERE / 'protocol.json', staging / 'protocol.json')
    requirements = ROOT / '.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity'
    for name in ['task-P19-brief.md', 'task-P19-matrix-inventory.md', 'task-P19-performance-protocol.md']:
        shutil.copyfile(requirements / name, staging / name)
    identities = {}
    for jar in sorted((work / 'artifacts').glob('*.jar')):
        with zipfile.ZipFile(jar) as archive:
            identities[jar.name] = dict(sha256=digest(jar), bytes=jar.stat().st_size,
                                       entries={name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if not name.endswith('/')})
    write(staging / 'build-identities.json', identities)
    # Only measured binaries are archived; all other packaging identities remain exact.
    # A single xz stream deduplicates identical nested dependencies across these jars.
    with tarfile.open(staging / 'measured-jars.tar.xz', 'w:xz', preset=9) as archive:
        for name in ['A-none.jar', 'B-none.jar', 'B-async-queue.jar']:
            info = archive.gettarinfo(str(work / 'artifacts' / name), arcname=name)
            info.mtime = 0
            info.uid = info.gid = 0
            info.uname = info.gname = ''
            with (work / 'artifacts' / name).open('rb') as source:
                archive.addfile(info, source)
    source_paths = ['.dockerignore', '.gitattributes', '.gitignore', 'README.md', 'build.gradle', 'settings.gradle',
                    'gradle.properties', 'gradlew', 'gradlew.bat', 'gradle', 'platform', 'sdks', 'services', 'functions',
                    'runtimes', 'scripts', 'tools', 'clients', 'openapi', 'deploy']
    for side, revision in [('A', '61d72e73528db62cf8ca465c6a037981d7ec13b0'), ('B', '6d08303371d803f44187ec5f4e37827d54fec597')]:
        available = set(subprocess.check_output(['git', 'ls-tree', '--name-only', revision], cwd=ROOT, text=True).splitlines())
        paths = [path for path in source_paths if path in available]
        archive_bytes = subprocess.check_output(['git', 'archive', revision, *paths], cwd=ROOT)
        with gzip.GzipFile(filename=str(staging / ('build-source-' + side + '.tar.gz')), mode='wb', mtime=0) as stream:
            stream.write(archive_bytes)
    runs = [json.loads((staging / 'runs' / (side + str(i)) / 'run.json').read_text()) for i in range(1, 4) for side in 'AB']
    validate_pairs(runs, {side: identities[side+'-none.jar'] for side in 'AB'})
    table = []
    for run_file in sorted((staging / 'runs').glob('*/run.json')):
        run = json.loads(run_file.read_text())
        assert run['valid'] and run['shutdown']['server_proc_absent'], run['label']
        checkpoints = json.loads((run_file.parent / 'checkpoints.json').read_text())
        assert all(c['gc_verified'] for c in checkpoints)
        if run['revision'].startswith('6d083033'):
            for point in checkpoints:
                for population in ['live', 'execution_reservations', 'canonical_input_bytes', 'physical_input_copy_bytes', 'waiters', 'retained_waiters']:
                    assert point['populations'][population]['value'] == 0, (run['label'], point['phase'], population)
                assert point['observer_terminal_events'] == 0
            removed = checkpoints[-1]['owner_fields']
            for key in ['functionCapacityRegistry.entries.count', 'metrics.registeredFunctions.count', 'metrics.retiringOffloadOwners.count', 'functionRegistry.functions.publicView.count']:
                assert removed[key] == 0, (run['label'], key)
        row = {key: run[key] for key in ['label', 'profile', 'scenario', 'offered', 'admitted', 'refused', 'unique_successes', 'useful_successes_per_s', 'success_latency_ns', 'allocated_bytes_per_success']}
        row['post_gc_heap_bytes'] = {c['phase']: c['heap_used_bytes'] for c in checkpoints}
        with gzip.open(run_file.parent / 'requests.jsonl.gz', 'rt') as stream:
            raw = [json.loads(line) for line in stream]
        row['segments'] = [{'start_index': i, 'success_latency_ns': percentiles([r['latency_ns'] for r in raw[i:i+2000] if r['success']])}
                           for i in range(0, len(raw), 2000)]
        if run['scenario'] == 'async':
            row['by_shape'] = {shape: percentiles([r['latency_ns'] for r in raw if r['shape'] == shape and r['success']]) for shape in ['flat', 'deep', 'wide', 'large']}
        table.append(row)
    paired = []
    for a, b in zip(runs[::2], runs[1::2]):
        paired.append(dict(pair=a['label'] + '/' + b['label'],
                           useful_rate_pct=100*(b['useful_successes_per_s']/a['useful_successes_per_s']-1),
                           p95_pct=100*(b['success_latency_ns']['p95']/a['success_latency_ns']['p95']-1),
                           p99_pct=100*(b['success_latency_ns']['p99']/a['success_latency_ns']['p99']-1),
                           allocation_pct=100*(b['allocated_bytes_per_success']/a['allocated_bytes_per_success']-1)))
    write(staging / 'comparison.json', dict(schema='p19-comparison-v1', runs=table, paired=paired,
          verdict=('BLOCKED candidate B, not an accepted P23 control; diagnostic costs only.' if review.get('status') == 'BLOCKED'
                   else 'Diagnostic A/B costs only; corrected B is the P23 control. No maximum-throughput or equivalence claim.')))
    # Logs are generated output: deterministic compression keeps the repository small.
    for file in list(staging.rglob('*.log')) + list(staging.rglob('*-histogram.txt')) + list(staging.rglob('backend-executions.json')):
        with gzip.GzipFile(filename=str(file) + '.gz', mode='wb', mtime=0) as stream:
            stream.write(file.read_bytes())
        file.unlink()
    manifest = dict(schema='nanofaas-p19-dossier-v2', status=review.get('status', 'DONE_WITH_CONCERNS'),
                    accepted_control=None if review.get('status') == 'BLOCKED' else 'B',
                    review=review, production_source='6d08303371d803f44187ec5f4e37827d54fec597',
                    production_tree=subprocess.check_output(['git', 'rev-parse', '6d083033^{tree}'], cwd=ROOT, text=True).strip(),
                    diagnostic_source='61d72e73528db62cf8ca465c6a037981d7ec13b0',
                    build_source_archive_scope='All listed production/build input roots; historical experiments and prose omitted. Exact full Git tree recorded.',
                    binary_archive_scope='Exact measured JVM jars; other profile jar entry hashes and native image digest retained.',
                    concerns=review.get('concerns', ['Consult protocol limitations; no peak-capacity or soak claim.']),
                    files={str(path.relative_to(staging)): dict(sha256=digest(path), bytes=path.stat().st_size)
                           for path in sorted(staging.rglob('*')) if path.is_file()})
    write(staging / 'manifest.json', manifest)
    sha = digest(staging / 'manifest.json')
    (staging / 'SHA256SUMS').write_text(''.join(f"{manifest['files'][name]['sha256']}  {name}\n" for name in sorted(manifest['files'])) + f'{sha}  manifest.json\n')
    destination = HERE / 'dossiers' / sha
    destination.parent.mkdir(exist_ok=True)
    shutil.move(str(staging), destination)
    verify(destination)
    write(HERE / 'baseline.json', dict(schema='nanofaas-p19-pointer-v1', path='dossiers/' + sha,
                                     manifest_sha256=sha, control_source=manifest['production_source'], status=manifest['status']))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['freeze', 'verify'])
    parser.add_argument('path', type=pathlib.Path)
    args = parser.parse_args()
    (freeze if args.action == 'freeze' else verify)(args.path)
