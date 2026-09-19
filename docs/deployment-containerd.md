# Rootless containerd deployment

The `containerd-deployment-provider` runs managed functions through the user's
containerd daemon with `crun` and CNI. The control plane and daemon run as the
same unprivileged user. RootlessKit supplies their shared user, network and mount
namespaces; each function gets its own CNI network namespace. The bridge gateway
`10.90.0.1` receives function callbacks on control-plane port 8080.

Build the JVM control plane with the provider selected:

```bash
./gradlew :control-plane:bootJar -PcontrolPlaneModules=containerd-deployment-provider
```

The JAR is `platform/control-plane/build/libs/app.jar`. For a native build,
select the same module and set `NANOFAAS_CONTROL_PLANE_MODE=native` and
`NANOFAAS_CONTROL_PLANE_ARTIFACT` to the absolute path of the executable.
The launcher defaults to JVM mode and the JAR above. Use `JAVA_TOOL_OPTIONS`
for JVM tuning. Native-only extra options may be supplied as whitespace-separated
tokens in `NANOFAAS_CONTROL_PLANE_NATIVE_ARGS`; shell quotes and substitutions
are not interpreted.

NanoLab provisions the rootless daemon, user systemd service, subordinate UID/GID
mapping, delegated `cpu`, `cpuset`, `memory`, and `pids` controllers, CNI binaries,
per-run environment, registry, and RootlessKit port publications. It renders
`nanofaas.service` by replacing `@NANOFAAS_ROOT@` and `@NANOLAB_ENV_FILE@` with
absolute per-run paths. The systemd service runs the launcher in the foreground.
For load and soak runs, NanoLab adds a per-run service drop-in with `CPUQuota`
and `MemoryMax` so limits apply to the control-plane process.

`10-nanofaas.conflist` documents the network installed by NanoLab. If installing
it manually, replace `@ROOTLESS_HOME@` with the unprivileged user's absolute home
path before writing it to `~/.config/cni/net.d/10-nanofaas.conflist`. CNI bridge
gateway `10.90.0.1` is the callback target; it is not a DNS server. The resolver
list uses `10.0.2.3` and `1.1.1.1`.

The per-run environment sets `SERVER_ADDRESS=0.0.0.0`, `SERVER_PORT=8080`,
`MANAGEMENT_SERVER_PORT=8081`, and
`NANOFAAS_DEPLOYMENT_DEFAULTBACKEND=containerd`. It supplies absolute
`NANOFAAS_CONTAINERD_SOCKETPATH`, `CNIPLUGINDIRECTORY`, `CNICONFIGDIRECTORY`,
`CNICACHEDIRECTORY`, and `STATEDIRECTORY` values, plus `NETWORKNAME`,
`CALLBACKURL=http://10.90.0.1:8080`, and `BINDHOST=127.0.0.1`. These are
Spring's canonical environment names; the launcher passes them through.
RootlessKit publishes the API and management ports to the host. NanoLab owns
the port IDs, function image builds and registry, and cleanup after each run.
Function images must be pushed to a registry reachable inside RootlessKit;
Docker's image store is separate from containerd's. The rootless containerd
Transfer plugin needs an HTTP `hosts.toml`/`config_path` for the test registry.

The launcher reads the daemon's child PID from
`$XDG_RUNTIME_DIR/containerd-rootless/child_pid`, checks the artifact and absolute
paths, changes to the repository root, and then enters RootlessKit's user,
network, and mount namespaces. It passes the real absolute `HOME` and
`XDG_RUNTIME_DIR` to Java or the native executable after entering.
