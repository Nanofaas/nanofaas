import assert from "node:assert/strict";
import { test } from "node:test";
import { handle } from "../src/handler.js";

test("returns body and envelope header", async () => {
    const output = await handle({ logger: { debug() {}, info() {}, warn() {}, error() {} }, executionId: "x", isColdStart: false, signal: new AbortController().signal }, { input: { message: "body-sentinel", headers: { "x-e2e-token": "forged" } }, headers: { "x-e2e-token": "header-sentinel" } });
    assert.deepEqual(output, { body: "body-sentinel", header: "header-sentinel" });
});
