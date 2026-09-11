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

`createRuntime` also accepts finite positive ownership limits. Defaults retain the
existing 128-callback behavior while making every retained resource finite:

- `maxConcurrentHandlers: 32`
- `maxInputBytes: 1 MiB`
- `maxOutputBytes: 1 MiB`
- `maxCallbackPayloadBytes: 2 MiB`
- `maxPendingCallbackBytes: 16 MiB`
- `bodyReadTimeoutMs: 5000`
- `callbackAttemptTimeoutMs: 5000`
- `callbackMaxAttempts: 3`
- `shutdownTimeoutMs: 5000`

Handler and callback admission is fail-fast. Retryable handler/callback saturation
and stopping responses include `Retry-After: 1`. The metrics endpoint exposes
active handler, retained input/output byte, and pending/serialized callback gauges.
`maxCallbackPayloadBytes` must fit every canonical terminal error callback, and
`maxPendingCallbackBytes` must be at least one callback payload reservation.
`maxOutputBytes` therefore has a 102-byte minimum and `maxCallbackPayloadBytes`
has a 132-byte minimum; configuration below either canonical-envelope bound is
rejected when the runtime is created.

## Development

```bash
npm install
npm test
npm run build
```
