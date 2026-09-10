import assert from "node:assert/strict";
import { execFile } from "node:child_process";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { promisify } from "node:util";
import { test } from "node:test";

const execute = promisify(execFile);
type JsonRecord = Record<string, unknown>;
type ScenarioProjection = {
    id: string;
    kind: string;
    requestIds: string[];
    actionCount: number;
    deadlineMs: number;
    callbackStates: Array<[boolean, boolean, boolean, number]>;
    observations: string[];
    finalCounters: Record<string, number>;
};

function record(value: unknown, path: string): JsonRecord {
    assert.ok(typeof value === "object" && value !== null && !Array.isArray(value), `${path} must be an object`);
    return value as JsonRecord;
}

function array(value: unknown, path: string): unknown[] {
    assert.ok(Array.isArray(value), `${path} must be an array`);
    return value;
}

function string(value: unknown, path: string): string {
    assert.equal(typeof value, "string", `${path} must be a string`);
    return value as string;
}

function number(value: unknown, path: string): number {
    assert.ok(typeof value === "number" && Number.isFinite(value), `${path} must be finite`);
    return value as number;
}

function boolean(value: unknown, path: string): boolean {
    assert.equal(typeof value, "boolean", `${path} must be a boolean`);
    return value as boolean;
}

function projectScenario(value: unknown): ScenarioProjection {
    const scenario = record(value, "scenario");
    array(scenario.implementationOwners, "scenario.implementationOwners").forEach((item) => string(item, "owner"));
    string(scenario.runtimeConfigRef, "scenario.runtimeConfigRef");
    const requests = array(scenario.requests, "scenario.requests").map((entry) => {
        const request = record(entry, "request");
        const metadata = record(request.metadata, "request.metadata");
        const payload = record(request.payload, "request.payload");
        string(request.role, "request.role"); string(request.method, "request.method");
        string(request.path, "request.path"); number(payload.inputBytes, "payload.inputBytes");
        string(payload.relationToInputLimit, "payload.relationToInputLimit");
        for (const key of ["executionId", "dispatchAttempt", "traceId", "callbackUrl"]) {
            assert.ok(key in metadata, `metadata.${key} is required`);
        }
        return string(request.id, "request.id");
    });
    const backend = record(scenario.backend, "scenario.backend");
    for (const entry of array(backend.handlers, "backend.handlers")) {
        const handler = record(entry, "handler backend");
        string(handler.requestId, "handler.requestId"); string(handler.behavior, "handler.behavior");
        number(handler.outputBytes, "handler.outputBytes"); string(handler.outputRelationToLimit, "handler.outputRelationToLimit");
        assert.ok("barrier" in handler);
    }
    for (const entry of array(backend.callbacks, "backend.callbacks")) {
        const callback = record(entry, "callback backend");
        string(callback.requestId, "callback.requestId"); string(callback.behavior, "callback.behavior");
        assert.ok("barrier" in callback);
    }
    const harness = record(scenario.harness, "scenario.harness");
    for (const entry of array(harness.barriers, "harness.barriers")) {
        const barrier = record(entry, "barrier"); string(barrier.id, "barrier.id"); string(barrier.initialState, "barrier.initialState");
    }
    for (const entry of array(harness.actions, "harness.actions")) {
        const action = record(entry, "action"); number(action.sequence, "action.sequence");
        string(action.actor, "action.actor"); string(action.action, "action.action");
        assert.ok("requestId" in action && "barrier" in action);
    }
    const expected = record(scenario.expected, "scenario.expected");
    const initialCounters = record(scenario.initialCounters, "scenario.initialCounters");
    Object.entries(initialCounters).forEach(([key, item]) => number(item, `initialCounter.${key}`));
    for (const entry of array(expected.responses, "expected.responses")) {
        const response = record(entry, "response"); string(response.requestId, "response.requestId");
        string(response.connectionOutcome, "response.connectionOutcome"); number(response.status, "response.status");
        record(response.requiredHeaders, "response.requiredHeaders"); assert.ok("body" in response);
    }
    for (const entry of array(expected.handlers, "expected.handlers")) {
        const handler = record(entry, "expected handler"); string(handler.requestId, "handler.requestId");
        boolean(handler.started, "handler.started"); boolean(handler.cancelRequested, "handler.cancelRequested");
        string(handler.terminal, "handler.terminal");
    }
    const callbackStates = array(expected.callbacks, "expected.callbacks").map((entry): [boolean, boolean, boolean, number] => {
        const callback = record(entry, "expected callback"); string(callback.requestId, "callback.requestId");
        string(callback.terminal, "callback.terminal"); array(callback.dispatchAttempts, "callback.dispatchAttempts");
        return [boolean(callback.required, "callback.required"), boolean(callback.attempted, "callback.attempted"),
            boolean(callback.delivered, "callback.delivered"), number(callback.attempts, "callback.attempts")];
    });
    const identity = record(expected.identity, "expected.identity");
    assert.ok("executionId" in identity); array(identity.requestDispatchAttempts, "identity.requestDispatchAttempts");
    number(identity.runtimeRedispatchCount, "identity.runtimeRedispatchCount");
    const counters = record(expected.finalCounters, "expected.finalCounters");
    return {
        id: string(scenario.id, "scenario.id"), kind: string(scenario.kind, "scenario.kind"),
        requestIds: requests, actionCount: array(harness.actions, "harness.actions").length,
        deadlineMs: number(scenario.deadlineMs, "scenario.deadlineMs"), callbackStates,
        observations: array(expected.observations, "expected.observations").map((item) => string(item, "observation")),
        finalCounters: Object.fromEntries(Object.entries(counters).map(([key, item]) => [key, number(item, `counter.${key}`)])),
    };
}

