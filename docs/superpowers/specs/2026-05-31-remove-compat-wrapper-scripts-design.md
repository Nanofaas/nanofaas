# Sub-5/6-a — Remove bash compatibility-wrapper scripts (Design)

**Status:** approved (design), pending implementation plan
**Date:** 2026-05-31
**Roadmap:** first slice of the bash-elimination effort (sub-projects 5/6 of the
workflow_tasks component-library roadmap). "One way to do things" — collapse the
duplicate alias scripts onto the single canonical launcher `scripts/controlplane.sh`.

## Context / landscape

The repo has 39 `.sh` files. Most are **out of scope** for bash elimination:
- `watchdog/*` — a separate component's own build/test scripts.
- `examples/bash/*` — example FaaS handlers (product, not tooling).
- `python-runtime/build.sh` — deprecated runtime's build.

The in-scope workflow scripts split into three bands:
- **A — compatibility wrappers** (this sub-project): ~9 five-line scripts that
  only `exec scripts/controlplane.sh <subcommand> "$@"`.
- **B — scripts with real logic** (later): `build-push-images.sh`,
  `native-build.sh`, `e2e-loadtest.sh`, `experiments/k6/run-all.sh`,
  `experiments/run.sh`.
- **C — large experiment scripts** (final phase): `e2e-runtime-ab.sh`,
  `e2e-memory-ab.sh`, `e2e-cold-start-metrics.sh`, `e2e-memory-ab-batch.sh`,
  `e2e-runtime-config.sh`.

`scripts/controlplane.sh` (`uv run --project tools/controlplane controlplane-tool "$@"`)
and `scripts/fn-init.sh` are the legitimate launchers — **they stay**. CI
(`.github/workflows/gitops.yml`) already invokes `controlplane.sh`, not the wrappers.

## Goal

Delete the 9 compatibility-wrapper scripts and repoint every consumer to
`scripts/controlplane.sh <subcommand>`, so there is exactly one way to invoke each
workflow.

## Scripts to delete (all pure `exec controlplane.sh ...` aliases)

| Wrapper | Equivalent |
|---|---|
| `scripts/e2e.sh` | `controlplane.sh e2e run docker` |
| `scripts/e2e-all.sh` | `controlplane.sh e2e all` |
| `scripts/e2e-k3s-junit-curl.sh` | `controlplane.sh e2e run k3s-junit-curl` |
| `scripts/e2e-k3s-helm.sh` | `controlplane.sh e2e run helm-stack` |
| `scripts/e2e-container-local.sh` | `controlplane.sh e2e run container-local` |
| `scripts/e2e-buildpack.sh` | `controlplane.sh e2e run buildpack` |
| `scripts/control-plane-build.sh` | `controlplane.sh "$@"` (passthrough) |
| `scripts/control-plane-building.sh` | `controlplane.sh "$@"` (passthrough) |
| `scripts/controlplane-tool.sh` | `controlplane.sh tui` |

## Consumers to repoint first (then delete)

1. **`build.gradle:70`** — the `k8sE2e` Gradle task runs
   `commandLine 'bash', 'scripts/e2e-k3s-junit-curl.sh'`. Change to
   `commandLine 'bash', 'scripts/controlplane.sh', 'e2e', 'run', 'k3s-junit-curl'`.
   (`./gradlew k8sE2e` must keep working.)
2. **Fascia-C experiment scripts** (stay until the final phase, only this line
   changes): `experiments/e2e-memory-ab.sh:205` and `experiments/e2e-runtime-ab.sh:128`
   call `bash "${PROJECT_ROOT}/scripts/e2e-k3s-helm.sh"` → change to
   `bash "${PROJECT_ROOT}/scripts/controlplane.sh" e2e run helm-stack`.
3. **Docs** — update every reference to a deleted wrapper to the
   `scripts/controlplane.sh <subcommand>` form:
   - `GEMINI.md` (lines ~59-61)
   - `AGENTS.md` (lines ~22-23, 35)
   - `docs/testing.md` (multiple: ~189, 245-321, 465-466)
   - `tooling/controlplane_tui/README.md` (lines ~33-35: `controlplane-tool.sh` → `controlplane.sh tui`)
   - `CLAUDE.md` — check/keep consistent (it already uses `controlplane.sh`).

   Snapshot/archived docs under
   `experiments/control-plane-staging/versions/.../snapshot/.../README.md` are
   historical artifacts — **do not edit** (frozen snapshots).

## Out of scope

- Any script with real logic (band B) and the experiment scripts (band C).
- `scripts/controlplane.sh`, `scripts/fn-init.sh` (the launchers — stay).
- watchdog / examples / python-runtime scripts.
- Editing frozen snapshot READMEs under `experiments/control-plane-staging/versions/`.

## Testing / verification

- `grep -rn` for each deleted wrapper basename across the repo (excluding
  `experiments/control-plane-staging/versions/` snapshots, `node_modules`, `.git`)
  → no live references remain.
- `./gradlew k8sE2e --dry-run` (or at least `./gradlew help --task k8sE2e`) resolves;
  the task's `commandLine` now points at `controlplane.sh`.
- `bash -n` (syntax check) on the two edited experiment scripts.
- `scripts/controlplane.sh --help` still works (sanity that the launcher is intact).

## Success criteria

- The 9 wrapper files are gone; `controlplane.sh` + `fn-init.sh` remain.
- `build.gradle`, the two experiment scripts, and all live docs reference
  `controlplane.sh`.
- No live reference to any deleted wrapper basename remains.
