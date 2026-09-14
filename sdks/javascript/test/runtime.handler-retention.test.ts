import assert from "node:assert/strict";
import { getEventListeners } from "node:events";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";

for (const fails of [false, true]) {
    test(`settled handler releases its abort listener (${fails ? "failure" : "success"})`, async () => {
        const signals: AbortSignal[] = [];
        const runtime = createRuntime({ port: 0 });
        runtime.register("echo", async (ctx, request) => {
            signals.push(ctx.signal);
            if (fails) throw new Error("handler failure");
            return request.input;
        });
        await runtime.start();
        try {
            for (let index = 0; index < 8; index += 1) {
                const response = await fetch(`${runtime.baseUrl}/invoke`, {
                    method: "POST",
                    headers: {
                        "content-type": "application/json",
                        "x-execution-id": `retention-${index}`,
                    },
                    body: JSON.stringify({ input: { text: "retained payload" } }),
                });
                await response.text();
                assert.equal(response.status, fails ? 500 : 200);
            }
            assert.equal(signals.length, 8);
            for (const signal of signals) {
                assert.equal(signal.aborted, false);
                assert.equal(getEventListeners(signal, "abort").length, 0,
                    "completed work must not remain rooted through a composite signal's listener");
            }
        } finally {
            await runtime.stop();
        }
    });
}
