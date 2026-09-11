import assert from "node:assert/strict";
import {
    createServer, request, type ClientRequest, type IncomingHttpHeaders, type Server,
} from "node:http";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";

import { createRuntime, type JsonValue, type Runtime } from "../src/index.js";

type JsonRecord = Record<string, unknown>;
type RuntimeCounters = {
    activeHandlers: number;
    inputBytes: number;
    outputBytes: number;
    pendingCallbacks: number;
    pendingCallbackBytes: number;
    serializedCallbackBytes: number;
};
type CorpusRequest = {
    id: string;
    role: "invoke" | "health";
    method: string;
    path: string;
    metadata: {
        executionId: string | null;
        dispatchAttempt: number | null;
        traceId: string | null;
        callbackUrl: string | null;
    };
    payload: { inputBytes: number; relationToInputLimit: string };
};
type ExpectedResponse = {
    requestId: string;
    connectionOutcome: "response" | "client-disconnected";
    status: number;
    body: unknown;
    requiredHeaders: Record<string, string>;
};
type ExpectedCallback = {
    requestId: string;
    required: boolean;
    attempted: boolean;
    delivered: boolean;
    attempts: number;
    dispatchAttempts: number[];
    terminal: string;
    requestProjection: null | {
        method: string;
        url: string;
        headers: Record<string, string>;
        payload: unknown;
    };
};
type Scenario = {
    id: string;
    kind: string;
    runtimeConfigRef: string;
    requests: CorpusRequest[];
    backend: {
        handlers: Array<{ requestId: string; behavior: string }>;
        callbacks: Array<{ requestId: string; behavior: string }>;
    };
    harness: {
        barriers: Array<{ id: string; initialState: "closed" }>;
        actions: Array<{
            sequence: number;
            actor: string;
            action: string;
            requestId: string | null;
            barrier: string | null;
        }>;
    };
    initialCounters: RuntimeCounters;
    expected: {
        responses: ExpectedResponse[];
        handlers: Array<{
            requestId: string;
            started: boolean;
            cancelRequested: boolean;
            terminal: string;
        }>;
        callbacks: ExpectedCallback[];
        identity: { executionId: string | null; requestDispatchAttempts: number[]; runtimeRedispatchCount: number };
        observations: string[];
        finalCounters: RuntimeCounters;
    };
    deadlineMs: number;
};
type Corpus = {
    runtimeConfigurations: Record<string, {
        maxConcurrentHandlers: number;
        maxInputBytes: number;
        maxOutputBytes: number;
        maxPendingCallbacks: number;
        maxPendingCallbackBytes: number;
        handlerTimeoutMs: number;
        callbackAttemptTimeoutMs: number;
        callbackMaxAttempts: number;
        bodyReadTimeoutMs: number;
        shutdownTimeoutMs: number;
    }>;
    scenarios: Scenario[];
};
type ActualResponse = {
    connectionOutcome: "response" | "client-disconnected";
    status: number;
    body: unknown;
    headers: Headers;
};
type HandlerObservation = { started: boolean; cancelRequested: boolean; terminal: string };
type CallbackAttempt = {
    method: string;
    url: string;
    headers: IncomingHttpHeaders;
    payload: unknown;
    responseStatus: number;
};

const corpus = JSON.parse(
    await readFile(resolve("..", "runtime-contract", "saturation-wire-corpus.json"), "utf8"),
) as Corpus;

function deferred(): { promise: Promise<void>; resolve: () => void } {
    let resolvePromise!: () => void;
    const promise = new Promise<void>((resolve) => { resolvePromise = resolve; });
    return { promise, resolve: resolvePromise };
}

async function listen(server: Server): Promise<number> {
    await new Promise<void>((resolvePromise) => server.listen(0, "127.0.0.1", resolvePromise));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("fixture server did not bind");
    return address.port;
}

