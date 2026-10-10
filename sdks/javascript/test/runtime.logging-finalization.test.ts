import assert from "node:assert/strict";
import {test} from "node:test";
import {createRuntime, HandlerResponse} from "../src/index.js";
import {createLogger} from "../src/logger.js";

test("concurrent response warnings retain canonical IDs and restore the caller", async () => {
    const previous = console.log;
    const lines: string[] = [];
    process.env.NANOFAAS_LOG_FORMAT = "json";
    console.log = line => { lines.push(String(line)); };
    const runtime = createRuntime({port: 0});
    runtime.register("warn", async (_ctx, req) => {
        await new Promise(resolve => setTimeout(resolve, 5));
        return req.input === "invalid" ? new HandlerResponse(null, 999) : new HandlerResponse(null, 200, {"x-private": "drop"});
    });
    try {
        await runtime.start();
        await Promise.all(["invalid", "dropped"].map(async id => {
            const response = await fetch(`${runtime.baseUrl}/invoke`, {method: "POST", headers: {
                "content-type": "application/json", "x-execution-id": id, "x-trace-id": "trace-" + id,
            }, body: JSON.stringify({input: id})});
            assert.equal(response.status, id === "invalid" ? 500 : 200);
        }));
        const warnings = lines.map(line => JSON.parse(line)).filter(event => event.level === "WARN");
        assert.equal(warnings.length, 2);
        for (const event of warnings) {
            const id = event.message.includes("invalid") ? "invalid" : "dropped";
            assert.equal(event.executionId, id);
            assert.equal(event.traceId, "trace-" + id);
        }
        createLogger().info("outside");
        assert.equal(JSON.parse(lines.at(-1)!).executionId, undefined);
        assert.equal(JSON.parse(lines.at(-1)!).traceId, undefined);
    } finally {
        await runtime.stop(); console.log = previous; delete process.env.NANOFAAS_LOG_FORMAT;
    }
});
