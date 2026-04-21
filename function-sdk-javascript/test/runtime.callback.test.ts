import assert from "node:assert/strict";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";

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
