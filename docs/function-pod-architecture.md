# Function Pod Architecture

## Overview

Every function registered on NanoFaaS runs in a container. What the container
contains and how the control plane reaches it depends on the function's
`executionMode`:

| Mode | Meaning |
|---|---|
| `DEPLOYMENT` (default) | Managed deployment. The control plane provisions a Deployment + Service (Kubernetes) or container instances (local Docker/Podman) and dispatches invocations to the warm instances. |
| `EXTERNAL` | The function is hosted outside the control plane; invocations are forwarded to its `endpointUrl` as a passthrough (no lifecycle management). |
| `LOCAL` | In-process execution for testing. |

A managed container has two layers: the **watchdog** (process supervisor) and
the **runtime** (user code). The watchdog is a static Rust binary (~2 MB) that
owns the lifecycle of the user process.

```
+--------------------------------------------------------------+
|  FUNCTION CONTAINER (DEPLOYMENT mode)                        |
|                                                              |
|  +--------------------------------------------------------+  |
|  |  ENTRYPOINT: /watchdog  (Rust, static, ~2 MB)        |  |
|  |  - reads config from env                              |  |
|  |  - spawns the child process (WATCHDOG_CMD)            |  |
|  |  - handles health checks, timeout, callback, tracing  |  |
|  +----+---------------------------------------------------+  |
|       | spawn (WATCHDOG_CMD)                                 |
|       v                                                      |
|  +--------------------------------------------------------+  |
|  |  RUNTIME: user process                                |  |
|  |  - Java: Spring Boot SDK (sdks/java) on :8080         |  |
|  |  - Python: FastAPI runtime (sdks/python) on :8080     |  |
|  |  - Go: function-sdk-go embedded HTTP runtime on :8080 |  |
|  |  - Rust: nanofaas-sdk (sdks/rust) on :8080            |  |
|  |  - JS/Node: nanofaas-function-sdk HTTP runtime        |  |
|  |  - Any binary: HTTP /invoke, or stdin/stdout (STDIO), |  |
|  |    or file I/O (FILE)                                  |  |
|  +--------------------------------------------------------+  |
+--------------------------------------------------------------+
```

## The watchdog

`runtimes/watchdog/src/main.rs` is a Rust binary compiled statically with
musl, shipped on a `FROM scratch` image (~2 MB).

### Communication modes (RuntimeMode)

| Mode | Protocol |
|---|---|
| `HTTP` (default) | Watchdog polls `GET /health` until ready, then proxies `POST /invoke` to the runtime. |
| `STDIO` | Watchdog writes the JSON payload to stdin, reads the JSON response from stdout, and reaps the process. |
| `FILE` | Watchdog writes `/tmp/input.json`, spawns the process with `INPUT_FILE`/`OUTPUT_FILE` env vars, and reads `/tmp/output.json`. |

In **DEPLOYMENT** mode the watchdog stays alive between invocations (warm
containers): it exposes its own HTTP server on `:8080`, executes
`WATCHDOG_CMD` per invocation for `STDIO`/`FILE`, and reverse-proxies to the
internal runtime on `127.0.0.1:8081` for `HTTP`. The control plane sends the
request body as an `InvocationRequest` and reads the response body directly —
no per-invocation callback.

The full contract — environment variables, callback format, retries, signal
handling, error codes, per-mode examples — lives in
`runtimes/watchdog/README.md`, which is the authority on watchdog behavior.

## Environment variables

### One-shot mode (legacy cold mode)

`EXECUTION_ID`, `CALLBACK_URL`, `INVOCATION_PAYLOAD`, `TIMEOUT_MS`,
`TRACE_ID`, `WATCHDOG_CMD`, `EXECUTION_MODE` are injected into the container
environment.

### Warm DEPLOYMENT mode

