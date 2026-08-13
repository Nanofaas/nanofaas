import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { getLogger, HandlerResponse, type HandlerContext, type JsonValue } from "nanofaas-function-sdk";

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
    const fixture = JSON.parse(await readFile("../../test-data/roman-numeral/correctness.json", "utf8"));
    for (const contractCase of fixture.cases) {
        const actual = await handleRomanNumeral(createContext(), { input: contractCase.input });
        if (contractCase.expectedStatusCode) {
            assert.ok(actual instanceof HandlerResponse, contractCase.name);
            assert.equal(actual.statusCode, contractCase.expectedStatusCode, contractCase.name);
            assert.deepEqual(actual.output, contractCase.expected, contractCase.name);
        } else {
            assert.deepEqual(actual, contractCase.expected, contractCase.name);
        }
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

    const cases: Array<[JsonValue, { error: string }]> = [
        [{}, { error: "missing required field: number" }],
        [{ number: "42" }, { error: "field 'number' must be an integer" }],
        [{ number: 0 }, { error: "number must be between 1 and 3999, got: 0" }],
        [null, { error: "Input must be a JSON object" }],
    ];
    for (const [input, output] of cases) {
        const actual = await handleRomanNumeral(context, { input });
        assert.ok(actual instanceof HandlerResponse);
        assert.equal(actual.statusCode, 422);
        assert.deepEqual(actual.output, output);
    }
});
