import assert from "node:assert/strict";
import { test } from "node:test";
import { getLogger } from "nanofaas-function-sdk";
import { handleWordStats } from "../src/handler.js";
function createContext() {
    return {
        executionId: "exec-test",
        logger: getLogger("word-stats.test"),
        signal: new AbortController().signal,
        isColdStart: false,
    };
}
test("handleWordStats returns an error when text is missing", async () => {
    const output = await handleWordStats(createContext(), {
        input: {},
    });
    assert.deepEqual(output, {
        error: "Field 'text' is required and must be non-empty",
    });
});
test("handleWordStats counts words and limits top words with topN", async () => {
    const output = await handleWordStats(createContext(), {
        input: {
            text: "hello world hello nano",
            topN: 2,
        },
    });
    assert.deepEqual(output, {
        wordCount: 4,
        uniqueWords: 3,
        topWords: [
            { word: "hello", count: 2 },
            { word: "nano", count: 1 },
        ],
        averageWordLength: 4.75,
    });
});
