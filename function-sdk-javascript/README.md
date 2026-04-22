# nanofaas JavaScript Function SDK

TypeScript-first SDK for authoring NanoFaaS functions on Node.js.

## Public API

```ts
import { createRuntime } from "nanofaas-function-sdk";

const runtime = createRuntime();
runtime.register("echo", async (_ctx, req) => req.input);
await runtime.start();
```

Handlers receive a request-scoped context with `executionId`, optional `traceId`,
structured logger methods, timeout `signal`, and `isColdStart`.

## Runtime contract

- `POST /invoke`
- `GET /health`
- `GET /metrics`

Inputs follow the NanoFaaS `InvocationRequest` shape:

```json
{
  "input": { "hello": "world" },
  "metadata": { "tenant": "demo" }
}
```

Runtime configuration comes from:

- `PORT`
- `EXECUTION_ID`
- `TRACE_ID`
- `CALLBACK_URL`
- `FUNCTION_HANDLER`
- `NANOFAAS_HANDLER_TIMEOUT`

## Error contract

- `EXECUTION_ID_REQUIRED` -> HTTP 400
- `INVALID_JSON` -> HTTP 400
- `INVALID_REQUEST` -> HTTP 400
- `HANDLER_TIMEOUT` -> HTTP 504
- `UNHANDLED_ERROR` -> HTTP 500

Callback behavior for validation failures is intentional:

- `EXECUTION_ID_REQUIRED` does not emit a callback because the runtime cannot identify the execution.
- `INVALID_JSON` and `INVALID_REQUEST` emit a failure callback when `X-Execution-Id` and callback resolution are available.

## Install

```bash
npm install nanofaas-function-sdk
```

## Development

```bash
npm install
npm test
npm run build
```

## Release verification

```bash
env npm_config_cache=/tmp/codex-npm-cache npm test
env npm_config_cache=/tmp/codex-npm-cache npm pack --dry-run
```