test("consumes the shared runtime saturation wire contract", async () => {
    const corpusPath = resolve("..", "runtime-contract", "saturation-wire-corpus.json");
    const validator = resolve("..", "runtime-contract", "validate_saturation_wire_corpus.py");
    await execute("python3", [validator, "--run-mutations", corpusPath], { timeout: 10_000 });
    const corpus = record(JSON.parse(await readFile(corpusPath, "utf8")), "corpus");
    string(corpus.schemaVersion, "schemaVersion");
    const policy = record(corpus.policy, "policy");
    const maximum = number(policy.maximumScenarioDeadlineMs, "maximumScenarioDeadlineMs");
    const identity = record(policy.identity, "policy.identity");
    Object.entries(identity).forEach(([key, item]) => string(item, `policy.identity.${key}`));
    const vocabulary = record(policy.vocabulary, "policy.vocabulary");
    Object.entries(vocabulary).forEach(([key, items]) =>
        array(items, `vocabulary.${key}`).forEach((item) => string(item, `vocabulary.${key} value`)));
    const scenarioKinds = array(vocabulary.scenarioKinds, "vocabulary.scenarioKinds").map((item) => string(item, "scenarioKind"));
    const configurations = record(corpus.runtimeConfigurations, "runtimeConfigurations");
    Object.entries(configurations).forEach(([name, item]) =>
        Object.entries(record(item, `runtimeConfigurations.${name}`))
            .forEach(([key, configValue]) => number(configValue, `runtimeConfigurations.${name}.${key}`)));
    const projections = array(corpus.scenarios, "scenarios").map(projectScenario);
    assert.deepEqual(new Set(projections.map(({ kind }) => kind)), new Set(scenarioKinds));
    assert.ok(projections.every(({ requestIds, actionCount, deadlineMs, observations, finalCounters }) =>
        requestIds.length > 0 && actionCount > 0 && deadlineMs > 0 && deadlineMs <= maximum &&
        observations.length > 0 && Object.values(finalCounters).every((count) => count === 0)));
    const mutations = array(corpus.mutationTests, "mutationTests");
    mutations.forEach((item) => {
        const mutation = record(item, "mutation");
        string(mutation.id, "mutation.id"); string(mutation.operation, "mutation.operation");
        string(mutation.path, "mutation.path"); assert.ok("value" in mutation);
    });
    assert.ok(mutations.length > 0);
});
