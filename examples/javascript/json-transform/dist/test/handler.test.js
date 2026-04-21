import assert from "node:assert/strict";
import { test } from "node:test";
import { getLogger } from "nanofaas-function-sdk";
import { handleJsonTransform } from "../src/handler.js";
function createContext() {
    return {
        executionId: "exec-test",
        logger: getLogger("json-transform.test"),
        signal: new AbortController().signal,
        isColdStart: false,
    };
}
test("handleJsonTransform renames configured fields", async () => {
    const output = await handleJsonTransform(createContext(), {
        input: {
            data: {
                first_name: "Ada",
                last_name: "Lovelace",
            },
            fieldMap: {
                first_name: "firstName",
                last_name: "lastName",
            },
        },
    });
    assert.deepEqual(output, {
        firstName: "Ada",
        lastName: "Lovelace",
    });
});
test("handleJsonTransform preserves unmapped fields", async () => {
    const output = await handleJsonTransform(createContext(), {
        input: {
            data: {
                first_name: "Ada",
                city: "London",
            },
            fieldMap: {
                first_name: "firstName",
            },
        },
    });
    assert.deepEqual(output, {
        firstName: "Ada",
        city: "London",
    });
});
