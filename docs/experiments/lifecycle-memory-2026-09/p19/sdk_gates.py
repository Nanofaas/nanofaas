"""Actual SDK processes; command-tree resource data is not runtime retained heap."""
import pathlib
import sys
from verification import ROOT, execute

destination = pathlib.Path(sys.argv[1])
go = '/home/michele/go/pkg/mod/golang.org/toolchain@v0.0.1-go1.24.0.linux-arm64/bin/go'
for label, cwd, command in [
    ('python-sdk', ROOT / 'sdks/python', ['.venv/bin/python', '-m', 'pytest', 'tests', '-q', '--junitxml=' + str(destination / 'python-junit.xml')]),
    ('javascript-sdk', ROOT / 'sdks/javascript', ['npm', 'test']),
    ('go-sdk', ROOT / 'sdks/go', [go, 'test', '-count=1', '-json', './...']),
    ('go-race', ROOT / 'sdks/go', [go, 'test', '-count=1', '-race', '-json', './...']),
    ('go-vet', ROOT / 'sdks/go', [go, 'vet', './...']),
    ('wire-validator', ROOT, ['sdks/python/.venv/bin/python', '-m', 'unittest', 'discover', '-s', 'sdks/runtime-contract', '-p', 'test_*.py'])]:
    result = execute(label, command, destination, cwd=cwd)
    if result['exit_code']:
        raise SystemExit(1)
