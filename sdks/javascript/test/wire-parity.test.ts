import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";
import { HandlerResponse } from "../src/response.js";
import type { Handler } from "../src/types.js";

// The frozen wire contract, shared with platform/libs/common (Java), sdks/python and sdks/go.
const MARKER_HEADER = "x-nanofaas-function-status";
const ENCODING_HEADER = "x-nanofaas-encoding";
const CALLBACK_KEYS = ["statusCode", "headers", "encoding"];

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

async function captureCallbackBody(handler: Handler): Promise<Record<string, unknown>> {
    let resolveDelivered!: (body: Record<string, unknown>) => void;
    const delivered = new Promise<Record<string, unknown>>((resolve) => {
        resolveDelivered = resolve;
    });
    const callbackServer = createServer((req, res) => {
        const chunks: Buffer[] = [];
        req.on("data", (chunk: Buffer) => chunks.push(chunk));
        req.on("end", () => {
            res.statusCode = 204;
            res.end();
            resolveDelivered(JSON.parse(Buffer.concat(chunks).toString("utf8")));
        });
    });
    await new Promise<void>((resolve) => callbackServer.listen(0, "127.0.0.1", resolve));
    const address = callbackServer.address();
    if (!address || typeof address === "string") throw new Error("callback server did not bind");

    const runtime = createRuntime({ port: 0, callbackUrl: `http://127.0.0.1:${address.port}/callbacks` });
    runtime.register("fn", handler);
    await runtime.start();
    try {
        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: { "content-type": "application/json", "x-execution-id": "exec-1" },
            body: JSON.stringify({ input: "payload" }),
        });
        return await Promise.race([
            delivered,
            new Promise<Record<string, unknown>>((_, reject) => {
                setTimeout(() => reject(new Error("callback not received")), 2000);
            }),
        ]);
    } finally {
        await runtime.stop();
        await new Promise<void>((resolve) => callbackServer.close(() => resolve()));
    }
}

test("the runtime emits the exact marker header names", async () => {
    const response = await invokeWith(() => new HandlerResponse("x", 201, {}, "base64"));

    assert.equal(response.headers.get(MARKER_HEADER), "true");
    assert.equal(response.headers.get(ENCODING_HEADER), "base64");
});

test("the callback body uses camelCase keys, never snake_case", async () => {
    const body = await captureCallbackBody(() => new HandlerResponse("x", 201, {}, "base64"));

    for (const key of CALLBACK_KEYS) {
        assert.ok(Object.keys(body).includes(key), `callback body must contain ${key}`);
    }
    assert.equal(Object.keys(body).includes("status_code"), false);
});
