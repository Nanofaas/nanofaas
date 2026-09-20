# Rootless containerd deployment

The `containerd-deployment-provider` runs managed `DEPLOYMENT` functions through
the current user's containerd daemon and `crun`. The control plane joins that
daemon's RootlessKit user, mount and network namespaces. Each function has a CNI
network namespace; the control plane reaches its CNI IP directly, and the
function calls back through the CNI bridge gateway. This deployment mode needs
Linux, Java 25 for JVM builds, and a rootless containerd installation with a
working Unix socket. Docker is used by NanoLab only to build and push function
images; it does not own the running functions.

## Build from reviewed source revisions

`io.nanofaas:containerd-java`, `io.nanofaas:containerd-java-cni`, and
`io.libcni:libcni-java` are source snapshots. Their exact revisions and Maven
coordinates are in [`dependencies.env`](../deploy/containerd-rootless/dependencies.env).
The feature commits may not yet be present on public remotes. Supply local
checkouts containing those commits; the script exports the recorded trees to a
temporary directory and never builds or edits the supplied checkout in place:

```bash
scripts/bootstrap-containerd-dependencies.sh \
  /path/to/libcni-java /path/to/containerd-java
MAVEN_REPOSITORY="$PWD/.gradle/containerd-m2"
cat "$MAVEN_REPOSITORY/containerd-source-revisions.txt"
./gradlew :control-plane:bootJar \
  -PcontrolPlaneModules=containerd-deployment-provider \
  -PcontainerdMavenLocal=true -Dmaven.repo.local="$MAVEN_REPOSITORY"
```

The receipt records the source commits and SHA-256 of all three produced JARs.
Pass a third script argument to stage into another Maven directory. The Gradle
repository flag admits only `io.nanofaas` and `io.libcni` from that directory.
For native compilation, use the same module selection and repository:

```bash
./gradlew :control-plane:nativeCompile \
  -PcontrolPlaneModules=containerd-deployment-provider \
  -PcontainerdMavenLocal=true -Dmaven.repo.local="$MAVEN_REPOSITORY"
```

For async callbacks or scaling scenarios, add the scenario's queue, autoscaler
and other optional modules to `-PcontrolPlaneModules`; `none` and `all` retain
their existing meanings. The three managed providers are mutually exclusive:
`k8s-deployment-provider`, `container-deployment-provider` (Docker), and
`containerd-deployment-provider`. The default and `all` choose Kubernetes;
explicitly selecting a pair fails during settings configuration. The shared
`container-deployment-runtime` project is a mandatory library outside the
optional module selector, and `META-INF/services` from the Java dependencies
must remain in the selected artifact. The Gradle `bootJar` and `nativeCompile`
tasks include them through the selected provider's dependency graph.

The JVM image Dockerfile packages that `app.jar`; build the selected JAR first
and use `platform/control-plane` as its Docker context. For a native OCI image,
the script passes the staged repository as a Docker named context:

```bash
CONTROL_PLANE_MODULES=containerd-deployment-provider \
CONTAINERD_MAVEN_REPO="$MAVEN_REPOSITORY" \
  scripts/native-java-image.sh control-plane
```

This proves image packaging only. A working rootless host launch still needs
the namespace and socket setup below; the image itself is not a substitute for
that launch path.

## Runtime setup

Run containerd as the same unprivileged user as the control plane. Provide
subordinate UID/GID mappings, RootlessKit, `crun`, CNI bridge/host-local/portmap
plugins, a writable CNI cache and state directory, and delegated cgroup v2
`cpu`, `cpuset`, `memory`, and `pids` controllers. The user systemd service must
see the containerd socket and the RootlessKit child PID at
`$XDG_RUNTIME_DIR/containerd-rootless/child_pid`. NanoLab owns installation,
per-run service and environment rendering, host port publication and cleanup.
No VM provisioning runs from this repository.