`FUNCTION_NAME`, `WARM=true`, `TIMEOUT_MS`, `EXECUTION_MODE`, `WATCHDOG_CMD`,
`RUNTIME_URL` (`http://127.0.0.1:8081/invoke`). No `EXECUTION_ID`/`CALLBACK_URL`
as env vars: the `executionId` arrives as the `X-Execution-Id` header and the
body is an `InvocationRequest`.

## Kubernetes resources (DEPLOYMENT mode)

The `k8s` deployment provider (`platform/modules/k8s-deployment-provider`)
provisions one Deployment + Service per function in the configured namespace:

```
+-- Namespace: nanofaas ----------------------------------------+
|  Function "image-resize"                                      |
|  +-----------------------+   +--------------------+           |
|  |  Deployment           |   |  Service (ClusterIP)|          |
|  |  fn-image-resize      |   |  fn-image-resize    |          |
|  |  replicas: N          |   |  port: 8080         |          |
|  |                       |   |  selector:           |          |
|  |  (pods)               |<--|    function:          |          |
|  +-----------------------+   |    image-resize       |          |
|                              +--------------------+            |
|  +--------------------+                                       |
|  |  HPA (optional)    |  when scaling strategy is HPA        |
|  |  min: 1, max: 10   |                                       |
|  +--------------------+                                       |
+----------------------------------------------------------------+
```

- Deployments and Services are **reconciled in place** on provisioning
  updates (never deleted and recreated).
- HPAs are reconciled only when the function's scaling strategy is `HPA`;
  stale HPAs are removed when a function switches to another strategy.
- The internal autoscaler (`autoscaler` module) drives replica changes for
  the `INTERNAL` strategy through the same provider.

## Using the watchdog

### Case 1: Java function with the SDK (no watchdog)

`sdks/java` is a Spring Boot application that exposes `POST /invoke`,
`GET /health`, and `GET /metrics` itself. In DEPLOYMENT mode the watchdog is
not needed — the control plane calls the Kubernetes Service (or container
instances) directly:

```dockerfile
FROM gcr.io/distroless/java25-debian12
COPY build/libs/my-function.jar /app/app.jar
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
```

### Case 2: Python function with the SDK (no watchdog)

`sdks/python` provides a FastAPI runtime: `uvicorn nanofaas.runtime.app:app`
exposes `/invoke`, `/health`, and `/metrics`. Handlers are decorated with
`@nanofaas_function`.

### Case 3: Any binary or script (watchdog)

The watchdog wraps arbitrary executables — Go, Rust, C, Node scripts, bash —
in HTTP, STDIO, or FILE mode:

```dockerfile
FROM scratch
COPY --from=watchdog /watchdog /watchdog
COPY my-cli-tool /app/handler
ENV WATCHDOG_CMD="/app/handler"
ENV EXECUTION_MODE=STDIO
ENTRYPOINT ["/watchdog"]
```

STDIO mode is ideal for CLI tools and simple scripts: read JSON from stdin,
write JSON to stdout, exit.

## Compiling Java functions natively

Java functions can be compiled ahead-of-time with GraalVM `native-image`:

```bash
./gradlew :functions:java:word-stats-lite:nativeCompile
```

The resulting binary embeds SubstrateVM, Spring Boot (AOT-processed), the
Java SDK, and the handler; typical size 40-80 MB with sub-100 ms startup. The
repo-wide native build is orchestrated by `scripts/native-build.sh`, and
`scripts/native-java-image.sh <service>` builds Distroless native images.

| Scenario | JVM | Native |
|---|---|---|
| Cold start critical | No | **Yes** |
| Many replicas (RAM cost) | No | **Yes** |
| Scale-to-zero | No | **Yes** |
| CPU-intensive sustained | **Yes** | No |
| Fast development/debug | **Yes** | No |

### Native build caveats

- Spring AOT handles reflection automatically, but libraries relying on heavy
  reflection may need `RuntimeHints`.
- Compilation takes 1-3 minutes (JIT-enabled JVM builds take seconds).
- After warmup, the JVM can outperform native for CPU-intensive workloads.
- Debugging native binaries is harder.
