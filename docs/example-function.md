# Example Function

## FunctionSpec (sync + async)

```json
{
  "name": "echo",
  "image": "nanofaas/java-warm-echo:0.5.0",
  "timeoutMs": 10000,
  "concurrency": 2,
  "queueSize": 50,
  "maxRetries": 3,
  "executionMode": "DEPLOYMENT"
}
```

## Scale a managed deployment to zero

Opt in with internal scaling and a zero minimum replica count:

```yaml
name: echo
image: ghcr.io/miciav/nanofaas/java-warm-echo:latest
executionMode: DEPLOYMENT
scalingConfig:
  strategy: INTERNAL
  minReplicas: 0
  maxReplicas: 3
  metrics:
    - type: in_flight
      target: "1"
```

Scale-to-zero applies only to managed `DEPLOYMENT` functions using the `k8s`
or `container-local` backends. `EXTERNAL` functions are not platform-managed,
so NanoFaaS does not scale, probe, or wake them. The first request after idle
waits for a bounded wake-up and readiness check, and therefore incurs
cold-start latency.

## Sync invoke

```bash
curl -X POST http://localhost:8080/v1/functions/echo:invoke \
  -H 'Content-Type: application/json' \
  -d '{"input": {"message": "hi"}}'
```

## Go runtime example

The Go SDK examples live under:

- `functions/go/word-stats`
- `functions/go/json-transform`

Run one locally:

```bash
cd functions/go/word-stats
go run .
```

## JavaScript runtime examples

The JavaScript SDK examples live under:

- `functions/javascript/word-stats`
- `functions/javascript/json-transform`

Run one locally:

```bash
cd functions/javascript/word-stats
npm install
npm start
```

## Async invoke

```bash
curl -X POST http://localhost:8080/v1/functions/echo:enqueue \
  -H 'Content-Type: application/json' \
  -d '{"input": {"message": "hi"}}'
```
