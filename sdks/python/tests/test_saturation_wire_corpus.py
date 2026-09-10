import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path


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
