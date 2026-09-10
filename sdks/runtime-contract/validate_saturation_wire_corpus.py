#!/usr/bin/env python3
"""Schema-mechanical validator for the source-driven SDK saturation contract."""

from __future__ import annotations

import argparse
import copy
import json
import math
import re
import sys
from pathlib import Path
from typing import Any
from urllib.parse import quote


class ContractError(ValueError):
    pass


VOCABULARY_KEYS = {
    "scenarioKinds", "implementationOwners", "requestRoles", "sizeRelations",
    "handlerBehaviors", "callbackBehaviors", "actors", "actions", "handlerTerminals",
    "callbackTerminals", "connectionOutcomes", "observations", "counterNames",
}
CONFIG_KEYS = {
    "maxConcurrentHandlers", "maxInputBytes", "maxOutputBytes", "maxPendingCallbacks",
    "maxPendingCallbackBytes", "handlerTimeoutMs", "callbackAttemptTimeoutMs",
    "callbackMaxAttempts", "bodyReadTimeoutMs", "shutdownTimeoutMs",
}
DEFINITION_KEYS = {
    "vocabulary", "actorActionCompatibility", "handlerLifecycles",
    "handlerBehaviorLifecycleRefs", "callbackLifecycles",
    "callbackBehaviorLifecycleRefs", "wireOutcomes", "callbackEnvelopes",
    "callbackRequestTemplate", "sizeRelationOperators", "finalCountersRule",
    "identityRules", "crossFieldRules",
    "observationSets",
}
RULE_KEYS = {
    "all-equal": {"id", "scope", "operator", "source", "expected", "ignoreNull"},
    "sequence-equals": {"id", "scope", "operator", "source", "expected", "ignoreNull"},
    "strictly-increasing-by": {"id", "scope", "operator", "source", "increment"},
    "repeat-request-field": {"id", "scope", "operator", "source", "expected"},
    "equals": {"id", "scope", "operator", "source", "value"},
    "presence-equals": {"id", "scope", "operator", "source", "expected"},
    "positive-iff": {"id", "scope", "operator", "source", "expected"},
    "implies": {"id", "scope", "operator", "source", "expected"},
    "length-equals": {"id", "scope", "operator", "source", "expected"},
    "presence-iff-positive": {"id", "scope", "operator", "source", "expected"},
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


def object_map(value: Any, path: str, *, nonempty: bool = False) -> dict[str, Any]:
    if not isinstance(value, dict) or (nonempty and not value):
        fail(path, "must be a non-empty object" if nonempty else "must be an object")
    for key in value:
        string(key, f"{path} key")
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


def finite_json(value: Any, path: str) -> None:
    if isinstance(value, float) and not math.isfinite(value):
        fail(path, "must not contain a non-finite number")
    if isinstance(value, list):
        for index, item in enumerate(value):
            finite_json(item, f"{path}/{index}")
    elif isinstance(value, dict):
        for key, item in value.items():
            string(key, f"{path} key")
            finite_json(item, f"{path}/{key}")


def validate_counters(value: Any, names: list[str], path: str) -> dict[str, int]:
    counters = exact_keys(value, set(names), path)
    for name, count in counters.items():
        integer(count, f"{path}/{name}")
    return counters


def validate_rule(rule_value: Any, path: str) -> dict[str, Any]:
    rule = object_map(rule_value, path)
    operator = string(rule.get("operator"), f"{path}/operator")
    keys = RULE_KEYS.get(operator)
    if keys is None:
        fail(f"{path}/operator", "is not a supported generic validation operator")
    exact_keys(rule, keys, path)
    string(rule["id"], f"{path}/id")
    member(rule["scope"], ["scenario", "callback"], f"{path}/scope")
    string(rule["source"], f"{path}/source")
    if "expected" in rule:
        string(rule["expected"], f"{path}/expected")
    if "ignoreNull" in rule:
        boolean(rule["ignoreNull"], f"{path}/ignoreNull")
    if "increment" in rule:
        integer(rule["increment"], f"{path}/increment", minimum=1)
    finite_json(rule.get("value"), f"{path}/value")
    return rule


def validate_expression(value: Any, path: str) -> None:
    expression = object_map(value, path)
    operator = string(expression.get("operator"), f"{path}/operator")
    if operator == "literal":
        exact_keys(expression, {"operator", "value"}, path)
        finite_json(expression["value"], f"{path}/value")
    elif operator in {"field", "decimal-field"}:
        exact_keys(expression, {"operator", "path"}, path)
        string(expression["path"], f"{path}/path")
    elif operator == "join-callback-url":
        exact_keys(expression, {"operator", "callbackUrlPath", "executionIdPath", "suffix"}, path)
        string(expression["callbackUrlPath"], f"{path}/callbackUrlPath")
        string(expression["executionIdPath"], f"{path}/executionIdPath")
        string(expression["suffix"], f"{path}/suffix")
    else:
        fail(f"{path}/operator", "is not a supported generic projection operator")


def validate_template(value: Any, path: str) -> None:
    if isinstance(value, dict):
        if "operator" in value:
            validate_expression(value, path)
            return
        for key, item in value.items():
            string(key, f"{path} key")
            validate_template(item, f"{path}/{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            validate_template(item, f"{path}/{index}")
    else:
        finite_json(value, path)


def validate_definitions(value: Any, path: str) -> tuple[dict[str, Any], dict[str, list[str]]]:
    definitions = exact_keys(value, DEFINITION_KEYS, path)
    vocabulary = exact_keys(definitions["vocabulary"], VOCABULARY_KEYS, f"{path}/vocabulary")
    for name, items in vocabulary.items():
        unique_strings(items, f"{path}/vocabulary/{name}", nonempty=True)

    compatibility = exact_keys(
        definitions["actorActionCompatibility"], set(vocabulary["actors"]),
        f"{path}/actorActionCompatibility",
    )
    assigned_actions: set[str] = set()
    for actor, actions_value in compatibility.items():
        actions = unique_strings(actions_value, f"{path}/actorActionCompatibility/{actor}")
        if not set(actions) <= set(vocabulary["actions"]):
            fail(f"{path}/actorActionCompatibility/{actor}", "contains an unknown action")
        assigned_actions.update(actions)
    if assigned_actions != set(vocabulary["actions"]):
        fail(f"{path}/actorActionCompatibility", "must assign every action in the vocabulary")

    handler_lifecycles = object_map(
        definitions["handlerLifecycles"], f"{path}/handlerLifecycles", nonempty=True
    )
    for name, lifecycle_value in handler_lifecycles.items():
        lifecycle = exact_keys(
            lifecycle_value, {"started", "cancelRequested", "terminal"},
            f"{path}/handlerLifecycles/{name}",
        )
        boolean(lifecycle["started"], f"{path}/handlerLifecycles/{name}/started")
        boolean(lifecycle["cancelRequested"], f"{path}/handlerLifecycles/{name}/cancelRequested")
        member(lifecycle["terminal"], vocabulary["handlerTerminals"],
               f"{path}/handlerLifecycles/{name}/terminal")
    validate_compatibility_map(
        definitions["handlerBehaviorLifecycleRefs"], vocabulary["handlerBehaviors"],
        handler_lifecycles, f"{path}/handlerBehaviorLifecycleRefs",
    )

    callback_lifecycles = object_map(
        definitions["callbackLifecycles"], f"{path}/callbackLifecycles", nonempty=True
    )
    for name, lifecycle_value in callback_lifecycles.items():
        lifecycle = exact_keys(
            lifecycle_value, {"required", "attempted", "delivered", "attempts", "terminal"},
            f"{path}/callbackLifecycles/{name}",
        )
        for flag in ("required", "attempted", "delivered"):
            boolean(lifecycle[flag], f"{path}/callbackLifecycles/{name}/{flag}")
        attempt_rule = object_map(
            lifecycle["attempts"], f"{path}/callbackLifecycles/{name}/attempts"
        )
        operator = string(attempt_rule.get("operator"),
                          f"{path}/callbackLifecycles/{name}/attempts/operator")
        if operator == "literal":
            exact_keys(attempt_rule, {"operator", "value"},
                       f"{path}/callbackLifecycles/{name}/attempts")
            integer(attempt_rule["value"],
                    f"{path}/callbackLifecycles/{name}/attempts/value")
        elif operator == "config-field":
            exact_keys(attempt_rule, {"operator", "field"},
                       f"{path}/callbackLifecycles/{name}/attempts")
            member(attempt_rule["field"], list(CONFIG_KEYS),
                   f"{path}/callbackLifecycles/{name}/attempts/field")
        else:
            fail(f"{path}/callbackLifecycles/{name}/attempts/operator",
                 "is not a supported generic value operator")
        member(lifecycle["terminal"], vocabulary["callbackTerminals"],
               f"{path}/callbackLifecycles/{name}/terminal")
    validate_compatibility_map(
        definitions["callbackBehaviorLifecycleRefs"], vocabulary["callbackBehaviors"],
        callback_lifecycles, f"{path}/callbackBehaviorLifecycleRefs",
    )

    outcomes = object_map(definitions["wireOutcomes"], f"{path}/wireOutcomes", nonempty=True)
    for name, outcome_value in outcomes.items():
        outcome = exact_keys(
            outcome_value, {"connectionOutcome", "status", "body", "requiredHeaders"},
            f"{path}/wireOutcomes/{name}",
        )
        member(outcome["connectionOutcome"], vocabulary["connectionOutcomes"],
               f"{path}/wireOutcomes/{name}/connectionOutcome")
        integer(outcome["status"], f"{path}/wireOutcomes/{name}/status")
        finite_json(outcome["body"], f"{path}/wireOutcomes/{name}/body")
        validate_string_map(outcome["requiredHeaders"],
                            f"{path}/wireOutcomes/{name}/requiredHeaders")

    request_template = exact_keys(
        definitions["callbackRequestTemplate"], {"method", "url", "headers"},
        f"{path}/callbackRequestTemplate",
    )
    string(request_template["method"], f"{path}/callbackRequestTemplate/method")
    validate_expression(request_template["url"], f"{path}/callbackRequestTemplate/url")
    template_headers = object_map(
        request_template["headers"], f"{path}/callbackRequestTemplate/headers", nonempty=True
    )
    for header, expression in template_headers.items():
        string(header, f"{path}/callbackRequestTemplate/headers key")
        validate_expression(expression, f"{path}/callbackRequestTemplate/headers/{header}")

    envelopes = object_map(
        definitions["callbackEnvelopes"], f"{path}/callbackEnvelopes", nonempty=True
    )
    for name, envelope_value in envelopes.items():
        envelope = exact_keys(
            envelope_value, {"emitsRequest", "payload"}, f"{path}/callbackEnvelopes/{name}"
        )
        emits_request = boolean(
            envelope["emitsRequest"], f"{path}/callbackEnvelopes/{name}/emitsRequest"
        )
        if not emits_request and envelope["payload"] is not None:
            fail(f"{path}/callbackEnvelopes/{name}/payload",
                 "must be null when no request is emitted")
        validate_template(envelope["payload"], f"{path}/callbackEnvelopes/{name}/payload")

    relations = exact_keys(
        definitions["sizeRelationOperators"], set(vocabulary["sizeRelations"]),
        f"{path}/sizeRelationOperators",
    )
    for name, operator in relations.items():
        member(operator, ["less-than", "greater-than", "zero"],
               f"{path}/sizeRelationOperators/{name}")

    final_rule = exact_keys(
        definitions["finalCountersRule"], {"operator"}, f"{path}/finalCountersRule"
    )
    member(final_rule["operator"], ["all-zero"], f"{path}/finalCountersRule/operator")

    observation_sets = object_map(
        definitions["observationSets"], f"{path}/observationSets", nonempty=True
    )
    for name, items in observation_sets.items():
        observations = unique_strings(items, f"{path}/observationSets/{name}", nonempty=True)
        if not set(observations) <= set(vocabulary["observations"]):
            fail(f"{path}/observationSets/{name}", "contains an unknown observation")

    identity_rules = array(definitions["identityRules"], f"{path}/identityRules", nonempty=True)
    cross_rules = array(definitions["crossFieldRules"], f"{path}/crossFieldRules", nonempty=True)
    rules = [validate_rule(item, f"{path}/identityRules/{index}")
             for index, item in enumerate(identity_rules)]
    rules += [validate_rule(item, f"{path}/crossFieldRules/{index}")
              for index, item in enumerate(cross_rules)]
    rule_ids = [rule["id"] for rule in rules]
    if len(rule_ids) != len(set(rule_ids)):
        fail(path, "rule IDs must be unique")
    return definitions, vocabulary


def validate_compatibility_map(value: Any, keys: list[str], referenced: dict[str, Any],
                               path: str) -> None:
    mapping = exact_keys(value, set(keys), path)
    for name, refs_value in mapping.items():
        refs = unique_strings(refs_value, f"{path}/{name}", nonempty=True)
        if not set(refs) <= set(referenced):
            fail(f"{path}/{name}", "contains an unknown reference")


def validate_string_map(value: Any, path: str) -> dict[str, str]:
    result = object_map(value, path)
    for key, item in result.items():
        string(key, f"{path} key")
        string(item, f"{path}/{key}")
    return result


def apply_size_relation(size: int, limit: int, relation: str,
                        definitions: dict[str, Any], path: str) -> None:
    operator = definitions["sizeRelationOperators"][relation]
    valid = ((operator == "less-than" and size < limit)
             or (operator == "greater-than" and size > limit)
             or (operator == "zero" and size == 0))
    if not valid:
        fail(path, f"does not satisfy referenced {operator} relation")


def validate_document(document: Any) -> None:
    finite_json(document, "$")
    root = exact_keys(
        document,
        {"schemaVersion", "contractDefinitions", "policy", "runtimeConfigurations",
         "scenarios", "mutationTests"},
        "$",
    )
    if root["schemaVersion"] != "nanofaas.runtime-saturation/v3":
        fail("$/schemaVersion", "unsupported schema version")

    definition_sets = object_map(
        root["contractDefinitions"], "$/contractDefinitions", nonempty=True
    )
    policy = exact_keys(
        root["policy"], {"maximumScenarioDeadlineMs", "definitionsRef"}, "$/policy"
    )
    definition_ref = string(policy["definitionsRef"], "$/policy/definitionsRef")
    if definition_ref not in definition_sets:
        fail("$/policy/definitionsRef", "does not name a contract definition set")
    definitions, vocabulary = validate_definitions(
        definition_sets[definition_ref], f"$/contractDefinitions/{definition_ref}"
    )
    maximum_deadline = integer(
        policy["maximumScenarioDeadlineMs"], "$/policy/maximumScenarioDeadlineMs", minimum=1
    )
    if maximum_deadline > 60_000:
        fail("$/policy/maximumScenarioDeadlineMs", "must be at most 60000")

    configurations = object_map(
        root["runtimeConfigurations"], "$/runtimeConfigurations", nonempty=True
    )
    for name, config_value in configurations.items():
        config = exact_keys(config_value, CONFIG_KEYS, f"$/runtimeConfigurations/{name}")
        for key, item in config.items():
            integer(item, f"$/runtimeConfigurations/{name}/{key}", minimum=1)

    scenarios = array(root["scenarios"], "$/scenarios", nonempty=True)
    ids: list[str] = []
    kinds: list[str] = []
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
        kinds.append(member(scenario["kind"], vocabulary["scenarioKinds"], f"{path}/kind"))
        owners = unique_strings(
            scenario["implementationOwners"], f"{path}/implementationOwners", nonempty=True
        )
        if not set(owners) <= set(vocabulary["implementationOwners"]):
            fail(f"{path}/implementationOwners", "contains an unknown owner")
        config_ref = string(scenario["runtimeConfigRef"], f"{path}/runtimeConfigRef")
        if config_ref not in configurations:
            fail(f"{path}/runtimeConfigRef", "does not name a runtime configuration")
        config = configurations[config_ref]
        deadline = integer(scenario["deadlineMs"], f"{path}/deadlineMs", minimum=1)
        if deadline > maximum_deadline:
            fail(f"{path}/deadlineMs", "exceeds maximumScenarioDeadlineMs")

        requests = validate_requests(
            scenario["requests"], config, definitions, vocabulary, path
        )
        request_ids = {request["id"] for request in requests}
        actions, barriers = validate_harness(
            scenario["harness"], request_ids, definitions, vocabulary, path
        )
        backend = validate_backend(
            scenario["backend"], requests, request_ids, config, barriers,
            definitions, vocabulary, path,
        )
        initial = validate_counters(
            scenario["initialCounters"], vocabulary["counterNames"],
            f"{path}/initialCounters",
        )
        validate_initial_bounds(initial, config, path)
        validate_expected(
            scenario, requests, request_ids, backend, config, actions,
            definitions, vocabulary, path,
        )

    if len(ids) != len(set(ids)):
        fail("$/scenarios", "scenario IDs must be unique")
    if len(kinds) != len(set(kinds)) or set(kinds) != set(vocabulary["scenarioKinds"]):
        fail("$/scenarios", "must contain exactly one scenario for each defined kind")
    validate_mutation_definitions(root["mutationTests"])


def validate_requests(value: Any, config: dict[str, Any], definitions: dict[str, Any],
                      vocabulary: dict[str, list[str]], scenario_path: str) -> list[dict[str, Any]]:
    requests = array(value, f"{scenario_path}/requests", nonempty=True)
    seen: set[str] = set()
    for index, request_value in enumerate(requests):
        path = f"{scenario_path}/requests/{index}"
        request = exact_keys(
            request_value, {"id", "role", "method", "path", "metadata", "payload"}, path
        )
        request_id = string(request["id"], f"{path}/id")
        if request_id in seen:
            fail(f"{path}/id", "must be unique")
        seen.add(request_id)
        member(request["role"], vocabulary["requestRoles"], f"{path}/role")
        string(request["method"], f"{path}/method")
        string(request["path"], f"{path}/path")
        metadata = exact_keys(
            request["metadata"],
            {"executionId", "dispatchAttempt", "traceId", "callbackUrl"},
            f"{path}/metadata",
        )
        string(metadata["executionId"], f"{path}/metadata/executionId", nullable=True)
        if metadata["dispatchAttempt"] is not None:
            integer(metadata["dispatchAttempt"], f"{path}/metadata/dispatchAttempt", minimum=1)
        string(metadata["traceId"], f"{path}/metadata/traceId", nullable=True)
        string(metadata["callbackUrl"], f"{path}/metadata/callbackUrl", nullable=True)
        payload = exact_keys(
            request["payload"], {"inputBytes", "relationToInputLimit"}, f"{path}/payload"
        )
        size = integer(payload["inputBytes"], f"{path}/payload/inputBytes")
        relation = member(
            payload["relationToInputLimit"], vocabulary["sizeRelations"],
            f"{path}/payload/relationToInputLimit",
        )
        apply_size_relation(
            size, config["maxInputBytes"], relation, definitions,
            f"{path}/payload/relationToInputLimit",
        )
    return requests


def validate_harness(value: Any, request_ids: set[str], definitions: dict[str, Any],
                     vocabulary: dict[str, list[str]], scenario_path: str
                     ) -> tuple[list[dict[str, Any]], set[str]]:
    harness = exact_keys(value, {"barriers", "actions"}, f"{scenario_path}/harness")
    barriers: set[str] = set()
    for index, barrier_value in enumerate(array(harness["barriers"], f"{scenario_path}/harness/barriers")):
        path = f"{scenario_path}/harness/barriers/{index}"
        barrier = exact_keys(barrier_value, {"id", "initialState"}, path)
        barrier_id = string(barrier["id"], f"{path}/id")
        if barrier_id in barriers:
            fail(f"{path}/id", "must be unique")
        barriers.add(barrier_id)
        string(barrier["initialState"], f"{path}/initialState")
    actions = array(harness["actions"], f"{scenario_path}/harness/actions", nonempty=True)
    for index, action_value in enumerate(actions):
        path = f"{scenario_path}/harness/actions/{index}"
        action = exact_keys(
            action_value, {"sequence", "actor", "action", "requestId", "barrier"}, path
        )
        if integer(action["sequence"], f"{path}/sequence", minimum=1) != index + 1:
            fail(f"{path}/sequence", "must be contiguous and ordered from one")
        actor = member(action["actor"], vocabulary["actors"], f"{path}/actor")
        action_name = member(action["action"], vocabulary["actions"], f"{path}/action")
        if action_name not in definitions["actorActionCompatibility"][actor]:
            fail(path, "actor is not permitted to perform this action")
        request_id = string(action["requestId"], f"{path}/requestId", nullable=True)
        if request_id is not None and request_id not in request_ids:
            fail(f"{path}/requestId", "does not name a request")
        barrier = string(action["barrier"], f"{path}/barrier", nullable=True)
        if barrier is not None and barrier not in barriers:
            fail(f"{path}/barrier", "does not name a barrier")
    return actions, barriers


def validate_backend(value: Any, requests: list[dict[str, Any]], request_ids: set[str],
                     config: dict[str, Any], barriers: set[str],
                     definitions: dict[str, Any], vocabulary: dict[str, list[str]],
                     scenario_path: str) -> dict[str, dict[str, dict[str, Any]]]:
    backend = exact_keys(value, {"handlers", "callbacks"}, f"{scenario_path}/backend")
    result: dict[str, dict[str, dict[str, Any]]] = {}
    for family, behavior_key, relation_limit in (
        ("handlers", "handlerBehaviors", "maxOutputBytes"),
        ("callbacks", "callbackBehaviors", None),
    ):
        entries = array(backend[family], f"{scenario_path}/backend/{family}", nonempty=True)
        by_request: dict[str, dict[str, Any]] = {}
        for index, entry_value in enumerate(entries):
            path = f"{scenario_path}/backend/{family}/{index}"
            keys = {"requestId", "behavior", "barrier"}
            if family == "handlers":
                keys |= {"outputBytes", "outputRelationToLimit"}
            entry = exact_keys(entry_value, keys, path)
            request_id = string(entry["requestId"], f"{path}/requestId")
            if request_id not in request_ids or request_id in by_request:
                fail(f"{path}/requestId", "must name one unique request")
            by_request[request_id] = entry
            member(entry["behavior"], vocabulary[behavior_key], f"{path}/behavior")
            barrier = string(entry["barrier"], f"{path}/barrier", nullable=True)
            if barrier is not None and barrier not in barriers:
                fail(f"{path}/barrier", "does not name a barrier")
            if family == "handlers":
                size = integer(entry["outputBytes"], f"{path}/outputBytes")
                relation = member(
                    entry["outputRelationToLimit"], vocabulary["sizeRelations"],
                    f"{path}/outputRelationToLimit",
                )
                apply_size_relation(
                    size, config[relation_limit], relation, definitions,
                    f"{path}/outputRelationToLimit",
                )
        if set(by_request) != request_ids:
            fail(f"{scenario_path}/backend/{family}", "must contain one entry per request")
        result[family] = by_request
    return result


def validate_initial_bounds(initial: dict[str, int], config: dict[str, int],
                            scenario_path: str) -> None:
    pairs = (
        ("activeHandlers", "maxConcurrentHandlers"),
        ("pendingCallbacks", "maxPendingCallbacks"),
        ("pendingCallbackBytes", "maxPendingCallbackBytes"),
    )
    for counter, limit in pairs:
        if initial[counter] > config[limit]:
            fail(f"{scenario_path}/initialCounters/{counter}", f"exceeds {limit}")


def validate_expected(scenario: dict[str, Any], requests: list[dict[str, Any]],
                      request_ids: set[str], backend: dict[str, dict[str, dict[str, Any]]],
                      config: dict[str, Any], actions: list[dict[str, Any]],
                      definitions: dict[str, Any], vocabulary: dict[str, list[str]],
                      scenario_path: str) -> None:
    expected = exact_keys(
        scenario["expected"],
        {"responses", "handlers", "callbacks", "identity", "observationSetRef",
         "observations", "finalCounters"},
        f"{scenario_path}/expected",
    )
    responses = indexed_expected(
        expected["responses"], request_ids,
        {"requestId", "outcomeRef", "connectionOutcome", "status", "body", "requiredHeaders"},
        f"{scenario_path}/expected/responses",
    )
    for request_id, response in responses.items():
        path = f"{scenario_path}/expected/responses/{request_id}"
        outcome_ref = string(response["outcomeRef"], f"{path}/outcomeRef")
        if outcome_ref not in definitions["wireOutcomes"]:
            fail(f"{path}/outcomeRef", "does not name a wire outcome")
        projected = {
            "connectionOutcome": response["connectionOutcome"],
            "status": response["status"],
            "body": response["body"],
            "requiredHeaders": response["requiredHeaders"],
        }
        if projected != definitions["wireOutcomes"][outcome_ref]:
            fail(path, "does not exactly match referenced wire outcome")
        validate_string_map(response["requiredHeaders"], f"{path}/requiredHeaders")
        finite_json(response["body"], f"{path}/body")

    handlers = indexed_expected(
        expected["handlers"], request_ids,
        {"requestId", "lifecycleRef", "started", "cancelRequested", "terminal"},
        f"{scenario_path}/expected/handlers",
    )
    for request_id, handler in handlers.items():
        path = f"{scenario_path}/expected/handlers/{request_id}"
        lifecycle_ref = string(handler["lifecycleRef"], f"{path}/lifecycleRef")
        lifecycle = definitions["handlerLifecycles"].get(lifecycle_ref)
        if lifecycle is None:
            fail(f"{path}/lifecycleRef", "does not name a handler lifecycle")
        projected = {key: handler[key] for key in ("started", "cancelRequested", "terminal")}
        if projected != lifecycle:
            fail(path, "does not exactly match referenced handler lifecycle")
        behavior = backend["handlers"][request_id]["behavior"]
        if lifecycle_ref not in definitions["handlerBehaviorLifecycleRefs"][behavior]:
            fail(path, "handler behavior is incompatible with referenced lifecycle")

    callbacks = indexed_expected(
        expected["callbacks"], request_ids,
        {"requestId", "lifecycleRef", "envelopeRef", "required", "attempted", "delivered",
         "attempts", "terminal", "dispatchAttempts", "requestProjection"},
        f"{scenario_path}/expected/callbacks",
    )
    request_by_id = {request["id"]: request for request in requests}
    for request_id, callback in callbacks.items():
        path = f"{scenario_path}/expected/callbacks/{request_id}"
        lifecycle_ref = string(callback["lifecycleRef"], f"{path}/lifecycleRef")
        lifecycle = definitions["callbackLifecycles"].get(lifecycle_ref)
        if lifecycle is None:
            fail(f"{path}/lifecycleRef", "does not name a callback lifecycle")
        expected_attempts = evaluate_value_rule(
            lifecycle["attempts"], config, f"{path}/attempts"
        )
        projected = {key: callback[key] for key in ("required", "attempted", "delivered", "terminal")}
        canonical = {key: lifecycle[key] for key in ("required", "attempted", "delivered", "terminal")}
        if projected != canonical or callback["attempts"] != expected_attempts:
            fail(path, "does not exactly match referenced callback lifecycle")
        behavior = backend["callbacks"][request_id]["behavior"]
        if lifecycle_ref not in definitions["callbackBehaviorLifecycleRefs"][behavior]:
            fail(path, "callback behavior is incompatible with referenced lifecycle")
        envelope_ref = string(callback["envelopeRef"], f"{path}/envelopeRef")
        envelope = definitions["callbackEnvelopes"].get(envelope_ref)
        if envelope is None:
            fail(f"{path}/envelopeRef", "does not name a callback envelope")
        request_projection = callback["requestProjection"]
        if envelope["emitsRequest"]:
            template = {
                **definitions["callbackRequestTemplate"],
                "payload": envelope["payload"],
            }
            rendered = render_template(
                template, {"request": request_by_id[request_id]}, f"{path}/requestProjection"
            )
            if request_projection != rendered:
                fail(f"{path}/requestProjection",
                     "does not match referenced callback transport and envelope")
            validate_callback_request(request_projection, f"{path}/requestProjection")
        elif request_projection is not None:
            fail(f"{path}/requestProjection", "must be null when envelope emits no request")

    identity = exact_keys(
        expected["identity"],
        {"executionId", "requestDispatchAttempts", "runtimeRedispatchCount"},
        f"{scenario_path}/expected/identity",
    )
    string(identity["executionId"], f"{scenario_path}/expected/identity/executionId", nullable=True)
    for index, attempt in enumerate(
        array(identity["requestDispatchAttempts"],
              f"{scenario_path}/expected/identity/requestDispatchAttempts")
    ):
        integer(attempt, f"{scenario_path}/expected/identity/requestDispatchAttempts/{index}",
                minimum=1)
    integer(identity["runtimeRedispatchCount"],
            f"{scenario_path}/expected/identity/runtimeRedispatchCount")

    observations = unique_strings(
        expected["observations"], f"{scenario_path}/expected/observations", nonempty=True
    )
    if not set(observations) <= set(vocabulary["observations"]):
        fail(f"{scenario_path}/expected/observations", "contains an unknown observation")
    observation_ref = string(
        expected["observationSetRef"], f"{scenario_path}/expected/observationSetRef"
    )
    canonical_observations = definitions["observationSets"].get(observation_ref)
    if canonical_observations is None:
        fail(f"{scenario_path}/expected/observationSetRef", "does not name an observation set")
    if observations != canonical_observations:
        fail(f"{scenario_path}/expected/observations",
             "does not exactly match referenced observation set")
    final = validate_counters(
        expected["finalCounters"], vocabulary["counterNames"],
        f"{scenario_path}/expected/finalCounters",
    )
    apply_final_rule(final, definitions["finalCountersRule"], scenario_path)

    context = {"requests": requests, "expected": expected}
    apply_rules(definitions["identityRules"], context, callbacks, request_by_id, scenario_path)
    apply_rules(definitions["crossFieldRules"], context, callbacks, request_by_id, scenario_path)
    if not actions:
        fail(f"{scenario_path}/harness/actions", "must be non-empty")


def indexed_expected(value: Any, request_ids: set[str], keys: set[str],
                     path: str) -> dict[str, dict[str, Any]]:
    entries = array(value, path, nonempty=True)
    result: dict[str, dict[str, Any]] = {}
    for index, entry_value in enumerate(entries):
        entry_path = f"{path}/{index}"
        entry = exact_keys(entry_value, keys, entry_path)
        request_id = string(entry["requestId"], f"{entry_path}/requestId")
        if request_id not in request_ids or request_id in result:
            fail(f"{entry_path}/requestId", "must name one unique request")
        result[request_id] = entry
    if set(result) != request_ids:
        fail(path, "must contain one entry per request")
    return result


def evaluate_value_rule(rule: dict[str, Any], config: dict[str, Any], path: str) -> int:
    if rule["operator"] == "literal":
        return rule["value"]
    if rule["operator"] == "config-field":
        field = rule["field"]
        if field not in config:
            fail(path, "references a missing runtime configuration field")
        return config[field]
    fail(path, "uses an unsupported value operator")


def resolve_path(context: Any, path: str) -> Any:
    current = context
    for part in path.split("."):
        if part.endswith("[]"):
            key = part[:-2]
            current = current[key]
            continue
        current = current[part]
    return current


def render_template(value: Any, context: dict[str, Any], path: str) -> Any:
    if isinstance(value, dict) and "operator" in value:
        operator = value["operator"]
        if operator == "literal":
            return copy.deepcopy(value["value"])
        if operator == "field":
            return resolve_path(context, value["path"])
        if operator == "decimal-field":
            source = resolve_path(context, value["path"])
            if isinstance(source, bool) or not isinstance(source, int):
                fail(path, "decimal-field source must be an integer")
            return str(source)
        if operator == "join-callback-url":
            callback_url = resolve_path(context, value["callbackUrlPath"])
            execution_id = resolve_path(context, value["executionIdPath"])
            if not isinstance(callback_url, str) or not isinstance(execution_id, str):
                fail(path, "join-callback-url sources must be strings")
            return f"{callback_url.rstrip('/')}/{quote(execution_id, safe='')}{value['suffix']}"
        fail(path, "uses an unsupported projection operator")
    if isinstance(value, dict):
        return {key: render_template(item, context, f"{path}/{key}")
                for key, item in value.items()}
    if isinstance(value, list):
        return [render_template(item, context, f"{path}/{index}")
                for index, item in enumerate(value)]
    return copy.deepcopy(value)


def validate_callback_request(value: Any, path: str) -> None:
    request = exact_keys(value, {"method", "url", "headers", "payload"}, path)
    string(request["method"], f"{path}/method")
    string(request["url"], f"{path}/url")
    validate_string_map(request["headers"], f"{path}/headers")
    finite_json(request["payload"], f"{path}/payload")


def apply_final_rule(counters: dict[str, int], rule: dict[str, Any],
                     scenario_path: str) -> None:
    if rule["operator"] == "all-zero" and any(counters.values()):
        fail(f"{scenario_path}/expected/finalCounters", "does not satisfy all-zero")
    if rule["operator"] != "all-zero":
        fail(f"{scenario_path}/expected/finalCounters", "uses an unsupported operator")


def resolve_rule_source(context: dict[str, Any], path: str) -> Any:
    if "[]" not in path:
        return resolve_path(context, path)
    prefix, suffix = path.split("[]", 1)
    sequence = resolve_path(context, prefix.rstrip("."))
    tail = suffix.lstrip(".")
    return [resolve_path(item, tail) if tail else item for item in sequence]


def apply_rules(rules: list[dict[str, Any]], scenario_context: dict[str, Any],
                callbacks: dict[str, dict[str, Any]],
                request_by_id: dict[str, dict[str, Any]], scenario_path: str) -> None:
    for rule in rules:
        path = f"{scenario_path}/rule/{rule['id']}"
        contexts: list[dict[str, Any]]
        if rule["scope"] == "scenario":
            contexts = [scenario_context]
        else:
            contexts = [
                {"request": request_by_id[request_id], "callback": callback}
                for request_id, callback in callbacks.items()
            ]
        for context in contexts:
            source = resolve_rule_source(context, rule["source"])
            expected = (resolve_rule_source(context, rule["expected"])
                        if "expected" in rule else None)
            operator = rule["operator"]
            valid = True
            if operator == "all-equal":
                items = [item for item in source if item is not None] if rule["ignoreNull"] else source
                valid = all(item == expected for item in items)
            elif operator == "sequence-equals":
                items = [item for item in source if item is not None] if rule["ignoreNull"] else source
                valid = items == expected
            elif operator == "strictly-increasing-by":
                valid = all(
                    right - left == rule["increment"]
                    for left, right in zip(source, source[1:])
                )
            elif operator == "repeat-request-field":
                valid = all(item == source for item in expected)
            elif operator == "equals":
                valid = source == rule["value"]
            elif operator == "presence-equals":
                valid = (source is not None) == expected
            elif operator == "positive-iff":
                valid = (source > 0) == expected
            elif operator == "implies":
                valid = (not source) or bool(expected)
            elif operator == "length-equals":
                valid = len(source) == expected
            elif operator == "presence-iff-positive":
                valid = (source is not None) == (expected > 0)
            else:
                fail(path, "uses an unsupported generic validation operator")
            if not valid:
                fail(path, f"{operator} relation is not satisfied")


def validate_mutation_definitions(value: Any) -> None:
    mutations = array(value, "$/mutationTests", nonempty=True)
    ids: set[str] = set()
    for index, mutation_value in enumerate(mutations):
        path = f"$/mutationTests/{index}"
        mutation = exact_keys(mutation_value, {"id", "operation", "path", "value"}, path)
        mutation_id = string(mutation["id"], f"{path}/id")
        if mutation_id in ids:
            fail(f"{path}/id", "must be unique")
        ids.add(mutation_id)
        member(mutation["operation"], ["remove", "replace", "remove-value"],
               f"{path}/operation")
        pointer = string(mutation["path"], f"{path}/path")
        if not pointer.startswith("/"):
            fail(f"{path}/path", "must be a JSON pointer")
        finite_json(mutation["value"], f"{path}/value")


def apply_mutation(document: dict[str, Any], mutation: dict[str, Any]) -> None:
    segments = [
        segment.replace("~1", "/").replace("~0", "~")
        for segment in mutation["path"].split("/")[1:]
    ]
    target: Any = document
    for segment in segments[:-1]:
        target = target[int(segment)] if isinstance(target, list) else target[segment]
    final = segments[-1]
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
    elif operation == "remove-value":
        target = target[int(final)] if isinstance(target, list) else target[final]
        target.remove(mutation["value"])
    else:
        fail("$/mutationTests", "unsupported mutation operation")


def load_document(path: Path) -> dict[str, Any]:
    def reject_constant(value: str) -> None:
        raise ContractError(f"non-finite JSON number {value} is forbidden")

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
                mutated = copy.deepcopy(document)
                apply_mutation(mutated, mutation)
                try:
                    validate_document(mutated)
                except (ContractError, KeyError, IndexError, TypeError):
                    continue
                fail(f"$/mutationTests/{mutation['id']}", "mutation was incorrectly accepted")
    except (ContractError, KeyError, IndexError, TypeError, OSError, json.JSONDecodeError) as error:
        print(error, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
