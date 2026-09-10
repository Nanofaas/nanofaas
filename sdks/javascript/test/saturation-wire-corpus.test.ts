import assert from "node:assert/strict";
import { readFile } from "node:fs/promises";
import { resolve } from "node:path";
import { test } from "node:test";

type CorpusCase = {
    id: string;
    implementationOwner: string;
    stimulus: string;
    timeoutMs: number;
    expected: {
        httpStatus: number;
        errorCode: string;
        message: string;
        retryable: boolean;
        handlerStarted: boolean;
        callbackExpected: boolean;
        release: string[];
    };
};

test("consumes the shared runtime saturation wire contract", async () => {
    const corpus = JSON.parse(await readFile(
        resolve("..", "runtime-contract", "saturation-wire-corpus.json"),
        "utf8",
    )) as {
        version: number;
        scope: string;
        admissionPoint: string;
        releaseOn: string[];
        retryIdentity: Record<string, string>;
        runner: {
            requiredCaseIds: string[];
            requiredReleaseEvents: string[];
            maximumCaseTimeoutMs: number;
            minimumHttpStatus: number;
            maximumHttpStatus: number;
            noSecondResponseStatus: number;
        };
        cases: CorpusCase[];
    };

    assert.ok(corpus.version > 0 && corpus.scope && corpus.admissionPoint);
    assert.deepEqual(corpus.releaseOn, corpus.runner.requiredReleaseEvents);
    assert.ok(Object.values(corpus.retryIdentity).every((value) => value.length > 0));
    const ids = corpus.cases.map(({ id }) => id);
    assert.equal(new Set(ids).size, ids.length);
    assert.deepEqual(new Set(ids), new Set(corpus.runner.requiredCaseIds));
    for (const corpusCase of corpus.cases) {
        assert.ok(corpusCase.implementationOwner && corpusCase.stimulus);
        assert.ok(corpusCase.timeoutMs > 0 && corpusCase.timeoutMs <= corpus.runner.maximumCaseTimeoutMs);
        const expected = corpusCase.expected;
        assert.ok(expected.httpStatus === corpus.runner.noSecondResponseStatus ||
            expected.httpStatus >= corpus.runner.minimumHttpStatus &&
            expected.httpStatus <= corpus.runner.maximumHttpStatus);
        assert.match(expected.errorCode, /^[A-Z][A-Z0-9_]+$/);
        assert.ok(expected.message && expected.release.length > 0);
        assert.equal(typeof expected.retryable, "boolean");
        assert.equal(typeof expected.handlerStarted, "boolean");
        assert.equal(typeof expected.callbackExpected, "boolean");
    }
});
