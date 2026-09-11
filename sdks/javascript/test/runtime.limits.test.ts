import assert from "node:assert/strict";
import { request } from "node:http";
import { createServer, type Server } from "node:http";
import { createConnection, type Socket } from "node:net";
import { test } from "node:test";

import { HandlerResponse, NanofaasError, createRuntime, type RuntimeOptions } from "../src/index.js";

type ExtendedRuntimeOptions = RuntimeOptions & {
    maxConcurrentHandlers: number;
    maxInputBytes: number;
    maxOutputBytes: number;
    maxCallbackPayloadBytes: number;
    maxPendingCallbackBytes: number;
    bodyReadTimeoutMs: number;
    callbackAttemptTimeoutMs: number;
    callbackMaxAttempts: number;
    shutdownTimeoutMs: number;
};

const boundedOptions: ExtendedRuntimeOptions = {
    port: 0,
    handlerTimeoutMs: 200,
    callbackQueueSize: 1,
    maxConcurrentHandlers: 1,
    maxInputBytes: 1_024,
    maxOutputBytes: 1_024,
    maxCallbackPayloadBytes: 1_024,
    maxPendingCallbackBytes: 1_024,
    bodyReadTimeoutMs: 50,
    callbackAttemptTimeoutMs: 50,
    callbackMaxAttempts: 3,
    shutdownTimeoutMs: 100,
};

function options(overrides: Partial<ExtendedRuntimeOptions> = {}): RuntimeOptions {
    return { ...boundedOptions, ...overrides } as RuntimeOptions;
}

function deferred(): { promise: Promise<void>; resolve: () => void } {
    let resolve!: () => void;
    const promise = new Promise<void>((done) => {
        resolve = done;
    });
    return { promise, resolve };
}

async function listen(server: Server): Promise<number> {
    await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
    const address = server.address();
    if (!address || typeof address === "string") throw new Error("server did not bind");
    return address.port;
}

async function close(server: Server): Promise<void> {
    server.closeAllConnections();
    await new Promise<void>((resolve) => server.close(() => resolve()));
}

async function invoke(baseUrl: string, executionId: string, input: unknown): Promise<Response> {
    return fetch(`${baseUrl}/invoke`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-execution-id": executionId },
        body: JSON.stringify({ input }),
    });
}

