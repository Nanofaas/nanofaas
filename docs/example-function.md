# Example functions

This page collects working examples: `FunctionSpec` manifests for each
execution mode, raw HTTP calls against the control-plane API, and pointers to
the real example functions in the repository. For a complete step-by-step
walkthrough (scaffold → test → deploy → invoke), read the
[tutorial](tutorial-function.md); for the manifest reference, read
[function-definition](function-definition.md).
For a backend-independent HTTP example and catalog restart checks, read
[Function lifecycle](function-lifecycle.md).

## FunctionSpec examples

### DEPLOYMENT (managed warm instances)

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

The control plane provisions a Deployment + Service (Kubernetes) or container
instances (local Docker-compatible runtime or rootless containerd) from the image.

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

```bash
nanofaas fn replicas set echo 0   # scale to zero
nanofaas fn replicas get echo     # desired + ready replicas
```

### EXTERNAL (passthrough to a hosted endpoint)

```json
{
  "name": "legacy-service",
  "image": "legacy.example.com/transform:1",
  "executionMode": "EXTERNAL",
  "endpointUrl": "http://legacy.example.com/api/transform"
}
```

`image` is required by the `FunctionSpec` contract even here, although nothing
is pulled or run from it: the control plane forwards every invocation to
`endpointUrl` and relays the endpoint's response as the function output. The
function must already be serving the `InvocationRequest` contract (`input` +
`metadata`) on that URL. There is no lifecycle management — deleting the
function only removes the registration.

## Invoking from the CLI

```bash
nanofaas invoke echo -d '{"message": "hi"}'                  # sync
nanofaas enqueue echo -d '{"message": "hi"}'                 # async
nanofaas exec get <executionId> --watch                       # poll async result
```

`invoke` prints the `InvocationResponse` envelope (the handler result nested
under `output`), not the raw handler result.
CLI data is raw function input; the CLI adds the `input` wrapper. Direct HTTP
requests below must include that wrapper themselves.

## Invoking over HTTP

Sync invoke:

```bash
curl -X POST http://localhost:8080/v1/functions/echo:invoke \
  -H 'Content-Type: application/json' \
  -d '{"input": {"message": "hi"}}'
```

Async invoke (requires the `async-queue` module):

```bash
curl -X POST http://localhost:8080/v1/functions/echo:enqueue \
  -H 'Content-Type: application/json' \
  -d '{"input": {"message": "hi"}}'
```

## Real examples in the repository

The same three reference families are implemented in every SDK and share the
contract corpora under `functions/test-data/`:

| Family | Java | Java Lite | Go | Python | JavaScript | Bash |
| --- | --- | --- | --- | --- | --- | --- |
| word-stats | `functions/java/word-stats` | `functions/java/word-stats-lite` | `functions/go/word-stats` | — | `functions/javascript/word-stats` | `functions/bash/word-stats` |
| json-transform | `functions/java/json-transform` | `functions/java/json-transform-lite` | `functions/go/json-transform` | — | `functions/javascript/json-transform` | `functions/bash/json-transform` |
| roman-numeral | `functions/java/roman-numeral` | `functions/java/roman-numeral-lite` | `functions/go/roman-numeral` | `functions/python/roman-numeral` | `functions/javascript/roman-numeral` | `functions/bash/roman-numeral` |

Run one locally without the platform:

```bash
# Go
cd functions/go/word-stats && go run .

# JavaScript
cd functions/javascript/word-stats && npm install && npm start

# Java
./gradlew :functions:java:word-stats:bootRun
```

Additional examples outside the three families: `functions/java/figlet`
and `functions/bash/figlet` (ASCII-art), and `functions/python/mlimage`
(Machine-Learning inference, image input).

The cross-SDK contract gate that exercises all six runtimes:

```bash
./functions/contract-tests/run.sh
```

Benchmark corpora and the k6 matrix are documented in
[loadtest-payload-profile.md](loadtest-payload-profile.md).