For a NanoLab Multipass containerd environment, set
`containerdMavenRepository: /absolute/host/path` to the bootstrap output. The
runner copies only the three reviewed `io.nanofaas`/`io.libcni` versions and
their JAR, POM, Gradle module and Maven metadata files into a run-owned remote
repository. It records a `nanolab-receipt.json` of artifact hashes there and
builds with `-PcontainerdMavenLocal=true` and
`-Dmaven.repo.local=<remote repository>`. Point it at an isolated bootstrap
output; do not stage an entire personal Maven cache. The NanoLab scenario
revision in `dependencies.env` is pinned to the exact scenario checkout used by
the recorded checks. The lifecycle run used NanoFaaS `18d7b98f`; recovery and
the focused JVM async run used `00ab7ac9`. Keep these revisions in the receipt
when reproducing a run.

The checked-in [`10-nanofaas.conflist`](../deploy/containerd-rootless/10-nanofaas.conflist)
is the CNI network template. Replace `@ROOTLESS_HOME@` with the user's absolute
home before installing it under `~/.config/cni/net.d`. Its bridge gateway
`10.90.0.1` is the callback target on port 8080, while DNS uses `10.0.2.3` and
`1.1.1.1`; the gateway is not a DNS server. The control plane must be able to
reach each function CNI IP, and each function must reach the callback URL.
RootlessKit publishes the API and management ports to the host.

Use Spring's canonical environment names (no extra underscores inside property
names):

| Environment variable | Meaning / default |
| --- | --- |
| `NANOFAAS_DEPLOYMENT_DEFAULTBACKEND` | `containerd` for new managed functions |
| `SERVER_ADDRESS`, `SERVER_PORT`, `MANAGEMENT_SERVER_PORT` | `0.0.0.0`, `8080`, `8081` in the rootless service |
| `NANOFAAS_CONTAINERD_SOCKETPATH` | Absolute UDS; default `$XDG_RUNTIME_DIR/containerd/containerd.sock` |
| `NANOFAAS_CONTAINERD_NAMESPACE` | Containerd namespace; default `nanofaas` |
| `NANOFAAS_CONTAINERD_RUNTIMEBINARY`, `NANOFAAS_CONTAINERD_SNAPSHOTTER` | `crun`, `native` |
| `NANOFAAS_CONTAINERD_NETWORKNAME` | CNI network name; default `nanofaas` |
| `NANOFAAS_CONTAINERD_CNIPLUGINDIRECTORY` | Absolute plugin directory; default `/opt/cni/bin` |
| `NANOFAAS_CONTAINERD_CNICONFIGDIRECTORY` | Absolute config directory; default `$HOME/.config/cni/net.d` |
| `NANOFAAS_CONTAINERD_CNICACHEDIRECTORY` | Absolute writable cache; default `$HOME/.local/share/nanofaas/cni` |
| `NANOFAAS_CONTAINERD_STATEDIRECTORY` | Absolute writable client state; default `$HOME/.local/share/nanofaas/containerd` |
| `NANOFAAS_CONTAINERD_CALLBACKURL` | Reachable function-to-control-plane URL, e.g. `http://10.90.0.1:8080` |
| `NANOFAAS_CONTAINERD_BINDHOST` | Local bind host; default `127.0.0.1` |
| `NANOFAAS_CONTAINERD_SYSTEMDCGROUP`, `NANOFAAS_CONTAINERD_CGROUPSPATH` | `true`; parent systemd slice `user.slice` (or parent cgroup directory when systemd cgroups are disabled) |
| `NANOFAAS_CONTAINERD_CPUSET` | Optional cpuset string, subject to delegated CPUs |
| `NANOFAAS_CONTAINERD_CNIPLUGINTIMEOUT` | CNI plugin timeout; default `30s` |
| `NANOFAAS_CONTAINERD_STOPTIMEOUT` | Container stop timeout; default `10s` |
| `NANOFAAS_CONTAINERD_AVAILABILITYTIMEOUT` | Daemon availability probe timeout; default `3s` |
| `NANOFAAS_CONTAINERD_READINESSTIMEOUT`, `NANOFAAS_CONTAINERD_READINESSPOLLINTERVAL` | Function readiness window and polling; defaults `20s`, `250ms` |