function infiniteUpload(
    baseUrl: string,
    headers: Record<string, string>,
): {
    response: Promise<{ status: number | undefined; connection: string | undefined; body: string }>;
    closed: Promise<void>;
    request: ReturnType<typeof request>;
} {
    let closeUpload!: () => void;
    const closed = new Promise<void>((resolve) => { closeUpload = resolve; });
    let upload!: ReturnType<typeof request>;
    const response = new Promise<{
        status: number | undefined;
        connection: string | undefined;
        body: string;
    }>((resolve, reject) => {
        upload = request(`${baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "transfer-encoding": "chunked",
                ...headers,
            },
        }, (res) => {
            const chunks: Buffer[] = [];
            res.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
            res.on("end", () => resolve({
                status: res.statusCode,
                connection: res.headers.connection,
                body: Buffer.concat(chunks).toString("utf8"),
            }));
        });
        upload.once("close", closeUpload);
        upload.once("error", reject);
        upload.write('{"input":');
    });
    return { response, closed, request: upload };
}

async function assertRejectedUploadClosed(
    upload: ReturnType<typeof infiniteUpload>,
    status: number,
    code: string,
): Promise<void> {
    const response = await upload.response;
    assert.equal(response.status, status);
    assert.equal(response.connection, "close");
    assert.equal(JSON.parse(response.body).error.code, code);
    await Promise.race([
        upload.closed,
        new Promise((_, reject) => setTimeout(() => reject(new Error("rejected upload remained owned")), 250)),
    ]);
    assert.equal(upload.request.destroyed, true);
}

function metricValue(metrics: string, name: string): number {
    const match = metrics.match(new RegExp(`^${name} (\\d+)$`, "m"));
    if (!match) throw new Error(`metric ${name} not found`);
    return Number(match[1]);
}

async function waitForCountersToDrain(baseUrl: string): Promise<string> {
    const deadline = Date.now() + 500;
    while (Date.now() < deadline) {
        const metrics = await (await fetch(`${baseUrl}/metrics`)).text();
        if (metricValue(metrics, "runtime_pending_callbacks") === 0) return metrics;
        await new Promise<void>((resolve) => setImmediate(resolve));
    }
    throw new Error("runtime counters did not drain");
}

async function waitUntilAllCallbackOwnershipDrains(baseUrl: string): Promise<void> {
    const deadline = Date.now() + 500;
    while (Date.now() < deadline) {
        const metrics = await (await fetch(`${baseUrl}/metrics`)).text();
        if (
            metricValue(metrics, "runtime_pending_callbacks") === 0
            && metricValue(metrics, "runtime_pending_callback_bytes") === 0
            && metricValue(metrics, "runtime_serialized_callback_bytes") === 0
        ) return;
        await new Promise<void>((resolve) => setImmediate(resolve));
    }
    throw new Error("callback ownership did not drain");
}

async function waitForMetric(baseUrl: string, name: string, expected: number): Promise<void> {
    const deadline = Date.now() + 500;
    while (Date.now() < deadline) {
        const metrics = await (await fetch(`${baseUrl}/metrics`)).text();
        if (metricValue(metrics, name) === expected) return;
        await new Promise<void>((resolve) => setImmediate(resolve));
    }
    throw new Error(`${name} did not reach ${expected}`);
}

test("all ownership limits reject zero, negative, NaN, and infinity", () => {
    const fields: Array<keyof ExtendedRuntimeOptions> = [
        "handlerTimeoutMs",
        "callbackQueueSize",
        "maxConcurrentHandlers",
        "maxInputBytes",
        "maxOutputBytes",
        "maxCallbackPayloadBytes",
        "maxPendingCallbackBytes",
        "bodyReadTimeoutMs",
        "callbackAttemptTimeoutMs",
        "callbackMaxAttempts",
        "shutdownTimeoutMs",
    ];

    for (const field of fields) {
        for (const value of [0, -1, Number.NaN, Number.POSITIVE_INFINITY]) {
            assert.throws(
                () => createRuntime(options({ [field]: value })),
                new RegExp(`${field}.*finite positive`, "i"),
                `${String(field)} accepted ${String(value)}`,
            );
        }
    }
    assert.throws(
        () => createRuntime(options({
            maxCallbackPayloadBytes: 1,
            maxPendingCallbackBytes: 1_024,
        })),
        /maxCallbackPayloadBytes must be at least/i,
    );
    assert.throws(
        () => createRuntime(options({
            maxCallbackPayloadBytes: 512,
            maxPendingCallbackBytes: 511,
        })),
        /maxPendingCallbackBytes must be at least/i,
    );
});

test("canonical runtime envelopes define exact output and callback byte minimums", () => {
    assert.throws(
        () => createRuntime(options({ maxOutputBytes: 101 })),
        /maxOutputBytes must be at least 102 bytes/i,
    );
    assert.doesNotThrow(() => createRuntime(options({ maxOutputBytes: 102 })));

    assert.throws(
        () => createRuntime(options({
            maxCallbackPayloadBytes: 131,
            maxPendingCallbackBytes: 1_024,
        })),
        /maxCallbackPayloadBytes must be at least 132 bytes/i,
    );
    assert.doesNotThrow(() => createRuntime(options({
        maxCallbackPayloadBytes: 132,
        maxPendingCallbackBytes: 132,
    })));
});

test("early 400 rejection terminates an infinite chunked upload after flushing the response", async () => {
    const runtime = createRuntime(options());
    runtime.register("echo", async () => null);
    await runtime.start();
    const upload = infiniteUpload(runtime.baseUrl, {});
    try {
        await assertRejectedUploadClosed(upload, 400, "EXECUTION_ID_REQUIRED");
    } finally {
        upload.request.destroy();
        await runtime.stop();
    }
});

test("handler and callback saturation terminate infinite chunked uploads", async () => {
    const handlerEntered = deferred();
    const releaseHandler = deferred();
    const handlerRuntime = createRuntime(options());
    handlerRuntime.register("echo", async () => {
        handlerEntered.resolve();
        await releaseHandler.promise;
        return null;
    });
    await handlerRuntime.start();
    const admitted = invoke(handlerRuntime.baseUrl, "handler-owner", null);
    await handlerEntered.promise;
    const handlerRejected = infiniteUpload(handlerRuntime.baseUrl, { "x-execution-id": "handler-rejected" });
    try {
        await assertRejectedUploadClosed(handlerRejected, 429, "RUNTIME_HANDLER_SATURATED");
    } finally {
        handlerRejected.request.destroy();
        releaseHandler.resolve();
        await admitted;
        await handlerRuntime.stop();
    }

    const callbackEntered = deferred();
    const releaseCallback = deferred();
    const callbackServer = createServer(async (_req, res) => {
        res.writeHead(200);
        res.write("partial");
        callbackEntered.resolve();
        await releaseCallback.promise;
        res.end();
    });
    const callbackPort = await listen(callbackServer);
    const callbackRuntime = createRuntime(options({
        callbackUrl: `http://127.0.0.1:${callbackPort}`,
        callbackAttemptTimeoutMs: 500,
    }));
    callbackRuntime.register("echo", async () => null);
    await callbackRuntime.start();
    await invoke(callbackRuntime.baseUrl, "callback-owner", null);
    await callbackEntered.promise;
    const callbackRejected = infiniteUpload(callbackRuntime.baseUrl, { "x-execution-id": "callback-rejected" });
    try {
        await assertRejectedUploadClosed(callbackRejected, 429, "RUNTIME_CALLBACK_SATURATED");
    } finally {
        callbackRejected.request.destroy();
        releaseCallback.resolve();
        await callbackRuntime.stop();
        await close(callbackServer);
    }
});