async function close(server: Server): Promise<void> {
    server.closeAllConnections();
    await new Promise<void>((resolvePromise) => server.close(() => resolvePromise()));
}

async function waitUntil(predicate: () => boolean | Promise<boolean>, deadlineMs: number, label: string): Promise<void> {
    const deadline = Date.now() + deadlineMs;
    while (Date.now() <= deadline) {
        if (await predicate()) return;
        await new Promise<void>((resolvePromise) => setImmediate(resolvePromise));
    }
    throw new Error(`deadline exceeded waiting for ${label}`);
}

function exactInvocationBody(requestFixture: CorpusRequest): string {
    const empty = JSON.stringify({ input: "", metadata: { requestId: requestFixture.id } });
    assert.ok(empty.length <= requestFixture.payload.inputBytes, `${requestFixture.id} fixture is too small`);
    return JSON.stringify({
        input: "x".repeat(requestFixture.payload.inputBytes - empty.length),
        metadata: { requestId: requestFixture.id },
    });
}

function metricValue(metrics: string, name: string): number {
    const match = metrics.match(new RegExp(`^${name} (\\d+)$`, "m"));
    if (!match) throw new Error(`metric ${name} not found`);
    return Number(match[1]);
}

async function runtimeCounters(runtime: Runtime): Promise<RuntimeCounters> {
    const metrics = await (await fetch(`${runtime.baseUrl}/metrics`)).text();
    return Object.fromEntries([
        "activeHandlers",
        "inputBytes",
        "outputBytes",
        "pendingCallbacks",
        "pendingCallbackBytes",
        "serializedCallbackBytes",
    ].map((name) => [
        name,
        metricValue(metrics, `runtime_${name.replace(/[A-Z]/g, (letter) => `_${letter.toLowerCase()}`)}`),
    ])) as RuntimeCounters;
}

async function invokeFixture(
    runtimeBaseUrl: string,
    callbackBaseUrl: string,
    requestFixture: CorpusRequest,
): Promise<ActualResponse> {
    if (requestFixture.role === "health") {
        const response = await fetch(`${runtimeBaseUrl}${requestFixture.path}`);
        return {
            connectionOutcome: "response",
            status: response.status,
            body: await response.json(),
            headers: response.headers,
        };
    }
    try {
        const headers: Record<string, string> = { "content-type": "application/json" };
        if (requestFixture.metadata.executionId !== null) {
            headers["x-execution-id"] = requestFixture.metadata.executionId;
        }
        if (requestFixture.metadata.dispatchAttempt !== null) {
            headers["x-dispatch-attempt"] = String(requestFixture.metadata.dispatchAttempt);
        }
        if (requestFixture.metadata.traceId !== null) {
            headers["x-trace-id"] = requestFixture.metadata.traceId;
        }
        if (requestFixture.metadata.callbackUrl !== null) {
            headers["x-callback-url"] = callbackBaseUrl;
        }
        const response = await fetch(`${runtimeBaseUrl}${requestFixture.path}`, {
            method: requestFixture.method,
            headers,
            body: exactInvocationBody(requestFixture),
        });
        return {
            connectionOutcome: "response",
            status: response.status,
            body: await response.json(),
            headers: response.headers,
        };
    } catch {
        return { connectionOutcome: "client-disconnected", status: 0, body: null, headers: new Headers() };
    }
}

