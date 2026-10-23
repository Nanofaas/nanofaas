"""Retain the read-only review evidence and check local document references."""
import hashlib
import json
import re
import shutil
import subprocess
from pathlib import Path

here = Path(__file__).parent
root = Path('/home/michele/Documenti/nanofaas/.worktrees/edge-logging')
target = root/'docs/experiments/one-shot-review-2026-10-13/evidence'
target.mkdir(parents=True, exist_ok=True)
names = ['verify.py','reproduce.py','reproductions.json','k6-null.mjs','k6-null.json',
         'scope.json','tests.json','tests.xml','probe-tests.xml','reference-tests.xml','publish_evidence.py']
if (here/'complete-tests.xml').exists():
    names.append('complete-tests.xml')
source = Path('/tmp/nanolab-one-shot-host-placement')
for row in json.loads((here/'scope.json').read_text()):
    assert hashlib.sha256((source/row['path']).read_bytes()).hexdigest() == row['sha256'], row['path']
for name in names:
    shutil.copyfile(here/name, target/name)
docs = [root/'docs/superpowers/plans/2026-10-13-one-shot-observation-readiness.md',
        root/'docs/superpowers/specs/2026-10-13-one-shot-observation-readiness-design.md',
        target.parent/'README.md']
for path in docs:
    for link in re.findall(r'\]\(([^)]+)\)', path.read_text()):
        if not link.startswith(('https:', 'http:', '#')):
            assert (path.parent/link).exists(), (path, link)
    assert not any(line.rstrip()!=line for line in path.read_text().splitlines()), path
result = subprocess.run(['git','diff','--check'], cwd=root, text=True, capture_output=True)
assert result.returncode==0, result.stdout+result.stderr
print(json.dumps({'documents': len(docs), 'evidenceFiles': len(names), 'localLinks': 'valid', 'diffCheck': 'passed'}))
