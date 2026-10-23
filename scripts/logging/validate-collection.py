#!/usr/bin/env python3
"""Real Docker/Fluent Bit/Loki recovery and loss test. Owns only its unique project."""
import argparse
from collections import Counter
import datetime
import hashlib
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / 'deploy/observability/compose.yaml'


def port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--runtime-evidence', type=Path, action='append', default=[])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    project = 'nf-logs-' + uuid.uuid4().hex[:10]
    started = time.time_ns()
    receipt = {'project': project, 'status': 'running', 'images': {}, 'checks': {}}
    with tempfile.TemporaryDirectory(prefix=project) as directory:
        source = Path(directory) / 'source' / 'fixture'
        source.mkdir(parents=True)
        logfile = source / 'fixture-json.log'
        env = dict(os.environ, DOCKER_LOG_ROOT=str(source.parent),
                   LOKI_LISTEN_PORT=str(port()), GRAFANA_PORT=str(port()),
                   METRICS_PORT=str(port()), NANOFAAS_NODE_ID=project,
                   NANOFAAS_LOG_BUFFER_SIZE='256M')
        command = ['docker', 'compose', '-p', project, '-f', str(COMPOSE), '--profile', 'agent', '--profile', 'backend']

        def compose(*argv, check=True):
            return subprocess.run(command + list(argv), env=env, text=True,
                                  capture_output=True, check=check, timeout=60)

        def get(path, metrics=False):
            number = env['METRICS_PORT' if metrics else 'LOKI_LISTEN_PORT']
            return urllib.request.urlopen('http://127.0.0.1:' + number + path, timeout=3).read().decode()

        def wait(predicate, description, seconds=90):
            deadline = time.monotonic() + seconds
            while time.monotonic() < deadline:
                try:
                    value = predicate()
                    if value:
                        return value
                except (OSError, ValueError):
                    pass
                time.sleep(.5)
            raise AssertionError(description + ' timed out')

        def emit(ids, padding=''):
            with logfile.open('a') as stream:
                for identity in ids:
                    stamp = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
                    event = {'schemaVersion': 1, 'timestamp': stamp, 'level': 'INFO',
                             'service': 'fixture', 'logger': 'contract',
                             'message': 'caffè ☕\n"next"' + padding,
                             'executionId': identity, 'traceId': 'trace-' + identity}
                    stream.write(json.dumps({'log': json.dumps(event) + '\n',
                                             'time': stamp, 'stream': 'stdout'}) + '\n')

        def query(contains=''):
            expression = '{job="nanofaas",node="' + project + '"}'
            if contains:
                expression += ' |= ' + json.dumps(contains)
            params = urllib.parse.urlencode({'query': expression, 'start': str(started),
                                             'end': str(time.time_ns()), 'limit': '5000'})
            return json.loads(get('/loki/api/v1/query_range?' + params))

        def entries(data):
            return [(stream['stream'], json.loads(value[1]))
                    for stream in data['data']['result'] for value in stream['values']]

        def found(ids):
            data = query()
            present = {e.get('executionId') for _, e in entries(data)}
            return data if set(ids) <= present else None

        try:
            compose('--profile', 'backend', '--profile', 'agent', 'up', '-d')
            wait(lambda: get('/ready').strip() == 'ready', 'backend readiness')
            wait(lambda: get('/api/v1/metrics/prometheus', True), 'collector metrics')
            emit(['initial'])
            initial = wait(lambda: found(['initial']), 'initial correlated log')
            for labels, event in entries(initial):
                assert not {'executionId', 'traceId'} & labels.keys(), labels
                assert event['service'] == 'fixture' and event['nodeId'] == project, event
                assert event['traceId'] == 'trace-initial' and '\n' in event['message'], event
            with logfile.open('a') as stream:
                stamp = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
                stream.write(json.dumps({'log': 'plain-text-fixture\n', 'time': stamp, 'stream': 'stderr'}) + '\n')
            text = wait(lambda: query('plain-text-fixture')['data']['result'], 'text fallback')
            receipt['checks']['textFallback'] = bool(text)
            expected = set()
            with logfile.open('a') as stream:
                for directory in args.runtime_evidence:
                    for path in directory.glob('*.log'):
                        for line in path.read_text().splitlines():
                            if not line.startswith('{'):
                                continue
                            event = json.loads(line)
                            if event.get('schemaVersion') != 1 or not event.get('executionId'):
                                continue
                            expected.add((event['executionId'], event['traceId'], event['service']))
                            stamp = datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00', 'Z')
                            stream.write(json.dumps({'log': line+'\n', 'time': stamp, 'stream':'stdout'})+'\n')
            if expected:
                def runtime_records():
                    data = query()
                    present = {(e.get('executionId'),e.get('traceId'),e.get('service')) for _,e in entries(data)}
                    return data if expected <= present else None
                runtime_data = wait(runtime_records, 'all runtime identities and services')
                for labels, event in entries(runtime_data):
                    assert not {'executionId','traceId'} & labels.keys(), labels
                (args.output/'runtime-query.json').write_text(json.dumps(runtime_data,indent=2)+'\n')
                receipt['checks']['runtimeIdentityServiceTriples'] = len(expected)

            compose('stop', 'loki')
            offline = ['offline-' + str(i) for i in range(40)]
            emit(offline)
            wait(lambda: any(line.startswith('fluentbit_output_retries_total') and float(line.split()[1]) > 0
                 for line in get('/api/v1/metrics/prometheus', True).splitlines()), 'output retries')
            time.sleep(3)
            (args.output / 'offline-metrics.txt').write_text(get('/api/v1/metrics/prometheus', True))
            compose('restart', 'agent')
            compose('start', 'loki')
            wait(lambda: get('/ready').strip() == 'ready', 'backend recovery')
            recovery = wait(lambda: found(offline), 'buffered events after restart', 120)
            (args.output / 'recovery-query.json').write_text(json.dumps(recovery, indent=2) + '\n')
            receipt['checks']['recoveredIds'] = len(offline)
            counts = Counter(e.get('executionId') for _, e in entries(recovery))
            receipt['checks']['duplicatesObserved'] = sum(max(0, n-1) for n in counts.values())
            logfile.rename(logfile.with_suffix('.log.1'))
            emit(['after-rotation'])
            wait(lambda: found(['after-rotation']), 'source rotation')
            receipt['checks']['rotation'] = True
            # Separate reduced quota; this test intentionally causes loss.
            compose('stop', 'loki')
            env['NANOFAAS_LOG_BUFFER_SIZE'] = '3M'
            compose('--profile', 'agent', 'up', '-d', '--force-recreate', 'agent')
            wait(lambda: get('/api/v1/metrics/prometheus', True), 'overflow collector readiness')
            for batch in range(8):
                emit(['overflow-' + str(batch) + '-' + str(i) for i in range(120)], 'x'*20000)
                time.sleep(1.5)
            metrics = get('/api/v1/metrics/prometheus', True)
            (args.output / 'overflow-metrics.txt').write_text(metrics)
            agent_logs = compose('logs', '--no-color', 'agent').stdout
            (args.output / 'agent.log').write_text(agent_logs)
            # Quota eviction must be observed, not inferred from missing IDs.
            dropped = [line for line in metrics.splitlines() if line.startswith('fluentbit_output_dropped_records_total')]
            drop_count = sum(float(line.split()[1]) for line in dropped)
            assert drop_count > 0, ('No explicit drop metric', agent_logs[-2000:])
            receipt['checks']['overflowDroppedRecords'] = drop_count
            stats = subprocess.check_output(['docker', 'stats', '--no-stream', '--format', '{{json .}}',
                                             compose('ps', '-q', 'agent').stdout.strip()], text=True)
            receipt['checks']['agentResourceSnapshot'] = json.loads(stats)
            compose('start', 'loki')
            wait(lambda: get('/ready').strip() == 'ready', 'backend post-overflow recovery')
            emit(['post-overflow'])
            wait(lambda: found(['post-overflow']), 'delivery resumes after overflow', 120)
            receipt['checks']['resumedAfterOverflow'] = True
            (args.output / 'final-metrics.txt').write_text(get('/api/v1/metrics/prometheus', True))
            for image in ['fluent/fluent-bit:5.1.3', 'grafana/loki:3.7.8', 'grafana/grafana:13.2.3']:
                receipt['images'][image] = json.loads(subprocess.check_output(
                    ['docker', 'image', 'inspect', '--format', '{{json .RepoDigests}}', image], text=True))
            receipt['status'] = 'passed'
        finally:
            (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
            (args.output / 'stack.log').write_text(compose('logs', '--no-color', check=False).stdout)
            cleanup = compose('down', '-v', '--remove-orphans', check=False)
            (args.output / 'cleanup.log').write_text(cleanup.stdout + cleanup.stderr)
            containers = subprocess.check_output(['docker', 'ps', '-aq', '--filter', 'label=com.docker.compose.project='+project], text=True).strip()
            volumes = subprocess.check_output(['docker', 'volume', 'ls', '-q', '--filter', 'label=com.docker.compose.project='+project], text=True).strip()
            receipt['cleanupVerified'] = cleanup.returncode == 0 and not containers and not volumes
            (args.output / 'receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
            assert receipt['cleanupVerified'], 'Owned resource cleanup failed'
            hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest()
                      for p in args.output.iterdir() if p.is_file() and p.name != 'sha256.json'}
            (args.output / 'sha256.json').write_text(json.dumps(hashes, indent=2) + '\n')
    print(json.dumps(receipt, indent=2))

if __name__ == '__main__':
    main()
