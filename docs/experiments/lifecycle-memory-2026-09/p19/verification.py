"""Execute the reviewed P19 catalog; preserve fresh method results before reuse."""
import argparse
import hashlib
import json
import pathlib
import re
import shlex
import subprocess
import time
import xml.etree.ElementTree as ET
import gzip

ROOT = pathlib.Path(__file__).resolve().parents[4]
INVENTORY = ROOT / '.superpowers/sdd/2026-09-08-control-plane-lifecycle-memory-and-modularity/task-P19-matrix-inventory.md'


def execute(label, command, destination, cwd=ROOT, expected=0):
    folder = destination / label
    folder.mkdir(parents=True, exist_ok=False)
    start = time.time()
    with (folder / 'output.log').open('wb') as output:
        result = subprocess.run(command, cwd=cwd, stdout=output, stderr=subprocess.STDOUT, timeout=1200)
    record = dict(label=label, command=command, cwd=str(cwd), exit_code=result.returncode,
                  expected_exit_code=expected, started_unix_s=start, elapsed_s=time.time()-start, cases=[])
    # Only reports for explicitly requested tasks, never an old report from another module.
    for task in command:
        if task.endswith(':test'):
            project = task.removesuffix(':test')
            paths = {':control-plane': 'platform/control-plane', ':common': 'platform/common'}
            relative = paths.get(project, project.lstrip(':').replace('control-plane-modules:', 'platform/modules:').replace(':', '/'))
            for xml in (cwd / relative / 'build/test-results/test').glob('TEST-*.xml'):
                if xml.stat().st_mtime < start:
                    continue
                suite = ET.parse(xml).getroot()
                for case in suite.findall('testcase'):
                    state = 'failed' if case.find('failure') is not None or case.find('error') is not None else 'skipped' if case.find('skipped') is not None else 'passed'
                    record['cases'].append(dict(classname=case.get('classname'), name=case.get('name'), state=state, seconds=case.get('time')))
                with gzip.GzipFile(filename=str(folder / xml.name) + '.gz', mode='wb', mtime=0) as target:
                    target.write(xml.read_bytes())
    record['counts'] = ({state: sum(c['state'] == state for c in record['cases']) for state in ['passed', 'failed', 'skipped']}
                        if record['cases'] else None)
    record['counts_status'] = 'observed' if record['cases'] else 'unavailable: no fresh JUnit XML selected; consult native result format'
    record['output_sha256'] = hashlib.sha256((folder / 'output.log').read_bytes()).hexdigest()
    (folder / 'result.json').write_text(json.dumps(record, indent=2) + '\n')
    print(label, result.returncode, record['counts'], flush=True)
    return record


def catalog():
    text = INVENTORY.read_text()
    return {name: shlex.split(command.replace('\\\n', ' ')) for name, command in
            re.findall(r'### (G\d+) [^\n]*\n+```bash\n(.*?)\n```', text, re.S)}


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('destination', type=pathlib.Path)
    parser.add_argument('groups', nargs='+')
    args = parser.parse_args()
    commands = catalog()
    for group in args.groups:
        command = commands[group] + ['--rerun-tasks', '-I', str(pathlib.Path(__file__).with_name('verification.init.gradle'))]
        record = execute(group, command, args.destination)
        if record['exit_code'] != 0 or not record['counts'] or record['counts']['failed']:
            raise SystemExit(1)
