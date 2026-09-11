"""Equal-work paired sampling-overhead observations, separate from acceptance."""
import pathlib
import subprocess
import sys
from http_runner import write_json

work = pathlib.Path(sys.argv[1])
for side, revision in [('A', '61d72e73528db62cf8ca465c6a037981d7ec13b0'), ('B', '6d08303371d803f44187ec5f4e37827d54fec597')]:
    for mode in ['sampled', 'no-periodic-sampling']:
        label = side + '-' + mode
        command = [sys.executable, str(work / 'common-harness/http_runner.py'),
                   '--jar', str(work / 'artifacts' / (side + '-none.jar')), '--probe', str(work / 'probe'),
                   '--output', str(work / 'auxiliary' / label), '--label', label, '--revision', revision,
                   '--warmup', '2000', '--count', '2000', '--rate', '200',
                   '--sample-seconds', '1' if mode == 'sampled' else '0']
        print('START', label, flush=True)
        result = subprocess.run(command, timeout=180)
        if result.returncode:
            raise SystemExit(result.returncode)
