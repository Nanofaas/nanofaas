import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { createLogger } from "../src/logger.js";
import { runWithContext, getExecutionId } from "../src/context.js";

test("logger consumes shared corpus without overwriting runtime fields", () => {
    process.env.NANOFAAS_LOG_FORMAT = "json";
    process.env.NANOFAAS_LOG_SERVICE = "function-runtime";
    const lines: string[] = [];
    const originalLog = console.log, originalError = console.error;
    console.log = console.error = (line: unknown) => { lines.push(String(line)); };
    try {
        const corpus = JSON.parse(readFileSync(new URL("../../../runtime-contract/logging-corpus.json", import.meta.url), "utf8"));
        for (const item of corpus.cases) {
            const input = item.input;
            const log = createLogger("contract");
            const emit = () => log[input.level.toLowerCase() as "info" | "warn" | "error"](input.message, input.attributes);
            if (input.executionId) runWithContext({ executionId: input.executionId, traceId: input.traceId }, emit); else emit();
            const entry = JSON.parse(lines.pop()!);
            for (const [key, value] of Object.entries(item.expected)) {
                if (key !== "timestamp" && key !== "exception") assert.deepEqual(entry[key], value);
            }
            assert.ok(entry.timestamp.endsWith("Z"));
            if (!input.executionId) assert.equal(entry.executionId, undefined);
        }
        runWithContext({ executionId: "real" }, () => logReserved());
        function logReserved() { createLogger("contract").info("reserved", { executionId: "fake", level: "fake", service: "fake" }); }
        const entry = JSON.parse(lines.pop()!);
        assert.equal(entry.executionId, "real"); assert.equal(entry.level, "INFO"); assert.equal(entry.attributes.executionId, "fake");
    } finally { console.log = originalLog; console.error = originalError; delete process.env.NANOFAAS_LOG_FORMAT; delete process.env.NANOFAAS_LOG_SERVICE; }
});

test("concurrent async contexts survive failures and restore their caller", async () => {
    await Promise.all(["one", "two"].map(id => runWithContext({ executionId: id }, async () => {
        await Promise.resolve(); assert.equal(getExecutionId(), id);
        assert.throws(() => runWithContext({ executionId: "nested" }, () => { throw new Error("failure"); }));
        assert.equal(getExecutionId(), id);
    })));
    assert.equal(getExecutionId(), undefined);
});
