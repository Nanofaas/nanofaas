#!/usr/bin/env python3
"""Authoritative structural and semantic validator for the SDK wire corpus."""

from __future__ import annotations

import argparse
import copy
import json
import re
import sys
from pathlib import Path
from typing import Any


class ContractError(ValueError):
    pass


IDENTITY = {
    "executionIdAcrossDispatchRetries": "stable",
    "dispatchAttemptAcrossDispatchRetries": "increment",
    "callbackDeliveryRetries": "echo-current-dispatch-attempt",
    "runtimeRedispatch": "never",
}
VOCABULARY = {
    "scenarioKinds": ["success-drain", "input-too-large", "output-too-large", "callback-saturated",
                      "handler-timeout", "cancellation", "health-under-saturation",
                      "stop-with-full-queue", "restart", "callback-delivery-exhausted",
                      "dispatch-retry-identity"],
    "implementationOwners": ["P16b", "P17", "P18"],
    "requestRoles": ["invoke", "health"],
    "sizeRelations": ["below-limit", "above-limit", "not-applicable"],
    "handlerBehaviors": ["succeed", "fail", "block-until-released", "block-until-cancelled",
                         "not-invoked"],
    "callbackBehaviors": ["succeed", "retryable-failure", "block-until-released", "not-invoked"],
    "actors": ["harness", "client", "control-plane", "runtime", "handler", "callback-backend"],
    "actions": ["start-runtime", "send-request", "probe-health", "await-barrier",
                "release-barrier", "cancel-request", "fill-callback-capacity", "begin-stop",
                "await-stop", "start-runtime-again", "await-response", "await-callback",
                "control-plane-redispatch", "drain-callbacks"],
    "handlerTerminals": ["not-started", "succeeded", "failed", "output-rejected", "timed-out",
                         "cancelled"],
    "callbackTerminals": ["not-required", "delivered", "exhausted", "cancelled",
                          "rejected-before-handler"],
    "connectionOutcomes": ["response", "client-disconnected"],
    "observations": ["wire-response", "no-wire-response", "handler-start", "handler-cancel",
                     "callback-attempt", "callback-delivery", "callback-failure-metric",
                     "structured-log", "health-response", "stop-complete", "restart-complete",
                     "counters-zero", "runtime-redispatch-zero"],
    "counterNames": ["activeHandlers", "inputBytes", "outputBytes", "pendingCallbacks",
                     "pendingCallbackBytes", "serializedCallbackBytes"],
}
CONFIG_KEYS = {
    "maxConcurrentHandlers", "maxInputBytes", "maxOutputBytes", "maxPendingCallbacks",
    "maxPendingCallbackBytes", "handlerTimeoutMs", "callbackAttemptTimeoutMs",
    "callbackMaxAttempts", "bodyReadTimeoutMs", "shutdownTimeoutMs",
}
ERROR_OUTCOMES = {
    "input-too-large": (413, "RUNTIME_INPUT_TOO_LARGE"),
    "output-too-large": (500, "RUNTIME_OUTPUT_TOO_LARGE"),
    "callback-saturated": (429, "RUNTIME_CALLBACK_SATURATED"),
    "handler-timeout": (504, "HANDLER_TIMEOUT"),
    "stop-with-full-queue": (503, "RUNTIME_STOPPING"),
}


def fail(path: str, message: str) -> None:
    raise ContractError(f"{path}: {message}")


