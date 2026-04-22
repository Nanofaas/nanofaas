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
