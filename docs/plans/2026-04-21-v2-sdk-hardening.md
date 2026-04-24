# V2 JavaScript SDK Hardening Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Harden the JavaScript SDK so malformed requests, timeout/callback races, observability output, and example container builds behave predictably and stay protected by automated regression checks.

**Architecture:** Keep `function-sdk-javascript/` as a small TypeScript runtime built around `createRuntime()`, `AsyncLocalStorage` request context, JSON-over-HTTP endpoints, and Prometheus metrics from `prom-client`. Do not reopen npm publishing or control-plane catalog integration here; those already have dedicated V2 plans. This plan focuses on runtime contract correctness, deterministic failure semantics, and repository guardrails that keep the SDK, example apps, and docs aligned.

**Tech Stack:** Node.js 20, TypeScript, `node:test`, `prom-client`, Python 3.11 + `pytest` repo guards, Dockerfiles for `examples/javascript/`, GitNexus impact analysis.

---

## Scope Guardrails

- Do **not** reopen `docs/plans/2026-04-21-v2-packaging-and-release.md`. Packaging, release-manager wiring, and npm publication are already covered there and are already green in the repo.
- Do **not** reopen `docs/plans/2026-04-21-v2-controlplane-e2e-integration.md`. Control-plane presets, scenario fixtures, and VM-backed JavaScript demo integration belong there.
- Keep the public runtime model small: `createRuntime()`, handler registration, `/invoke`, `/health`, `/metrics`, and the existing request-scoped context helpers.
- Do **not** add CommonJS, browser support, worker threads, or a second runtime abstraction in this plan.
- Prefer additive contract hardening and tests over API redesign. If a behavior can be clarified in docs and locked with tests, do that before inventing a new public option.

## Command Working Directories

- Run `npm` commands from `function-sdk-javascript/` unless the task explicitly says otherwise.
- Run `uv` and repo-level pytest commands from the repository root.

## Commit Safety Rule

Before **every** commit in Tasks 1 through 5:

1. Stage only the files listed in the current task.
2. Run:

```text
gitnexus_detect_changes(scope="staged")
```

3. Confirm the staged report only mentions the intended files and JavaScript SDK flows for the current task.
4. Commit only after the staged-scope report matches the task boundary.

## Verified Current State (checked 2026-04-22)

- `function-sdk-javascript/package.json` is already publishable: `private: false`, repo version `0.16.1`, `dist/`-only pack surface, `prepack`, and `publishConfig.access`.
- `function-sdk-javascript/README.md` already exists and documents install, runtime endpoints, and basic release verification commands.
- `env npm_config_cache=/tmp/codex-npm-cache npm test` passes in `function-sdk-javascript/` with 12 passing tests.
- `env npm_config_cache=/tmp/codex-npm-cache npm pack --dry-run` succeeds and the tarball is limited to `README.md`, `dist/*`, and `package.json`.
- `env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest scripts/tests/test_javascript_sdk_packaging.py scripts/tests/test_javascript_example_dockerfiles.py scripts/tests/test_javascript_packaging_docs.py scripts/tests/test_release_manager_javascript_sdk.py -q` passes with 7 tests.
- `tools/fn-init/tests/test_generator.py` already covers JavaScript scaffold generation inside and outside the monorepo, including local file dependency vs published semver dependency.
- `examples/javascript/word-stats/Dockerfile` and `examples/javascript/json-transform/Dockerfile` already build the SDK before the app, but they still use `npm install`, not `npm ci`, so reproducibility can be improved.
- By code inspection, malformed JSON is still a real contract gap: `function-sdk-javascript/src/runtime.ts` maps `INVALID_JSON` to HTTP 400, but `function-sdk-javascript/src/errors.ts` cannot currently produce `INVALID_JSON`, so `JSON.parse()` failures fall through to `UNHANDLED_ERROR`.
- Callback coverage is still shallow: current tests only prove that callback failure does not block a successful invoke. Success payload shape, error-path callbacks, trace header propagation, and timeout-race determinism are not locked yet.
- The Python SDK already has a concurrency cold-start test. The JavaScript SDK does not.

