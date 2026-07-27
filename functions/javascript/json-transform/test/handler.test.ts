import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { test } from "node:test";

import { getLogger, type HandlerContext } from "nanofaas-function-sdk";

import { handleJsonTransform } from "../src/handler.js";

function createContext(): HandlerContext {
    return {
        executionId: "exec-test",
        logger: getLogger("json-transform.test"),
        signal: new AbortController().signal,
        isColdStart: false,
    };
}

test("handleJsonTransform satisfies the shared contract", async () => {
    const fixture = JSON.parse(await readFile("../../test-data/json-transform/correctness.json", "utf8"));
    for (const contractCase of fixture.cases) {
        assert.deepEqual(
            await handleJsonTransform(createContext(), { input: contractCase.input }),
            contractCase.expected,
            contractCase.name,
        );
    }
});

test("handleJsonTransform aggregates the shared sample", async () => {
    // The scenario sample is generated from this corpus' first case, so read the
    // corpus directly rather than the derived copy the ops tooling carries.
    const corpus = JSON.parse(await readFile(
        "../../test-data/json-transform/performance-small.json",
        "utf8",
    ));
    const input = corpus.cases[0].input;
    const output = await handleJsonTransform(createContext(), {
        input,
    });

    assert.deepEqual(output, {
        groupBy: "dept",
        operation: "count",
        groups: {
            eng: 3,
            sales: 3,
            ops: 2,
            finance: 2,
        },
    });
});

test("handleJsonTransform supports count, sum, min, and max", async () => {
    const data = [
        { dept: "eng", salary: 90 },
        { dept: "eng", salary: 110 },
        { dept: "ops", salary: 70 },
    ];

    for (const [operation, groups] of [
        ["count", { eng: 2, ops: 1 }],
        ["sum", { eng: 200, ops: 70 }],
        ["min", { eng: 90, ops: 70 }],
        ["max", { eng: 110, ops: 70 }],
    ] as const) {
    const output = await handleJsonTransform(createContext(), {
        input: {
                data,
                groupBy: "dept",
                operation,
                valueField: "salary",
        },
    });

    assert.deepEqual(output, {
            groupBy: "dept",
            operation,
            groups,
    });
    }
});
