import assert from "node:assert/strict";
import { networkInterfaces } from "node:os";
import { test } from "node:test";

import { NanofaasError, createRuntime } from "../src/index.js";

async function withRuntime(
    setup: (runtime: ReturnType<typeof createRuntime>) => void,
): Promise<ReturnType<typeof createRuntime>> {
    const runtime = createRuntime({ port: 0 });
    setup(runtime);
    await runtime.start();
    return runtime;
}

function firstNonLoopbackIpv4(): string | undefined {
    for (const interfaces of Object.values(networkInterfaces())) {
        for (const entry of interfaces ?? []) {
            if (entry.family === "IPv4" && !entry.internal) {
                return entry.address;
            }
        }
    }
    return undefined;
}

test("health endpoint returns ok status", async () => {
    const runtime = await withRuntime((rt) => {
        rt.register("echo", async (_ctx, req) => req.input);
    });

    try {
        const response = await fetch(`${runtime.baseUrl}/health`);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), { status: "ok" });
    } finally {
        await runtime.stop();
    }
});

test("runtime accepts pod-ip style connections for Kubernetes probes", async (t) => {
    const host = firstNonLoopbackIpv4();
    if (!host) {
        t.skip("no non-loopback IPv4 address available");
        return;
    }

    const runtime = await withRuntime((rt) => {
        rt.register("echo", async (_ctx, req) => req.input);
    });

    try {
        const response = await fetch(`http://${host}:${runtime.port}/health`);
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), { status: "ok" });
    } finally {
        await runtime.stop();
    }
});

test("metrics endpoint exposes prometheus text", async () => {
    const runtime = await withRuntime((rt) => {
        rt.register("echo", async (_ctx, req) => req.input);
    });

    try {
        const response = await fetch(`${runtime.baseUrl}/metrics`);
        assert.equal(response.status, 200);
        assert.match(
            response.headers.get("content-type") ?? "",
            /text\/plain|application\/openmetrics-text/i,
        );
        assert.match(await response.text(), /runtime_invocations_total/);
    } finally {
        await runtime.stop();
    }
});

test("invoke requires execution id from header or environment", async () => {
    const runtime = await withRuntime((rt) => {
        rt.register("echo", async (_ctx, req) => req.input);
    });

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: { "content-type": "application/json" },
            body: JSON.stringify({ input: { hello: "world" } }),
        });
        assert.equal(response.status, 400);
        assert.deepEqual(await response.json(), {
            error: {
                code: "EXECUTION_ID_REQUIRED",
                message: "Execution ID required",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("invoke returns raw handler output on success", async () => {
    const runtime = await withRuntime((rt) => {
        rt.register("echo", async (ctx, req) => ({
            executionId: ctx.executionId,
            echoed: req.input,
        }));
    });

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-123",
            },
            body: JSON.stringify({ input: { hello: "world" } }),
        });
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), {
            executionId: "exec-123",
            echoed: { hello: "world" },
        });
    } finally {
        await runtime.stop();
    }
});

test("NanofaasError maps to 500 and preserves error code", async () => {
    const runtime = await withRuntime((rt) => {
        rt.register("echo", async () => {
            throw new NanofaasError("BAD_INPUT", "Invalid payload");
        });
    });

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-500",
            },
            body: JSON.stringify({ input: { bad: true } }),
        });
        assert.equal(response.status, 500);
        assert.deepEqual(await response.json(), {
            error: {
                code: "BAD_INPUT",
                message: "Invalid payload",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("generic error maps to 500 with UNHANDLED_ERROR", async () => {
    const runtime = await withRuntime((rt) => {
        rt.register("echo", async () => {
            throw new Error("boom");
        });
    });

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-501",
            },
            body: JSON.stringify({ input: { ok: false } }),
        });
        assert.equal(response.status, 500);
        assert.deepEqual(await response.json(), {
            error: {
                code: "UNHANDLED_ERROR",
                message: "boom",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("timeout maps to 504 with HANDLER_TIMEOUT", async () => {
    const runtime = createRuntime({
        port: 0,
        handlerTimeoutMs: 10,
    });
    runtime.register("echo", async ({ signal }) => {
        await new Promise((resolve, reject) => {
            const timer = setTimeout(resolve, 50);
            signal.addEventListener("abort", () => {
                clearTimeout(timer);
                reject(new Error("aborted"));
            });
        });
        return { ok: true };
    });
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-timeout",
            },
            body: JSON.stringify({ input: { slow: true } }),
        });
        assert.equal(response.status, 504);
        assert.deepEqual(await response.json(), {
            error: {
                code: "HANDLER_TIMEOUT",
                message: "Handler execution timed out",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("startup fails when no handlers are registered", async () => {
    const runtime = createRuntime({ port: 0 });
    await assert.rejects(
        runtime.start(),
        /No handlers registered/,
    );
});

test("startup fails when multiple handlers exist without FUNCTION_HANDLER", async () => {
    const runtime = createRuntime({ port: 0 });
    runtime.register("one", async () => ({ one: true }));
    runtime.register("two", async () => ({ two: true }));

    await assert.rejects(
        runtime.start(),
        /multiple handlers/i,
    );
});

test("FUNCTION_HANDLER selects a specific handler when multiple are registered", async () => {
    const original = process.env.FUNCTION_HANDLER;
    process.env.FUNCTION_HANDLER = "two";

    const runtime = createRuntime({ port: 0 });
    runtime.register("one", async () => ({ selected: "one" }));
    runtime.register("two", async () => ({ selected: "two" }));
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-handler",
            },
            body: JSON.stringify({ input: null }),
        });
        assert.equal(response.status, 200);
        assert.deepEqual(await response.json(), { selected: "two" });
    } finally {
        await runtime.stop();
        if (original === undefined) {
            delete process.env.FUNCTION_HANDLER;
        } else {
            process.env.FUNCTION_HANDLER = original;
        }
    }
});