## GitNexus Risk Notes (checked 2026-04-22)

- `gitnexus_impact(target="createRuntime", direction="upstream")` returned `MEDIUM`.
  Direct d=1 callers are the JavaScript runtime tests and the two JavaScript examples.
- `gitnexus_impact(target="toErrorInfo", direction="upstream")` returned `LOW`.
  Direct d=1 callers are `sendCallback` and `handleInvoke` in `function-sdk-javascript/src/runtime.ts`.
- `gitnexus_impact(target="createMetrics", direction="upstream")` returned `LOW`.
  It is only reached through `createRuntime`.
- `gitnexus_impact(target="generate_function", direction="upstream")` returned `HIGH`.
  Any `fn-init` template change must stay isolated and must be paired with targeted `tools/fn-init/tests/test_generator.py` coverage.
- `gitnexus_impact(target="createLogger", direction="upstream")` returned `HIGH`, and the result set is noisy because the symbol name is generic across the repo.
  Treat `function-sdk-javascript/src/logger.ts` as a narrow local change surface. Do not rename or broaden that API in this plan.

### Task 0: Preflight and boundary lock

**Files:**
- Inspect: `function-sdk-javascript/src/runtime.ts`
- Inspect: `function-sdk-javascript/src/errors.ts`
- Inspect: `function-sdk-javascript/src/logger.ts`
- Inspect: `function-sdk-javascript/src/metrics.ts`
- Inspect: `function-sdk-javascript/test/runtime.contract.test.ts`
- Inspect: `function-sdk-javascript/test/runtime.callback.test.ts`
- Inspect: `function-sdk-javascript/test/runtime.context.test.ts`
- Inspect: `examples/javascript/word-stats/Dockerfile`
- Inspect: `examples/javascript/json-transform/Dockerfile`
- Inspect: `docs/plans/2026-04-21-v2-packaging-and-release.md`
- Inspect: `docs/plans/2026-04-21-v2-controlplane-e2e-integration.md`

**Step 1: Re-run the impact checks before touching shared symbols**

Run:

```text
gitnexus_impact(target="createRuntime", direction="upstream")
gitnexus_impact(target="toErrorInfo", direction="upstream")
gitnexus_impact(target="createMetrics", direction="upstream")
gitnexus_impact(target="generate_function", direction="upstream")
gitnexus_impact(target="createLogger", direction="upstream")
```

Expected:
- `createRuntime` stays `MEDIUM`
- `toErrorInfo` and `createMetrics` stay `LOW`
- `generate_function` stays `HIGH`
- `createLogger` stays `HIGH` and noisy, so logger work must remain tightly scoped

**Step 2: Write down the hard boundary in scratch notes**

Use this exact note before editing:

```text
This plan hardens the JavaScript runtime contract and its regression coverage.
Packaging/release automation stays in docs/plans/2026-04-21-v2-packaging-and-release.md.
Control-plane catalog and VM/E2E integration stay in docs/plans/2026-04-21-v2-controlplane-e2e-integration.md.
`fn-init` changes are allowed only if a JavaScript scaffold contract really changes.
```

**Step 3: No code change in this task**

Move straight to Task 1 after the boundary is explicit.

### Task 1: Make malformed request handling explicit and deterministic

**Files:**
- Create: `function-sdk-javascript/test/runtime.request-validation.test.ts`
- Modify: `function-sdk-javascript/src/errors.ts`
- Modify: `function-sdk-javascript/src/runtime.ts`
- Modify: `function-sdk-javascript/README.md`

**Step 1: Write the failing request-validation tests**

Create `function-sdk-javascript/test/runtime.request-validation.test.ts`:

```ts
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";

import { createRuntime } from "../src/index.js";

async function withRuntime() {
    const runtime = createRuntime({ port: 0 });
    runtime.register("echo", async (_ctx, req) => req.input);
    await runtime.start();
    return runtime;
}

test("invalid json returns 400 with INVALID_JSON", async () => {
    const runtime = await withRuntime();
    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-invalid-json",
            },
            body: "{",
        });

        assert.equal(response.status, 400);
        assert.deepEqual(await response.json(), {
            error: {
                code: "INVALID_JSON",
                message: "Request body must be valid JSON",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("metadata must be a string map", async () => {
    const runtime = await withRuntime();
    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-invalid-metadata",
            },
            body: JSON.stringify({
                input: { ok: true },
                metadata: { attempt: 1 },
            }),
        });

        assert.equal(response.status, 400);
        assert.deepEqual(await response.json(), {
            error: {
                code: "INVALID_REQUEST",
                message: "Invocation metadata must be a string map",
            },
        });
    } finally {
        await runtime.stop();
    }
});

test("invalid metadata still sends failure callback when execution id is known", async () => {
    const received = new Promise<unknown>((resolve, reject) => {
        const timeout = setTimeout(() => reject(new Error("callback not received")), 1_000);

        void (async () => {
            const callbackServer = createServer(async (req, res) => {
                const chunks: Buffer[] = [];
                for await (const chunk of req) {
                    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
                }
                clearTimeout(timeout);
                resolve(JSON.parse(Buffer.concat(chunks).toString("utf8")));
                res.statusCode = 200;
                res.end("ok");
                callbackServer.close();
            });

            await new Promise<void>((innerResolve) => callbackServer.listen(0, "127.0.0.1", innerResolve));
            const address = callbackServer.address();
            if (!address || typeof address === "string") {
                reject(new Error("callback server did not bind"));
                return;
            }

            const runtime = createRuntime({
                port: 0,
                callbackUrl: `http://127.0.0.1:${address.port}/callbacks`,
            });
            runtime.register("echo", async (_ctx, req) => req.input);
            await runtime.start();

            try {
                const response = await fetch(`${runtime.baseUrl}/invoke`, {
                    method: "POST",
                    headers: {
                        "content-type": "application/json",
                        "x-execution-id": "exec-invalid-metadata-callback",
                    },
                    body: JSON.stringify({
                        input: { ok: true },
                        metadata: { attempt: 1 },
                    }),
                });

                assert.equal(response.status, 400);
            } finally {
                await runtime.stop();
            }
        })().catch(reject);
    });

    assert.deepEqual(await received, {
        success: false,
        output: null,
        error: {
            code: "INVALID_REQUEST",
            message: "Invocation metadata must be a string map",
        },
    });
});
```

**Step 2: Run the tests and verify they fail**

Run:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
```

Expected:
- FAIL because invalid JSON still falls through to `UNHANDLED_ERROR`
- FAIL because non-string metadata is currently ignored instead of rejected

**Step 3: Add the minimal internal error types**

Update `function-sdk-javascript/src/errors.ts` so it can represent runtime-owned request validation failures:

```ts
export class InvalidJsonError extends Error {
    constructor(message = "Request body must be valid JSON") {
        super(message);
        this.name = "InvalidJsonError";
    }
}

export class InvalidRequestError extends Error {
    constructor(message: string) {
        super(message);
        this.name = "InvalidRequestError";
    }
}
```

Extend `toErrorInfo()` with:

```ts
if (error instanceof InvalidJsonError) {
    return {
        code: "INVALID_JSON",
        message: error.message,
    };
}
if (error instanceof InvalidRequestError) {
    return {
        code: "INVALID_REQUEST",
        message: error.message,
    };
}
```

**Step 4: Make JSON parsing and metadata normalization reject bad input**

Update `function-sdk-javascript/src/runtime.ts`:

```ts
async function readJson(req: IncomingMessage): Promise<unknown> {
    const chunks: Buffer[] = [];
    for await (const chunk of req) {
        chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
    }
    if (chunks.length === 0) {
        return {};
    }
    try {
        return JSON.parse(Buffer.concat(chunks).toString("utf8"));
    } catch {
        throw new InvalidJsonError();
    }
}

function normalizeInvocationRequest(payload: unknown): InvocationRequest {
    if (isJsonObject(payload)) {
        if ("metadata" in payload && payload.metadata !== undefined && !isMetadataRecord(payload.metadata)) {
            throw new InvalidRequestError("Invocation metadata must be a string map");
        }
        const input = "input" in payload ? (payload.input as JsonValue) : (payload as JsonValue);
        const metadata = isMetadataRecord(payload.metadata) ? payload.metadata : undefined;
        return metadata === undefined ? { input } : { input, metadata };
    }
    return { input: payload as JsonValue };
}
```