test("streaming ingress rejects the first byte beyond the configured limit before handler start", async () => {
    let handlerCalls = 0;
    const runtime = createRuntime(options({ maxInputBytes: 32 }));
    runtime.register("echo", async () => {
        handlerCalls += 1;
        return null;
    });
    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "input-large", "x".repeat(40));
        assert.equal(response.status, 413);
        assert.deepEqual(await response.json(), {
            error: {
                code: "RUNTIME_INPUT_TOO_LARGE",
                message: "Runtime input exceeds configured byte limit",
            },
        });
        assert.equal(handlerCalls, 0);
    } finally {
        await runtime.stop();
    }
});

test("oversized streaming upload returns 413 and closes the request connection", async () => {
    const runtime = createRuntime(options({ maxInputBytes: 32 }));
    runtime.register("echo", async () => null);
    await runtime.start();
    let upload!: ReturnType<typeof request>;
    try {
        const response = await new Promise<{
            status: number | undefined;
            connection: string | undefined;
            body: string;
        }>((resolve, reject) => {
            upload = request(`${runtime.baseUrl}/invoke`, {
                method: "POST",
                headers: {
                    "content-type": "application/json",
                    "x-execution-id": "input-stream-large",
                    "transfer-encoding": "chunked",
                },
            }, (res) => {
                const chunks: Buffer[] = [];
                res.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
                res.on("end", () => resolve({
                    status: res.statusCode,
                    connection: res.headers.connection,
                    body: Buffer.concat(chunks).toString("utf8"),
                }));
            });
            upload.on("error", reject);
            upload.write("x".repeat(40));
        });
        assert.equal(response.status, 413);
        assert.equal(response.connection, "close");
        assert.deepEqual(JSON.parse(response.body), { error: {
            code: "RUNTIME_INPUT_TOO_LARGE",
            message: "Runtime input exceeds configured byte limit",
        } });
        await Promise.race([
            new Promise<void>((resolve) => upload.once("close", resolve)),
            new Promise((_, reject) => setTimeout(() => reject(new Error("oversized upload connection remained open")), 250)),
        ]);
    } finally {
        upload?.destroy();
        await runtime.stop();
    }
});

test("a stalled streaming body has a finite runtime-owned read deadline", async () => {
    let handlerCalls = 0;
    const runtime = createRuntime(options({ bodyReadTimeoutMs: 20 }));
    runtime.register("echo", async () => {
        handlerCalls += 1;
        return null;
    });
    await runtime.start();
    let stalledRequest!: ReturnType<typeof request>;
    const requestClosed = deferred();
    try {
        const response = await new Promise<{
            status: number | undefined;
            connection: string | undefined;
            body: string;
        }>((resolve, reject) => {
            stalledRequest = request(`${runtime.baseUrl}/invoke`, {
                method: "POST",
                headers: {
                    "content-type": "application/json",
                    "x-execution-id": "slow-body",
                    "transfer-encoding": "chunked",
                },
            }, (res) => {
                const chunks: Buffer[] = [];
                res.on("data", (chunk) => chunks.push(Buffer.from(chunk)));
                res.on("end", () => resolve({
                    status: res.statusCode,
                    connection: res.headers.connection,
                    body: Buffer.concat(chunks).toString("utf8"),
                }));
            });
            stalledRequest.once("close", requestClosed.resolve);
            stalledRequest.on("error", reject);
            stalledRequest.write('{"input":');
        });
        assert.equal(response.status, 408);
        assert.equal(response.connection, "close");
        assert.deepEqual(JSON.parse(response.body), {
            error: { code: "RUNTIME_BODY_TIMEOUT", message: "Runtime request body read timed out" },
        });
        assert.equal(handlerCalls, 0);
        await Promise.race([
            requestClosed.promise,
            new Promise((_, reject) => setTimeout(() => reject(new Error("timed-out upload connection remained open")), 250)),
        ]);
    } finally {
        stalledRequest?.destroy();
        await runtime.stop();
    }
});

test("shutdown cancellation terminates a partial body read before the stop deadline", async () => {
    let handlerCalls = 0;
    const runtime = createRuntime(options({
        bodyReadTimeoutMs: 1_000,
        shutdownTimeoutMs: 120,
    }));
    runtime.register("echo", async () => {
        handlerCalls += 1;
        return null;
    });
    await runtime.start();
    const upload = infiniteUpload(runtime.baseUrl, { "x-execution-id": "shutdown-body" });
    void upload.response.catch(() => undefined);
    upload.request.on("error", () => undefined);
    await waitForMetric(runtime.baseUrl, "runtime_input_bytes", 9);
    const startedAt = Date.now();
    await runtime.stop();
    const elapsed = Date.now() - startedAt;
    assert.ok(elapsed < 90, `body read ignored shutdown cancellation for ${elapsed}ms`);
    assert.equal(upload.request.destroyed, true);
    assert.equal(handlerCalls, 0);
});

