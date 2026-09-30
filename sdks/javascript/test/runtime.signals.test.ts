import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { once } from "node:events";
import { createInterface } from "node:readline";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";

const sdkEntry = new URL("../src/index.js", import.meta.url).href;

/**
 * Starts a runtime in a child process and resolves once it listens. As PID 1 in a container,
 * Node ignores SIGTERM unless a handler exists, so without one `docker stop` waits for its
 * timeout and SIGKILLs the function; a plain child process shows the missing handler as a
 * death by signal instead of a clean exit.
 */
async function startChildRuntime(): Promise<ReturnType<typeof spawn>> {
    const child = spawn(process.execPath, ["--input-type=module", "-e", `
        const { createRuntime } = await import(${JSON.stringify(sdkEntry)});
        const runtime = createRuntime({ port: 0, shutdownTimeoutMs: 2000 });
        runtime.register("echo", async (_ctx, req) => req.input);
        await runtime.start();
        console.log("listening " + runtime.port);
    `], { stdio: ["ignore", "pipe", "inherit"] });
    for await (const line of createInterface({ input: child.stdout! })) {
        if (line.startsWith("listening ")) return child;
    }
    throw new Error("the child runtime never started");
}

for (const signal of ["SIGTERM", "SIGINT"] as const) {
    test(`${signal} stops the runtime and exits cleanly`, async () => {
        const child = await startChildRuntime();
        const exited = once(child, "exit");
        child.kill(signal);
        const timeout = new Promise((_, reject) => {
            setTimeout(() => reject(new Error(`the runtime ignored ${signal}`)), 5000).unref();
        });
        const [code, killedBy] = (await Promise.race([exited, timeout])) as [number | null, string | null];
        assert.equal(killedBy, null, `the process died from ${killedBy} instead of stopping`);
        assert.equal(code, 0);
    });
}

test("stop() removes the signal handlers that start() installed", async () => {
    const before = process.listenerCount("SIGTERM");
    const runtime = createRuntime({ port: 0 });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    let installed: number;
    try {
        installed = process.listenerCount("SIGTERM");
        assert.equal(process.listenerCount("SIGINT") > 0, true);
    } finally {
        await runtime.stop();
    }
    assert.equal(installed, before + 1);
    assert.equal(process.listenerCount("SIGTERM"), before);
});

test("handleSignals: false leaves the process signals to the embedding application", async () => {
    const before = process.listenerCount("SIGTERM");
    const runtime = createRuntime({ port: 0, handleSignals: false });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    try {
        assert.equal(process.listenerCount("SIGTERM"), before);
    } finally {
        await runtime.stop();
    }
});
