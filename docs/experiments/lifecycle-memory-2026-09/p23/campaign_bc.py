"""P23 paired campaign: the frozen P19 arm B against the current head as arm C.

Reuses P19's harness unchanged — the same http_runner, the same probe bytes, the same jar for B
that P19 actually measured — so the only difference between the two arms is the product jar.
Fresh serial processes, alternating, exactly as P19's protocol prescribes. Do not run builds or
tests during this sequence.
"""
import os
import pathlib
import subprocess
import sys
import time

work = pathlib.Path(sys.argv[1])
harness = pathlib.Path(sys.argv[2])            # the P19 harness directory
sys.path.insert(0, str(harness))
from http_runner import write_json                                    # noqa: E402
from supervision import supervise                                     # noqa: E402

REVISIONS = {'B': 'd93b68cdf1cca6e7af17e1c641c8e236c80d0fca',
             'C': subprocess.run(['git', 'rev-parse', 'HEAD'], capture_output=True, text=True,
                                 cwd='/home/michele/Documenti/nanofaas').stdout.strip()}

environment = {'started_unix_s': time.time(), 'affinity': sorted(os.sched_getaffinity(0)),
               'revisions': REVISIONS, 'commands': {}}
for command in [['uname', '-a'], ['java', '-version'], ['lscpu'], ['python3', '--version']]:
    result = subprocess.run(command, capture_output=True, text=True)
    environment['commands'][' '.join(command)] = {'exit_code': result.returncode,
                                                 'output': result.stdout + result.stderr}
for path in ['/proc/meminfo', '/proc/loadavg', '/proc/self/cgroup', '/sys/fs/cgroup/cpu.stat']:
    try:
        environment[path] = pathlib.Path(path).read_text()
    except OSError as error:
        environment[path] = {'status': 'unavailable', 'reason': str(error)}
write_json(work / 'environment.json', environment)

failures = []
for repeat in range(1, 4):
    for side, revision in REVISIONS.items():
        label = side + str(repeat)
        command = [sys.executable, str(harness / 'http_runner.py'),
                   '--jar', str(work / 'artifacts' / (side + '-none.jar')),
                   '--probe', str(work / 'probe'),
                   '--output', str(work / 'runs' / label), '--label', label,
                   '--revision', revision, '--warmup', '6000', '--count', '12000', '--rate', '200']
        print('START', label, time.time(), flush=True)
        result = supervise(command, work / 'supervision' / (label + '.json'), timeout=600)
        print('END', label, result, flush=True)
        if result.get('exit_code') != 0:
            failures.append(label)
write_json(work / 'campaign.json', {'schema': 'p23-campaign-v1', 'revisions': REVISIONS,
                                    'order': [s + str(r) for r in range(1, 4) for s in REVISIONS],
                                    'failures': failures})
print('FAILURES:', failures, flush=True)