test("body completion racing stop cannot start work or retain capacity across restart", async () => {
    let handlerCalls = 0;
    const callbackServer = createServer((_req, res) => {
        res.statusCode = 204;
        res.end();
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        bodyReadTimeoutMs: 1_000,
        handlerTimeoutMs: 1_000,
        shutdownTimeoutMs: 40,
        callbackUrl: `http://127.0.0.1:${callbackPort}`,
    }));
    runtime.register("echo", async (context) => {
        handlerCalls += 1;
        if (context.executionId === "stop-body-race") {
            return new Promise<never>(() => undefined);
        }
        return null;
    });
    await runtime.start();
    const upload = infiniteUpload(runtime.baseUrl, { "x-execution-id": "stop-body-race" });
    void upload.response.catch(() => undefined);
    upload.request.on("error", () => undefined);
    await waitForMetric(runtime.baseUrl, "runtime_input_bytes", 9);
    const stopping = runtime.stop();
    upload.request.end("null}");
    await stopping;
    assert.equal(handlerCalls, 0);

    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "after-body-race", null);
        assert.equal(response.status, 200);
    } finally {
        upload.request.destroy();
        await runtime.stop();
        await close(callbackServer);
    }
});

test("concurrent streaming readers release only the bytes they own", async () => {
    const runtime = createRuntime(options({
        maxConcurrentHandlers: 2,
        maxInputBytes: 16,
        bodyReadTimeoutMs: 500,
    }));
    runtime.register("echo", async () => null);
    await runtime.start();
    const openRequest = (executionId: string): ReturnType<typeof request> => request(
        `${runtime.baseUrl}/invoke`,
        {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": executionId,
                "transfer-encoding": "chunked",
            },
        },
    );
    const first = openRequest("reader-one");
    const secondResponse = new Promise<number | undefined>((resolve, reject) => {
        const second = request(
            `${runtime.baseUrl}/invoke`,
            {
                method: "POST",
                headers: {
                    "content-type": "application/json",
                    "x-execution-id": "reader-two",
                    "transfer-encoding": "chunked",
                },
            },
            (response) => {
                response.resume();
                response.once("end", () => resolve(response.statusCode));
            },
        );
        second.once("error", reject);
        first.write("12345");
        void waitForMetric(runtime.baseUrl, "runtime_input_bytes", 5).then(async () => {
            const reading = await (await fetch(`${runtime.baseUrl}/metrics`)).text();
            assert.equal(metricValue(reading, "runtime_active_handlers"), 0);
            second.write("12345");
            await waitForMetric(runtime.baseUrl, "runtime_input_bytes", 10);
            first.write("67890");
            await waitForMetric(runtime.baseUrl, "runtime_input_bytes", 15);
            second.write("x".repeat(20));
        }).catch(reject);
    });
    first.once("error", () => undefined);
    try {
        assert.equal(await secondResponse, 413);
        await waitForMetric(runtime.baseUrl, "runtime_input_bytes", 10);
    } finally {
        first.destroy();
        await runtime.stop();
    }
});

test("handler capacity is reserved before execution and released on physical settlement", async () => {
    const release = deferred();
    const started = deferred();
    let calls = 0;
    const runtime = createRuntime(options({ callbackQueueSize: 2, maxPendingCallbackBytes: 2_048 }));
    runtime.register("echo", async () => {
        calls += 1;
        started.resolve();
        await release.promise;
        return { ok: true };
    });
    await runtime.start();
    try {
        const first = invoke(runtime.baseUrl, "handler-one", null);
        await started.promise;
        const saturated = await invoke(runtime.baseUrl, "handler-two", null);
        assert.equal(saturated.status, 429);
        assert.equal(saturated.headers.get("retry-after"), "1");
        assert.deepEqual(await saturated.json(), {
            error: { code: "RUNTIME_HANDLER_SATURATED", message: "Runtime handler capacity exhausted" },
        });
        assert.equal(calls, 1);
        release.resolve();
        assert.equal((await first).status, 200);
        assert.equal((await invoke(runtime.baseUrl, "handler-three", null)).status, 200);
    } finally {
        release.resolve();
        await runtime.stop();
    }
});

test("a timed-out non-cooperative handler owns its permit and input until physical exit", async () => {
    const release = deferred();
    let calls = 0;
    const runtime = createRuntime(options({
        handlerTimeoutMs: 20,
        callbackQueueSize: 2,
        maxPendingCallbackBytes: 2_048,
    }));
    runtime.register("blocked", async () => {
        calls += 1;
        if (calls === 1) await release.promise;
        return { ok: true };
    });
    await runtime.start();
    try {
        assert.equal((await invoke(runtime.baseUrl, "timeout-one", "retained")).status, 504);
        const saturated = await invoke(runtime.baseUrl, "timeout-two", null);
        assert.equal(saturated.status, 429);
        assert.deepEqual(await saturated.json(), {
            error: { code: "RUNTIME_HANDLER_SATURATED", message: "Runtime handler capacity exhausted" },
        });
        release.resolve();
        await new Promise<void>((resolve) => setImmediate(resolve));
        assert.equal((await invoke(runtime.baseUrl, "timeout-three", null)).status, 200);
    } finally {
        release.resolve();
        await runtime.stop();
    }
});

