# Sub-5/6-b1-ii — Repoint release.py at the `images` command + align image naming (Design)

**Status:** approved (design), pending implementation plan
**Date:** 2026-05-31
**Roadmap:** second/final slice of consolidating the three image-build implementations
(after b1-i landed `controlplane-tool images` and deleted `build-push-images.sh` +
`scripts/image-builder/`). This slice retires the **third** copy — the embedded build
matrix in `scripts/release-manager/release.py::build_and_push_arm64` — and resolves the
`exec-*`/`bash-*` naming inconsistency it surfaced.

## Problem

`release.py::build_and_push_arm64(version)` (~120 LOC) is a third hand-maintained copy
of the OCI build+push matrix (arm64-only, `v{version}-arm64` tags). It duplicates the
catalog now owned by `controlplane-tool images`. It also names the bash demo images
`exec-*`, whereas the deployed artifacts (`helm/nanofaas/values.yaml`,
`examples/bash/*/function.yaml`, `tools/controlplane/tests/test_function_catalog.py`)
and the `images` command use `bash-*`. The CI release pipeline (`.github/workflows/
gitops.yml`) also builds `exec-*`. So three places build `exec-*` while everything that
consumes the images uses `bash-*`.

## Goal

1. Replace `build_and_push_arm64`'s duplicated matrix with a single call to
   `scripts/controlplane.sh images`.
2. Make `bash-*` the one canonical name for the bash demo images everywhere — fix the
   CI pipeline to match.

## Design

### 1. `release.py::build_and_push_arm64`
Replace the per-target build/push body with one canonical invocation:
```
./scripts/controlplane.sh images --arch arm64 --arch-suffix --tag v{version}
```
This builds+pushes all 16 targets as `ghcr.io/miciav/nanofaas/{name}:v{version}-arm64`
(arm64 platform, `-arm64` suffix, the arm64 tiny-builder overrides already encoded in
the `images` command). Run it through the existing `run_with_disk_retry` wrapper.

**Smoke tests retained:** after the build call, keep the two
`smoke_test_service_image(...)` checks for `control-plane` and `function-runtime`,
reconstructing their refs locally (`{REGISTRY}/{GH_OWNER}/{GH_REPO}/control-plane:
v{version}-arm64` and `.../function-runtime:v{version}-arm64`). The control-plane smoke
test keeps its `allowed_error_patterns` (kubernetesClient bean / KubernetesClient
exception). Note: smoke tests now run **after** the command pushes (the original gated
push on smoke); this best-effort post-build validation is an accepted simplification.

**Helper cleanup:** `resolve_native_image_build_args`, `resolve_native_active_processors`,
`run_with_disk_retry`, `prune_docker_build_caches` in release.py are kept only if still
referenced after the rewrite; remove any that become unused. `smoke_test_service_image`,
`run_command`, `try_command`, `get_current_version` stay (used by smoke + the rest of the
release flow). The `REGISTRY`/`GH_OWNER`/`GH_REPO` constants stay (used to build the
smoke refs).

**Accepted behavior change:** control-plane arm64 now builds via the `images` command's
bare `:control-plane:bootBuildImage` task (no `--profile all` module selection) —
consistent with b1-i's deferred profile-aware flow.

### 2. CI naming alignment (`.github/workflows/gitops.yml`)
The two steps "Build and push Exec Word Stats" / "Build and push Exec JSON Transform"
build from `examples/bash/{word-stats,json-transform}/Dockerfile` but tag `exec-*`.
Rename the tags to `bash-word-stats` / `bash-json-transform` (context/Dockerfile
unchanged). After this, CI + release + helm/examples/function-catalog all use `bash-*`.

### 3. Obsolete test cleanup
`scripts/tests/test_release_manager_native_args.py` asserts release.py contains the old
inline build logic (`resolve_native_image_build_args`, `./scripts/controlplane.sh image
--profile all --`). After the rewrite those strings are gone, so the test is obsolete —
delete it (the native-args behavior now lives in `controlplane-tool images`, covered by
`tools/controlplane/tests/test_image_matrix.py`). Check
`scripts/tests/test_release_manager_javascript_sdk.py` — it should be about the npm/JS
SDK flow (untouched here); leave it unless it references the deleted arm64 build logic.

## Testing / verification

- `release.py` is an interactive standalone wizard; verification is structural + dry-run:
  - The arm64 path now contains `./scripts/controlplane.sh images --arch arm64`.
  - `grep` shows NO `exec-word-stats`/`exec-json-transform` anywhere live (gitops.yml now
    `bash-*`); `bash-*` present in gitops.yml.
  - `python -m py_compile scripts/release-manager/release.py` (or `ruff check`) passes.
  - The controlplane suite stays green (no controlplane code changes here, but
    `test_function_catalog.py` already expects `bash-*` — confirm still green).
- Note `scripts/tests/` is NOT in the main CI (`gitops` runs `pytest tests/` in
  `function-sdk-python/`); the release-manager test edits are hygiene.

## Out of scope

- The remaining band-B sibling scripts (`native-build.sh`, `experiments/k6/run-all.sh`).
- The 4 sub-5a-orphaned `scripts/tests/` wrapper tests (separate debt).
- Re-introducing the profile-aware control-plane image flow (a later follow-up for the
  `images` command, applies to both the CLI and release).
- release.py's semver/git/PR/npm logic — untouched.

## Success criteria

- `release.py::build_and_push_arm64` calls `scripts/controlplane.sh images` and no longer
  contains a per-target build/push matrix; the 2 smoke tests remain.
- `gitops.yml` builds `bash-word-stats`/`bash-json-transform`; no live `exec-*` demo image
  references remain.
- `test_release_manager_native_args.py` removed; release.py compiles; controlplane suite green.