Keep the existing status mapping in `handleInvoke()`:

```ts
const status =
    info.code === "HANDLER_TIMEOUT" ? 504 :
    info.code === "INVALID_JSON" || info.code === "INVALID_REQUEST" ? 400 :
    500;
```

**Step 5: Document the contract in the README**

Add a short `Error contract` section to `function-sdk-javascript/README.md` that explicitly lists:

- `EXECUTION_ID_REQUIRED` -> HTTP 400
- `INVALID_JSON` -> HTTP 400
- `INVALID_REQUEST` -> HTTP 400
- `HANDLER_TIMEOUT` -> HTTP 504
- `UNHANDLED_ERROR` -> HTTP 500

Add one short note under the same section:

- `EXECUTION_ID_REQUIRED` does **not** emit a callback because the runtime cannot identify the execution
- `INVALID_JSON` and `INVALID_REQUEST` **do** emit a failure callback when `X-Execution-Id` and callback resolution are available

**Step 6: Re-run the SDK test suite**

Run:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
```

Expected:
- PASS with the new request-validation test file included

**Step 7: Commit**

Run:

```text
gitnexus_detect_changes(scope="staged")
```

Then:

```bash
git add \
  function-sdk-javascript/test/runtime.request-validation.test.ts \
  function-sdk-javascript/src/errors.ts \
  function-sdk-javascript/src/runtime.ts \
  function-sdk-javascript/README.md
git commit -m "feat(js-sdk): harden malformed request handling"
```

### Task 2: Make timeout, callback, and cold-start behavior deterministic

**Files:**
- Modify: `function-sdk-javascript/src/runtime.ts`
- Modify: `function-sdk-javascript/test/runtime.contract.test.ts`
- Modify: `function-sdk-javascript/test/runtime.callback.test.ts`

**Step 1: Write the failing timeout-race and callback tests**

Add these tests first.

In `function-sdk-javascript/test/runtime.contract.test.ts` add:

```ts
test("exactly one concurrent request reports cold start", async () => {
    const runtime = await withRuntime((rt) => {
        rt.register("echo", async (_ctx, req) => req.input);
    });

    try {
        const responses = await Promise.all(
            Array.from({ length: 8 }, (_, index) =>
                fetch(`${runtime.baseUrl}/invoke`, {
                    method: "POST",
                    headers: {
                        "content-type": "application/json",
                        "x-execution-id": `exec-cold-${index}`,
                    },
                    body: JSON.stringify({ input: index }),
                }),
            ),
        );

        const coldStarts = responses.filter(
            (response) => response.headers.get("x-cold-start") === "true",
        );
        assert.equal(coldStarts.length, 1);
    } finally {
        await runtime.stop();
    }
});