function startCancelledInvocation(
    runtimeBaseUrl: string,
    callbackBaseUrl: string,
    requestFixture: CorpusRequest,
): { request: ClientRequest; response: Promise<ActualResponse> } {
    let clientRequest!: ClientRequest;
    const response = new Promise<ActualResponse>((resolvePromise) => {
        clientRequest = request(`${runtimeBaseUrl}${requestFixture.path}`, {
            method: requestFixture.method,
            headers: {
                "content-type": "application/json",
                "x-execution-id": requestFixture.metadata.executionId!,
                "x-dispatch-attempt": String(requestFixture.metadata.dispatchAttempt),
                "x-trace-id": requestFixture.metadata.traceId!,
                "x-callback-url": callbackBaseUrl,
            },
        }, (incoming) => {
            const chunks: Buffer[] = [];
            incoming.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
            incoming.on("end", () => {
                if (settled) return;
                settled = true;
                const text = Buffer.concat(chunks).toString("utf8");
                resolvePromise({
                    connectionOutcome: "response",
                    status: incoming.statusCode ?? 0,
                    body: text === "" ? null : JSON.parse(text),
                    headers: new Headers(Object.entries(incoming.headers).flatMap(([name, value]) =>
                        value === undefined
                            ? []
                            : [[name, Array.isArray(value) ? value.join(", ") : value] as [string, string]])),
                });
            });
        });
        let settled = false;
        const disconnected = (): void => {
            if (settled) return;
            settled = true;
            resolvePromise({ connectionOutcome: "client-disconnected", status: 0, body: null, headers: new Headers() });
        };
        clientRequest.once("error", disconnected);
        clientRequest.once("close", disconnected);
        clientRequest.end(exactInvocationBody(requestFixture));
    });
    return { request: clientRequest, response };
}

function assertResponse(actual: ActualResponse, expected: ExpectedResponse): void {
    assert.equal(actual.connectionOutcome, expected.connectionOutcome);
    assert.equal(actual.status, expected.status);
    assert.deepEqual(actual.body, expected.body);
    for (const [name, value] of Object.entries(expected.requiredHeaders)) {
        const actualValue = actual.headers.get(name);
        if (name === "content-type") assert.ok(actualValue?.startsWith(value), `${name} mismatch`);
        else assert.equal(actualValue, value);
    }
}

