import assert from "node:assert/strict";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";
import { HandlerResponse } from "../src/response.js";
import type { Handler } from "../src/types.js";

async function invokeWith(handler: Handler): Promise<Response> {
    const runtime = createRuntime({ port: 0 });
    runtime.register("fn", handler);
    await runtime.start();
    try {
        return await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: { "content-type": "application/json", "x-execution-id": "exec-1" },
            body: JSON.stringify({ input: "payload" }),
        });
    } finally {
        await runtime.stop();
    }
}

test("envelope applies the status, allow-listed headers and both markers", async () => {
    const response = await invokeWith(() => new HandlerResponse(
        { error: "not found" },
        404,
        { Location: "/x", "X-Custom": "dropped" },
        "base64",
    ));

    assert.equal(response.status, 404);
    assert.equal(response.headers.get("location"), "/x");
    assert.equal(response.headers.get("x-custom"), null);
    assert.equal(response.headers.get("x-nanofaas-function-status"), "true");
    assert.equal(response.headers.get("x-nanofaas-encoding"), "base64");
    assert.deepEqual(await response.json(), { error: "not found" });
});

test("envelope omits the encoding marker when the handler set no encoding", async () => {
    const response = await invokeWith(() => new HandlerResponse({ ok: true }, 201));

    assert.equal(response.status, 201);
    assert.equal(response.headers.get("x-nanofaas-function-status"), "true");
    assert.equal(response.headers.get("x-nanofaas-encoding"), null);
});

test("an out-of-range status is a platform error", async () => {
    const response = await invokeWith(() => new HandlerResponse({ ok: true }, 999));

    assert.equal(response.status, 500);
    assert.equal(response.headers.get("x-nanofaas-function-status"), null);
});

test("a handler cannot spoof the control headers", async () => {
    const response = await invokeWith(() => new HandlerResponse({ ok: true }, 200, {
        "X-NanoFaaS-Function-Status": "spoofed",
        "X-NanoFaaS-Encoding": "spoofed",
        "X-Execution-Id": "spoofed",
    }));

    assert.equal(response.headers.get("x-nanofaas-encoding"), null);
    assert.equal(response.headers.get("x-nanofaas-function-status"), "true");
});

test("a plain-value return behaves exactly as before", async () => {
    const response = await invokeWith(() => ({ roman: "XLII" }));

    assert.equal(response.status, 200);
    assert.equal(response.headers.get("x-nanofaas-function-status"), null);
    assert.equal(response.headers.get("x-nanofaas-encoding"), null);
    assert.deepEqual(await response.json(), { roman: "XLII" });
});
