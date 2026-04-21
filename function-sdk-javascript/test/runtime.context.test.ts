import assert from "node:assert/strict";
import { test } from "node:test";

import {
    createRuntime,
    getExecutionId,
    getTraceId,
} from "../src/index.js";

test("concurrent requests keep execution and trace context isolated", async () => {
    const runtime = createRuntime({ port: 0 });
    runtime.register("echo", async (ctx, req) => {
        const delay = typeof req.input === "object" && req.input !== null && "delayMs" in req.input
            ? Number(req.input.delayMs)
            : 0;
        await new Promise((resolve) => setTimeout(resolve, delay));

        return {
            ctxExecutionId: ctx.executionId,
            ctxTraceId: ctx.traceId,
            storeExecutionId: getExecutionId(),
            storeTraceId: getTraceId(),
        };
    });
    await runtime.start();

    try {
        const [first, second] = await Promise.all([
            fetch(`${runtime.baseUrl}/invoke`, {
                method: "POST",
                headers: {
                    "content-type": "application/json",
                    "x-execution-id": "exec-a",
                    "x-trace-id": "trace-a",
                },
                body: JSON.stringify({ input: { delayMs: 25 } }),
            }),
            fetch(`${runtime.baseUrl}/invoke`, {
                method: "POST",
                headers: {
                    "content-type": "application/json",
                    "x-execution-id": "exec-b",
                    "x-trace-id": "trace-b",
                },
                body: JSON.stringify({ input: { delayMs: 5 } }),
            }),
        ]);

        assert.deepEqual(await first.json(), {
            ctxExecutionId: "exec-a",
            ctxTraceId: "trace-a",
            storeExecutionId: "exec-a",
            storeTraceId: "trace-a",
        });
        assert.deepEqual(await second.json(), {
            ctxExecutionId: "exec-b",
            ctxTraceId: "trace-b",
            storeExecutionId: "exec-b",
            storeTraceId: "trace-b",
        });
    } finally {
        await runtime.stop();
    }
});
