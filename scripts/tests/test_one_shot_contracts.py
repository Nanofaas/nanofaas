"""Check the wire contracts and reproducible reference export boundary."""

import copy
import importlib.util
import json
from pathlib import Path
import subprocess

import pytest

ROOT = Path(__file__).resolve().parents[2]

def test_published_one_shot_profile_has_valid_openapi_30_numeric_constraints():
    import yaml
    from jsonschema import Draft4Validator

    fragment = yaml.safe_load((ROOT / "platform/modules/offload/openapi.yaml").read_text())
    schema = fragment["components"]["schemas"]["OneShotServiceProfile"]
    Draft4Validator.check_schema(schema)
    validator = Draft4Validator(schema)
    validator.validate(profile())
    for field in ("cpuQuota", "serviceSeconds"):
        invalid = profile()
        invalid["functions"][0][field] = 0
        assert not validator.is_valid(invalid), field
    for field in ("meanSeconds", "p95Seconds"):
        invalid = profile()
        invalid["functions"][0]["statistics"][field] = 0
        assert not validator.is_valid(invalid), field


def exporter():
    path = ROOT / "scripts/one-shot/export_reference.py"
    assert path.is_file(), "one-shot reference exporter is missing"
    spec = importlib.util.spec_from_file_location("one_shot_reference", path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def forecast():
    return {
        "schemaVersion": 1, "nodeId": "edge-a", "revision": 1,
        "provider": "oracle", "producedAt": "2026-10-04T12:00:00Z",
        "entries": [{
            "function": "workload", "generation": 3,
            "start": "2026-10-04T12:10:00Z", "end": "2026-10-04T12:20:00Z",
            "rate": 12.5, "unit": "requests/s",
        }],
    }


def profile():
    return {
        "schemaVersion": 1, "profileId": "local-workload",
        "provider": "multipass", "purpose": "workflow-validation",
        "synthetic": False, "sourceCommit": "a" * 40,
        "environmentFingerprint": "sha256:" + "b" * 64,
        "environment": {"host": "local", "vm": "edge-a", "os": "linux",
                        "architecture": "aarch64", "cpu": "test-cpu"},
        "functions": [{
            "function": "workload", "imageDigest": "sha256:" + "c" * 64,
            "runtime": "rust", "backend": "container-local",
            "inputHash": "sha256:" + "d" * 64,
            "cpuQuota": 1.0, "memoryMiB": 128, "replicas": 1,
            "coLocation": [], "serviceSeconds": 0.025,
            "measurement": {"warmupInvocations": 10, "includesColdStarts": False,
                            "occupancy": "physical-handler",
                            "rawSamplesHash": "sha256:" + "e" * 64},
            "statistics": {"sampleCount": 100, "meanSeconds": 0.025,
                           "stddevSeconds": 0.002, "p95Seconds": 0.029},
            "validity": {"minReplicas": 1, "maxReplicas": 2,
                         "maxRelativeCapacityError": 0.1},
        }],
    }


def event():
    return {
        "schemaVersion": 1, "nodeId": "edge-a", "incarnation": "run-a",
        "epoch": 1, "round": 0, "type": "AUCTION_CLOSED",
        "monotonicOffsetNanos": 1200, "at": "2026-10-04T12:00:00Z",
        "status": "CONVERGED", "censored": False, "correlationId": "epoch-1",
    }


@pytest.mark.parametrize("name,document", [
    ("forecast", forecast()), ("service-profile", profile()), ("run-events", event()),
])
def test_complete_contract_documents_are_accepted(name, document):
    exporter().validate_contract(name, document)


@pytest.mark.parametrize("mutation", ["nan", "negative", "missing", "version", "interval", "generation"])
def test_invalid_forecasts_are_rejected(mutation):
    document = forecast()
    if mutation == "nan":
        document["entries"][0]["rate"] = float("nan")
    elif mutation == "negative":
        document["entries"][0]["rate"] = -1
    elif mutation == "missing":
        del document["nodeId"]
    elif mutation == "version":
        document["schemaVersion"] = 2
    elif mutation == "generation":
        document["entries"][0]["generation"] = 0
    else:
        document["entries"][0]["end"] = document["entries"][0]["start"]
    with pytest.raises(ValueError):
        exporter().validate_contract("forecast", document)


def test_negative_service_duration_is_rejected():
    document = profile()
    document["functions"][0]["serviceSeconds"] = -0.01
    with pytest.raises(ValueError):
        exporter().validate_contract("service-profile", document)


def test_inconsistent_profile_validity_is_rejected():
    document = profile()
    document["functions"][0]["validity"]["maxReplicas"] = 0
    with pytest.raises(ValueError):
        exporter().validate_contract("service-profile", document)


def test_duplicate_forecast_rows_are_rejected():
    document = forecast()
    document["entries"].append(copy.deepcopy(document["entries"][0]))
    with pytest.raises(ValueError):
        exporter().validate_contract("forecast", document)


def test_export_refuses_a_different_git_revision_before_importing_solver(tmp_path):
    repo = tmp_path / "wrong-reference"
    repo.mkdir()
    subprocess.run(["git", "init", "-q", str(repo)], check=True)
    subprocess.run(["git", "-C", str(repo), "-c", "user.name=Test",
                    "-c", "user.email=test@example.invalid", "commit", "--allow-empty",
                    "-qm", "different revision"], check=True)
    output = tmp_path / "output"
    with pytest.raises(ValueError, match="revision"):
        exporter().export_reference(repo, output)
    assert not output.exists()


def test_checked_in_fixtures_have_verifiable_provenance():
    directory = ROOT / "platform/modules/offload/src/test/resources/one-shot/reference"
    assert (directory / "manifest.json").is_file(), "reference fixture manifest is missing"
    manifest = json.loads((directory / "manifest.json").read_text())
    assert manifest["commit"] == "71899f720a4ffffd070ebf7ddc5b74afddfde9c5"
    for filename, expected in manifest["fixtures"].items():
        assert exporter().sha256((directory / filename).read_bytes()) == expected
    cases = json.loads((directory / "local-problems.json").read_text())
    assert {case["input"]["model"] for case in cases} == {"LSP", "LSPr_x"}
    assert len(cases) >= 20
    transcripts = json.loads((directory / "auction-transcripts.json").read_text())
    assert transcripts[0]["rounds"]


def test_profile_requires_environment_and_warm_measurement_provenance():
    document = profile()
    del document['environment']
    with pytest.raises(ValueError):
        exporter().validate_contract('service-profile', document)


def test_profile_rejects_cold_samples_in_warm_distribution():
    document = profile()
    document['functions'][0]['measurement']['includesColdStarts'] = True
    with pytest.raises(ValueError):
        exporter().validate_contract('service-profile', document)


def test_small_reference_cases_match_independent_exhaustive_enumeration():
    import itertools
    import math
    directory = ROOT / 'platform/modules/offload/src/test/resources/one-shot/reference'
    for case in json.loads((directory / 'local-problems.json').read_text()):
        problem, expected = case['input'], case['expected']
        choices = []
        for f in problem['functions']:
            options = []
            local_values = [f['fixedLocal']] if problem['model'] == 'LSPr_x' else range(f['load'] + 1)
            for local in local_values:
                offloads = [f['fixedOffload']] if problem['model'] == 'LSPr_x' else range(f['load'] - local + 1)
                for offload in offloads:
                    rejected = f['load'] - local - offload
                    if rejected < 0:
                        continue
                    inbound = f['inbound'] if problem['model'] == 'LSPr_x' else 0
                    replicas = max(0, math.ceil((local + inbound) * f['demandSeconds'] / f['utilization'] - 1e-9))
                    price = 0 if problem['model'] == 'LSPr_x' else f['price']
                    cost = -(f['alpha'] * local + (f['delta'] - price) * offload - f['gamma'] * rejected) / (f['load'] or 1)
                    options.append((replicas * f['memoryMiB'], cost))
            choices.append(options)
        feasible = [sum(option[1] for option in allocation)
                    for allocation in itertools.product(*choices)
                    if sum(option[0] for option in allocation) <= problem['memoryCapacityMiB']]
        if not feasible:
            assert expected['status'] == 'INFEASIBLE', problem['id']
        else:
            assert expected['status'] == 'OPTIMAL', problem['id']
            assert expected['objective'] == pytest.approx(min(feasible), abs=1e-9), problem['id']


def test_memory_replica_planning_transcript_is_frozen():
    directory = ROOT / 'platform/modules/offload/src/test/resources/one-shot/reference'
    cases = json.loads((directory / 'auction-transcripts.json').read_text())
    memory = next(case for case in cases if case['id'] == 'proportional-memory-requests')
    assert memory['additionalReplicas'][1] == [1, 1]
    assert memory['remainingMemory'][1] == 0
