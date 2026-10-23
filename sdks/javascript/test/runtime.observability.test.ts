import assert from "node:assert/strict";
import { test } from "node:test";

import { createRuntime, NanofaasError } from "../src/index.js";

test("callback failure logs retain both identities for concurrent invocations", async () => {
    process.env.NANOFAAS_LOG_FORMAT = "json";
    const original = console.log;
    const events: Record<string, unknown>[] = [];
    console.log = (line: unknown) => { try { events.push(JSON.parse(String(line))); } catch { /* non-JSON */ } };
    const runtime = createRuntime({ port: 0, callbackUrl: "http://127.0.0.1:9/callbacks", callbackMaxAttempts: 1 });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    try {
        await Promise.all(["one", "two"].map(id => fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST", headers: {"content-type":"application/json", "x-execution-id":id, "x-trace-id":"trace-"+id}, body: JSON.stringify({input:"ok"}),
        })));
        const deadline = Date.now()+2000;
        while (events.filter(e=>e.message === "callback delivery failed").length < 2 && Date.now() < deadline) await new Promise(resolve=>setTimeout(resolve,10));
        const failures = events.filter(e=>e.message === "callback delivery failed");
        assert.equal(failures.length, 2);
        for (const event of failures) assert.equal(event.traceId, "trace-"+event.executionId);
    } finally { await runtime.stop(); console.log=original; delete process.env.NANOFAAS_LOG_FORMAT; }
});

test("handler logger emits structured json with execution and trace ids", async () => {
    process.env.NANOFAAS_LOG_FORMAT = "json";
    const originalLog = console.log;
    const lines: string[] = [];
    console.log = (line?: unknown) => {
        lines.push(String(line));
    };

    const runtime = createRuntime({ port: 0 });
    runtime.register("echo", async ({ logger }) => {
        logger.info("handler called", { phase: "invoke" });
        return { ok: true };
    });
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-log",
                "x-trace-id": "trace-log",
            },
            body: JSON.stringify({ input: null }),
        });
        assert.equal(response.status, 200);

        const entry = JSON.parse(lines.find((line) => line.includes("handler called"))!);
        assert.equal(entry.level, "INFO");
        assert.equal(entry.logger, "nanofaas.handler");
        assert.equal(entry.executionId, "exec-log");
        assert.equal(entry.traceId, "trace-log");
        assert.equal(entry.attributes.phase, "invoke");
    } finally {
        delete process.env.NANOFAAS_LOG_FORMAT;
        console.log = originalLog;
        await runtime.stop();
    }
});

test("metrics expose success, failure, in-flight, cold-start, and callback-failure counters", async () => {
    const runtime = createRuntime({ port: 0, callbackUrl: "http://127.0.0.1:9/callbacks" });
    runtime.register("echo", async (_ctx, req) => {
        if (req.input === "boom") {
            throw new NanofaasError("BAD_INPUT", "Invalid payload");
        }
        return { ok: true };
    });
    await runtime.start();

    try {
        async function waitFor(assertion: () => Promise<void>, timeoutMs = 1_000): Promise<void> {
            const startedAt = Date.now();
            while (true) {
                try {
                    await assertion();
                    return;
                } catch (error) {
                    if (Date.now() - startedAt >= timeoutMs) {
                        throw error;
                    }
                    await new Promise((resolve) => setTimeout(resolve, 20));
                }
            }
        }

        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-metrics-1",
            },
            body: JSON.stringify({ input: "ok" }),
        });

        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-metrics-2",
            },
            body: JSON.stringify({ input: "boom" }),
        });

        await waitFor(async () => {
            const response = await fetch(`${runtime.baseUrl}/metrics`);
            const body = await response.text();

            assert.match(body, /runtime_invocations_total\{success="true"\}\s+1/);
            assert.match(body, /runtime_invocations_total\{success="false"\}\s+1/);
            assert.match(body, /runtime_in_flight\s+0/);
            assert.match(body, /runtime_cold_start\s+1/);
            assert.match(body, /runtime_callback_failures\s+[1-9]/);
        });
    } finally {
        await runtime.stop();
    }
});