async function runScenario(scenario: Scenario): Promise<void> {
    const startedAt = Date.now();
    const config = corpus.runtimeConfigurations[scenario.runtimeConfigRef]!;
    const callbackRelease = deferred();
    const callbackEntered = deferred();
    const handlerRelease = deferred();
    const harnessBarriers = new Map(scenario.harness.barriers.map((item) => [item.id, deferred()]));
    const handlerEntered = new Map(scenario.requests.map((item) => [item.id, deferred()]));
    const handlerObservations = new Map<string, HandlerObservation>(
        scenario.requests.map((item) => [item.id, { started: false, cancelRequested: false, terminal: "not-started" }]),
    );
    const handlerStartCounts = new Map<string, number>();
    const callbackAttempts: CallbackAttempt[] = [];
    const structuredLogs: JsonRecord[] = [];
    const originalConsoleLog = console.log;
    console.log = (...values: unknown[]): void => {
        if (typeof values[0] !== "string") return;
        try {
            const parsed = JSON.parse(values[0]) as JsonRecord;
            structuredLogs.push(parsed);
        } catch {
            // Non-JSON output is not a runtime structured log.
        }
    };
    const requestsByIdentity = new Map(
        scenario.requests.filter((item) => item.metadata.executionId !== null).map((item) => [
            `${item.metadata.executionId}:${item.metadata.dispatchAttempt}`,
            item,
        ]),
    );
    const callbackBehavior = new Map(scenario.backend.callbacks.map((item) => [item.requestId, item.behavior]));
    const callbackServer = createServer(async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const chunk of req) chunks.push(Buffer.from(chunk));
        if (req.url?.includes("fixture-callback")) {
            assert.equal(
                Buffer.concat(chunks).byteLength,
                scenario.initialCounters.serializedCallbackBytes,
                "callback capacity fixture must retain the corpus byte count",
            );
            callbackEntered.resolve();
            await callbackRelease.promise;
            res.statusCode = 204;
            res.end();
            return;
        }
        if (req.url?.includes("fixture-handler")) {
            res.statusCode = 204;
            res.end();
            return;
        }
        const fixtureKey = `${decodeURIComponent(
            (req.url ?? "").split("/").at(-1)?.replace(/:complete$/, "") ?? "",
        )}:${req.headers["x-dispatch-attempt"]}`;
        const fixture = requestsByIdentity.get(fixtureKey);
        const responseStatus = fixture && callbackBehavior.get(fixture.id) === "retryable-failure" ? 429 : 204;
        const attempt: CallbackAttempt = {
            method: req.method ?? "",
            url: req.url ?? "",
            headers: req.headers,
            payload: JSON.parse(Buffer.concat(chunks).toString("utf8")),
            responseStatus,
        };
        callbackAttempts.push(attempt);
        res.statusCode = responseStatus;
        res.end();
    });
    const callbackPort = await listen(callbackServer);
    const callbackBaseUrl = `http://127.0.0.1:${callbackPort}`;
    const initialCallbackBytes = scenario.initialCounters.pendingCallbackBytes;
    const runtime = createRuntime({
        port: 0,
        maxConcurrentHandlers: config.maxConcurrentHandlers,
        maxInputBytes: config.maxInputBytes,
        maxOutputBytes: config.maxOutputBytes,
        callbackQueueSize: config.maxPendingCallbacks,
        maxCallbackPayloadBytes: initialCallbackBytes > 0
            ? initialCallbackBytes
            : config.maxPendingCallbackBytes,
        maxPendingCallbackBytes: config.maxPendingCallbackBytes,
        handlerTimeoutMs: config.handlerTimeoutMs,
        callbackAttemptTimeoutMs: config.callbackAttemptTimeoutMs,
        callbackMaxAttempts: config.callbackMaxAttempts,
        bodyReadTimeoutMs: config.bodyReadTimeoutMs,
        shutdownTimeoutMs: config.shutdownTimeoutMs,
    });
    const handlerBehavior = new Map(scenario.backend.handlers.map((item) => [item.requestId, item.behavior]));
    runtime.register("corpus", async (context, invocation) => {
        const requestId = invocation.metadata?.requestId ?? "";
        if (requestId === "fixture-callback") {
            const emptyCallbackBytes = Buffer.byteLength(JSON.stringify({
                success: true,
                output: "",
                error: null,
            }));
            return "x".repeat(scenario.initialCounters.serializedCallbackBytes - emptyCallbackBytes);
        }
        if (requestId === "fixture-handler") {
            handlerEntered.get(requestId)?.resolve();
            await handlerRelease.promise;
            return { fixture: true };
        }
        const observation = handlerObservations.get(requestId)!;
        handlerStartCounts.set(requestId, (handlerStartCounts.get(requestId) ?? 0) + 1);
        observation.started = true;
        handlerEntered.get(requestId)?.resolve();
        context.signal.addEventListener("abort", () => {
            observation.cancelRequested = true;
            observation.terminal = scenario.harness.actions.some((item) =>
                item.action === "cancel-request" && item.requestId === requestId)
                ? "cancelled"
                : "timed-out";
        }, { once: true });
        const behavior = handlerBehavior.get(requestId);
        if (behavior === "fail") {
            observation.terminal = "failed";
            throw new Error("fixture handler failure");
        }
        if (behavior === "block-until-cancelled") {
            await new Promise<void>((resolvePromise) => {
                if (context.signal.aborted) resolvePromise();
                else context.signal.addEventListener("abort", () => resolvePromise(), { once: true });
            });
            return null;
        }
        if (scenario.kind === "output-too-large") {
            observation.terminal = "output-rejected";
            return "x".repeat(config.maxOutputBytes + 1);
        }
        observation.terminal = "succeeded";
        return { result: "ok" };
    });

    const actualResponses = new Map<string, ActualResponse>();
    const pendingResponses = new Map<string, Promise<ActualResponse>>();
    const cancellableRequests = new Map<string, ClientRequest>();
    const requestById = new Map(scenario.requests.map((item) => [item.id, item]));
    const observed = new Set<string>();
    let runtimeBaseUrl = "";
    let stopPromise: Promise<void> | undefined;
    let stopped = false;
    let initialCountersAsserted = false;
    let fixtureHandlerResponse: Promise<ActualResponse> | undefined;

    const assertInitialCounters = async (): Promise<void> => {
        if (initialCountersAsserted) return;
        assert.deepEqual(await runtimeCounters(runtime), scenario.initialCounters);
        initialCountersAsserted = true;
    };
    const fillCallback = async (): Promise<void> => {
        const fixture: CorpusRequest = {
            id: "fixture-callback",
            role: "invoke",
            method: "POST",
            path: "/invoke",
            metadata: { executionId: "fixture-callback", dispatchAttempt: 1, traceId: null, callbackUrl: "fixture" },
            payload: { inputBytes: 64, relationToInputLimit: "below-limit" },
        };
        const response = await fetch(`${runtimeBaseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "fixture-callback",
                "x-callback-url": callbackBaseUrl,
            },
            body: exactInvocationBody(fixture),
        });
        assert.equal(response.status, 200);
        await response.arrayBuffer();
        await callbackEntered.promise;
    };
    const fillHandler = async (withCallbackReservation: boolean): Promise<void> => {
        const entered = deferred();
        handlerEntered.set("fixture-handler", entered);
        const fixture: CorpusRequest = {
            id: "fixture-handler",
            role: "invoke",
            method: "POST",
            path: "/invoke",
            metadata: { executionId: "fixture-handler", dispatchAttempt: 1, traceId: null, callbackUrl: null },
            payload: {
                inputBytes: scenario.initialCounters.inputBytes,
                relationToInputLimit: "below-limit",
            },
        };
        fixtureHandlerResponse = invokeFixture(
            runtimeBaseUrl,
            withCallbackReservation ? callbackBaseUrl : "",
            withCallbackReservation
                ? { ...fixture, metadata: { ...fixture.metadata, callbackUrl: "fixture" } }
                : fixture,
        );
        void fixtureHandlerResponse.catch(() => undefined);
        await entered.promise;
    };

    const actions = [...scenario.harness.actions].sort((left, right) => left.sequence - right.sequence);
    assert.deepEqual(actions.map((item) => item.sequence), actions.map((_, index) => index + 1));

    const executeAction = async (action: Scenario["harness"]["actions"][number]): Promise<void> => {
        if (["send-request", "probe-health", "begin-stop"].includes(action.action)) {
            await assertInitialCounters();
        }
        switch (action.action) {
        case "start-runtime":
            await runtime.start();
            runtimeBaseUrl = runtime.baseUrl;
            return;
        case "fill-callback-capacity":
            await fillCallback();
            if (scenario.initialCounters.activeHandlers > 0) await fillHandler(false);
            return;
        case "fill-handler-capacity":
            await fillHandler(true);
            return;
        case "send-request": {
            const requestFixture = requestById.get(action.requestId!)!;
            const willCancel = actions.some((item) =>
                item.action === "cancel-request" && item.requestId === requestFixture.id);
            const pending = startCancelledInvocation(runtimeBaseUrl, callbackBaseUrl, requestFixture);
            pendingResponses.set(requestFixture.id, pending.response);
            if (willCancel) {
                cancellableRequests.set(requestFixture.id, pending.request);
            }
            return;
        }
        case "probe-health": {
            const requestFixture = requestById.get(action.requestId!)!;
            pendingResponses.set(
                requestFixture.id,
                invokeFixture(runtimeBaseUrl, callbackBaseUrl, requestFixture),
            );
            return;
        }
        case "await-response":
            actualResponses.set(action.requestId!, await pendingResponses.get(action.requestId!)!);
            return;
        case "await-barrier":
            if (action.barrier === "handler-started") {
                await handlerEntered.get(action.requestId!)!.promise;
            } else {
                await harnessBarriers.get(action.barrier!)!.promise;
            }
            return;
        case "cancel-request":
            cancellableRequests.get(action.requestId!)!.destroy();
            actualResponses.set(action.requestId!, await pendingResponses.get(action.requestId!)!);
            return;
        case "await-callback": {
            const expected = scenario.expected.callbacks.find((item) => item.requestId === action.requestId)!;
            await waitUntil(() => {
                const requestFixture = requestById.get(action.requestId!)!;
                return callbackAttempts.filter((item) =>
                    item.url.endsWith(`/${encodeURIComponent(requestFixture.metadata.executionId!)}:complete`)
                    && item.headers["x-dispatch-attempt"] === String(requestFixture.metadata.dispatchAttempt)).length
                    >= expected.attempts;
            }, scenario.deadlineMs, `${scenario.id}/${action.requestId} callback`);
            return;
        }
        case "begin-stop":
            stopPromise = runtime.stop();
            if (action.barrier) harnessBarriers.get(action.barrier)!.resolve();
            return;
        case "await-stop":
            await stopPromise!;
            stopped = true;
            if (scenario.expected.observations.includes("stop-complete")) {
                observed.add("stop-complete");
            }
            return;
        case "start-runtime-again":
            await runtime.start();
            runtimeBaseUrl = runtime.baseUrl;
            stopped = false;
            observed.add("restart-complete");
            return;
        case "drain-callbacks":
            callbackRelease.resolve();
            handlerRelease.resolve();
            if (fixtureHandlerResponse) await fixtureHandlerResponse;
            return;
        case "drain-handlers":
            handlerRelease.resolve();
            if (fixtureHandlerResponse) await fixtureHandlerResponse;
            return;
        case "control-plane-redispatch":
            return;
        default:
            throw new Error(`unsupported corpus harness action: ${action.action}`);
        }
    };

    try {
        for (const action of actions) await executeAction(action);
        await assertInitialCounters();

        callbackRelease.resolve();
        handlerRelease.resolve();
        if (fixtureHandlerResponse) await fixtureHandlerResponse;

        for (const expected of scenario.expected.responses) {
            const actual = actualResponses.get(expected.requestId)!;
            assertResponse(actual, expected);
            observed.add(expected.connectionOutcome === "response" ? "wire-response" : "no-wire-response");
            if (requestById.get(expected.requestId)?.role === "health" && actual.status === 200) {
                observed.add("health-response");
            }
        }
        for (const expected of scenario.expected.handlers) {
            const actual = handlerObservations.get(expected.requestId)!;
            assert.deepEqual(actual, {
                started: expected.started,
                cancelRequested: expected.cancelRequested,
                terminal: expected.terminal,
            });
            if (actual.started) observed.add("handler-start");
            if (actual.cancelRequested) observed.add("handler-cancel");
        }
        for (const expected of scenario.expected.callbacks) {
            const fixture = requestById.get(expected.requestId)!;
            const required = fixture.role === "invoke" && fixture.metadata.callbackUrl !== null;
            const attempts = callbackAttempts.filter((item) =>
                item.url.endsWith(`/${encodeURIComponent(fixture.metadata.executionId!)}:complete`)
                && item.headers["x-dispatch-attempt"] === String(fixture.metadata.dispatchAttempt));
            const attempted = attempts.length > 0;
            const delivered = attempts.some((item) => item.responseStatus >= 200 && item.responseStatus < 300);
            const terminal = !required
                ? "not-required"
                : !attempted
                    ? "rejected-before-handler"
                    : delivered
                        ? "delivered"
                        : attempts.length === config.callbackMaxAttempts ? "exhausted" : "cancelled";
            assert.equal(required, expected.required, `${scenario.id}/${expected.requestId} callback required`);
            assert.equal(attempted, expected.attempted, `${scenario.id}/${expected.requestId} callback attempted`);
            assert.equal(delivered, expected.delivered, `${scenario.id}/${expected.requestId} callback delivered`);
            assert.equal(terminal, expected.terminal, `${scenario.id}/${expected.requestId} callback terminal`);
            assert.equal(attempts.length, expected.attempts, `${scenario.id}/${expected.requestId} callback attempts`);
            assert.deepEqual(
                attempts.map((item) => Number(item.headers["x-dispatch-attempt"])),
                expected.dispatchAttempts,
            );
            if (attempted) observed.add("callback-attempt");
            if (delivered) observed.add("callback-delivery");
            if (expected.requestProjection) {
                const attempt = attempts[0]!;
                assert.equal(attempt.method, expected.requestProjection.method);
                assert.equal(`http://callback.invalid${attempt.url}`, expected.requestProjection.url);
                for (const [name, value] of Object.entries(expected.requestProjection.headers)) {
                    assert.equal(attempt.headers[name], value);
                }
                assert.deepEqual(attempt.payload, expected.requestProjection.payload);
            }
        }

        const requestDispatchAttempts = scenario.requests
            .map((item) => item.metadata.dispatchAttempt)
            .filter((item): item is number => item !== null);
        assert.deepEqual(requestDispatchAttempts, scenario.expected.identity.requestDispatchAttempts);
        assert.ok(scenario.requests.every((item) =>
            item.metadata.executionId === null
            || item.metadata.executionId === scenario.expected.identity.executionId));
        const sentRequestCounts = new Map<string, number>();
        for (const action of actions.filter((item) => item.action === "send-request")) {
            sentRequestCounts.set(action.requestId!, (sentRequestCounts.get(action.requestId!) ?? 0) + 1);
        }
        const runtimeRedispatchCount = [...handlerStartCounts].reduce(
            (sum, [requestId, count]) => sum + Math.max(0, count - (sentRequestCounts.get(requestId) ?? 0)),
            0,
        );
        assert.equal(runtimeRedispatchCount, scenario.expected.identity.runtimeRedispatchCount);
        if (
            runtimeRedispatchCount === 0
            && scenario.expected.observations.includes("runtime-redispatch-zero")
        ) observed.add("runtime-redispatch-zero");

        if (stopped) {
            await runtime.start();
            runtimeBaseUrl = runtime.baseUrl;
            stopped = false;
        }
        await waitUntil(async () => {
            const actual = await runtimeCounters(runtime);
            return Object.entries(scenario.expected.finalCounters).every(([name, value]) =>
                actual[name as keyof RuntimeCounters] === value);
        }, scenario.deadlineMs, `${scenario.id} final counters`);
        const finalCounters = await runtimeCounters(runtime);
        assert.deepEqual(finalCounters, scenario.expected.finalCounters);
        if (Object.values(finalCounters).every((value) => value === 0)) observed.add("counters-zero");

        const metrics = await (await fetch(`${runtimeBaseUrl}/metrics`)).text();
        if (metricValue(metrics, "runtime_callback_failures") > 0) {
            observed.add("callback-failure-metric");
        }
        if (structuredLogs.some((entry) =>
            entry.logger === "nanofaas.runtime"
            && entry.message === "callback delivery failed"
            && entry.executionId === scenario.expected.identity.executionId)) {
            observed.add("structured-log");
        }
        assert.deepEqual([...observed].sort(), [...scenario.expected.observations].sort());
        assert.ok(Date.now() - startedAt <= scenario.deadlineMs, `${scenario.id} exceeded corpus deadline`);
    } finally {
        callbackRelease.resolve();
        handlerRelease.resolve();
        for (const clientRequest of cancellableRequests.values()) clientRequest.destroy();
        await runtime.stop();
        await close(callbackServer);
        console.log = originalConsoleLog;
    }
}

for (const scenario of corpus.scenarios) {
    test(`createRuntime executes saturation corpus: ${scenario.id}`, {
        timeout: scenario.deadlineMs + 1_000,
        concurrency: false,
    }, async () => runScenario(scenario));
}
