# Image releases

Official NanoFaaS images are built, benchmarked, and published by
`controlplane-tool release` from a controlled Azure run. **Azure is the only
publishing path**: local, Multipass, and Proxmox builds are non-publishing
experiments, and GitHub Actions only runs tests (enforced by
`scripts/tests/test_release_authority.py`).

Design: `docs/plans/2026-07-16-azure-image-release-design.md`.

## The 52-cell matrix

`controlplane-tool images` owns the logical matrix: 21 targets × 2
architectures, with JVM/native flavors for the five Spring targets and
native-only for the three Java Lite functions — 52 cells total, split into 42
Dockerfile/Buildx-Bake cells and 10 Spring-native Gradle cells.

```bash
# Inspect without building or publishing (any machine)
controlplane-tool images plan --version v0.18.0 --arch all --flavor all

# Portable non-publishing build (cannot reach GHCR release tags)
controlplane-tool images build --version v0.18.0 --arch amd64 \
  --environment environments/multipass.yaml
```

## Tag policy

- Immutable architecture tags: `v0.18.0-amd64-native`, `v0.18.0-arm64-jvm`,
  `v0.18.0-amd64` (unflavored), …
- Multi-architecture flavor manifests: `v0.18.0-native`, `v0.18.0-jvm`, and
  `v0.18.0` for unflavored images.
- `v0.18.0` on flavored images is an alias of the native manifest, changed
  last. `latest` is not part of the release contract and is never updated by
  this flow.

Publication order is architecture tags → verified manifests → aliases, so an
interrupted release never exposes a partially updated alias.

## Preparing a version

```bash
controlplane-tool release prepare v0.18.0
# review the diff, run tests, commit the version change
```

`release prepare` rewrites the curated version locations (Gradle, Helm, k8s
deployment, watchdog Cargo, Python SDK locks, fn-init default, CLI assertion)
and regenerates lockfiles. The release run refuses an unprepared or dirty
tree.

## Running the paid Azure release

Credentials are file-based and must be private (mode `0600`, outside the
repository); secret values never appear on the command line or in logs.

```bash
controlplane-tool release plan v0.18.0 \
  --environment tools/controlplane/environments/azure-release.yaml

controlplane-tool release run v0.18.0 \
  --environment tools/controlplane/environments/azure-release.yaml \
  --ghcr-token-file /secure/ghcr-token \
  --cosign-key-file /secure/cosign.key \
  --cosign-password-file /secure/cosign-password \
  --provision
```

The pinned profile is `Standard_D8s_v5` (stack, 128 GB) plus `Standard_D2s_v5`
(loadgen, 30 GB) in West Europe with an exact Ubuntu URN. Expect several hours
of VM time; the two VMs are dedicated to the release and torn down at the end
unless `--keep` is passed.

### Phase order

1. `source-tests` — full source test suite on the stack VM from a
   checksum-verified `git archive` of the guarded commit.
2. `amd64-build` — full AMD64 matrix (Bake + Gradle native) on a named
   Buildx builder bounded by `build.maxParallelism`.
3. `local-registry-push` — candidates go to the stack-local registry only.
4. `benchmark-1/2/3` — three k6 load tests deploy the exact AMD64-native
   candidates by digest.
5. `aggregate` + `regression-gate` — per-metric medians compared against the
   newest identical-profile record in `docs/performance/releases/`
   (thresholds in `tools/controlplane/release.yaml`).
6. `arm64-build` + `arm64-smoke` — QEMU (digest-pinned binfmt) ARM64 builds
   plus functional smoke; QEMU results are functional evidence only, never
   performance data.
7. `publish-architectures` → `publish-manifests` → `publish-aliases` —
   `skopeo copy --preserve-digests` to GHCR, digest-verified, then manifests
   (exactly AMD64+ARM64), then aliases.
8. `attest` — SPDX SBOMs (pinned syft) and cosign signatures/attestations
   (pinned cosign); the signing key and password reach the VM only for this
   phase and are always deleted.
9. `finalize` — only after verification: write
   `docs/performance/releases/<version>.json`, regenerate
   `docs/performance/history.md`, append the final journal record.

### Runs, reports, and the journal

Everything lives under `runs/releases/<version>/`: benchmark run directories
(`run-1..3`), aggregate and gate decisions, the generated Bake files, and the
append-only journal under `state/`. The journal records each phase with
digest-bearing evidence and is the local audit record — cleanup never deletes
it.

### Resume and recovery

```bash
controlplane-tool release run v0.18.0 ... --resume
```

`--resume` re-verifies every completed phase against the journal: same commit,
prepared version, config and environment digests, and every referenced
artifact must still match its recorded digest. Anything missing or changed
invalidates that phase and everything downstream, which reruns from the
earliest invalid phase. A mere success flag is never trusted. Immutable
architecture uploads are safe to repeat; aliases move only after manifests
verify, and a failed finalization retries without rebuilding verified images.

### Cleanup

Remote credential files are deleted by always-run cleanup (success or
failure). Azure resources are torn down unless `--keep` was explicit. The
local journal and run reports remain.

## Performance claims

Published performance records describe only the pinned Azure AMD64-native
profile (`azure-d8s-v5+d2s-v5-amd64-native-loadtest-v1`). ARM64 images are
functionally smoke-tested under QEMU; no ARM64 performance is measured or
claimed.