def exact_keys(value: Any, keys: set[str], path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        fail(path, "must be an object")
    actual = set(value)
    if actual != keys:
        fail(path, f"keys must be {sorted(keys)}; got {sorted(actual)}")
    return value


def array(value: Any, path: str, *, nonempty: bool = False) -> list[Any]:
    if not isinstance(value, list) or (nonempty and not value):
        fail(path, "must be a non-empty array" if nonempty else "must be an array")
    return value


def string(value: Any, path: str, *, nullable: bool = False) -> str | None:
    if nullable and value is None:
        return None
    if not isinstance(value, str) or not value:
        fail(path, "must be a non-empty string")
    return value


def integer(value: Any, path: str, *, minimum: int = 0) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        fail(path, f"must be a finite integer >= {minimum}")
    return value


def boolean(value: Any, path: str) -> bool:
    if not isinstance(value, bool):
        fail(path, "must be a boolean")
    return value


def member(value: Any, choices: list[str], path: str) -> str:
    result = string(value, path)
    if result not in choices:
        fail(path, f"must be one of {choices}")
    return result


def unique_strings(values: Any, path: str, *, nonempty: bool = False) -> list[str]:
    result = array(values, path, nonempty=nonempty)
    for index, value in enumerate(result):
        string(value, f"{path}/{index}")
    if len(result) != len(set(result)):
        fail(path, "must contain unique values")
    return result


def validate_counters(value: Any, path: str) -> dict[str, int]:
    counters = exact_keys(value, set(VOCABULARY["counterNames"]), path)
    for name, count in counters.items():
        integer(count, f"{path}/{name}")
    return counters


def validate_relation(size: int, limit: int, relation: str, path: str) -> None:
    if relation == "below-limit" and not size < limit:
        fail(path, "below-limit size must be below its configured limit")
    if relation == "above-limit" and not size > limit:
        fail(path, "above-limit size must exceed its configured limit")
    if relation == "not-applicable" and size != 0:
        fail(path, "not-applicable size must be zero")


def validate_document(document: Any) -> None:
    root = exact_keys(document, {"schemaVersion", "policy", "runtimeConfigurations",
                                 "scenarios", "mutationTests"}, "$")
    if root["schemaVersion"] != "nanofaas.runtime-saturation/v2":
        fail("$/schemaVersion", "unsupported schema version")

    policy = exact_keys(root["policy"], {"maximumScenarioDeadlineMs", "identity", "vocabulary"},
                        "$/policy")
    maximum_deadline = integer(policy["maximumScenarioDeadlineMs"],
                               "$/policy/maximumScenarioDeadlineMs", minimum=1)
    if maximum_deadline > 60_000:
        fail("$/policy/maximumScenarioDeadlineMs", "must be at most 60000")
    if exact_keys(policy["identity"], set(IDENTITY), "$/policy/identity") != IDENTITY:
        fail("$/policy/identity", "must use the canonical P01 identity rules")
    vocabulary = exact_keys(policy["vocabulary"], set(VOCABULARY), "$/policy/vocabulary")
    if vocabulary != VOCABULARY:
        fail("$/policy/vocabulary", "must equal the versioned canonical vocabulary")

    configurations = root["runtimeConfigurations"]
    if not isinstance(configurations, dict) or not configurations:
        fail("$/runtimeConfigurations", "must be a non-empty object")
    for name, config_value in configurations.items():
        string(name, "$/runtimeConfigurations key")
        config = exact_keys(config_value, CONFIG_KEYS, f"$/runtimeConfigurations/{name}")
        for key, value in config.items():
            integer(value, f"$/runtimeConfigurations/{name}/{key}", minimum=1)

    scenarios = array(root["scenarios"], "$/scenarios", nonempty=True)
    kinds: list[str] = []
    ids: list[str] = []
    for index, scenario_value in enumerate(scenarios):
        path = f"$/scenarios/{index}"
        scenario = exact_keys(
            scenario_value,
            {"id", "kind", "implementationOwners", "runtimeConfigRef", "requests", "backend",
             "harness", "initialCounters", "expected", "deadlineMs"},
            path,
        )
        scenario_id = string(scenario["id"], f"{path}/id")
        if not re.fullmatch(r"[a-z][a-z0-9-]+", scenario_id):
            fail(f"{path}/id", "must be a lowercase kebab-case identifier")
        ids.append(scenario_id)
        kind = member(scenario["kind"], VOCABULARY["scenarioKinds"], f"{path}/kind")
        kinds.append(kind)
        owners = unique_strings(scenario["implementationOwners"], f"{path}/implementationOwners",
                                nonempty=True)
        if not set(owners) <= set(VOCABULARY["implementationOwners"]):
            fail(f"{path}/implementationOwners", "contains an unknown task owner")
        config_ref = string(scenario["runtimeConfigRef"], f"{path}/runtimeConfigRef")
        if config_ref not in configurations:
            fail(f"{path}/runtimeConfigRef", "does not name a runtime configuration")
        config = configurations[config_ref]
        deadline = integer(scenario["deadlineMs"], f"{path}/deadlineMs", minimum=1)
        if deadline > maximum_deadline:
            fail(f"{path}/deadlineMs", "exceeds maximumScenarioDeadlineMs")

        requests = validate_requests(scenario["requests"], config, path)
        request_ids = {request["id"] for request in requests}
        actions, barrier_ids = validate_harness(scenario["harness"], request_ids, path)
        validate_backend(scenario["backend"], requests, request_ids, config, barrier_ids, path)
        initial = validate_counters(scenario["initialCounters"], f"{path}/initialCounters")
        if initial["activeHandlers"] > config["maxConcurrentHandlers"]:
            fail(f"{path}/initialCounters/activeHandlers", "exceeds configured handler count")
        if initial["pendingCallbacks"] > config["maxPendingCallbacks"]:
            fail(f"{path}/initialCounters/pendingCallbacks", "exceeds configured callback count")
        if initial["pendingCallbackBytes"] > config["maxPendingCallbackBytes"]:
            fail(f"{path}/initialCounters/pendingCallbackBytes", "exceeds callback byte limit")
        validate_expected(scenario["expected"], requests, request_ids, config, kind, actions, path)

    if len(ids) != len(set(ids)):
        fail("$/scenarios", "scenario IDs must be unique")
    if len(kinds) != len(set(kinds)) or set(kinds) != set(VOCABULARY["scenarioKinds"]):
        fail("$/scenarios", "must contain exactly one scenario for each canonical kind")
    validate_mutation_definitions(root["mutationTests"])


def validate_requests(value: Any, config: dict[str, Any], scenario_path: str) -> list[dict[str, Any]]:
    requests = array(value, f"{scenario_path}/requests", nonempty=True)
    seen: set[str] = set()
    for index, request_value in enumerate(requests):
        path = f"{scenario_path}/requests/{index}"
        request = exact_keys(request_value, {"id", "role", "method", "path", "metadata", "payload"},
                             path)
        request_id = string(request["id"], f"{path}/id")
        if request_id in seen:
            fail(f"{path}/id", "request ID must be unique")
        seen.add(request_id)
        role = member(request["role"], VOCABULARY["requestRoles"], f"{path}/role")
        method = string(request["method"], f"{path}/method")
        route = string(request["path"], f"{path}/path")
        metadata = exact_keys(request["metadata"],
                              {"executionId", "dispatchAttempt", "traceId", "callbackUrl"},
                              f"{path}/metadata")
        payload = exact_keys(request["payload"], {"inputBytes", "relationToInputLimit"},
                             f"{path}/payload")
        input_bytes = integer(payload["inputBytes"], f"{path}/payload/inputBytes")
        relation = member(payload["relationToInputLimit"], VOCABULARY["sizeRelations"],
                          f"{path}/payload/relationToInputLimit")
        validate_relation(input_bytes, config["maxInputBytes"], relation,
                          f"{path}/payload/relationToInputLimit")
        if role == "invoke":
            if method != "POST" or route != "/invoke":
                fail(path, "invoke request must be POST /invoke")
            string(metadata["executionId"], f"{path}/metadata/executionId")
            integer(metadata["dispatchAttempt"], f"{path}/metadata/dispatchAttempt", minimum=1)
            string(metadata["traceId"], f"{path}/metadata/traceId", nullable=True)
            string(metadata["callbackUrl"], f"{path}/metadata/callbackUrl", nullable=True)
            if relation == "not-applicable":
                fail(f"{path}/payload/relationToInputLimit", "invoke input must have a size relation")
        else:
            if method != "GET" or route != "/health":
                fail(path, "health request must be GET /health")
            if any(metadata.values()) or input_bytes != 0 or relation != "not-applicable":
                fail(path, "health request cannot carry invocation metadata or payload")
    return requests


def validate_backend(value: Any, requests: list[dict[str, Any]], request_ids: set[str],
                     config: dict[str, Any], barrier_ids: set[str], scenario_path: str) -> None:
    backend = exact_keys(value, {"handlers", "callbacks"}, f"{scenario_path}/backend")
    for category, choices in (
        ("handlers", VOCABULARY["handlerBehaviors"]),
        ("callbacks", VOCABULARY["callbackBehaviors"]),
    ):
        entries = array(backend[category], f"{scenario_path}/backend/{category}", nonempty=True)
        seen: set[str] = set()
        for index, entry_value in enumerate(entries):
            path = f"{scenario_path}/backend/{category}/{index}"
            keys = {"requestId", "behavior", "barrier"}
            if category == "handlers":
                keys |= {"outputBytes", "outputRelationToLimit"}
            entry = exact_keys(entry_value, keys, path)
            request_id = string(entry["requestId"], f"{path}/requestId")
            if request_id not in request_ids or request_id in seen:
                fail(f"{path}/requestId", "must uniquely reference a scenario request")
            seen.add(request_id)
            member(entry["behavior"], choices, f"{path}/behavior")
            barrier = string(entry["barrier"], f"{path}/barrier", nullable=True)
            if barrier is not None and barrier not in barrier_ids:
                fail(f"{path}/barrier", "references an unknown harness barrier")
            if category == "handlers":
                output_bytes = integer(entry["outputBytes"], f"{path}/outputBytes")
                relation = member(entry["outputRelationToLimit"], VOCABULARY["sizeRelations"],
                                  f"{path}/outputRelationToLimit")
                validate_relation(output_bytes, config["maxOutputBytes"], relation,
                                  f"{path}/outputRelationToLimit")
        if seen != request_ids:
            fail(f"{scenario_path}/backend/{category}", "must cover every request exactly once")


def validate_harness(value: Any, request_ids: set[str],
                     scenario_path: str) -> tuple[set[str], set[str]]:
    harness = exact_keys(value, {"barriers", "actions"}, f"{scenario_path}/harness")
    barriers = array(harness["barriers"], f"{scenario_path}/harness/barriers")
    barrier_ids: set[str] = set()
    for index, barrier_value in enumerate(barriers):
        path = f"{scenario_path}/harness/barriers/{index}"
        barrier = exact_keys(barrier_value, {"id", "initialState"}, path)
        barrier_id = string(barrier["id"], f"{path}/id")
        if barrier_id in barrier_ids or barrier["initialState"] != "closed":
            fail(path, "barriers must have unique IDs and start closed")
        barrier_ids.add(barrier_id)
    actions = array(harness["actions"], f"{scenario_path}/harness/actions", nonempty=True)
    for index, action_value in enumerate(actions):
        path = f"{scenario_path}/harness/actions/{index}"
        action = exact_keys(action_value, {"sequence", "actor", "action", "requestId", "barrier"},
                            path)
        if integer(action["sequence"], f"{path}/sequence", minimum=1) != index + 1:
            fail(f"{path}/sequence", "actions must be ordered consecutively from one")
        member(action["actor"], VOCABULARY["actors"], f"{path}/actor")
        member(action["action"], VOCABULARY["actions"], f"{path}/action")
        request_id = string(action["requestId"], f"{path}/requestId", nullable=True)
        barrier = string(action["barrier"], f"{path}/barrier", nullable=True)
        if request_id is not None and request_id not in request_ids:
            fail(f"{path}/requestId", "references an unknown request")
        if barrier is not None and barrier not in barrier_ids:
            fail(f"{path}/barrier", "references an unknown barrier")
    action_names = [action["action"] for action in actions]
    if "start-runtime" not in action_names:
        fail(f"{scenario_path}/harness/actions", "must start the runtime")
    sent = {action["requestId"] for action in actions
            if action["action"] in {"send-request", "probe-health"}}
    if sent != request_ids:
        fail(f"{scenario_path}/harness/actions", "must send or probe every request")
    return set(action_names), barrier_ids


def validate_expected(value: Any, requests: list[dict[str, Any]], request_ids: set[str],
                      config: dict[str, Any], kind: str, actions: set[str],
                      scenario_path: str) -> None:
    expected = exact_keys(value, {"responses", "handlers", "callbacks", "identity",
                                  "observations", "finalCounters"}, f"{scenario_path}/expected")
    request_by_id = {request["id"]: request for request in requests}
    for category in ("responses", "handlers", "callbacks"):
        entries = array(expected[category], f"{scenario_path}/expected/{category}", nonempty=True)
        refs = [entry.get("requestId") if isinstance(entry, dict) else None for entry in entries]
        if set(refs) != request_ids or len(refs) != len(set(refs)):
            fail(f"{scenario_path}/expected/{category}", "must cover every request exactly once")

    responses_by_id: dict[str, dict[str, Any]] = {}
    for index, response_value in enumerate(expected["responses"]):
        path = f"{scenario_path}/expected/responses/{index}"
        response = exact_keys(response_value,
                              {"requestId", "connectionOutcome", "status", "body", "requiredHeaders"},
                              path)
        request_id = string(response["requestId"], f"{path}/requestId")
        outcome = member(response["connectionOutcome"], VOCABULARY["connectionOutcomes"],
                         f"{path}/connectionOutcome")
        status = integer(response["status"], f"{path}/status")
        headers = response["requiredHeaders"]
        if not isinstance(headers, dict) or any(
            not isinstance(key, str) or not key or key != key.lower()
            or not isinstance(header_value, str) or not header_value
            for key, header_value in headers.items()
        ):
            fail(f"{path}/requiredHeaders", "must be a lower-case string map")
        if outcome == "client-disconnected":
            if status != 0 or response["body"] is not None or headers:
                fail(path, "client-disconnected must have status 0, null body and no headers")
        else:
            if not 100 <= status <= 599 or "content-type" not in headers:
                fail(path, "wire response needs an HTTP status and content-type")
            if status >= 400:
                body = exact_keys(response["body"], {"error"}, f"{path}/body")
                error = exact_keys(body["error"], {"code", "message"}, f"{path}/body/error")
                if not re.fullmatch(r"[A-Z][A-Z0-9_]+", string(error["code"], f"{path}/body/error/code")):
                    fail(f"{path}/body/error/code", "must be an upper snake-case code")
                string(error["message"], f"{path}/body/error/message")
        responses_by_id[request_id] = response

    handlers_by_id: dict[str, dict[str, Any]] = {}
    for index, handler_value in enumerate(expected["handlers"]):
        path = f"{scenario_path}/expected/handlers/{index}"
        handler = exact_keys(handler_value, {"requestId", "started", "cancelRequested", "terminal"},
                             path)
        request_id = string(handler["requestId"], f"{path}/requestId")
        started = boolean(handler["started"], f"{path}/started")
        cancelled = boolean(handler["cancelRequested"], f"{path}/cancelRequested")
        terminal = member(handler["terminal"], VOCABULARY["handlerTerminals"], f"{path}/terminal")
        if (terminal == "not-started") != (not started):
            fail(path, "not-started is the only terminal allowed when started is false")
        if cancelled != (terminal in {"timed-out", "cancelled"}):
            fail(path, "cancelRequested must match timed-out/cancelled terminal state")
        if request_by_id[request_id]["role"] == "health" and terminal != "not-started":
            fail(path, "health cannot start a handler")
        handlers_by_id[request_id] = handler

    callbacks_by_id: dict[str, dict[str, Any]] = {}
    for index, callback_value in enumerate(expected["callbacks"]):
        path = f"{scenario_path}/expected/callbacks/{index}"
        callback = exact_keys(
            callback_value,
            {"requestId", "required", "attempted", "delivered", "attempts", "terminal",
             "dispatchAttempts"},
            path,
        )
        request_id = string(callback["requestId"], f"{path}/requestId")
        required = boolean(callback["required"], f"{path}/required")
        attempted = boolean(callback["attempted"], f"{path}/attempted")
        delivered = boolean(callback["delivered"], f"{path}/delivered")
        attempts = integer(callback["attempts"], f"{path}/attempts")
        terminal = member(callback["terminal"], VOCABULARY["callbackTerminals"], f"{path}/terminal")
        dispatch_attempts = array(callback["dispatchAttempts"], f"{path}/dispatchAttempts")
        for attempt_index, attempt in enumerate(dispatch_attempts):
            integer(attempt, f"{path}/dispatchAttempts/{attempt_index}", minimum=1)
        states = {
            "not-required": (False, False, False, 0),
            "rejected-before-handler": (True, False, False, 0),
            "delivered": (True, True, True, attempts),
            "exhausted": (True, True, False, config["callbackMaxAttempts"]),
            "cancelled": (True, True, False, attempts),
        }
        if (required, attempted, delivered, attempts) != states[terminal]:
            fail(path, "callback booleans/attempts contradict terminal state")
        if terminal in {"delivered", "cancelled"} and attempts < 1:
            fail(f"{path}/attempts", "attempted callback must have at least one attempt")
        if len(dispatch_attempts) != attempts:
            fail(f"{path}/dispatchAttempts", "must record every callback delivery attempt")
        request_attempt = request_by_id[request_id]["metadata"]["dispatchAttempt"]
        if any(attempt != request_attempt for attempt in dispatch_attempts):
            fail(f"{path}/dispatchAttempts", "callback retries must echo the current dispatch attempt")
        if not required and request_by_id[request_id]["metadata"]["callbackUrl"] is not None:
            fail(path, "callback URL makes callback required")
        callbacks_by_id[request_id] = callback

    identity = exact_keys(expected["identity"],
                          {"executionId", "requestDispatchAttempts", "runtimeRedispatchCount"},
                          f"{scenario_path}/expected/identity")
    execution_id = string(identity["executionId"], f"{scenario_path}/expected/identity/executionId",
                          nullable=True)
    request_attempts = array(identity["requestDispatchAttempts"],
                             f"{scenario_path}/expected/identity/requestDispatchAttempts")
    invoke_requests = [request for request in requests if request["role"] == "invoke"]
    actual_execution_ids = [request["metadata"]["executionId"] for request in invoke_requests]
    actual_attempts = [request["metadata"]["dispatchAttempt"] for request in invoke_requests]
    if actual_execution_ids:
        if len(set(actual_execution_ids)) != 1 or execution_id != actual_execution_ids[0]:
            fail(f"{scenario_path}/expected/identity/executionId",
                 "must be stable across dispatch retries")
        if request_attempts != actual_attempts:
            fail(f"{scenario_path}/expected/identity/requestDispatchAttempts",
                 "must equal request dispatch attempts")
    elif execution_id is not None or request_attempts:
        fail(f"{scenario_path}/expected/identity", "non-invoke scenario cannot carry identity")
    if kind == "dispatch-retry-identity":
        if len(actual_attempts) < 2 or any(
            right != left + 1 for left, right in zip(actual_attempts, actual_attempts[1:])
        ):
            fail(f"{scenario_path}/expected/identity/requestDispatchAttempts",
                 "dispatch retries must increment")
    if integer(identity["runtimeRedispatchCount"],
               f"{scenario_path}/expected/identity/runtimeRedispatchCount") != 0:
        fail(f"{scenario_path}/expected/identity/runtimeRedispatchCount",
             "runtime must never redispatch")

    observations = unique_strings(expected["observations"],
                                  f"{scenario_path}/expected/observations", nonempty=True)
    if not set(observations) <= set(VOCABULARY["observations"]):
        fail(f"{scenario_path}/expected/observations", "contains an unknown observation")
    final_counters = validate_counters(expected["finalCounters"],
                                       f"{scenario_path}/expected/finalCounters")
    if any(final_counters.values()) or "counters-zero" not in observations:
        fail(f"{scenario_path}/expected/finalCounters",
             "all retained-resource counters must drain to zero and be observed")

    if kind in ERROR_OUTCOMES:
        expected_status, expected_code = ERROR_OUTCOMES[kind]
        response = responses_by_id[next(iter(request_ids))]
        if response["status"] != expected_status or response["body"]["error"]["code"] != expected_code:
            fail(f"{scenario_path}/expected/responses", "does not match canonical error outcome")
    if kind == "input-too-large":
        request_id = next(iter(request_ids))
        if handlers_by_id[request_id]["terminal"] != "not-started":
            fail(f"{scenario_path}/expected/handlers", "input rejection cannot start handler")
    if kind == "output-too-large":
        request_id = next(iter(request_ids))
        if handlers_by_id[request_id]["terminal"] != "output-rejected":
            fail(f"{scenario_path}/expected/handlers", "output rejection requires handler output")
    if kind == "callback-delivery-exhausted":
        callback = callbacks_by_id[next(iter(request_ids))]
        required_observations = {"callback-failure-metric", "structured-log"}
        if callback["terminal"] != "exhausted" or not required_observations <= set(observations):
            fail(f"{scenario_path}/expected", "exhaustion needs exact attempts and observations")
    required_actions = {
        "cancellation": {"cancel-request", "await-barrier"},
        "health-under-saturation": {"fill-callback-capacity", "probe-health"},
        "stop-with-full-queue": {"fill-callback-capacity", "begin-stop", "await-stop"},
        "restart": {"begin-stop", "await-stop", "start-runtime-again"},
        "dispatch-retry-identity": {"control-plane-redispatch"},
    }.get(kind, set())
    if not required_actions <= actions:
        fail(f"{scenario_path}/harness/actions",
             f"{kind} requires actions {sorted(required_actions)}")


def validate_mutation_definitions(value: Any) -> None:
    mutations = array(value, "$/mutationTests", nonempty=True)
    ids: set[str] = set()
    for index, mutation_value in enumerate(mutations):
        path = f"$/mutationTests/{index}"
        mutation = exact_keys(mutation_value, {"id", "operation", "path", "value"}, path)
        mutation_id = string(mutation["id"], f"{path}/id")
        if mutation_id in ids:
            fail(f"{path}/id", "mutation IDs must be unique")
        ids.add(mutation_id)
        if mutation["operation"] not in {"remove", "replace", "remove-value"}:
            fail(f"{path}/operation", "unsupported mutation operation")
        pointer = string(mutation["path"], f"{path}/path")
        if not pointer.startswith("/") or pointer.startswith("/mutationTests"):
            fail(f"{path}/path", "must target the canonical contract")


def apply_mutation(document: dict[str, Any], mutation: dict[str, Any]) -> None:
    tokens = [token.replace("~1", "/").replace("~0", "~")
              for token in mutation["path"].split("/")[1:]]
    target: Any = document
    for token in tokens[:-1]:
        target = target[int(token)] if isinstance(target, list) else target[token]
    final = tokens[-1]
    operation = mutation["operation"]
    if operation == "remove":
        if isinstance(target, list):
            del target[int(final)]
        else:
            del target[final]
    elif operation == "replace":
        if isinstance(target, list):
            target[int(final)] = mutation["value"]
        else:
            target[final] = mutation["value"]
    else:
        values = target[int(final)] if isinstance(target, list) else target[final]
        values.remove(mutation["value"])


def load_document(path: Path) -> dict[str, Any]:
    def reject_constant(value: str) -> None:
        fail("$", f"non-finite JSON number {value} is forbidden")

    with path.open(encoding="utf-8") as source:
        document = json.load(source, parse_constant=reject_constant)
    if not isinstance(document, dict):
        fail("$", "must be an object")
    return document


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--run-mutations", action="store_true")
    parser.add_argument("corpus", type=Path)
    args = parser.parse_args()
    try:
        document = load_document(args.corpus)
        validate_document(document)
        if args.run_mutations:
            for mutation in document["mutationTests"]:
                changed = copy.deepcopy(document)
                apply_mutation(changed, mutation)
                try:
                    validate_document(changed)
                except (ContractError, KeyError, IndexError, TypeError):
                    continue
                fail(f"$/mutationTests/{mutation['id']}", "mutation was incorrectly accepted")
    except (ContractError, OSError, json.JSONDecodeError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
