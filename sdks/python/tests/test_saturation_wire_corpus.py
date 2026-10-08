import asyncio
import importlib
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

import pytest

import nanofaas.runtime.app as runtime_app
from sdks.python.tests.runtime_corpus_adapter import (
    ScenarioHarness,
    _runtime_environment,
    execute_corpus_against_runtime,
)


CONTRACT_DIR = Path(__file__).resolve().parents[2] / "runtime-contract"
CORPUS = CONTRACT_DIR / "saturation-wire-corpus.json"
VALIDATOR = CONTRACT_DIR / "validate_saturation_wire_corpus.py"


@dataclass(frozen=True)
class ScenarioProjection:
    scenario_id: str
    kind: str
    owners: tuple[str, ...]
    runtime_config: str
    request_ids: tuple[str, ...]
    action_count: int
    deadline_ms: int
    callback_states: tuple[tuple[bool, bool, bool, int], ...]
    observations: tuple[str, ...]
    final_counters: tuple[tuple[str, int], ...]


def _reject_constant(value):
    raise ValueError(f"non-finite JSON number {value}")


def _touch_complete_typed_model(corpus):
    assert isinstance(corpus["schemaVersion"], str)
    policy = corpus["policy"]
    assert isinstance(policy["maximumScenarioDeadlineMs"], int)
    assert isinstance(policy["definitionsRef"], str)
    definitions = corpus["contractDefinitions"][policy["definitionsRef"]]
    assert all(
        isinstance(item, str)
        for values in definitions["vocabulary"].values()
        for item in values
    )
    for field in (
        "actorActionCompatibility", "handlerLifecycles", "handlerBehaviorLifecycleRefs",
        "callbackLifecycles", "callbackBehaviorLifecycleRefs", "wireOutcomes",
        "callbackEnvelopes", "callbackRequestTemplate", "sizeRelationOperators",
        "finalCountersRule", "identityRules", "crossFieldRules", "observationSets",
    ):
        assert field in definitions
    assert all(isinstance(value, list) for value in definitions["actorActionCompatibility"].values())
    assert all(isinstance(rule["operator"], str) for rule in (
        definitions["identityRules"] + definitions["crossFieldRules"]
    ))
    callback_template = definitions["callbackRequestTemplate"]
    assert all(key in callback_template for key in ("method", "url", "headers"))
    assert all(
        isinstance(value, int)
        for config in corpus["runtimeConfigurations"].values()
        for value in config.values()
    )
    for scenario in corpus["scenarios"]:
        assert all(isinstance(owner, str) for owner in scenario["implementationOwners"])
        assert isinstance(scenario["runtimeConfigRef"], str)
        assert all(isinstance(value, int) for value in scenario["initialCounters"].values())
        for request in scenario["requests"]:
            assert all(key in request["metadata"] for key in (
                "executionId", "dispatchAttempt", "traceId", "callbackUrl"
            ))
            assert isinstance(request["payload"]["inputBytes"], int)
            assert isinstance(request["payload"]["relationToInputLimit"], str)
        for handler in scenario["backend"]["handlers"]:
            assert all(key in handler for key in (
                "requestId", "behavior", "outputBytes", "outputRelationToLimit", "barrier"
            ))
        for callback in scenario["backend"]["callbacks"]:
            assert all(key in callback for key in ("requestId", "behavior", "barrier"))
        for barrier in scenario["harness"]["barriers"]:
            assert isinstance(barrier["id"], str) and isinstance(barrier["initialState"], str)
        for action in scenario["harness"]["actions"]:
            assert all(key in action for key in ("sequence", "actor", "action", "requestId", "barrier"))
        expected = scenario["expected"]
        for response in expected["responses"]:
            assert all(key in response for key in (
                "requestId", "outcomeRef", "connectionOutcome", "status", "body", "requiredHeaders"
            ))
        for handler in expected["handlers"]:
            assert isinstance(handler["lifecycleRef"], str)
            assert isinstance(handler["started"], bool)
            assert isinstance(handler["cancelRequested"], bool)
        for callback in expected["callbacks"]:
            assert isinstance(callback["lifecycleRef"], str)
            assert isinstance(callback["envelopeRef"], str)
            assert isinstance(callback["required"], bool)
            assert isinstance(callback["attempted"], bool)
            assert isinstance(callback["delivered"], bool)
            assert isinstance(callback["dispatchAttempts"], list)
            projection = callback["requestProjection"]
            if projection is not None:
                assert all(key in projection for key in ("method", "url", "headers", "payload"))
        assert all(key in expected["identity"] for key in (
            "executionId", "requestDispatchAttempts", "runtimeRedispatchCount"
        ))
        assert isinstance(expected["observationSetRef"], str)
        assert all(isinstance(item, str) for item in expected["observations"])
        assert all(isinstance(value, int) for value in expected["finalCounters"].values())
    for mutation in corpus["mutationTests"]:
        assert all(key in mutation for key in ("id", "operation", "path", "value"))