test("timeout stays HANDLER_TIMEOUT even if the handler rejects on abort", async () => {
    const runtime = createRuntime({ port: 0, handlerTimeoutMs: 10 });
    runtime.register("echo", async ({ signal }) => {
        await new Promise((_resolve, reject) => {
            signal.addEventListener("abort", () => reject(new Error("aborted-by-signal")), { once: true });
        });
        return { ok: true };
    });
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-timeout-race",
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
```

In `function-sdk-javascript/test/runtime.callback.test.ts`, update the imports to:

```ts
import assert from "node:assert/strict";
import { createServer } from "node:http";
import { test } from "node:test";

import { NanofaasError, createRuntime } from "../src/index.js";
```

Then add a reusable helper instead of fixed sleeps:

```ts
async function startCallbackCaptureServer() {
    let resolveReceived!: (value: {
        url: string;
        traceId: string | undefined;
        body: unknown;
    }) => void;
    let rejectReceived!: (reason?: unknown) => void;
    const settledReceived = new Promise<{
        url: string;
        traceId: string | undefined;
        body: unknown;
    }>((resolve, reject) => {
        resolveReceived = resolve;
        rejectReceived = reject;
    });

    const server = createServer(async (req, res) => {
        const chunks: Buffer[] = [];
        for await (const chunk of req) {
            chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
        }
        res.statusCode = 200;
        res.end("ok");
        resolveReceived({
            url: req.url ?? "",
            traceId: req.headers["x-trace-id"] as string | undefined,
            body: JSON.parse(Buffer.concat(chunks).toString("utf8")),
        });
    });

    await new Promise<void>((resolve, reject) => {
        server.once("error", reject);
        server.listen(0, "127.0.0.1", () => {
            server.off("error", reject);
            resolve();
        });
    });

    const address = server.address();
    if (!address || typeof address === "string") {
        throw new Error("callback server did not bind");
    }

    const timeout = setTimeout(() => {
        rejectReceived(new Error("callback not received"));
    }, 1_000);

    return {
        baseUrl: `http://127.0.0.1:${address.port}/callbacks`,
        received: settledReceived.finally(() => clearTimeout(timeout)),
        close: () =>
            new Promise<void>((resolve, reject) =>
                server.close((error) => (error ? reject(error) : resolve())),
            ),
    };
}
```

Then add:

```ts
test("successful invoke sends callback payload and forwards trace id", async () => {
    const callbackServer = await startCallbackCaptureServer();

    const runtime = createRuntime({
        port: 0,
        callbackUrl: callbackServer.baseUrl,
    });
    runtime.register("echo", async (_ctx, req) => ({ echoed: req.input }));
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-callback-ok",
                "x-trace-id": "trace-callback-ok",
            },
            body: JSON.stringify({ input: { ok: true } }),
        });
        assert.equal(response.status, 200);

        const received = await callbackServer.received;

        assert.equal(received.url, "/callbacks/exec-callback-ok:complete");
        assert.equal(received.traceId, "trace-callback-ok");
        assert.deepEqual(received.body, {
            success: true,
            output: { echoed: { ok: true } },
            error: null,
        });
    } finally {
        await runtime.stop();
        await callbackServer.close();
    }
});
```

Add a second callback test for failures:

```ts
test("handler failure still sends a failure callback", async () => {
    const callbackServer = await startCallbackCaptureServer();

    const runtime = createRuntime({
        port: 0,
        callbackUrl: callbackServer.baseUrl,
    });
    runtime.register("echo", async () => {
        throw new NanofaasError("BAD_INPUT", "Invalid payload");
    });
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-callback-fail",
                "x-trace-id": "trace-callback-fail",
            },
            body: JSON.stringify({ input: { bad: true } }),
        });
        assert.equal(response.status, 500);

        const received = await callbackServer.received;

        assert.equal(received.url, "/callbacks/exec-callback-fail:complete");
        assert.equal(received.traceId, "trace-callback-fail");
        assert.deepEqual(received.body, {
            success: false,
            output: null,
            error: {
                code: "BAD_INPUT",
                message: "Invalid payload",
            },
        });
    } finally {
        await runtime.stop();
        await callbackServer.close();
    }
});
```

**Step 2: Run the tests and verify what fails**

Run:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
```

Expected:
- the timeout-race test is likely to fail or flap until timeout classification becomes deterministic
- the new callback tests may already pass; if they do, keep them and avoid unnecessary callback refactors
- the cold-start concurrency test should pass once locked in; if it fails, fix the first-invocation handling before touching anything else

**Step 3: Make timeout classification deterministic**

If the timeout-race test fails, narrow the fix to `invokeHandler()` in `function-sdk-javascript/src/runtime.ts`:

```ts
try {
    const output = await Promise.race([
        runWithContext(
            {
                executionId: ctx.executionId,
                ...(ctx.traceId === undefined ? {} : { traceId: ctx.traceId }),
            },
            () => Promise.resolve(handler(effectiveContext, request)),
        ),
        new Promise<JsonValue>((_, reject) => {
            mergedSignal.addEventListener("abort", () => reject(new TimeoutError()), { once: true });
        }),
    ]);
    return output;
} catch (error) {
    if (timeoutController.signal.aborted) {
        throw new TimeoutError();
    }
    throw error;
} finally {
    clearTimeout(timer);
}
```