test("client cancellation aborts the handler, emits one canonical callback, and drains ownership", async () => {
    const started = deferred();
    const delivered = deferred();
    let callbackBody = "";
    const callbackServer = createServer((req, res) => {
        req.setEncoding("utf8");
        req.on("data", (chunk) => { callbackBody += chunk; });
        req.on("end", () => {
            res.statusCode = 204;
            res.end();
            delivered.resolve();
        });
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("cancelled", async ({ signal }) => {
        started.resolve();
        await new Promise<void>((resolve) => signal.addEventListener("abort", () => resolve(), { once: true }));
        return null;
    });
    await runtime.start();
    const invocation = request(`${runtime.baseUrl}/invoke`, {
        method: "POST",
        headers: {
            "content-type": "application/json",
            "x-execution-id": "cancelled-one",
        },
    });
    invocation.once("error", () => undefined);
    invocation.end(JSON.stringify({ input: { retained: true } }));
    try {
        await started.promise;
        invocation.destroy();
        await Promise.race([
            delivered.promise,
            new Promise((_, reject) => setTimeout(() => reject(new Error("cancellation callback not delivered")), 250)),
        ]);
        assert.deepEqual(JSON.parse(callbackBody), {
            success: false,
            output: null,
            error: { code: "INVOCATION_CANCELLED", message: "Invocation cancelled" },
        });
        const drained = await waitForCountersToDrain(runtime.baseUrl);
        assert.equal(metricValue(drained, "runtime_active_handlers"), 0);
        assert.equal(metricValue(drained, "runtime_input_bytes"), 0);
        assert.equal(metricValue(drained, "runtime_pending_callback_bytes"), 0);
        assert.match(drained, /runtime_invocations_total\{success="false"\}\s+1/);
    } finally {
        invocation.destroy();
        await runtime.stop();
        await close(callbackServer);
    }
});

test("callback count and bytes are reserved before handler start and released after drain", async () => {
    const callbackEntered = deferred();
    const releaseCallback = deferred();
    const callbackReleased = deferred();
    let handlerCalls = 0;
    const callbackServer = createServer(async (_req, res) => {
        callbackEntered.resolve();
        await releaseCallback.promise;
        res.statusCode = 204;
        res.end();
        callbackReleased.resolve();
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        maxConcurrentHandlers: 2,
        callbackQueueSize: 2,
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("echo", async () => {
        handlerCalls += 1;
        return { ok: true };
    });
    await runtime.start();
    try {
        assert.equal((await invoke(runtime.baseUrl, "callback-one", null)).status, 200);
        await callbackEntered.promise;
        const saturated = await invoke(runtime.baseUrl, "callback-two", null);
        assert.equal(saturated.status, 429);
        assert.equal(saturated.headers.get("retry-after"), "1");
        assert.deepEqual(await saturated.json(), {
            error: { code: "RUNTIME_CALLBACK_SATURATED", message: "Runtime callback capacity exhausted" },
        });
        assert.equal(handlerCalls, 1);
        releaseCallback.resolve();
        await callbackReleased.promise;
        assert.equal((await invoke(runtime.baseUrl, "callback-three", null)).status, 200);
    } finally {
        releaseCallback.resolve();
        await runtime.stop();
        await close(callbackServer);
    }
});

test("oversized handler output becomes the canonical 500 and bounded error callback", async () => {
    const callbackBody = deferred();
    let received = "";
    const callbackServer = createServer((req, res) => {
        req.setEncoding("utf8");
        req.on("data", (chunk) => { received += chunk; });
        req.on("end", () => {
            res.statusCode = 204;
            res.end();
            callbackBody.resolve();
        });
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        maxOutputBytes: 102,
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("large", async () => ({ value: "x".repeat(103) }));
    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "output-large", null);
        assert.equal(response.status, 500);
        const expectedError = {
            code: "RUNTIME_OUTPUT_TOO_LARGE",
            message: "Runtime output exceeds configured byte limit",
        };
        assert.deepEqual(await response.json(), { error: expectedError });
        await callbackBody.promise;
        assert.deepEqual(JSON.parse(received), { success: false, output: null, error: expectedError });
    } finally {
        await runtime.stop();
        await close(callbackServer);
    }
});

test("a single oversized callback is converted to the bounded output error callback", async () => {
    const delivered = deferred();
    let received = "";
    const callbackServer = createServer((req, res) => {
        req.setEncoding("utf8");
        req.on("data", (chunk) => { received += chunk; });
        req.on("end", () => {
            res.statusCode = 204;
            res.end();
            delivered.resolve();
        });
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        maxOutputBytes: 256,
        maxCallbackPayloadBytes: 180,
        maxPendingCallbackBytes: 180,
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("large-callback", async () => ({ value: "x".repeat(140) }));
    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "callback-large", null);
        assert.equal(response.status, 500);
        await delivered.promise;
        assert.deepEqual(JSON.parse(received), {
            success: false,
            output: null,
            error: {
                code: "RUNTIME_OUTPUT_TOO_LARGE",
                message: "Runtime output exceeds configured byte limit",
            },
        });
    } finally {
        await runtime.stop();
        await close(callbackServer);
    }
});

test("an oversized custom handler error cannot silently discard its reserved callback", async () => {
    const delivered = deferred();
    let received = "";
    const callbackServer = createServer((req, res) => {
        req.setEncoding("utf8");
        req.on("data", (chunk) => { received += chunk; });
        req.on("end", () => {
            res.statusCode = 204;
            res.end();
            delivered.resolve();
        });
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        maxCallbackPayloadBytes: 180,
        maxPendingCallbackBytes: 180,
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("large-error", async () => {
        throw new NanofaasError("CUSTOM", "x".repeat(500));
    });
    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "error-large", null);
        assert.equal(response.status, 500);
        assert.deepEqual(await response.json(), { error: {
            code: "HANDLER_ERROR",
            message: "Handler failed",
        } });
        await Promise.race([
            delivered.promise,
            new Promise((_, reject) => setTimeout(() => reject(new Error("error callback not delivered")), 250)),
        ]);
        assert.deepEqual(JSON.parse(received), {
            success: false,
            output: null,
            error: { code: "HANDLER_ERROR", message: "Handler failed" },
        });
    } finally {
        await runtime.stop();
        await close(callbackServer);
    }
});

test("a direct attacker-sized NanofaasError is replaced before HTTP serialization", async () => {
    const runtime = createRuntime(options({ maxOutputBytes: 102 }));
    runtime.register("large-direct-error", async () => {
        throw new NanofaasError("ATTACKER_ERROR", "x".repeat(1_000_000));
    });
    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "direct-error-large", null);
        const responseBody = await response.text();
        assert.equal(response.status, 500);
        assert.ok(
            Buffer.byteLength(responseBody, "utf8") <= 102,
            `direct error response was ${Buffer.byteLength(responseBody, "utf8")} bytes`,
        );
        assert.deepEqual(JSON.parse(responseBody), {
            error: { code: "HANDLER_ERROR", message: "Handler failed" },
        });
    } finally {
        await runtime.stop();
    }
});

test("invalid allowed response header emits one canonical failure callback", async () => {
    const callbackBodies: unknown[] = [];
    const callbackServer = createServer(async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const chunk of req) chunks.push(Buffer.from(chunk));
        callbackBodies.push(JSON.parse(Buffer.concat(chunks).toString("utf8")));
        res.statusCode = 204;
        res.end();
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        callbackUrl: `http://127.0.0.1:${callbackPort}`,
    }));
    runtime.register("echo", async () => new HandlerResponse(
        { ok: true },
        200,
        { Location: "valid\r\nInjected: invalid" },
    ));
    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "invalid-response-header", null);
        assert.equal(response.status, 500);
        const expectedFailure = {
            success: false,
            output: null,
            error: {
                code: "OUTPUT_SERIALIZATION_ERROR",
                message: "Handler response headers are invalid",
            },
        };
        assert.deepEqual(await response.json(), { error: expectedFailure.error });
        await waitUntilAllCallbackOwnershipDrains(runtime.baseUrl);
        assert.deepEqual(callbackBodies, [expectedFailure]);
    } finally {
        await runtime.stop();
        await close(callbackServer);
    }
});

