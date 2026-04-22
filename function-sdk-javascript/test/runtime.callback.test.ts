import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";

import { NanofaasError, createRuntime } from "../src/index.js";

async function startCallbackCaptureServer() {
    let resolveReceived!: (value: {
        url: string;
        traceId: string | undefined;
        body: unknown;
    }) => void;
    let rejectReceived!: (reason?: unknown) => void;
    const settledReceived = new Promise<{
        url: string;
        traceId: string | undefined;
        body: unknown;
    }>((resolve, reject) => {
        resolveReceived = resolve;
        rejectReceived = reject;
    });

    const server = createServer(async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const chunk of req) {
            chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
        }
        res.statusCode = 200;
        res.end("ok");
        resolveReceived({
            url: req.url ?? "",
            traceId: req.headers["x-trace-id"] as string | undefined,
            body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
        });
    });

    await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", () => {
            server.off("error", reject);
            resolve();
        });
    });

    const address = server.address();
    if (!address || typeof address === "string") {
        throw new Error("callback server did not bind");
    }

    const timeout = setTimeout(() => {
        rejectReceived(new Error("callback not received"));
    }, 1_000);

    return {
        baseUrl: `http://127.0.0.1:${address.port}/callbacks`,
        received: settledReceived.finally(() => clearTimeout(timeout)),
        close: () =>
            new Promise<void>((resolve, reject) =>
                server.close((error) => (error ? reject(error) : resolve())),
            ),
    };
}

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

test("successful invoke sends callback payload and forwards trace id", async () => {
    const callbackServer = await startCallbackCaptureServer();

    const runtime = createRuntime({
        port: 0,
        callbackUrl: callbackServer.baseUrl,
    });
    runtime.register("echo", async (_ctx, req) => ({ echoed: req.input }));
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-callback-ok",
                "x-trace-id": "trace-callback-ok",
            },
            body: JSON.stringify({ input: { ok: true } }),
        });
        assert.equal(response.status, 200);

        const received = await callbackServer.received;

        assert.equal(received.url, "/callbacks/exec-callback-ok:complete");
        assert.equal(received.traceId, "trace-callback-ok");
        assert.deepEqual(received.body, {
            success: true,
            output: { echoed: { ok: true } },
            error: null,
        });
    } finally {
        await runtime.stop();
        await callbackServer.close();
    }
});

test("handler failure still sends a failure callback", async () => {
    const callbackServer = await startCallbackCaptureServer();

    const runtime = createRuntime({
        port: 0,
        callbackUrl: callbackServer.baseUrl,
    });
    runtime.register("echo", async () => {
        throw new NanofaasError("BAD_INPUT", "Invalid payload");
    });
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-callback-fail",
                "x-trace-id": "trace-callback-fail",
            },
            body: JSON.stringify({ input: { bad: true } }),
        });
        assert.equal(response.status, 500);

        const received = await callbackServer.received;

        assert.equal(received.url, "/callbacks/exec-callback-fail:complete");
        assert.equal(received.traceId, "trace-callback-fail");
        assert.deepEqual(received.body, {
            success: false,
            output: null,
            error: {
                code: "BAD_INPUT",
                message: "Invalid payload",
            },
        });
    } finally {
        await runtime.stop();
        await callbackServer.close();
    }
});