Do **not** change the callback API or add retries in this task.

**Step 4: Re-run the SDK test suite**

Run:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
```

Expected:
- PASS with timeout, callback, and cold-start coverage all green

**Step 5: Commit**

Run:

```text
gitnexus_detect_changes(scope="staged")
```

Then:

```bash
git add \
  function-sdk-javascript/src/runtime.ts \
  function-sdk-javascript/test/runtime.contract.test.ts \
  function-sdk-javascript/test/runtime.callback.test.ts
git commit -m "test(js-sdk): lock timeout and callback behavior"
```

### Task 3: Lock the observability contract without redesigning the logger

**Files:**
- Create: `function-sdk-javascript/test/runtime.observability.test.ts`
- Modify: `function-sdk-javascript/src/logger.ts` only if the new tests expose a real contract gap
- Modify: `function-sdk-javascript/src/metrics.ts` only if the exposed metric names or counters are inconsistent
- Modify: `function-sdk-javascript/README.md`

**Step 1: Write the failing observability tests**

Create `function-sdk-javascript/test/runtime.observability.test.ts`:

```ts
import assert from "node:assert/strict";
import { test } from "node:test";

import { createRuntime, NanofaasError } from "../src/index.js";

test("handler logger emits structured json with execution and trace ids", async () => {
    const originalLog = console.log;
    const lines: string[] = [];
    console.log = (line?: unknown) => {
        lines.push(String(line));
    };

    const runtime = createRuntime({ port: 0 });
    runtime.register("echo", async ({ logger }) => {
        logger.info("handler called", { phase: "invoke" });
        return { ok: true };
    });
    await runtime.start();

    try {
        const response = await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-log",
                "x-trace-id": "trace-log",
            },
            body: JSON.stringify({ input: null }),
        });
        assert.equal(response.status, 200);

        const entry = JSON.parse(lines.find((line) => line.includes("handler called"))!);
        assert.equal(entry.level, "info");
        assert.equal(entry.logger, "nanofaas.handler");
        assert.equal(entry.executionId, "exec-log");
        assert.equal(entry.traceId, "trace-log");
        assert.equal(entry.phase, "invoke");
    } finally {
        console.log = originalLog;
        await runtime.stop();
    }
});

test("metrics expose success, failure, in-flight, cold-start, and callback-failure counters", async () => {
    const runtime = createRuntime({ port: 0, callbackUrl: "http://127.0.0.1:9/callbacks" });
    runtime.register("echo", async (_ctx, req) => {
        if (req.input === "boom") {
            throw new NanofaasError("BAD_INPUT", "Invalid payload");
        }
        return { ok: true };
    });
    await runtime.start();

    try {
        async function waitFor(assertion: () => Promise<void>, timeoutMs = 1_000): Promise<void> {
            const startedAt = Date.now();
            while (true) {
                try {
                    await assertion();
                    return;
                } catch (error) {
                    if (Date.now() - startedAt >= timeoutMs) {
                        throw error;
                    }
                    await new Promise((resolve) => setTimeout(resolve, 20));
                }
            }
        }

        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-metrics-1",
            },
            body: JSON.stringify({ input: "ok" }),
        });

        await fetch(`${runtime.baseUrl}/invoke`, {
            method: "POST",
            headers: {
                "content-type": "application/json",
                "x-execution-id": "exec-metrics-2",
            },
            body: JSON.stringify({ input: "boom" }),
        });

        await waitFor(async () => {
            const response = await fetch(`${runtime.baseUrl}/metrics`);
            const body = await response.text();

            assert.match(body, /runtime_invocations_total\{success="true"\}\s+1/);
            assert.match(body, /runtime_invocations_total\{success="false"\}\s+1/);
            assert.match(body, /runtime_in_flight\s+0/);
            assert.match(body, /runtime_cold_start\s+1/);
            assert.match(body, /runtime_callback_failures\s+[1-9]/);
        });
    } finally {
        await runtime.stop();
    }
});
```

**Step 2: Run the tests and verify whether code changes are needed**

Run:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
```