test("attacker-sized invalid response status emits one bounded canonical callback and response", async () => {
    const callbackBodies: unknown[] = [];
    const callbackServer = createServer(async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const chunk of req) chunks.push(Buffer.from(chunk));
        callbackBodies.push(JSON.parse(Buffer.concat(chunks).toString("utf8")));
        res.statusCode = 204;
        res.end();
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        callbackUrl: `http://127.0.0.1:${callbackPort}`,
        maxOutputBytes: 256,
    }));
    const attackerStatus = {
        toString: (): string => "9".repeat(4_096),
    } as unknown as number;
    runtime.register("echo", async () => new HandlerResponse(null, attackerStatus));
    await runtime.start();
    try {
        const response = await invoke(runtime.baseUrl, "invalid-large-status", null);
        const responseText = await response.text();
        const expectedFailure = {
            success: false,
            output: null,
            error: {
                code: "OUTPUT_SERIALIZATION_ERROR",
                message: "Handler response status is invalid",
            },
        };
        assert.equal(response.status, 500);
        assert.ok(Buffer.byteLength(responseText) <= 256);
        assert.deepEqual(JSON.parse(responseText), { error: expectedFailure.error });
        await waitUntilAllCallbackOwnershipDrains(runtime.baseUrl);
        assert.deepEqual(callbackBodies, [expectedFailure]);
    } finally {
        await runtime.stop();
        await close(callbackServer);
    }
});

