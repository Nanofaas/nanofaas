# Function lifecycle

This guide follows one small synchronous HTTP function from source to a managed
deployment, then checks its registration after a control-plane restart. Use the
same function image with Kubernetes/k3s, standalone Docker or rootless
containerd; only platform preparation differs.

Run platform commands from the NanoFaaS repository root. You need Java 25
(Gradle can download it), Docker for the image build, curl, Python 3.12+ for
local example execution, and access to an image registry reachable from your
chosen runtime. Kubernetes additionally needs kubectl, Helm and storage for
the catalog; containerd needs the prerequisites in its provider guide.

## Runtime and gateway contracts

There are two HTTP hops:

| Hop | Request | Response |
| --- | --- | --- |
| Caller → control plane | POST `/v1/functions/{name}:invoke` with `{"input": ..., "metadata": {...}}` | InvocationResponse JSON: executionId, status, output and optional result metadata. |
| Control plane → HTTP function | POST `/invoke` on the managed instance's port 8080, with InvocationRequest | JSON handler output; NanoFaaS adds the caller-facing envelope. GET `/health` must work for readiness probes. |

The CLI accepts raw input and adds the `input` wrapper. curl calls to the gateway
must add it themselves. During dispatch NanoFaaS sends X-Execution-Id and
X-Dispatch-Attempt, plus trace/idempotency headers when supplied.

The example below returns ordinary JSON with status 200. For application-selected
statuses, allowed headers, base64 binary output, runtime limits and callbacks,
use an [SDK](../sdks/python/README.md) and its documented response contract.
A custom HTTP service must mark function-selected outcomes with
`X-NanoFaaS-Function-Status: true`; unmarked non-2xx responses are upstream
errors that NanoFaaS may retry.