Expected:
- if the logger already emits the documented shape, the new logger test may pass immediately
- if metric names or counters differ from the exposed Prometheus output, adjust `metrics.ts`, not the public API

**Step 3: Keep logger changes local and additive**

If the logger test fails, fix only the JSON entry shape in `function-sdk-javascript/src/logger.ts`.

Allowed change:

```ts
const entry = {
    timestamp: new Date().toISOString(),
    level,
    logger: name,
    message,
    executionId: getExecutionId() ?? null,
    traceId: getTraceId() ?? null,
    ...(fields ?? {}),
};
```

Do **not** rename `getLogger()` or `createLogger()` and do **not** widen the public logger interface in this task.

**Step 4: Document the observability contract**

Update `function-sdk-javascript/README.md` with:

- a short `Cold start headers` section documenting `X-Cold-Start` and `X-Init-Duration-Ms`
- a short `Metrics` section listing the exposed families:
  - `runtime_invocations_total`
  - `runtime_invocation_duration_seconds`
  - `runtime_in_flight`
  - `runtime_cold_start`
  - `runtime_callback_failures`
- one sentence explaining that logger entries include `executionId` and `traceId` when present

**Step 5: Re-run the SDK tests**

Run:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
```

Expected:
- PASS with the new observability test file included

**Step 6: Commit**

Run:

```text
gitnexus_detect_changes(scope="staged")
```

Then:

```bash
git add \
  function-sdk-javascript/test/runtime.observability.test.ts \
  function-sdk-javascript/src/logger.ts \
  function-sdk-javascript/src/metrics.ts \
  function-sdk-javascript/README.md
git commit -m "test(js-sdk): lock observability contract"
```

If `logger.ts` or `metrics.ts` did not change, leave them out of `git add`.

### Task 4: Make example Docker builds deterministic

**Files:**
- Modify: `examples/javascript/word-stats/Dockerfile`
- Modify: `examples/javascript/json-transform/Dockerfile`
- Modify: `scripts/tests/test_javascript_example_dockerfiles.py`

**Step 1: Write the failing Dockerfile tests**

Update `scripts/tests/test_javascript_example_dockerfiles.py`:

```python
def test_word_stats_dockerfile_uses_npm_ci_for_sdk_and_app() -> None:
    dockerfile = _read("examples/javascript/word-stats/Dockerfile")
    assert dockerfile.count("RUN npm ci") == 2
    assert "RUN npm install" not in dockerfile


def test_json_transform_dockerfile_uses_npm_ci_for_sdk_and_app() -> None:
    dockerfile = _read("examples/javascript/json-transform/Dockerfile")
    assert dockerfile.count("RUN npm ci") == 2
    assert "RUN npm install" not in dockerfile
```

Keep the existing build-order assertions.

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_example_dockerfiles.py -q
```

Expected:
- FAIL because both Dockerfiles still use `npm install`

**Step 3: Switch the example Dockerfiles to `npm ci`**

Update both Dockerfiles:

```dockerfile
WORKDIR /src/function-sdk-javascript
RUN npm ci
RUN npm run build

WORKDIR /src/examples/javascript/word-stats
RUN npm ci
RUN npm run build
RUN npm prune --omit=dev
```

Use the corresponding app path in the `json-transform` Dockerfile.

Do **not** change image names, entrypoints, or file layout in this task.

**Step 4: Re-run the Dockerfile tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_example_dockerfiles.py -q
```

Expected:
- PASS

**Step 5: Run real Docker smoke builds**

Run from the repository root:

```bash
docker build -f examples/javascript/word-stats/Dockerfile -t nanofaas-js-word-stats:test .
docker build -f examples/javascript/json-transform/Dockerfile -t nanofaas-js-json-transform:test .
```

Expected:
- both images build successfully from the repo root
- `npm ci` works in both the SDK layer and the example app layer
- no Dockerfile-only regression slips through text assertions

**Step 6: Commit**

Run:

```text
gitnexus_detect_changes(scope="staged")
```

Then:

```bash
git add \
  examples/javascript/word-stats/Dockerfile \
  examples/javascript/json-transform/Dockerfile \
  scripts/tests/test_javascript_example_dockerfiles.py
