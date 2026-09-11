"""Fresh serial processes; do not run builds/tests during this sequence."""
import os
import pathlib
import subprocess
import sys
import time
from http_runner import write_json

work = pathlib.Path(sys.argv[1])
harness = pathlib.Path(__file__).resolve().parent
environment = {'started_unix_s': time.time(), 'affinity': sorted(os.sched_getaffinity(0)), 'commands': {}}
for command in [['uname', '-a'], ['java', '-version'], ['lscpu'], ['node', '--version'], ['python3', '--version']]:
    result = subprocess.run(command, capture_output=True, text=True)
    environment['commands'][' '.join(command)] = {'exit_code': result.returncode, 'output': result.stdout + result.stderr}
for path in ['/proc/meminfo', '/proc/loadavg', '/proc/self/cgroup', '/sys/fs/cgroup/cpu.max', '/sys/fs/cgroup/memory.max', '/sys/fs/cgroup/cpu.stat']:
    try:
        environment[path] = pathlib.Path(path).read_text()
    except OSError as error:
        environment[path] = {'status': 'unavailable', 'reason': str(error)}
write_json(work / 'environment.json', environment)
for repeat in range(1, 4):
    for side, revision in [('A', '61d72e73528db62cf8ca465c6a037981d7ec13b0'), ('B', '6d08303371d803f44187ec5f4e37827d54fec597')]:
        label = side + str(repeat)
        command = [sys.executable, str(harness / 'http_runner.py'), '--jar', str(work / 'artifacts' / (side + '-none.jar')),
                   '--probe', str(work / 'probe'), '--output', str(work / 'runs' / label), '--label', label,
                   '--revision', revision, '--warmup', '6000', '--count', '12000', '--rate', '200']
        print('START', label, time.time(), flush=True)
        result = subprocess.run(command, timeout=300)
        if result.returncode:
            raise SystemExit(result.returncode)
for repeat in range(1, 4):
    label = 'B-async-' + str(repeat)
    command = [sys.executable, str(harness / 'http_runner.py'), '--jar', str(work / 'artifacts/B-async-queue.jar'),
               '--probe', str(work / 'probe'), '--output', str(work / 'runs' / label), '--label', label,
               '--revision', '6d08303371d803f44187ec5f4e37827d54fec597', '--profile', 'async-queue', '--scenario', 'async',
               '--warmup', '20', '--count', '120', '--rate', '50']
    print('START', label, time.time(), flush=True)
    result = subprocess.run(command, timeout=120)
    if result.returncode:
        raise SystemExit(result.returncode)
