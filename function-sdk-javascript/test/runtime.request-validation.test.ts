import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";

async function withRuntime() {
    const runtime = createRuntime({ port: 0 });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    return runtime;
}

test("invalid json returns 400 with INVALID_JSON", async () => {
    const runtime = await withRuntime();
    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-invalid-json",
            },
            body: "{",
        });

        assert.equal(response.status, 400);
        assert.deepEqual(await response.json(), {
            error: {
                code: "INVALID_JSON",
                message: "Request body must be valid JSON",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("metadata must be a string map", async () => {
    const runtime = await withRuntime();
    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-invalid-metadata",
            },
            body: JSON.stringify({
                input: { ok: true },
                metadata: { attempt: 1 },
            }),
        });

        assert.equal(response.status, 400);
        assert.deepEqual(await response.json(), {
            error: {
                code: "INVALID_REQUEST",
                message: "Invocation metadata must be a string map",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("invalid metadata still sends failure callback when execution id is known", async () => {
    let resolveReceived!: (value: unknown) => void;
    let rejectReceived!: (reason?: unknown) => void;
    const received = new Promise<unknown>((resolve, reject) => {
        resolveReceived = resolve;
        rejectReceived = reject;
    });

    const callbackServer = createServer(async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const chunk of req) {
            chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
        }
        res.statusCode = 200;
        res.end("ok");
        resolveReceived(JSON.parse(Buffer.concat(chunks).toString("utf8")));
    });

    await new Promise<void>((resolve, reject) => {
        callbackServer.once("error", reject);
        callbackServer.listen(0, "127.0.0.1", () => {
            callbackServer.off("error", reject);
            resolve();
        });
    });

    const address = callbackServer.address();
    if (!address || typeof address === "string") {
        throw new Error("callback server did not bind");
    }

    const timeout = setTimeout(() => {
        rejectReceived(new Error("callback not received"));
    }, 1_000);

    const runtime = createRuntime({
        port: 0,
        callbackUrl: `http://127.0.0.1:${address.port}/callbacks`,
    });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-invalid-metadata-callback",
            },
            body: JSON.stringify({
                input: { ok: true },
                metadata: { attempt: 1 },
            }),
        });

        assert.equal(response.status, 400);
        assert.deepEqual(await received, {
            success: false,
            output: null,
            error: {
                code: "INVALID_REQUEST",
                message: "Invocation metadata must be a string map",
            },
        });
    } finally {
        clearTimeout(timeout);
        await runtime.stop();
        await new Promise<void>((resolve, reject) =>
            callbackServer.close((error) => (error ? reject(error) : resolve())),
        );
    }
});