git commit -m "build(js-sdk): make example docker installs deterministic"
```

### Task 5: Tighten README and repo-level documentation guards

**Files:**
- Modify: `function-sdk-javascript/README.md`
- Modify: `scripts/tests/test_javascript_packaging_docs.py`

**Step 1: Write the failing documentation guard**

Extend `scripts/tests/test_javascript_packaging_docs.py`:

```python
def test_javascript_sdk_readme_documents_runtime_error_contract() -> None:
    readme = (REPO_ROOT / "function-sdk-javascript" / "README.md").read_text(encoding="utf-8")
    assert "INVALID_JSON" in readme
    assert "INVALID_REQUEST" in readme
    assert "HANDLER_TIMEOUT" in readme
    assert "UNHANDLED_ERROR" in readme
    assert "X-Cold-Start" in readme
    assert "X-Init-Duration-Ms" in readme
    assert "X-Callback-Url" in readme
    assert "runtime_cold_start" in readme
    assert "runtime_callback_failures" in readme
```

**Step 2: Run the docs test and verify it fails**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_packaging_docs.py -q
```

Expected:
- FAIL because the current README does not document the full runtime error and header contract yet

**Step 3: Expand the README to match the runtime**

Add these sections to `function-sdk-javascript/README.md`:

- `Header and environment precedence`
  - `X-Execution-Id` overrides `EXECUTION_ID`
  - `X-Trace-Id` overrides `TRACE_ID`
  - `X-Callback-Url` overrides `CALLBACK_URL`
- `Error contract`
  - the five error codes and their status mapping
  - whether each validation error emits a callback
- `Cold start behavior`
  - `X-Cold-Start: true` only on the first invocation
  - `X-Init-Duration-Ms` on the first invocation
- `Metrics`
  - `runtime_cold_start`
  - `runtime_callback_failures`

Keep the README short and behavior-focused. Do **not** turn it into a packaging/release guide.

**Step 4: Re-run the docs test**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_packaging_docs.py -q
```

Expected:
- PASS

**Step 5: Commit**

Run:

```text
gitnexus_detect_changes(scope="staged")
```

Then:

```bash
git add \
  function-sdk-javascript/README.md \
  scripts/tests/test_javascript_packaging_docs.py
git commit -m "docs(js-sdk): document runtime contract"
```

## Final Verification Matrix

Run all of these before claiming the hardening work is done:

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test

env npm_config_cache=/tmp/codex-npm-cache npm pack --dry-run

docker build -f examples/javascript/word-stats/Dockerfile -t nanofaas-js-word-stats:test .
docker build -f examples/javascript/json-transform/Dockerfile -t nanofaas-js-json-transform:test .

env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --with pytest python -m pytest \
  scripts/tests/test_javascript_sdk_packaging.py \
  scripts/tests/test_javascript_example_dockerfiles.py \
  scripts/tests/test_javascript_packaging_docs.py \
  scripts/tests/test_release_manager_javascript_sdk.py -q

env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/fn-init pytest \
  tools/fn-init/tests/test_generator.py -q
```

Expected:
- all Node tests pass
- `npm pack --dry-run` still produces the publishable dist-only artifact
- both example Docker images build successfully from source
- repo-level packaging, Dockerfile, docs, and release-manager guard tests pass
- `fn-init` generator coverage stays green, proving the hardening work did not regress JavaScript scaffolds

## Done Criteria

The plan is complete only when all of the following are true:

- malformed JSON and malformed metadata produce explicit HTTP 400 contracts
- timeout classification is deterministic even when handler abort paths race
- callback success and failure payloads are covered by automated tests
- validation errors have explicit callback semantics and tests
- the SDK documents headers, cold-start behavior, metrics, and runtime error codes
- example Dockerfiles use deterministic installs
- the existing packaging/release and scaffold guardrails remain green