test("each callback attempt times out and stop remains bounded with a full callback reservation", async () => {
    let attempts = 0;
    const attempted = deferred();
    const attemptClosed = deferred();
    const callbackServer = createServer((req, res) => {
        attempts += 1;
        attempted.resolve();
        req.once("aborted", attemptClosed.resolve);
        res.once("close", attemptClosed.resolve);
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        callbackAttemptTimeoutMs: 20,
        callbackMaxAttempts: 1,
        shutdownTimeoutMs: 60,
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("echo", async () => ({ ok: true }));
    await runtime.start();
    try {
        assert.equal((await invoke(runtime.baseUrl, "callback-timeout", null)).status, 200);
        await attempted.promise;
        await Promise.race([
            attemptClosed.promise,
            new Promise((_, reject) => setTimeout(() => reject(new Error("callback attempt did not time out")), 250)),
        ]);
        assert.equal(attempts, 1);
        await runtime.stop();
        assert.equal(attempts, 1);
    } finally {
        await runtime.stop();
        await close(callbackServer);
    }
});

test("callback ownership includes the response body until completion or attempt timeout", async () => {
    const headersSent = deferred();
    const responseClosed = deferred();
    const callbackServer = createServer((_req, res) => {
        res.writeHead(200, { "content-type": "application/octet-stream" });
        res.write("partial");
        headersSent.resolve();
        res.once("close", responseClosed.resolve);
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        callbackAttemptTimeoutMs: 50,
        callbackMaxAttempts: 1,
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("echo", async () => ({ ok: true }));
    await runtime.start();
    try {
        assert.equal((await invoke(runtime.baseUrl, "body-one", null)).status, 200);
        await headersSent.promise;
        const saturated = await invoke(runtime.baseUrl, "body-two", null);
        assert.equal(saturated.status, 429);
        assert.deepEqual(await saturated.json(), {
            error: {
                code: "RUNTIME_CALLBACK_SATURATED",
                message: "Runtime callback capacity exhausted",
            },
        });
        await Promise.race([
            responseClosed.promise,
            new Promise((_, reject) => setTimeout(() => reject(new Error("callback response body was not cancelled")), 250)),
        ]);
        await waitForCountersToDrain(runtime.baseUrl);
    } finally {
        await runtime.stop();
        await close(callbackServer);
    }
});

test("output bytes remain owned until the HTTP response physically finishes or closes", async () => {
    const output = "x".repeat(8 * 1024 * 1024);
    const runtime = createRuntime(options({ maxOutputBytes: 9 * 1024 * 1024 }));
    runtime.register("echo", async () => ({ output }));
    await runtime.start();
    let response: import("node:http").IncomingMessage | undefined;
    try {
        const pausedResponse = await new Promise<import("node:http").IncomingMessage>((resolve, reject) => {
            const req = request(`${runtime.baseUrl}/invoke`, {
                method: "POST",
                headers: {
                    "content-type": "application/json",
                    "content-length": "14",
                    "x-execution-id": "slow-response-reader",
                },
            }, (incoming) => {
                incoming.pause();
                resolve(incoming);
            });
            req.once("error", reject);
            req.end('{"input":null}');
        });
        response = pausedResponse;
        const metricsWhilePaused = await (await fetch(`${runtime.baseUrl}/metrics`)).text();
        assert.ok(
            metricValue(metricsWhilePaused, "runtime_output_bytes") > 0,
            "serialized output was released before the response finished",
        );

        const ended = new Promise<void>((resolve, reject) => {
            pausedResponse.once("end", resolve);
            pausedResponse.once("error", reject);
        });
        pausedResponse.resume();
        await ended;
        await waitForMetric(runtime.baseUrl, "runtime_output_bytes", 0);
    } finally {
        response?.destroy();
        await runtime.stop();
    }
});

test("owned resource counters expose reservations and return to zero after callback drain", async () => {
    const entered = deferred();
    const release = deferred();
    const callbackServer = createServer(async (_req, res) => {
        entered.resolve();
        await release.promise;
        res.statusCode = 204;
        res.end();
    });
    const callbackPort = await listen(callbackServer);
    const runtime = createRuntime(options({
        callbackUrl: `http://127.0.0.1:${callbackPort}/callbacks`,
    }));
    runtime.register("echo", async () => ({ ok: true }));
    await runtime.start();
    try {
        assert.equal((await invoke(runtime.baseUrl, "counter-one", null)).status, 200);
        await entered.promise;
        const retained = await (await fetch(`${runtime.baseUrl}/metrics`)).text();
        assert.equal(metricValue(retained, "runtime_active_handlers"), 0);
        assert.equal(metricValue(retained, "runtime_input_bytes"), 0);
        assert.equal(metricValue(retained, "runtime_output_bytes"), 0);
        assert.equal(metricValue(retained, "runtime_pending_callbacks"), 1);
        assert.equal(metricValue(retained, "runtime_pending_callback_bytes"), 1_024);
        assert.ok(metricValue(retained, "runtime_serialized_callback_bytes") > 0);

        release.resolve();
        const drained = await waitForCountersToDrain(runtime.baseUrl);
        for (const name of [
            "runtime_active_handlers",
            "runtime_input_bytes",
            "runtime_output_bytes",
            "runtime_pending_callbacks",
            "runtime_pending_callback_bytes",
            "runtime_serialized_callback_bytes",
        ]) assert.equal(metricValue(drained, name), 0);
    } finally {
        release.resolve();
        await runtime.stop();
        await close(callbackServer);
    }
});

test("stop is bounded, rejects admitted requests as stopping, and permits restart", async () => {
    const runtime = createRuntime(options({ shutdownTimeoutMs: 50 }));
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    const oldBaseUrl = runtime.baseUrl;
    const stopping = runtime.stop();
    try {
        const response = await fetch(`${oldBaseUrl}/invoke`, {
            method: "POST",
            headers: { "content-type": "application/json", "x-execution-id": "during-stop" },
            body: JSON.stringify({ input: null }),
        }).catch(() => undefined);
        if (response) {
            assert.equal(response.status, 503);
            assert.equal(response.headers.get("retry-after"), "1");
        }
        await stopping;
        await runtime.start();
        assert.equal((await invoke(runtime.baseUrl, "after-restart", { ok: true })).status, 200);
    } finally {
        await runtime.stop();
    }
});

test("stop force-closes active and idle sockets at one deadline before restart", async () => {
    const handlerEntered = deferred();
    const runtime = createRuntime(options({ handlerTimeoutMs: 1_000, shutdownTimeoutMs: 40 }));
    runtime.register("echo", async () => {
        handlerEntered.resolve();
        return new Promise<never>(() => undefined);
    });
    await runtime.start();

    let activeSocket!: Socket;
    const activeClosed = deferred();
    let activeWasClosed = false;
    const activeRequest = request(`${runtime.baseUrl}/invoke`, {
        method: "POST",
        headers: { "content-type": "application/json", "x-execution-id": "active-at-stop" },
    });
    activeRequest.once("socket", (socket) => {
        activeSocket = socket;
        socket.once("close", () => {
            activeWasClosed = true;
            activeClosed.resolve();
        });
        socket.on("error", () => undefined);
    });
    activeRequest.on("error", () => undefined);
    activeRequest.end('{"input":null}');
    await handlerEntered.promise;

    const idleSocket = createConnection(runtime.port, "127.0.0.1");
    const idleClosed = deferred();
    let idleWasClosed = false;
    idleSocket.once("close", () => {
        idleWasClosed = true;
        idleClosed.resolve();
    });
    idleSocket.on("error", () => undefined);
    await new Promise<void>((resolve, reject) => {
        idleSocket.once("connect", resolve);
        idleSocket.once("error", reject);
    });

    const startedAt = Date.now();
    await runtime.stop();
    const elapsed = Date.now() - startedAt;
    assert.equal(activeWasClosed, true, "stop resolved before the active client observed close");
    assert.equal(idleWasClosed, true, "stop resolved before the idle client observed close");
    assert.throws(() => runtime.port, /has not started/i);
    await Promise.race([
        Promise.all([activeClosed.promise, idleClosed.promise]),
        new Promise((_, reject) => setTimeout(() => reject(new Error("old runtime socket survived stop")), 100)),
    ]);
    assert.ok(elapsed < 70, `stop consumed more than one shutdown deadline: ${elapsed}ms`);
    assert.equal(activeSocket.destroyed, true);
    assert.equal(idleSocket.destroyed, true);

    await runtime.start();
    try {
        assert.equal((await fetch(`${runtime.baseUrl}/health`)).status, 200);
        assert.equal(activeSocket.write("GET /health HTTP/1.1\r\nHost: old\r\n\r\n"), false);
    } finally {
        activeRequest.destroy();
        idleSocket.destroy();
        await runtime.stop();
    }
});

test("stop-cancelled admitted invocation is counted as one failure across restart", async () => {
    const handlerEntered = deferred();
    const runtime = createRuntime(options({ shutdownTimeoutMs: 100 }));
    runtime.register("echo", async ({ executionId, signal }) => {
        if (executionId !== "cancelled-by-stop") return { ok: true };
        handlerEntered.resolve();
        await new Promise<void>((resolve) => {
            signal.addEventListener("abort", () => setImmediate(resolve), { once: true });
        });
        return null;
    });
    await runtime.start();
    const cancelledInvocation = invoke(runtime.baseUrl, "cancelled-by-stop", null);
    void cancelledInvocation.catch(() => undefined);
    await handlerEntered.promise;
    await runtime.stop();

    await runtime.start();
    try {
        const afterStop = await (await fetch(`${runtime.baseUrl}/metrics`)).text();
        assert.match(afterStop, /runtime_invocations_total\{success="false"\}\s+1(?:\.0+)?$/m);
        assert.equal((await invoke(runtime.baseUrl, "successful-after-stop", null)).status, 200);
        const afterSuccess = await (await fetch(`${runtime.baseUrl}/metrics`)).text();
        assert.match(afterSuccess, /runtime_invocations_total\{success="false"\}\s+1(?:\.0+)?$/m);
    } finally {
        await runtime.stop();
    }
});

test("concurrent start callers await one bind and stop owns a pending bind", async () => {
    const runtime = createRuntime(options());
    runtime.register("echo", async () => ({ ok: true }));

    const firstStart = runtime.start();
    const secondStart = runtime.start();
    await secondStart;
    assert.doesNotThrow(() => runtime.port, "a concurrent start resolved before bind completed");
    await firstStart;

    const stoppedDuringBind = createRuntime(options());
    stoppedDuringBind.register("echo", async () => ({ ok: true }));
    const pendingStart = stoppedDuringBind.start();
    const pendingStop = stoppedDuringBind.stop();
    await Promise.all([pendingStart, pendingStop]);
    await stoppedDuringBind.start();
    try {
        assert.equal((await invoke(stoppedDuringBind.baseUrl, "restart-after-bind", null)).status, 200);
    } finally {
        await stoppedDuringBind.stop();
        await runtime.stop();
    }
});