All configured paths must be absolute; plugin and config directories must
exist. The launcher [`start-control-plane.sh`](../deploy/containerd-rootless/start-control-plane.sh)
checks these paths, enters the RootlessKit namespaces with `nsenter`, and runs
the JVM JAR by default. Set `NANOFAAS_CONTROL_PLANE_MODE=native` and
`NANOFAAS_CONTROL_PLANE_ARTIFACT` to the absolute executable path to launch a
native build. `JAVA_TOOL_OPTIONS` tunes JVM mode;
`NANOFAAS_CONTROL_PLANE_NATIVE_ARGS` supplies whitespace-separated native
arguments (shell quoting is not interpreted). NanoLab renders the
[`nanofaas.service`](../deploy/containerd-rootless/nanofaas.service) placeholders
`@NANOFAAS_ROOT@` and `@NANOLAB_ENV_FILE@` to per-run absolute paths and adds
`CPUQuota`/`MemoryMax` for load runs where needed.

Function images must be pushed to a registry reachable *inside* RootlessKit;
Docker's local image store is separate. For a test HTTP registry, configure the
rootless containerd **Transfer** plugin's `config_path` to a `hosts.toml`
directory permitting HTTP only for that registry. CRI registry configuration
does not configure the Transfer pull path. The current image validator asks
containerd to pull the image at registration; authenticated and alternative
registry transfer paths still need explicit runtime proof.

Function resource requests map to CPU shares and memory reservation; limits
map to cgroup CPU quota/period and memory bytes. CPU quota uses a 100 ms period.
A positive CPU limit below `0.01` can yield a quota below 1000 microseconds and
may be rejected by the kernel; the provider does not silently clamp it. Optional
`cpuset` and `cgroups-path` must fit the delegated rootless cgroup subtree.
These function limits are separate from the control-plane systemd unit limits.
With systemd cgroups, configure `cgroups-path` as a parent **slice name** such
as `user.slice`, not a complete OCI scope. The provider derives each scope as
`<slice>:nanofaas:<client-scope>-<container-id>`, following the
[systemd cgroup path form](https://github.com/opencontainers/runc/blob/main/docs/systemd.md).
The client scope derives from the normalized containerd socket path and
namespace; the container ID includes the function identity and replica index.
The resulting path is distinct for replicas and remains the same after a
control-plane restart. With `systemd-cgroup=false`, the provider instead uses
`<configured-parent>/<client-scope>-<container-id>` as a cgroupfs path.

The persistent function catalog restores the recorded `containerd` backend
after a control-plane restart. Reconciliation adopts only containers with the
expected managed, backend, function and replica labels. Failed CNI DEL or
snapshot removal remains pending in the library's state for a later retry; a
failed deprovision is not a successful cleanup. Keep the catalog, CNI cache and
containerd client state on durable, owner-writable storage and preserve the
same namespace across restarts. Existing unrelated containers are not swept.
Invocation failure retries are controlled by each function's `maxRetries`
(default `3` in the control-plane configuration). Clients must make their
functions idempotent; the cleanup retry state is a separate lifecycle concern.

Recorded runtime evidence is deliberately split by artifact and scenario:
the lifecycle scenario passed on NanoFaaS `18d7b98f`, recovery passed on
`00ab7ac91796069832fd8b85f313e766ea4e7889`, and the focused JVM async run
exited successfully on that recovery revision. The 18-function async run
failed after the Go and Java paths passed during Java-lite callback output
serialization (`OUTPUT_SERIALIZATION_ERROR`); missing native reflection
metadata is the leading diagnosis and remains to be confirmed. Its diagnosis
is outside this build/documentation slice.

Native compilation succeeded on Linux aarch64 at NanoFaaS revision
`00ab7ac91796069832fd8b85f313e766ea4e7889` for the containerd provider,
async-queue and build-metadata modules. The native receipt records
`runtime_verified: false`; no native binary was launched. Linux amd64, native
runtime CNI/callback/restart/cleanup, the full matrix, soak and failure
injection remain unverified. These results must not be described as complete
deployment parity.
