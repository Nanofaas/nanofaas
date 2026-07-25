# nanolab Launcher Migration — Design

**Date:** 2026-07-25
**Status:** Approved for implementation planning

## Goal

Move the local dev launcher for the ops/provisioning tool out of the
nanofaas monorepo and into `miciav/nanolab`, now that `nanolab` (formerly
`controlplane-tool`) is published there (see
`docs/superpowers/specs/2026-07-24-ops-tooling-extraction-design.md` and
`docs/superpowers/plans/2026-07-24-ops-tooling-extraction.md`, both
complete). This is a narrow slice of the "next milestone" flagged in
[nanofaas#154](https://github.com/miciav/nanofaas/issues/154) — full
monorepo removal (deleting `tools/{controlplane,workflow-tasks,tui-toolkit}`)
is explicitly out of scope here, because `experiments/autoscaling.py` and
`experiments/lib/payload_corpora.py` still import `controlplane_tool` and
read `tools/controlplane/scenarios/payloads` directly. Decoupling
`experiments/` is deferred to a later, separate initiative.

## Current state

- `nanofaas/scripts/controlplane.sh` is a 12-line wrapper:
  `exec uv run --project tools/controlplane --locked controlplane-tool "$@"`.
- `nanofaas/scripts/tests/test_control_plane_build_wrapper_runtime.py` reads
  that script's content and asserts it fails fast when `uv` is missing.
- `tools/controlplane` is frozen (per #154) but still present and still
  imported by `experiments/autoscaling.py`
  (`from controlplane_tool.net_utils import pick_local_port`) and by
  `experiments/lib/payload_corpora.py` (path fallback
  `tools/controlplane/scenarios/payloads`). `experiments/e2e-memory-ab.sh`
  and `experiments/e2e-runtime-ab.sh` also shell out to
  `scripts/controlplane.sh e2e run helm-stack` directly.
- `nanofaas/scripts/tests/` additionally has two files
  (`test_e2e_runtime_contract.py`, `test_e2e_ansible_provisioning.py`) that
  assert on the content of `tools/workflow-tasks/.../ansible_assets/`
  playbooks — some of those assertions duplicate coverage nanolab's own
  test suite already has (added during the extraction), some do not.
- Around a dozen active docs (root `README.md`, `AGENTS.md`, `CLAUDE.md`,
  two `platform/modules/*/README.md`, and seven files under `docs/`)
  document `./scripts/controlplane.sh <args>` as the canonical invocation.
  Historical plan/spec docs under `docs/plans/` and `docs/superpowers/`
  also mention it but are point-in-time records and are not rewritten.

## Decisions and invariants

- `scripts/controlplane.sh` is deleted from nanofaas, not kept as a
  compatibility shim.
- The nanofaas↔nanolab boundary stays exactly as the extraction plan
  established: nanolab reads nanofaas source through `NANOFAAS_ROOT`; no new
  coupling is introduced in either direction.
- `tools/{controlplane,workflow-tasks,tui-toolkit}` are **not** deleted or
  modified in this initiative. `experiments/**` is **not** modified —
  `experiments/autoscaling.py`, `experiments/lib/payload_corpora.py`,
  `experiments/e2e-memory-ab.sh`, and `experiments/e2e-runtime-ab.sh` keep
  their existing (now-broken-once-the-script-is-deleted, for the last two)
  behavior; fixing that coupling is explicitly deferred.
- Test coverage that is unique (not duplicated in nanolab's own suite)
  moves to nanolab so it isn't silently lost. Test coverage that already
  exists in nanolab, or that isn't about controlplane-tool/nanolab at all,
  is left alone or deleted in place — nothing is moved "just in case."
- Historical docs under `docs/plans/`, `docs/superpowers/plans/`, and
  `docs/superpowers/specs/` are not edited.

## nanolab repo changes

- Add `nanolab.sh` at the repository root: a pass-through wrapper,
  structurally identical to the old `controlplane.sh` (checks `uv` is
  installed, then `exec uv run --locked --package nanolab nanolab "$@"`).
  It does not touch `NANOFAAS_ROOT` — validation of that variable already
  lives in `nanolab.workspace.paths.default_tool_paths()`.
- Add a test for `nanolab.sh` (content assertions equivalent to the deleted
  `test_control_plane_build_wrapper_runtime.py`: fails fast, mentions `uv`).
- Document `./nanolab.sh <args>` in the nanolab README as the canonical
  local invocation, alongside the existing `uv run --package nanolab
  nanolab ...` form.
- Add three test assertions (in `packages/workflow-tasks/tests/infra/`,
  alongside the existing playbook-content tests) carrying forward coverage
  that exists only in nanofaas today:
  - `provision-k3s.yml` resolves the k3s release dynamically from the
    GitHub API rather than a pinned version.
  - `provision-base.yml` and `ensure-registry.yml` keep their idempotence
    guards (Helm version string construction, registry-port check).
  - `provision-base.yml` installs `uv`.

## nanofaas repo changes

- Delete `scripts/controlplane.sh` and
  `scripts/tests/test_control_plane_build_wrapper_runtime.py`.
- Trim `scripts/tests/test_e2e_runtime_contract.py`: remove
  `test_ansible_playbooks_exist_for_vm_provisioning` and the unused
  `_tool_src`/`_ensure_tool_src_on_path` helpers (redundant with nanolab's
  own ansible-asset tests). Keep the legacy-deletion guards and the
  Helm-template test — neither is about controlplane-tool.
- Trim `scripts/tests/test_e2e_ansible_provisioning.py`: remove
  `test_ansible_layout_exists_for_vm_provisioning` and
  `test_k6_playbook_installs_k6_for_vm_loadtests` (redundant with nanolab).
  Remove `test_k3s_provisioning_resolves_latest_release_dynamically`,
  `test_ansible_playbooks_preserve_idempotence_guards_for_helm_and_registry`,
  and `test_base_playbook_installs_uv_for_controlplane_wrapper` (moved to
  nanolab, see above — deleted here so the assertion isn't checked twice
  against what is now frozen, unchanging source). Keep
  `test_e2e_k3s_common_is_deleted_ansible_helpers_live_in_python`.
- `scripts/tests/test_e2e_k3s_common_external_ssh_mode.py` and
  `scripts/tests/test_e2e_k3s_common_deleted_vm_recovery.py` are unchanged
  — they only guard that a long-deleted shell file stays deleted and do not
  reference `scripts/controlplane.sh` or exercise migrated code.
- Update the invocation examples in `README.md`, `AGENTS.md`, `CLAUDE.md`,
  `platform/modules/k8s-deployment-provider/README.md`,
  `platform/modules/container-deployment-provider/README.md`, and
  `docs/{control-plane,quickstart,no-k8s-profile,tutorial-function,
  nanofaas-cli,testing,e2e-tutorial}.md`: each `./scripts/controlplane.sh
  <args>` example becomes the nanolab-checkout equivalent (documented once
  per file, e.g. `(cd ../nanolab && NANOFAAS_ROOT=$(pwd)/.. ./nanolab.sh
  <args>)` or an equivalent copy-pasteable form — exact phrasing is an
  implementation detail, not a design constraint).

## Testing

- nanolab: run the full workspace gate (`pytest` ×3, ruff, basedpyright ×3,
  import-linter ×3) after adding `nanolab.sh`, its test, and the three
  carried-forward ansible-content assertions.
- nanofaas: run `uv run --project tools/controlplane pytest
  scripts/tests -q` (the dormant local suite scripts/tests belongs to) after
  trimming, confirming the remaining tests still pass and the deleted ones
  are gone, not just skipped.
- Manual smoke: from a nanolab checkout with `NANOFAAS_ROOT` pointing at
  the nanofaas checkout, `./nanolab.sh --help` and one real subcommand
  (e.g. `./nanolab.sh plan ...`) succeed.

## Non-goals

- Deleting `tools/{controlplane,workflow-tasks,tui-toolkit}` from nanofaas.
- Fixing `experiments/`'s coupling to `tools/controlplane` /
  `scripts/controlplane.sh`.
- Any change to GitHub Actions — `gitops.yml` never invoked
  `controlplane-tool` or `scripts/controlplane.sh`, so nothing there needs
  rewiring.
