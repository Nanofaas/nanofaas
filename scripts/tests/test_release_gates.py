"""The release gate must reject failed, skipped and cancelled required jobs."""
from pathlib import Path
import json
import os
import subprocess

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]


def workflow():
    return yaml.safe_load((ROOT / '.github/workflows/gitops.yml').read_text())


def test_required_tool_suites_are_scheduled():
    jobs = workflow()['jobs']
    commands = '\n'.join(step.get('run', '') for job in jobs.values() for step in job['steps'])
    for suite in ('scripts/tests', 'experiments/tests', 'tools/fn-init/tests'):
        assert suite in commands, f'{suite} is never run by CI'
    assert './gradlew releaseChecks -PcontrolPlaneModules=all' in commands


@pytest.mark.parametrize('result', ['success', 'failure', 'cancelled', 'skipped'])
def test_aggregate_gate_rejects_unsuccessful_jobs(result):
    jobs = workflow()['jobs']
    gate = jobs['release-gate']
    assert gate['if'] == '${{ always() }}'
    assert set(gate['needs']) == set(jobs) - {'release-gate'}
    outcomes = {name: {'result': 'success'} for name in gate['needs']}
    outcomes[gate['needs'][0]]['result'] = result
    step = gate['steps'][0]
    env = dict(os.environ, JOB_RESULTS=json.dumps(outcomes))
    completed = subprocess.run(['bash', '-euo', 'pipefail', '-c', step['run']], env=env, capture_output=True)
    assert (completed.returncode == 0) == (result == 'success'), completed.stderr.decode()


def test_native_gate_prepares_pinned_dependencies_and_checks_http_errors():
    commands = '\n'.join(step.get('run', '') for step in workflow()['jobs']['test-native-artifact']['steps'])
    assert 'bootstrap-containerd-dependencies.sh' in commands
    assert 'assert-native-api-errors.py' in commands
    assert 'sdks/runtime-contract' in '\n'.join(step.get('run', '') for step in workflow()['jobs']['test-tools']['steps'])


def test_core_only_gate_includes_configured_http_calibration():
    steps = workflow()['jobs']['test-java']['steps']
    core = next(step['run'] for step in steps if step.get('name') == 'Run Core-Only Tests')
    assert '*P07ConfiguredHttpCalibrationTest' in core
