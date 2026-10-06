# containerd-deployment-provider

Optional managed `DEPLOYMENT` backend `containerd`. It uses the rootless user's
containerd daemon, `crun`, and CNI. The module owns containerd configuration and
the adapter; lifecycle reconciliation, HTTP readiness and the bounded function
proxy live in the shared [`container-deployment-runtime`](../../container-deployment-runtime/)
project. The shared project has no Spring auto-configuration.

Select this provider alone among the managed deployment providers:

```bash
./gradlew :control-plane:bootJar \
  -PcontrolPlaneModules=containerd-deployment-provider \
  -PcontainerdMavenLocal=true -Dmaven.repo.local="$PWD/.gradle/containerd-m2"
```

The two `io.nanofaas` artifacts and `io.libcni` artifact are source snapshots.
Use [`scripts/bootstrap-containerd-dependencies.sh`](../../../scripts/bootstrap-containerd-dependencies.sh)
with checkouts containing the exact commits in
[`dependencies.env`](../../../deploy/containerd-rootless/dependencies.env). This
module has no Docker or Kubernetes runtime dependency. `all` and the default
selection continue to include Kubernetes; an explicit pair of deployment
providers fails at settings configuration.

Set `nanofaas.deployment.default-backend=containerd` for new managed functions.
The persisted catalog records the backend, so restore requires this provider to
be available again. The adapter lists only containers bearing NanoFaaS ownership
labels; a failed removal remains pending for retry. It does not sweep unrelated
containerd resources.

For registry-specific endpoints, mirrors or CA trust, set
`nanofaas.containerd.registry-hosts-directory` (canonical environment variable:
`NANOFAAS_CONTAINERD_REGISTRYHOSTSDIRECTORY`). The absolute directory is read by
the daemon in its own filesystem and forwarded by `containerd-java` 0.24.0 in
Transfer requests. Unset means the default resolver; authentication credentials
are not supplied by this setting.

See [rootless containerd deployment](../../../docs/deployment-containerd.md)
for paths, networking, image transfer, native builds, and operational limits.
