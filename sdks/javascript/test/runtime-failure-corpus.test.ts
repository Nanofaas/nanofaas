import { execFileSync } from "node:child_process";
import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { createServer, request } from "node:http";
import { resolve } from "node:path";
import { test } from "node:test";
import { HandlerResponse, createRuntime, type RuntimeOptions, type JsonValue } from "../src/index.js";

execFileSync("python3", [resolve("..", "runtime-contract", "validate_saturation_wire_corpus.py"), resolve("..", "runtime-contract", "failure-wire-corpus.json")], { timeout: 10000 });
const corpus = JSON.parse(await readFile(resolve("..", "runtime-contract", "failure-wire-corpus.json"), "utf8"));
for (const [name, definition] of Object.entries(corpus.contractDefinitions)) {
    const expected = { ...definition as object, ...corpus.knownDifferences.javascript?.[name] } as { httpStatus: number; errorCode: string | null; handlerStarted: boolean; callbackAttempts: number; callbackStatus: number | null };
    test(`shared failure: ${name}`, { timeout: corpus.config.deadlineMs }, async () => {
        const callbacks: { payload: unknown; path: string; attempt: string | undefined; trace: string | undefined }[] = [];
        const server = createServer(async (req, res) => {
            const chunks: Buffer[] = [];
            for await (const chunk of req) chunks.push(Buffer.from(chunk));
            callbacks.push({ path: req.url!, payload: JSON.parse(Buffer.concat(chunks).toString()), attempt: req.headers["x-dispatch-attempt"] as string, trace: req.headers["x-trace-id"] as string });
            if (name === "callback-io-timeout") return; // Runtime's finite I/O timeout closes it.
            res.writeHead(expected.callbackStatus ?? 204).end();
        });
        await new Promise<void>((resolve) => server.listen(0, "127.0.0.1", resolve));
        const address = server.address();
        assert.ok(address && typeof address !== "string");
        const runtime = createRuntime({ port: 0, callbackUrl: `http://127.0.0.1:${address.port}`, ...corpus.config, handlerTimeoutMs: 1000, shutdownTimeoutMs: 2000 } as RuntimeOptions);
        let started = false;
        runtime.register("failure", async () => {
            started = true;
            if (name === "envelope-serialization-failure") {
                const circular: Record<string, unknown> = {}; circular.self = circular;
                return new HandlerResponse(circular as JsonValue, 201);
            }
            return corpus.successOutput;
        });
        await runtime.start();
        try {
            const headers = { "content-type": "application/json", "x-execution-id": corpus.config.executionId, "x-dispatch-attempt": String(corpus.config.dispatchAttempt), "x-trace-id": corpus.config.traceId };
            let response: { status: number; body: any };
            if (name === "ingress-io-timeout") {
                response = await new Promise((resolve, reject) => {
                    const upload = request(`${runtime.baseUrl}/invoke`, { method: "POST", headers }, (res) => {
                        let text = ""; res.on("data", (chunk) => text += chunk);
                        res.on("end", () => { upload.destroy(); resolve({ status: res.statusCode!, body: JSON.parse(text) }); });
                    });
                    upload.on("error", reject); upload.write('{"input":');
                });
            } else {
                const res = await fetch(`${runtime.baseUrl}/invoke`, { method: "POST", headers, body: '{"input":null}' });
                response = { status: res.status, body: await res.json() };
            }
            assert.equal(response.status, expected.httpStatus);
            assert.equal(started, expected.handlerStarted);
            if (expected.errorCode) { assert.equal(response.body.error.code, expected.errorCode); assert.ok(response.body.error.message); }
            const deadline = Date.now() + corpus.config.deadlineMs;
            for (;;) {
                const metrics = await (await fetch(`${runtime.baseUrl}/metrics`)).text();
                const drained = Object.entries(corpus.finalCounters).every(([key, value]) => {
                    const metric = `runtime_${key.replace(/[A-Z]/g, (c) => `_${c.toLowerCase()}`)}`;
                    return Number(metrics.match(new RegExp(`^${metric} (\\d+)$`, "m"))?.[1]) === value;
                });
                if (drained) break;
                assert.ok(Date.now() < deadline, "callback resources did not drain");
                await new Promise<void>((resolve) => setTimeout(resolve, 1));
            }
            assert.equal(callbacks.length, expected.callbackAttempts);
            for (const callback of callbacks) {
                assert.ok(callback.path.includes(corpus.config.executionId));
                assert.equal(callback.attempt, String(corpus.config.dispatchAttempt)); assert.equal(callback.trace, corpus.config.traceId);
                assert.deepEqual(callback.payload, expected.errorCode ? { success: false, output: null, error: response.body.error } : { success: true, output: corpus.successOutput, error: null });
            }
        } finally {
            await runtime.stop(); server.closeAllConnections();
            await new Promise<void>((resolve) => server.close(() => resolve()));
        }
    });
}
