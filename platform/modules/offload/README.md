# offload

Optional control-plane module: conditional transparent proxy of **sync**
invocations (`:invoke`) to a remote nanofaas instance (e.g. edge → cloud).
The client talks only to the local control plane; the execution stays tracked
locally and the remote result flows back on the same request.

Design spec: `docs/superpowers/specs/2026-07-17-offload-module-design.md`.

## When it offloads

| Trigger | Condition |
|---------|-----------|
| `eager` | The function's `offload.mode` is `always` |
| `depth` / `est_wait` | The sync-queue admission rejected the request (saturation / estimated wait over threshold) and the function is not opted out — requires the `sync-queue` module |

A request received via offload (header `X-NanoFaaS-Offload-Hop`) is never
re-offloaded (single hop).

## What crosses the hop

The caller's application headers are forwarded as **real HTTP headers** on the
second hop, so a handler on the remote plane sees them exactly as a local one
would. Be aware of what that means operationally: any header the caller sent —
`authorization` and `cookie` included — is visible to the remote control plane
and to anything between the two planes. This platform has no authentication and
does not require TLS between planes, so treat the link as trusted infrastructure
or terminate TLS yourself.

Not forwarded:

| Class | Headers |
|---|---|
| Describe this hop's own message, not the caller's body | `content-type`, `content-length`, `content-encoding`, `accept`, `accept-encoding`, `transfer-encoding`, `expect`, `host`, `user-agent` |
| Owned by the gateway | `x-nanofaas-offload-hop`, `x-trace-id`, `traceparent`, `tracestate`, `x-execution-id`, `x-dispatch-attempt`, `x-timeout-ms`, `idempotency-key` |
| Hop-by-hop (RFC 9110) | `connection`, `keep-alive`, `proxy-connection`, `te`, `trailer`, `upgrade`, `proxy-*` |

Headers a caller nominates hop-by-hop through its own `Connection` field are
stripped at the **first** hop, in the controller, so they never reach a handler
or this gateway.

## Configuration

```yaml
nanofaas:
  offload:
    enabled: true            # default true; master switch
    target-url: http://cloud-host:8080   # global default remote
    pressure-enabled: true   # default true; switch for depth/est_wait triggers
```

Per-function, in the `FunctionSpec`:

```yaml
offload:
  enabled: true              # false opts the function out entirely
  targetUrl: http://other:8080   # overrides the global target (activates offload even without one)
  mode: pressure             # pressure (default) | always
```

## Semantics

- The function must already be registered on the remote instance (remote 404 → 502).
- No local fallback: remote failure → **502**, gateway timeout → **504**
  (response carries `X-NanoFaaS-Offloaded: <target>`); offloaded failures are
  never retried locally.
- Offloaded calls consume no local concurrency slots.
- Successful offloaded responses carry `X-NanoFaaS-Offloaded`; metrics:
  `nanofaas_offload_total{function,trigger}`, `nanofaas_offload_failure_total{function}`.
- `X-Trace-Id` is forwarded; W3C `traceparent`/`tracestate` are passed through
  as-is when the client sent them.
- The remote call's time budget is the effective invocation timeout
  (`X-Timeout-Ms` override included), minus a small margin so a gateway 504
  beats the local wait timeout.

## Testing

`OffloadPressureE2eTest` boots two full control planes in one JVM: a saturated
"edge" (sync queue `max-depth=1`, one concurrency slot against a deliberately
slow EXTERNAL endpoint) and a "cloud" running the same function as LOCAL. It
proves the `depth` pressure trigger end-to-end: overflow requests return 200
with `X-NanoFaaS-Offloaded` instead of 429, queued local work still completes,
no retries, offload metrics recorded. Runs in `./gradlew test`.

The `offload` scenario (`nanolab.sh run packages/nanolab/scenarios-v2/offload.yaml`)
exercises the eager path against two real JVM instances and a real function
container: build, dual registration (cloud `DEPLOYMENT`/container-local, edge
`LOCAL` + `offload.mode=always`), offloaded invocation with header check,
metrics, and the no-fallback 502 after deleting the remote function.

## Limitations & follow-ups

Deliberately out of scope in v1 — pick up here when needed:

1. **Async offload** (`:enqueue`) — sync-only by design. If added: the queue
   worker should call the remote `:invoke` synchronously and complete the
   local record (no remote `:enqueue`, no cross-instance execution-id mapping).
2. **Full W3C Trace Context / OTel** — today only pass-through. Real adoption
   is micrometer-tracing at the platform level (controllers, dispatchers,
   SDKs), not a module concern.
3. **Multi-target / round-robin, and automatic `FunctionSpec` propagation to
   the remote** — federation features; v1 assumes one target per function and
   out-of-band registration.
4. **Post-terminal idempotent replays** of a failed offload return the stored
   record state (200 with an `OFFLOAD_*` error body) rather than 502 — chosen
   behavior, documented here for the next reader.
