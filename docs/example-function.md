# Example Function

## FunctionSpec (sync + async)

```json
{
  "name": "echo",
  "image": "nanofaas/function-runtime:0.5.0",
  "timeoutMs": 10000,
  "concurrency": 2,
  "queueSize": 50,
  "maxRetries": 3,
  "executionMode": "REMOTE"
}
```

## Sync invoke

```bash
curl -X POST http://localhost:8080/v1/functions/echo:invoke \
  -H 'Content-Type: application/json' \
  -d '{"input": {"message": "hi"}}'
```

## Go runtime example

The Go SDK examples live under:

- `examples/go/word-stats`
- `examples/go/json-transform`

Run one locally:

```bash
cd examples/go/word-stats
go run .
```

## Async invoke

```bash
curl -X POST http://localhost:8080/v1/functions/echo:enqueue \
  -H 'Content-Type: application/json' \
  -d '{"input": {"message": "hi"}}'
```
