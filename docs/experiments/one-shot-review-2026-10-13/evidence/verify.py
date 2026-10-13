"""Read-only experiment review: no VM, Docker or campaign acquisition."""
import hashlib
import json
import os
import subprocess
from datetime import UTC, datetime
from pathlib import Path

root = Path('/tmp/nanolab-one-shot-host-placement')
output = Path(__file__).parent
env = dict(os.environ,
    ONE_SHOT_TUNING_ROOT='/home/michele/Documenti/nanofaas/.superpowers/archives/oneshot-first-experiment-20261012/load-tuning-02',
    ONE_SHOT_DISTRIBUTION='/home/michele/Documenti/nanofaas/.superpowers/archives/oneshot-first-experiment-20261012/oracle-freeze-04/distribution.json',
    ONE_SHOT_REFERENCE_ROOT='/tmp/oneshot-first-experiment-20261012/observation-preparation-02/reference',
    ONE_SHOT_RUNTIME_SOURCE='/home/michele/Documenti/nanofaas/.worktrees/edge-logging',
    NANOFAAS_ROOT='/home/michele/Documenti/nanofaas/.worktrees/edge-logging')
paths = set()
for pattern in ('packages/nanolab/src/nanolab/one_shot/*',
                'packages/nanolab/src/nanolab/tasks/one_shot/*',
                'packages/nanolab/src/nanolab/plans/one_shot*.py',
                'packages/nanolab/src/nanolab/config/one_shot.py',
                'packages/nanolab/src/nanolab/assets/k6/one-shot.js',
                'scripts/one-shot-*.py'):
    paths.update(p for p in root.glob(pattern) if p.is_file())
(output/'scope.json').write_text(json.dumps([
    {'path': str(p.relative_to(root)), 'sha256': hashlib.sha256(p.read_bytes()).hexdigest(),
     'lines': len(p.read_text().splitlines())} for p in sorted(paths)], indent=2)+'\n')
command = [str(root/'.venv/bin/python'), '-m', 'pytest', '--no-cov', '-q',
           'packages/nanolab/tests/one_shot', '--junitxml='+str(output/'tests.xml')]
started = datetime.now(UTC).isoformat()
result = subprocess.run(command, cwd=root, env=env, text=True, capture_output=True, check=False)
(output/'tests.json').write_text(json.dumps(dict(command=command, startedAtUtc=started,
    finishedAtUtc=datetime.now(UTC).isoformat(), exitCode=result.returncode,
    stdout=result.stdout, stderr=result.stderr), indent=2)+'\n')
print(result.stdout)
print(result.stderr)
raise SystemExit(result.returncode)
