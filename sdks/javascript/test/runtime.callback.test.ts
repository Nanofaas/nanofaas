import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";

import { createRuntime, type RuntimeOptions } from "../src/index.js";

test("callback failure does not change successful invoke response", async () => {
    const original = process.env.CALLBACK_URL;
    process.env.CALLBACK_URL = "http://127.0.0.1:9/callbacks";

    const runtime = createRuntime({ port: 0 });
    runtime.register("echo", async (_ctx, req) => ({
        echoed: req.input,
    }));
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-callback",
            },
            body: JSON.stringify({ input: { ok: true } }),
        });

        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), {
            echoed: { ok: true },
        });
    } finally {
        await runtime.stop();
        if (original === undefined) {
            delete process.env.CALLBACK_URL;
        } else {
            process.env.CALLBACK_URL = original;
        }
    }
});

test("callback retries 429 and forwards dispatch attempt", async () => {
    let attempts = 0;
    let dispatchAttempt: string | undefined;
    let resolveDelivered!: () => void;
    const delivered = new Promise<void>((resolve) => {
        resolveDelivered = resolve;
    });
    const callbackServer = createServer((req, res) => {
        attempts += 1;
        dispatchAttempt = req.headers["x-dispatch-attempt"] as string | undefined;
        res.statusCode = attempts === 1 ? 429 : 204;
        res.end();
        if (attempts === 2) resolveDelivered();
    });
    await new Promise<void>((resolve) => callbackServer.listen(0, "127.0.0.1", resolve));
    const address = callbackServer.address();
    if (!address || typeof address === "string") throw new Error("callback server did not bind");

    const runtime = createRuntime({ port: 0, callbackUrl: `http://127.0.0.1:${address.port}/callbacks` });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    try {
        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-retry",
                "x-dispatch-attempt": "3",
            },
            body: JSON.stringify({ input: "ok" }),
        });
        await Promise.race([
            delivered,
            new Promise((_, reject) => setTimeout(() => reject(new Error("callback retry not received")), 400)),
        ]);
        assert.equal(attempts, 2);
        assert.equal(dispatchAttempt, "3");
    } finally {
        await runtime.stop();
        await new Promise<void>((resolve) => callbackServer.close(() => resolve()));
    }
});

test("callback does not retry permanent 4xx", async () => {
    let attempts = 0;
    const callbackServer = createServer((_req, res) => {
        attempts += 1;
        res.statusCode = 400;
        res.end();
    });
    await new Promise<void>((resolve) => callbackServer.listen(0, "127.0.0.1", resolve));
    const address = callbackServer.address();
    if (!address || typeof address === "string") throw new Error("callback server did not bind");

    const runtime = createRuntime({ port: 0, callbackUrl: `http://127.0.0.1:${address.port}/callbacks` });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    try {
        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: { "content-type": "application/json", "x-execution-id": "exec-400" },
            body: JSON.stringify({ input: "ok" }),
        });
        await new Promise((resolve) => setTimeout(resolve, 250));
        assert.equal(attempts, 1);
    } finally {
        await runtime.stop();
        await new Promise<void>((resolve) => callbackServer.close(() => resolve()));
    }
});

test("callback dispatch is bounded and stop cancels in-flight delivery", async () => {
    let attempts = 0;
    const callbackServer = createServer((_req, _res) => {
        attempts += 1;
    });
    await new Promise<void>((resolve) => callbackServer.listen(0, "127.0.0.1", resolve));
    const address = callbackServer.address();
    if (!address || typeof address === "string") throw new Error("callback server did not bind");

    const runtime = createRuntime({
        port: 0,
        callbackUrl: `http://127.0.0.1:${address.port}/callbacks`,
        callbackQueueSize: 1,
    } as RuntimeOptions);
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    try {
        for (const executionId of ["exec-one", "exec-two"]) {
            await fetch(`${runtime.baseUrl}/invoke`, {
                method: "POST",
                headers: { "content-type": "application/json", "x-execution-id": executionId },
                body: JSON.stringify({ input: "ok" }),
            });
        }
        await new Promise((resolve) => setTimeout(resolve, 50));
        assert.equal(attempts, 1);

        await Promise.race([
            runtime.stop(),
            new Promise((_, reject) => setTimeout(() => reject(new Error("runtime stop timed out")), 500)),
        ]);
    } finally {
        await runtime.stop();
        callbackServer.closeAllConnections();
        await new Promise<void>((resolve) => callbackServer.close(() => resolve()));
    }
});

test("callbacks work after restarting the same runtime", async () => {
    let deliveries = 0;
    let resolveDelivered!: () => void;
    const delivered = new Promise<void>((resolve) => {
        resolveDelivered = resolve;
    });
    const callbackServer = createServer((_req, res) => {
        deliveries += 1;
        res.statusCode = 204;
        res.end();
        resolveDelivered();
    });
    await new Promise<void>((resolve) => callbackServer.listen(0, "127.0.0.1", resolve));
    const address = callbackServer.address();
    if (!address || typeof address === "string") throw new Error("callback server did not bind");

    const runtime = createRuntime({
        port: 0,
        callbackUrl: `http://127.0.0.1:${address.port}/callbacks`,
    });
    runtime.register("echo", async (_ctx, req) => req.input);

    try {
        await runtime.start();
        await runtime.stop();
        await runtime.start();
        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: { "content-type": "application/json", "x-execution-id": "exec-restarted" },
            body: JSON.stringify({ input: "ok" }),
        });
        await Promise.race([
            delivered,
            new Promise((_, reject) => setTimeout(() => reject(new Error("callback not delivered")), 300)),
        ]);
        assert.equal(deliveries, 1);
    } finally {
        await runtime.stop();
        await new Promise<void>((resolve) => callbackServer.close(() => resolve()));
    }
});
