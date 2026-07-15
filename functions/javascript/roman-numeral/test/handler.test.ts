import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { getLogger, type HandlerContext } from "nanofaas-function-sdk";

import { handleRomanNumeral } from "../src/handler.js";

function createContext(): HandlerContext {
    return {
        executionId: "exec-test",
        logger: getLogger("roman-numeral.test"),
        signal: new AbortController().signal,
        isColdStart: false,
    };
}

test("handleRomanNumeral satisfies the shared contract", async () => {
    const fixture = JSON.parse(await readFile("../../contract-tests/roman-numeral.json", "utf8"));
    for (const contractCase of fixture.cases) {
        assert.deepEqual(
            await handleRomanNumeral(createContext(), { input: contractCase.input }),
            contractCase.expected,
            contractCase.name,
        );
    }
});

test("handleRomanNumeral converts canonical values", async () => {
    const cases = new Map<number, string>([
        [1, "I"],
        [4, "IV"],
        [9, "IX"],
        [42, "XLII"],
        [1994, "MCMXCIV"],
        [3999, "MMMCMXCIX"],
    ]);

    for (const [number, roman] of cases) {
        assert.deepEqual(
            await handleRomanNumeral(createContext(), { input: { number } }),
            { roman },
        );
    }
});

test("handleRomanNumeral validates input", async () => {
    const context = createContext();

    assert.deepEqual(await handleRomanNumeral(context, { input: {} }), {
        error: "missing required field: number",
    });
    assert.deepEqual(await handleRomanNumeral(context, { input: { number: "42" } }), {
        error: "field 'number' must be an integer",
    });
    assert.deepEqual(await handleRomanNumeral(context, { input: { number: 0 } }), {
        error: "number must be between 1 and 3999, got: 0",
    });
    assert.deepEqual(await handleRomanNumeral(context, { input: null }), {
        error: "Input must be a JSON object",
    });
});
