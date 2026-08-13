import assert from "node:assert/strict";
import { test } from "node:test";

import { getLogger, HandlerResponse, type HandlerContext, type JsonValue } from "nanofaas-function-sdk";

import { handleQRCode } from "../src/handler.js";

const context: HandlerContext = { executionId: "test", logger: getLogger("qr-code.test"), signal: new AbortController().signal, isColdStart: false };

test("returns a base64 PNG envelope", async () => {
    const result = await handleQRCode(context, { input: { text: "https://example.org/invite/abc", size: 256 } });
    assert.ok(result instanceof HandlerResponse);
    assert.equal(result.statusCode, 200);
    assert.equal(result.headers["Content-Type"], "image/png");
    assert.equal(result.encoding, "base64");
    assert.deepEqual(Buffer.from(result.output as string, "base64").subarray(0, 8), Buffer.from("89504e470d0a1a0a", "hex"));
});

test("rejects invalid input", async () => {
    for (const input of [{} as JsonValue, { text: "" } as JsonValue, { text: 42 } as JsonValue, { text: "x".repeat(1025) } as JsonValue, { text: "https://example.org", size: 127 } as JsonValue]) {
        const result = await handleQRCode(context, { input });
        assert.ok(result instanceof HandlerResponse);
        assert.equal(result.statusCode, 422);
    }
});