The gateway always returns its invocation envelope. A function producing HTML
does not give the browser a raw HTML response: WeekPantry currently needs an
HTTP adapter to convert GET to invocation and unwrap the output.
See [#246](https://github.com/miciav/nanofaas/issues/246) for native page/asset
serving. Images can be represented as base64 output; that is separate from
hosting an image URL.

## Write and build the function

Create a directory outside the platform checkout, for example:

```bash
DEMO_DIR=/tmp/nanofaas-greet
mkdir -p "$DEMO_DIR"
```

Save this as `$DEMO_DIR/function.py`:

```python
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


class Handler(BaseHTTPRequestHandler):
    def reply(self, status, output):
        body = json.dumps(output).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply(200 if self.path == "/health" else 404,
                   {"status": "ok"} if self.path == "/health" else {"error": "not found"})

    def do_POST(self):
        if self.path != "/invoke":
            return self.reply(404, {"error": "not found"})
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 65536:
                return self.reply(413, {"error": "invalid body size"})
            request = json.loads(self.rfile.read(size))
            payload = request["input"]
            name = payload.get("name", "world")
            if not isinstance(name, str):
                raise ValueError("name must be a string")
        except (ValueError, KeyError, TypeError, AttributeError):
            return self.reply(400, {"error": "expected input object with a string name"})
        self.reply(200, {"greeting": f"{os.environ.get('GREETING', 'Hello')}, {name}!"})


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
```

This is a small protocol demonstration using the standard library, not a
replacement for SDK admission, timeout, callback and shutdown handling.

Save `$DEMO_DIR/Dockerfile`:

```dockerfile
FROM python:3.12-alpine
WORKDIR /app
COPY function.py .
USER 65532:65532
EXPOSE 8080
CMD ["python", "function.py"]
```

Choose a registry and build for the nodes' architecture. For a local registry,
follow [Function images and registries](image-registries.md) to configure each
runtime's HTTP mirror/trust settings and verify pulls. Docker's settings and image
store do not configure k3s/containerd. Replace this example hostname:

```bash
REGISTRY=registry.example
FUNCTION_IMAGE="$REGISTRY/greet:release-1"
docker build -t "$FUNCTION_IMAGE" "$DEMO_DIR"
docker push "$FUNCTION_IMAGE"
```

Before registration, test the image directly:

```bash
docker run --rm --name nanofaas-greet-check -p 127.0.0.1:18080:8080 "$FUNCTION_IMAGE"
```

In another terminal:

```bash
curl --fail-with-body http://127.0.0.1:18080/health
curl --fail-with-body http://127.0.0.1:18080/invoke \
  -H 'Content-Type: application/json' -d '{"input":{"name":"Alice"}}'
```

Expect `{"status":"ok"}` and `{"greeting":"Hello, Alice!"}`. Stop the temporary
container with Ctrl+C before continuing.

## Choose and start a backend

A control-plane artifact contains exactly one managed provider. Changing a
runtime flag cannot add a provider absent from the artifact. Save the source
revision (`git rev-parse HEAD`), selected modules and image digest with your
verification results; a public version tag is not proof of that source identity.

### Docker on the local host

With Docker available to your user, run from the NanoFaaS checkout:

```bash
export NANOFAAS_REGISTRY_PATH="$PWD/.local-state/functions.json"
./gradlew :control-plane:bootRun \
  -PcontrolPlaneModules=container-deployment-provider \
  --args='--nanofaas.deployment.default-backend=container-local'
```

Keep the process running; use a second terminal for requests. The catalog path
must be writable and retained across restarts. API URL: `http://localhost:8080`.
For a containerized control plane, mount durable storage and give it access to
the daemon and reachable function endpoints; see [Local development](local.md)
and the [provider guide](../platform/modules/container-deployment-provider/README.md).

### Kubernetes / k3s

Use an existing cluster/context with a default StorageClass, or provision one
through [NanoLab](quickstart.md#provision-and-validate-a-platform). Build the JVM
artifact and image from your checkout:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=k8s-deployment-provider
SOURCE_REVISION=$(git rev-parse HEAD)
CONTROL_PLANE_TAG="$SOURCE_REVISION"
docker build --label "org.opencontainers.image.revision=$SOURCE_REVISION" \
  -t "$REGISTRY/nanofaas/control-plane:$CONTROL_PLANE_TAG" platform/control-plane
docker push "$REGISTRY/nanofaas/control-plane:$CONTROL_PLANE_TAG"
```

Install the local chart, including its locked dependency:

```bash
helm repo add prometheus-community https://prometheus-community.github.io/helm-charts
helm dependency build deploy/helm/nanofaas
helm upgrade --install nanofaas-demo deploy/helm/nanofaas \
  --set namespace.name=function-demo --set namespace.create=true \
  --set demos.enabled=false --set prometheus.create=false \
  --set controlPlane.persistence.enabled=true \
  --set "controlPlane.image.repository=$REGISTRY/nanofaas/control-plane" \
  --set "controlPlane.image.tag=$CONTROL_PLANE_TAG" --wait --timeout 5m
kubectl -n function-demo port-forward svc/control-plane 8080:8080
```

The chart uses `namespace.name`; the command does not change your kubectl context.
The provider creates function Deployments with restartPolicy Always. Keep the
forward running and use `http://localhost:8080` for the common steps below.
If a registry is private, configure control-plane pulls separately from the
function's `imagePullSecrets`. See [Kubernetes deployment](k8s.md).

### Rootless containerd

First complete [Rootless containerd deployment](deployment-containerd.md):
stage the pinned Java dependencies, prepare RootlessKit/crun/CNI/cgroups, build
with `containerd-deployment-provider`, and launch the control plane in the
daemon's namespaces using the provided launcher/service.

Set `NANOFAAS_DEPLOYMENT_DEFAULTBACKEND=containerd` and a persistent, writable
`NANOFAAS_REGISTRY_PATH` visible to that launch environment. Use the API port
published to the host. Register the same image from a registry available to
containerd: Docker's image store is separate. A plain Java process outside the
documented namespaces is not an equivalent rootless launch.

## Register and invoke

In the client terminal, set the endpoint and the same image reference:

```bash
API=http://localhost:8080
DEMO_DIR=/tmp/nanofaas-greet
REGISTRY=registry.example
FUNCTION_IMAGE="$REGISTRY/greet:release-1"
```

Save `$DEMO_DIR/function.json`, substituting your actual image reference:

```json
{
  "name": "greet",
  "image": "registry.example/greet:release-1",
  "executionMode": "DEPLOYMENT",
  "runtimeMode": "HTTP",
  "env": {"GREETING": "Hello"},
  "timeoutMs": 10000,
  "concurrency": 2,
  "maxRetries": 0,
  "scalingConfig": {"strategy": "NONE", "minReplicas": 1, "maxReplicas": 1}
}
```

The example omits resources because of [#247](https://github.com/miciav/nanofaas/issues/247):
explicit CPU/memory resources broke catalog restart in the JVM build at
`4f3d58b7`. A fix must be verified before using that configuration in this
restart exercise. On Kubernetes a LimitRange can supply pod defaults.
No containerd-specific reproduction or native-image reproduction is claimed.

```bash
curl --fail-with-body -i "$API/v1/functions" \
  -H 'Content-Type: application/json' --data-binary @"$DEMO_DIR/function.json"
curl --fail-with-body "$API/v1/functions/greet"
curl --fail-with-body "$API/v1/functions/greet/replicas"
curl --fail-with-body -i "$API/v1/functions/greet:invoke" \
  -H 'Content-Type: application/json' -d '{"input":{"name":"Alice"}}'
```

Registration should return 201. Replica status should show one desired and one
ready replica before invocation. Allow bounded time for readiness; if it does
not become ready, inspect the provider's function logs and image/network setup.
The invocation returns 200 and an envelope like:

```json
{"executionId":"...","status":"success","output":{"greeting":"Hello, Alice!"}}
```

Record the execution ID; it identifies an invocation through NanoFaaS.
An existing function name returns 409 on POST. Use a distinct test name or
inspect the existing definition before replacing anything.

For the CLI equivalent, build `:nanofaas-cli:installDist` and use:

```bash
CLI=clients/cli/build/install/nanofaas-cli/bin/nanofaas-cli
"$CLI" --endpoint "$API" invoke greet -d '{"name":"Alice"}'
```

Async enqueue requires the async-queue module; the minimal builds above do not
select it. Add it at build time if you need asynchronous requests.

## Verify catalog recovery

Restart only the control plane prepared for this example, retaining its catalog:

- Docker host: stop bootRun with Ctrl+C, then run the same command again with
  the same catalog path and provider.
- Kubernetes: use the commands below; re-establish the port-forward after the
  old pod terminates.
- Containerd: restart the configured control-plane user service/launcher with
  the same registry path, runtime state, socket and namespace setup.

```bash
kubectl -n function-demo rollout restart deployment/nanofaas-control-plane
kubectl -n function-demo rollout status deployment/nanofaas-control-plane --timeout=120s
# In its terminal, start the port-forward again if it ended.
```

After readiness, repeat:

```bash
curl --fail-with-body "$API/v1/functions/greet"
curl --fail-with-body "$API/v1/functions/greet/replicas"
curl --fail-with-body "$API/v1/functions/greet:invoke" \
  -H 'Content-Type: application/json' -d '{"input":{"name":"Alice"}}'
```

Require the same definition, a ready replica, and the greeting in a new
successful invocation. On failure, inspect catalog-loading and provider logs.
Do not delete the catalog merely to make startup pass: that loses registrations
and is not evidence of recovery.

This checks durable function registration, not durable queues or execution
history. Stateful app data belongs in an external database/storage system.
For retries after a database commit, use a durable application request ID and
a transactionally recorded replay result. [WeekPantry](https://github.com/Nanofaas/weekpantry)
demonstrates PostgreSQL persistence, replay after a later edit, browser checks
and component restarts.

## Updates, secrets and cleanup

The current PATCH can change only concurrency, timeoutMs, maxRetries and
concurrencyControl. Image/env changes require delete/register (`--replace` in
the CLI), with possible downtime; [#249](https://github.com/miciav/nanofaas/issues/249)
requests deployment updates without removing registration.

Function env is literal and is persisted in plaintext. Kubernetes Secret refs
are not supported, and Docker standalone/containerd need their own delivery
mechanisms. Do not register real credentials in this demonstration.
[#248](https://github.com/miciav/nanofaas/issues/248) requests portable bindings.
See [Function definition](function-definition.md) for the complete contract.

Remove only the example function after verifying it:

```bash
curl --fail-with-body -X DELETE "$API/v1/functions/greet"
```

Expect 204. If deletion reports pending removal, follow its diagnostic and retry
cleanup; do not blindly register a replacement. Keep the catalog and application
storage when retaining the platform; deleting the demo namespace/volumes also
deletes its data.

## Verification scope

On 2026-10-06, the Python and Dockerfile blocks above were extracted and executed
with Python 3.12 Alpine. Health, greeting/default input and invalid input checks
passed. A separate source-built JVM control plane at `4f3d58b7`, using the Docker
provider, pulled that image from a temporary local registry, registered the
manifest, reported one ready replica and returned the greeting envelope. After
restarting with the same catalog, its image/env/settings were unchanged and a
new invocation succeeded; deleting the test function returned 204.

The Kubernetes values in this guide passed Helm lint. WeekPantry's separate
k3s deployment provides the linked application/browser/restart evidence; this
small example was not independently deployed on k3s or rootless containerd.
Those setup paths must be verified in the reader's environment. The test used
no real credentials and removed its temporary runtime resources.
