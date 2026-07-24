# Ops Tooling Extraction — Design

**Date:** 2026-07-24
**Status:** Draft (brainstorming)

## Goal

Move the Python ops tooling out of the `nanofaas` monorepo into a new,
dedicated GitHub repository. Target end state: the new repo owns **all** the
build/test/release/e2e/loadtest orchestration; `nanofaas` keeps only the
platform (Java control-plane, services, SDKs) plus `fn-init`, and the tool
operates on nanofaas **from the outside**.

The three co-dependent Python packages move together:

- `workflow-tasks` — the Workflow-of-Tasks execution engine (reusable library;
  a new version is already in progress).
- `tui-toolkit` — Rich-based TUI components.
- `controlplane-tool` — the nanofaas orchestration CLI + TUI (to be
  **renamed** — see Open Decisions).

## Current state

- Monorepo `nanofaas` contains `tools/{workflow-tasks, tui-toolkit,
  controlplane, fn-init}` as plain, git-tracked directories (not submodules).
- Dependency chain: `controlplane-tool` → `workflow-tasks` + `tui-toolkit` →
  (`azure-vm-sdk`, `multipass-sdk`, `proxmox-sdk`, `shellcraft`).
- The VM SDKs are already separate `miciav/*` GitHub repos, consumed by
  `workflow-tasks` as `git+https@rev` deps.
- `controlplane-tool` consumes `workflow-tasks` + `tui-toolkit` via **local
  path** deps (`[tool.uv.sources] path = "../workflow-tasks"`, editable).
- Size: `controlplane-tool` ≈ 9.3k LOC src + 62 test files.
- `miciav/{workflow-tasks, tui-toolkit, controlplane-tool}` do **not** exist on
  GitHub yet.
- Precedent: `workflow-tasks` and `tui-toolkit` were previously made
  standalone-*shaped* (own `pyproject`) inside the monorepo (PRs #84, tui PRs);
  this is the first true out-of-monorepo extraction.

## Decomposition (phased)

The migration is too large for one plan. Three sub-projects, each its own
spec → plan → implementation cycle:

### Phase 1 — Clean extraction *(this spec's scope)*

Create the new repo as a **uv workspace** containing the three packages, with
**git history preserved** (via `git filter-repo`, following the workflow-tasks
extraction precedent), that **builds, tests, lints, and type-checks
standalone**. Rename `controlplane-tool`. `nanofaas` stays untouched and fully
functional — nothing is removed from it yet, so no tooling breaks.

**Tasks:**
1. Decide the new name + repo name (see Open Decisions) — blocks the rest.
2. `git filter-repo` a copy of `tools/{controlplane, workflow-tasks,
   tui-toolkit}` (history for those paths only) into a new repo working tree.
3. Lay out the new repo as a uv workspace: root `pyproject`/`uv.workspace` with
   the three members; internal path deps (`controlplane → workflow-tasks,
   tui-toolkit`) stay path deps within the workspace; `workflow-tasks` keeps
   its `git+https` VM-SDK deps unchanged.
4. Apply the rename to `controlplane-tool` (package `controlplane_tool` →
   new name, `[project.scripts]` entry points, imports, the
   import-linter contract, `scripts/controlplane.sh`-style launchers that come
   along, test references).
5. Make the full gate green standalone: `uv run pytest` for all three members
   (controlplane ≈ 624 tests, workflow-tasks ≈ its suite, tui-toolkit),
   `ruff`, `basedpyright`, the import-linter contracts.
6. Add minimal CI to the new repo (GitHub Actions running the gate).
7. Create the GitHub repo (private), push with preserved history.

**Explicitly deferred to later phases:** any change to `nanofaas` itself; the
tool learning to operate on an external nanofaas checkout; consuming the new
repo from nanofaas.

### Phase 2 — Source access from outside

Teach the tool to operate on a nanofaas checkout it does **not** live inside:
it takes/clones a nanofaas ref and works on that (the "release from an
immutable ref" idea, A4 in the release-on-workflow-engine spec). Removes the
tool's baked-in assumption that it sits at `nanofaas/tools/...`. Surface the
nanofaas-specific knowledge (image matrix targets, function families,
`ghcr.io/miciav/nanofaas`, k6 scripts, scenarios) as configuration/inputs
rather than hard-coded co-located paths.

### Phase 3 — Monorepo cleanup

Remove `tools/{controlplane, workflow-tasks, tui-toolkit}` from `nanofaas`;
repoint its CI (`gitops.yml`), `scripts/controlplane.sh`, and e2e/release entry
points to consume the new repo (git dep or installed CLI). The final cut.

## Open decisions

1. **The new name** for `controlplane-tool` and the repo. "controlplane-tool"
   is misleading — nanofaas already has a *control-plane* (the Java pod); the
   tool is the ops/release orchestrator, not that. To brainstorm together.
   Candidate directions: orchestration (maestro/conductor/orchestryx),
   provisioning/ops (opsforge/opsmith/flightdeck), or Workflow-of-Tasks-derived.
2. **Repo shape:** single uv workspace with three members (recommended, keeps
   the internal path deps working) vs. later splitting `workflow-tasks` /
   `tui-toolkit` into their own repos too. Phase 1 keeps them co-located.
3. **Phase 2 source-access mechanism** — deferred, designed in its own spec.

## Non-goals (Phase 1)

- No changes to the `nanofaas` monorepo.
- No rewiring of nanofaas CI/scripts.
- No change to the tool's behavior or the release contract — pure relocation +
  rename + standalone-buildability.
