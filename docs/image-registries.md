# Function images and registries

This guide prepares image delivery for managed `DEPLOYMENT` functions on Docker,
k3s or standalone containerd. Use [Function lifecycle](function-lifecycle.md#write-and-build-the-function)
to create the example `greet` function and its Dockerfile in `/tmp/nanofaas-greet`.
Run build commands on the machine containing those files; run runtime configuration
commands on the daemon host or cluster nodes indicated below. Existing registry
and runtime configuration must be merged, not overwritten.

## Choose the image delivery path

| Backend | Who fetches the function image | Configuration location |
| --- | --- | --- |
| `container-local` (Docker) | The Docker daemon selected by NanoFaaS's adapter | That daemon's registry trust settings; CLI credentials belong to the control-plane user |
| `k8s` on k3s | Node containerd, for the validation pod and function pods | `/etc/rancher/k3s/registries.yaml` on every eligible node; function `imagePullSecrets` in the workload namespace |
| `containerd` standalone | The selected containerd daemon's Transfer service | Transfer resolver settings; CRI settings are separate, and custom endpoints need explicit NanoFaaS verification |

`127.0.0.1` means the host/network namespace of the process making the connection.
A registry on your laptop's loopback is not reachable through a VM's loopback.
For k3s, a node-local loopback registry must exist on every node that can receive
the validation pod or function replicas. A shared registry address is simpler
for multiple nodes. With rootless containerd, check reachability inside RootlessKit.

Docker's image store, k3s's `k8s.io` namespace and standalone containerd's
`nanofaas` namespace are separate. Building an image with Docker does not populate
either containerd store. The registry port and hostname are choices, not fixed
NanoFaaS requirements. Fully qualify image names, for example
`registry.example:5000/greet:release-1`; an unqualified name uses the runtime's
default registry resolution.

For a public HTTPS registry, build and push under your own registry account,
then use an anonymously pullable image reference. For a private HTTPS registry,
also configure runtime pull credentials and CA trust as described below. The
HTTP examples are for an isolated development network and use no credentials.
Use TLS for authenticated registries; see [Distribution deployment](https://distribution.github.io/distribution/about/deploying/).

## Docker on the same host

Start a registry on the Docker daemon host. Port 5000 must be free; choose another
port and update every image reference if it is occupied:

```bash
docker volume create nanofaas-registry-data
docker run -d --name nanofaas-registry --restart=unless-stopped \
  -p 127.0.0.1:5000:5000 \
  -v nanofaas-registry-data:/var/lib/registry registry:3
curl --fail http://127.0.0.1:5000/v2/
```

Expected registry response: `{}`. Docker currently permits HTTP for loopback
registries. For an HTTP registry at a non-loopback address, merge its exact
`host:port` into the daemon's `insecure-registries` list instead. On a Linux
system daemon, edit `/etc/docker/daemon.json`, preserving other keys:

```json
{
  "insecure-registries": ["192.0.2.10:5000"]
}
```

Replace the example address before applying it. Validate and restart that daemon
after a configuration change; the restart can interrupt its containers:

```bash
sudo dockerd --validate --config-file=/etc/docker/daemon.json
sudo systemctl restart docker
docker info
```

Rootless Docker and Docker Desktop have different configuration/restart paths.
Follow the [Docker daemon registry reference](https://docs.docker.com/reference/cli/dockerd/#insecure-registries)
for your installation. These settings do not configure k3s or standalone containerd.

On the build host, build for the daemon host's architecture and push:

```bash
DEMO_DIR=/tmp/nanofaas-greet
REGISTRY=127.0.0.1:5000
FUNCTION_IMAGE="$REGISTRY/greet:release-1"
docker build -t "$FUNCTION_IMAGE" "$DEMO_DIR"
docker push "$FUNCTION_IMAGE"
docker pull "$FUNCTION_IMAGE"
docker image inspect "$FUNCTION_IMAGE" --format '{{json .RepoDigests}}'
```

Use [Local development](local.md) to start a control plane with
`container-deployment-provider` and `container-local`. Then follow
[Register and invoke](#register-and-invoke). A named image is pulled during
registration even if it was built locally. The final `docker pull` should run
using the same daemon and CLI user/configuration as NanoFaaS.

### Private registries with Docker

For the Docker CLI adapter (`nanofaas.container-local.runtime-adapter=docker`),
log in as the user that runs the control plane, using that user's Docker
configuration and credential helper:

```bash
docker login registry.example:5000
docker pull registry.example:5000/greet:release-1
```

Do not copy registry passwords into `FunctionSpec.env`. `imagePullSecrets` names
Kubernetes Secrets; it does not configure Docker. A control plane running in a
container also needs access to its CLI configuration/credential helper, the
chosen Docker daemon and reachable function endpoints. A successful login on
the build host does not establish pull access for a different service user.
The optional `docker-java` adapter uses the Engine API; verify its credential
configuration independently rather than assuming a CLI login proves that path.

For a custom registry CA, install the CA on the daemon host at
`/etc/docker/certs.d/<registry-host:port>/ca.crt`; see [Docker registry certificates](https://docs.docker.com/engine/security/certificates/).

## k3s with a reachable development registry

Use a registry host reachable from the build host and every schedulable k3s node.
For example, run the registry on a VM's trusted network interface. On that
registry host, choose a free port and bind the registry to the intended interface:

```bash
# Replace with the registry host's actual interface address.
REGISTRY_BIND_ADDRESS=192.0.2.10
docker volume create nanofaas-registry-data
docker run -d --name nanofaas-registry --restart=unless-stopped \
  -p "$REGISTRY_BIND_ADDRESS:5000:5000" \
  -v nanofaas-registry-data:/var/lib/registry registry:3
```

On **each k3s node**, merge the following into
`/etc/rancher/k3s/registries.yaml`, replacing the example address in both places:

```yaml
mirrors:
  "192.0.2.10:5000":
    endpoint:
      - "http://192.0.2.10:5000"
```

Restart `k3s` on server nodes or `k3s-agent` on agent nodes, one node at a time;
this is a runtime configuration change. Check readiness afterwards:

```bash
# Server node; use k3s-agent instead on an agent node.
sudo systemctl restart k3s
sudo systemctl is-active k3s
```

From the machine with kubectl configured:

```bash
kubectl get nodes -o wide
```

K3s generates its containerd configuration from this file. Use an explicit HTTP
endpoint for an HTTP registry; skipping TLS certificate verification does not
switch HTTPS to HTTP. See [K3s registry configuration](https://docs.k3s.io/installation/private-registry).

On the build host, allow this HTTP endpoint in Docker as described above, then:

```bash
DEMO_DIR=/tmp/nanofaas-greet
REGISTRY=192.0.2.10:5000  # replace with the reachable registry address
FUNCTION_IMAGE="$REGISTRY/greet:release-1"
docker build -t "$FUNCTION_IMAGE" "$DEMO_DIR"
docker push "$FUNCTION_IMAGE"
```

When build and node architectures differ, select the target explicitly, for
example `docker build --platform linux/arm64 -t "$FUNCTION_IMAGE" "$DEMO_DIR"`
followed by `docker push "$FUNCTION_IMAGE"` for ARM64 nodes. Builds with foreign
architecture `RUN` steps also need emulation or a matching builder. A
mixed-architecture cluster needs an appropriate multi-platform image or scheduling
constraints; pulling an incompatible image does not make it runnable. If you use
a separate BuildKit builder to push directly to an HTTP registry, configure its
registry trust too; Docker daemon settings alone do not configure that builder.

On every eligible node, check the CRI pull path:

```bash
REGISTRY=192.0.2.10:5000  # replace as above
FUNCTION_IMAGE="$REGISTRY/greet:release-1"
curl --fail "http://$REGISTRY/v2/"
sudo k3s crictl pull "$FUNCTION_IMAGE"
```

Start NanoFaaS with the `k8s-deployment-provider` using
[Function lifecycle](function-lifecycle.md#kubernetes--k3s), then
[register and invoke](#register-and-invoke). NanoFaaS's Kubernetes validator
creates a temporary pod; registry access is required on its assigned node as
well as the nodes running the function Deployment. A registry address need not
be reachable from the control-plane pod merely to fetch function images.

### Private registries with k3s

For a custom CA, merge a registry-specific trust entry into `registries.yaml`
on each eligible node, install the CA at the indicated node path and restart
the appropriate k3s service:

```yaml
configs:
  "registry.example:5000":
    tls:
      ca_file: /etc/rancher/k3s/registry-ca.crt
```

For function pull credentials, create a `kubernetes.io/dockerconfigjson` Secret
in the namespace configured by `nanofaas.k8s.namespace`. Use a Docker configuration
file containing usable registry `auths`, obtained through your credential setup;
a file containing only credential-helper names is insufficient for Kubernetes:

```bash
FUNCTION_NAMESPACE=function-demo
kubectl -n "$FUNCTION_NAMESPACE" create secret generic function-registry \
  --type=kubernetes.io/dockerconfigjson \
  --from-file=.dockerconfigjson=/secure/path/registry-dockerconfig.json
```

Add `"imagePullSecrets": ["function-registry"]` to the registration JSON below.
NanoFaaS uses it for the validation pod and function pods. This Secret neither
injects application credentials nor configures the control-plane Deployment's
own image pull. See [Kubernetes private image pulls](https://kubernetes.io/docs/tasks/configure-pod-container/pull-image-private-registry/).

## Standalone rootless containerd

Prepare the daemon and namespace launch using
[Rootless containerd deployment](deployment-containerd.md#runtime-setup).
Use a registry address reachable **inside that daemon's RootlessKit network**.
Do not assume host loopback is shared; test the address from that namespace.

Use an anonymously pullable HTTPS registry with standard CA trust for the common
function workflow: build and push under your registry account, then use that
exact reference with the `containerd` backend. For a custom HTTP endpoint or CA,
check the following limitation before assuming a runtime configuration change
will make NanoFaaS pull successfully.

On the build host, replace the registry and account/repository with ones you
control, and enable anonymous pull access through that registry's settings:

```bash
DEMO_DIR=/tmp/nanofaas-greet
REGISTRY=registry.example
FUNCTION_IMAGE="$REGISTRY/my-account/greet:release-1"
docker login "$REGISTRY"
docker build -t "$FUNCTION_IMAGE" "$DEMO_DIR"
docker push "$FUNCTION_IMAGE"
```

On the prepared runtime host, set `FUNCTION_IMAGE` to that same HTTPS registry
reference, then check a pull without credentials or endpoint overrides:

```bash
CONTAINERD_SOCKET="$XDG_RUNTIME_DIR/containerd/containerd.sock"
CONTAINERD_NAMESPACE=nanofaas
: "${FUNCTION_IMAGE:?Set FUNCTION_IMAGE to the anonymously pullable HTTPS image}"
ctr --address "$CONTAINERD_SOCKET" --namespace "$CONTAINERD_NAMESPACE" \
  images pull --snapshotter native "$FUNCTION_IMAGE"
```

Then [register and invoke](#register-and-invoke). The following custom-endpoint
example is a separate diagnostic path.

### Custom endpoints and the Transfer limitation

The containerd 2.x Transfer plugin exposes this configuration table. A path must
be absolute; TOML does not expand `$HOME` or `~`:

```toml
[plugins."io.containerd.transfer.v1.local"]
  config_path = "/home/example/.config/containerd/certs.d"
```

**This table alone is not a verified solution for NanoFaaS custom registries.**
On standalone containerd 2.2.2, setting `config_path` to a directory containing
the HTTP endpoint below still left a default `ctr` Transfer pull trying HTTPS.
Passing that directory explicitly in the Transfer request is a distinct path.
The pinned Java client used by NanoFaaS sends an image reference without
Resolver options; the current function contract has no registry host-directory,
scheme or custom-CA fields. Do not assume the successful CLI check below proves
NanoFaaS can use those custom settings. The [pinned client implementation](https://github.com/Nanofaas/containerd-java/blob/ced2d3f3a0f511e1657e353745a4f54a599de75e/src/main/java/io/nanofaas/containerd/internal/ImagesServiceImpl.java)
and [containerd Transfer resolver](https://github.com/containerd/containerd/blob/v2.2.2/core/transfer/registry/registry.go)
show the request boundary.

Create the registry-specific directory as that runtime user. The command below
creates a new file and refuses to overwrite an existing one; if it already
exists, merge the endpoint and capabilities with its current settings instead:

```bash
REGISTRY=192.0.2.10:5000  # replace with an address reachable inside RootlessKit
REGISTRY_HOSTS_DIR="$HOME/.config/containerd/certs.d"
mkdir -p "$REGISTRY_HOSTS_DIR/$REGISTRY"
(
set -o noclobber
cat > "$REGISTRY_HOSTS_DIR/$REGISTRY/hosts.toml" <<EOF
server = "http://$REGISTRY"

[host."http://$REGISTRY"]
  capabilities = ["pull", "resolve"]
EOF
)
```

For a diagnostic HTTPS endpoint with a custom CA, use HTTPS URLs and a `ca` path
in `hosts.toml`. The host formats are documented in [containerd registry hosts](https://github.com/containerd/containerd/blob/v2.1.5/docs/hosts.md).
The [Transfer plugin configuration](https://github.com/containerd/containerd/blob/v2.1.5/plugins/transfer/plugin.go)
is separate from the CRI registry configuration. NanoFaaS's standalone provider
uses Transfer; changing only CRI settings does not configure its pull path.

If you change daemon plugin configuration, restart its actual user service
(commonly `systemctl --user restart containerd`), then restart the control-plane
launcher if the RootlessKit child PID changed. Preserve root, state, snapshotter
and networking settings. Re-enter the current RootlessKit namespaces when
checking connectivity. A CLI-only hosts directory does not require changing
the daemon's configuration.

For this separate HTTP diagnostic, permit the endpoint in the build host's Docker
daemon settings as described above, then build/push using a distinct variable:

```bash
DEMO_DIR=/tmp/nanofaas-greet
DIAGNOSTIC_REGISTRY=192.0.2.10:5000  # replace with the diagnostic endpoint
DIAGNOSTIC_IMAGE="$DIAGNOSTIC_REGISTRY/greet:release-1"
docker build -t "$DIAGNOSTIC_IMAGE" "$DEMO_DIR"
docker push "$DIAGNOSTIC_IMAGE"
```

For a diagnostic pull check, use the same socket and containerd namespace as
NanoFaaS; replace the socket below if your service uses another path. The hosts
directory must be accessible to the daemon, which performs the Transfer pull:

```bash
CONTAINERD_SOCKET="$XDG_RUNTIME_DIR/containerd/containerd.sock"
CONTAINERD_NAMESPACE=nanofaas
REGISTRY_HOSTS_DIR="$HOME/.config/containerd/certs.d"
REGISTRY=192.0.2.10:5000  # replace as above
DIAGNOSTIC_IMAGE="$REGISTRY/greet:release-1"
ctr --address "$CONTAINERD_SOCKET" --namespace "$CONTAINERD_NAMESPACE" \
  images pull --hosts-dir "$REGISTRY_HOSTS_DIR" --snapshotter native "$DIAGNOSTIC_IMAGE"
```

This command targets containerd 2.x's Transfer path with explicit resolver
configuration. It diagnoses registry reachability and `hosts.toml`; it is not
equivalent to NanoFaaS's request. Retain the anonymously pullable HTTPS
`FUNCTION_IMAGE` from the common workflow when registering; the diagnostic HTTP
reference does not replace it. Registration is the final check of NanoFaaS's own
pull path. Loopback HTTP registries may benefit from the runtime's localhost
exception, but must actually be reachable inside the rootless daemon namespace;
this does not establish support for a remote HTTP registry or custom CA.

`imagePullSecrets` does not deliver credentials to standalone containerd.
Authenticated Transfer pulls through the NanoFaaS Java client remain unverified
in the recorded runtime evidence; neither `docker login` nor a successful
`ctr --user` pull proves that NanoFaaS supplies credentials. Use a verified
anonymous registry path for this example and qualify private-registry support
with an actual NanoFaaS registration test before relying on it.

## Register and invoke

Keep the chosen control plane running. On the client, set `API` to its reachable
API URL and retain `FUNCTION_IMAGE` from your selected backend section. In a new
terminal, set it again to that exact reference before running these commands:

```bash
API=http://localhost:8080
: "${FUNCTION_IMAGE:?Set FUNCTION_IMAGE to the reference you pushed}"
cat > /tmp/nanofaas-registry-function.json <<EOF
{
  "name": "registry-greet",
  "image": "$FUNCTION_IMAGE",
  "executionMode": "DEPLOYMENT",
  "runtimeMode": "HTTP",
  "timeoutMs": 10000,
  "concurrency": 2,
  "maxRetries": 0,
  "scalingConfig": {"strategy": "NONE", "minReplicas": 1, "maxReplicas": 1}
}
EOF
curl --fail-with-body -H 'Content-Type: application/json' \
  --data-binary @/tmp/nanofaas-registry-function.json "$API/v1/functions"
curl --fail-with-body "$API/v1/functions/registry-greet"
curl --fail-with-body -H 'Content-Type: application/json' \
  --data '{"input":{"name":"Registry"}}' \
  "$API/v1/functions/registry-greet:invoke"
```

Expect registration HTTP 201, then a successful invocation with
`"output":{"greeting":"Hello, Registry!"}` inside `InvocationResponse`.
The name must be unused; a 409 is not an image pull error. The example omits
explicit resources because of the catalog defect described in
[Function definition](function-definition.md#resources).

Use a new tag for each build, or copy the registry digest shown by Docker and use
`registry.example:5000/greet@sha256:<manifest-digest>` in `FunctionSpec.image`.
A registry manifest digest differs from Docker's local image ID. For updates,
follow [Function lifecycle](function-lifecycle.md#updates-secrets-and-cleanup);
image changes are not supported by the current PATCH contract.

## Images imported without a registry

| Backend | Preparation | NanoFaaS registration behavior |
| --- | --- | --- |
| Docker | `docker load --input greet.tar` into the same daemon | A named reference is still pulled. Both Docker adapters support an existing bare `sha256:<local-image-id>` and inspect it instead of pulling; this is Docker-specific. |
| k3s | Import into `k8s.io` on **every** eligible node | Set `nanofaas.k8s.image-pull-policy=Never` for strictly local images, or `IfNotPresent` for cache-first pulls. Both validation and function pods use this setting. |
| Standalone containerd | Import into the configured daemon and namespace | The image validator still requests a Transfer pull; importing alone is not a documented registry-free registration path. |

For k3s, on each node after copying a Docker image archive there:

```bash
sudo k3s ctr --namespace k8s.io images import /path/to/greet.tar
sudo k3s crictl images
```

For a Helm-managed control plane, merge this into its existing values and perform
the normal upgrade using the complete installation values:

```yaml
controlPlane:
  extraEnv:
    - name: NANOFAAS_K8S_IMAGEPULLPOLICY
      value: "Never"
```

This is a **global function pull policy**, including validation pods. It differs
from `controlPlane.image.pullPolicy`, which governs the control-plane image.
Use the exact imported image name and tag; `Never` fails if any selected node
lacks it. `Always` may require registry access even when layers are cached.
See [Kubernetes image pull policies](https://kubernetes.io/docs/concepts/containers/images/#image-pull-policy)
and [K3s image import](https://docs.k3s.io/import-images/).

## Diagnose failures and clean up

| Symptom | Check |
| --- | --- |
| Connection refused / timeout | Registry process, interface binding, firewall, DNS and address reachability from the daemon/node namespace |
| HTTP response to HTTPS client | Explicit HTTP configuration in the runtime that pulls; Docker settings do not propagate to k3s/Transfer. For standalone Transfer, see the custom-endpoint limitation above. |
| `x509` certificate error | CA installed for the actual pull runtime and hostname matches the certificate |
| Unauthorized / denied | Pull credentials for the control-plane CLI user or Kubernetes workload namespace; build-host login alone is insufficient |
| Manifest unknown / image not found | Full registry/repository/tag or digest, and whether `docker push` completed |
| `ErrImageNeverPull` | Exact image imported on the pod's assigned node and the configured pull policy |
| No matching platform / exec format error | Image architecture versus node/daemon architecture |
| `IMAGE_REGISTRY_UNAVAILABLE` | Read the registration error details and the backend's pull logs; it may wrap trust, authentication or connectivity failures |

For k3s, inspect the node running the failed pod. Validation pods are temporary,
so capture events while registering if needed:

```bash
FUNCTION_NAMESPACE=function-demo
kubectl -n "$FUNCTION_NAMESPACE" get pods -o wide
kubectl -n "$FUNCTION_NAMESPACE" get events --sort-by=.lastTimestamp
POD_NAME=replace-with-failing-pod-name
kubectl -n "$FUNCTION_NAMESPACE" describe pod "$POD_NAME"
# On the assigned node:
sudo tail -n 100 /var/lib/rancher/k3s/agent/containerd/containerd.log
```

For Docker, repeat `docker pull` with the service's daemon/user configuration.
For standalone containerd, inspect the daemon's user-service journal and check
the Transfer pull before retrying registration. Keep passwords and credential
files out of shared logs.

Remove the example function first, then stop a registry only if you created it
for this exercise and no other workloads need it:

```bash
curl --fail-with-body -X DELETE "$API/v1/functions/registry-greet"
docker rm -f nanofaas-registry
```

The registry volume remains for reuse. Removing images from a node or stopping a
registry used by other functions can break their next rollout or restart.

## Verification scope

Verified on 2026-10-06 using the function source from the linked lifecycle guide:

- Docker 29.6.2: loopback HTTP registry, image build/push/pull, managed registration
  HTTP 201 and invocation HTTP 200 returning `Hello, Registry!`. The temporary JVM
  control plane was built from `4f3d58b7` with `container-deployment-provider`.
- k3s `v1.37.0+k3s1`: image build/push to the existing node-local HTTP registry,
  `k3s crictl pull`, managed registration HTTP 201 and invocation HTTP 200 with
  the same result. The JVM control-plane image was built from `4f3d58b7`.
  The node's registry configuration was already present; the new-file setup and
  node service restarts in this guide were not applied to the running lab.
- Standalone containerd 2.2.2: an isolated rootful daemon and socket reproduced
  an HTTPS/HTTP mismatch both without configuration and with only Transfer
  `config_path`. The same pull succeeded with an explicit request hosts directory.
  This checks the diagnostic configuration, not NanoFaaS's rootless lifecycle.
- Shell syntax, JSON, YAML, TOML, local links and anchors were checked; the
  function pull-policy Helm values were rendered against the chart.

The temporary function registrations, local registry container and standalone
daemon were removed. Existing application pods remained Ready. Private-registry
authentication, custom CA pulls, a multiple-node cluster, offline function
registration and a fresh rootless runtime were not executed in this check.
