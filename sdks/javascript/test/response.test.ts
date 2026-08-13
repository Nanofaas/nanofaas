import assert from "node:assert/strict";
import { test } from "node:test";

import { HandlerResponse, filterAllowedHeaders, isStatusCodeValid } from "../src/response.js";

test("HandlerResponse defaults headers to an empty object and encoding to undefined", () => {
    const response = new HandlerResponse({ error: "not found" }, 404);
    assert.deepEqual(response.output, { error: "not found" });
    assert.equal(response.statusCode, 404);
    assert.deepEqual(response.headers, {});
    assert.equal(response.encoding, undefined);
});

test("HandlerResponse is detected nominally, not structurally", () => {
    const lookalike = { output: "x", statusCode: 201, headers: {} };
    assert.equal(lookalike instanceof HandlerResponse, false);
    assert.equal(new HandlerResponse("x", 201) instanceof HandlerResponse, true);
});

test("isStatusCodeValid accepts 200..599 and rejects everything else", () => {
    for (const code of [200, 422, 599]) {
        assert.equal(isStatusCodeValid(code), true, `expected ${code} to be valid`);
    }
    for (const code of [199, 600, 0, -1, 999]) {
        assert.equal(isStatusCodeValid(code), false, `expected ${code} to be invalid`);
    }
});

test("filterAllowedHeaders keeps allow-listed headers and drops everything else", () => {
    assert.deepEqual(
        filterAllowedHeaders({
            "Content-Type": "application/pdf",
            "X-Custom": "nope",
            "X-Execution-Id": "spoofed",
        }),
        { "Content-Type": "application/pdf" },
    );
});

test("filterAllowedHeaders dedupes colliding casings keeping the first occurrence", () => {
    assert.deepEqual(
        filterAllowedHeaders({
            "Content-Type": "application/pdf",
            "content-type": "text/plain",
        }),
        { "Content-Type": "application/pdf" },
    );
});

test("filterAllowedHeaders returns an empty object for undefined", () => {
    assert.deepEqual(filterAllowedHeaders(undefined), {});
});