def test_consumes_the_shared_runtime_saturation_wire_contract():
    validation = subprocess.run(
        [sys.executable, str(VALIDATOR), "--run-mutations", str(CORPUS)],
        check=False,
        capture_output=True,
        text=True,
        timeout=10,
    )
    assert validation.returncode == 0, validation.stdout + validation.stderr
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"), parse_constant=_reject_constant)
    _touch_complete_typed_model(corpus)

    projections = tuple(
        ScenarioProjection(
            scenario_id=scenario["id"],
            kind=scenario["kind"],
            owners=tuple(scenario["implementationOwners"]),
            runtime_config=scenario["runtimeConfigRef"],
            request_ids=tuple(request["id"] for request in scenario["requests"]),
            action_count=len(scenario["harness"]["actions"]),
            deadline_ms=scenario["deadlineMs"],
            callback_states=tuple(
                (callback["required"], callback["attempted"], callback["delivered"], callback["attempts"])
                for callback in scenario["expected"]["callbacks"]
            ),
            observations=tuple(scenario["expected"]["observations"]),
            final_counters=tuple(sorted(scenario["expected"]["finalCounters"].items())),
        )
        for scenario in corpus["scenarios"]
    )

    assert {projection.kind for projection in projections} == set(
        corpus["contractDefinitions"][corpus["policy"]["definitionsRef"]]["vocabulary"]["scenarioKinds"]
    )
    assert all(projection.request_ids and projection.action_count > 0 for projection in projections)
    assert all(
        0 < projection.deadline_ms <= corpus["policy"]["maximumScenarioDeadlineMs"]
        for projection in projections
    )
    assert all(
        all(isinstance(value, bool) for value in callback[:3])
        for projection in projections
        for callback in projection.callback_states
    )
    assert all(not any(dict(projection.final_counters).values()) for projection in projections)
    assert corpus["mutationTests"]


def test_executes_every_shared_scenario_against_the_python_runtime():
    summary = execute_corpus_against_runtime(CORPUS)

    assert summary.scenarios == 12
    assert summary.barriers == 3
    assert summary.callbacks == 8
    assert summary.callback_attempts == 10
    assert summary.stops == 2
    assert summary.restarts == 1
    assert summary.observations == 53
    assert summary.nonzero_counters == (
        "activeHandlers",
        "inputBytes",
        "outputBytes",
        "pendingCallbackBytes",
        "pendingCallbacks",
        "serializedCallbackBytes",
    )


def _write_mutated_corpus(tmp_path, mutate):
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    mutate(corpus)
    path = tmp_path / "mutated-saturation-wire-corpus.json"
    path.write_text(json.dumps(corpus), encoding="utf-8")
    return path


def test_runtime_adapter_rejects_mutated_declared_initial_counter(tmp_path):
    def mutate(corpus):
        corpus["scenarios"][0]["initialCounters"]["activeHandlers"] = 999999

    with pytest.raises(AssertionError):
        execute_corpus_against_runtime(_write_mutated_corpus(tmp_path, mutate))


def test_runtime_adapter_health_probe_uses_the_asgi_route(monkeypatch):
    corpus = json.loads(CORPUS.read_text(encoding="utf-8"))
    scenario = next(
        item for item in corpus["scenarios"] if item["id"] == "health-under-saturation"
    )
    config = corpus["runtimeConfigurations"][scenario["runtimeConfigRef"]]
    for key, value in _runtime_environment(config).items():
        monkeypatch.setenv(key, value)
    runtime = importlib.reload(runtime_app)
    harness = ScenarioHarness(corpus, scenario, runtime, asyncio.sleep)
    action = next(
        item for item in scenario["harness"]["actions"] if item["action"] == "probe-health"
    )

    def direct_health_call_is_forbidden():
        raise AssertionError("adapter called health() instead of GET /health")

    monkeypatch.setattr(runtime, "health", direct_health_call_is_forbidden)
    monkeypatch.setattr(runtime.callback_transport, "post_callback", harness.callback_post)

    async def exercise():
        for name in ("start-runtime", "fill-callback-capacity", "probe-health"):
            await harness._run_action(
                next(
                    item
                    for item in scenario["harness"]["actions"]
                    if item["action"] == name
                )
            )
        await harness._drain_fixture_state()

    try:
        asyncio.run(exercise())
        response = harness.responses[action["requestId"]]
        assert response.status_code == 200
        assert response.json() == {"status": "ok"}
    finally:
        harness.callback_filler_release.set()
        asyncio.run(runtime._runtime_work.shutdown(0.5))
        monkeypatch.undo()
        importlib.reload(runtime_app)


def test_runtime_adapter_rejects_unverifiable_redispatch_action(tmp_path):
    def mutate(corpus):
        scenario = next(
            item for item in corpus["scenarios"] if item["id"] == "dispatch-retry-identity"
        )
        action = next(
            item
            for item in scenario["harness"]["actions"]
            if item["action"] == "control-plane-redispatch"
        )
        action["requestId"] = "attempt-1"

    with pytest.raises(AssertionError):
        execute_corpus_against_runtime(_write_mutated_corpus(tmp_path, mutate))


@pytest.mark.parametrize("field,value", [("required", False), ("terminal", "exhausted")])
def test_runtime_adapter_rejects_mutated_callback_lifecycle(
    tmp_path, field, value
):
    def mutate(corpus):
        corpus["scenarios"][0]["expected"]["callbacks"][0][field] = value

    with pytest.raises(AssertionError):
        execute_corpus_against_runtime(_write_mutated_corpus(tmp_path, mutate))
