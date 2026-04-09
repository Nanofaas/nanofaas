# Control Plane Tooling Restructure Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Replace the current split between `scripts/` and `tooling/controlplane_tui/` with one coherent control-plane orchestration product that exposes a stable CLI/TUI for build, image creation, local run, test, VM/E2E orchestration, load generation, function selection, and `nanofaas-cli` validation.

**Architecture:** Build a single orchestration application with one domain model and one execution engine. Keep shell scripts only as compatibility wrappers during migration. The TUI becomes a frontend over the same use cases used by the non-interactive CLI, CI, and future automation.

**Tech Stack:** Python, Typer, Rich, existing profile TOML files, Gradle, Docker-compatible runtime, Ansible, SSH/SCP, k6, pytest.

---

## Recommendation

Work on branch `codex/ansible-vm-provisioning`.

Reason:

- it is currently the most integrated branch
- it already contains the merged pluggable deployment-provider work from `codex/issue-51-pluggable-deployment-providers`
- it already owns the VM/Ansible side, which is one of the main orchestration dimensions we want to unify

## Target End State

The repository should converge toward three roles:

### 1. Product code

One orchestration product under a single root, recommended target:

- `tools/controlplane/`

Suggested structure:

- `tools/controlplane/pyproject.toml`
- `tools/controlplane/src/controlplane_tool/domain/`
- `tools/controlplane/src/controlplane_tool/usecases/`
- `tools/controlplane/src/controlplane_tool/adapters/`
- `tools/controlplane/src/controlplane_tool/ui/cli.py`
- `tools/controlplane/src/controlplane_tool/ui/tui.py`
- `tools/controlplane/assets/`
- `tools/controlplane/tests/`
- `tools/controlplane/profiles/`

### 2. Operational assets

Infra assets that are real project operations, not just UI assets:

- `ops/ansible/`

Suggested structure:

- `ops/ansible/ansible.cfg`
- `ops/ansible/playbooks/`
- `ops/ansible/requirements.txt`

### 3. Compatibility entrypoints

Keep `scripts/` only for:

- thin wrappers
- legacy compatibility
- repo-wide one-off scripts that are not part of the control-plane orchestration product

## Unified UX Contract

The orchestration product should expose one non-interactive CLI contract first. The TUI must call the same use cases.

Suggested command surface:

```text
controlplane build
controlplane run
controlplane image
controlplane test
controlplane e2e
controlplane vm up
controlplane vm down
controlplane loadtest
controlplane cli-test
controlplane inspect
```

Common selectors:

- `--profile core|k8s|container-local|all`
- `--modules <csv>`
- `--functions <preset|csv|path>`
- `--load-profile <quick|smoke|stress|custom>`
- `--vm-lifecycle multipass|external`
- `--output json|text`
- `--non-interactive`

Rules:

- `--profile` is the default UX
- `--modules` is the escape hatch
- TUI stores and edits profiles, but does not invent separate execution semantics
- CI and scripts call the same non-interactive commands

## Milestone 1: Unify Build/Run/Image/Test Core

**Objective:** establish the single orchestration engine and unify only the control-plane build lifecycle: `build`, `run`, `image`, `test`, `print/inspect`.

**Why this is the right first milestone:**

- it is narrow enough to complete without destabilizing VM/E2E immediately
- it attacks the core structural problem: duplicated orchestration logic across shell, Gradle callsites, and TUI Python
- every later milestone can build on the same engine instead of adding more wrappers

**In scope:**

- choose the canonical root for the orchestration product
- move/rename `tooling/controlplane_tui` into the canonical root
- define the unified non-interactive CLI contract
- refactor current TUI internals to call use cases instead of assembling Gradle commands ad hoc
- introduce a compatibility wrapper for old entrypoints
- centralize `profile -> module selector -> Gradle invocation`
- centralize `build_mode -> bootJar/nativeCompile/bootBuildImage`

**Out of scope:**

- VM provisioning orchestration changes
- load testing orchestration changes
- function matrix/scenario orchestration
- `nanofaas-cli` integrated test flows
- CI migration of every caller

**Primary files/areas likely involved:**

- move: `tooling/controlplane_tui/**`
- add: `tools/controlplane/**` or `tooling/controlplane/**`
- modify: `scripts/controlplane-tool.sh`
- add: `scripts/control-plane-build.sh` as temporary compatibility wrapper
- modify: `README.md`
- modify: `docs/control-plane.md`
- modify: `docs/control-plane-modules.md`
- modify: `docs/quickstart.md`

**Acceptance criteria:**

- one canonical engine for `bootJar`, `bootBuildImage`, `nativeCompile`, `bootRun`, `:control-plane:test`
- one canonical mapping from profile/modules to Gradle arguments
- TUI and non-interactive CLI share the same underlying execution code
- old script entrypoints still work as wrappers
- no new orchestration logic added to shell

**Verification target:**

- tool tests under the new product root
- `./gradlew :control-plane:bootJar` through the new CLI
- `./gradlew :control-plane:bootBuildImage` through the new CLI
- `./gradlew :control-plane:test` through the new CLI
- existing TUI smoke/integration tests updated and green

## Milestone 2: Migrate Script and CI Build Call Sites

**Objective:** migrate existing build-related consumers to the unified non-interactive CLI.

**In scope:**

- `.github/workflows/gitops.yml`
- `scripts/build-push-images.sh`
- `scripts/release-manager/release.py`
- `scripts/native-build.sh`
- `scripts/test-control-plane-module-combinations.sh`
- `control-plane` buildpack E2E helper paths

**Out of scope:**

- full VM orchestration redesign
- load-testing redesign

**Acceptance criteria:**

- raw `-PcontrolPlaneModules=...` assembly disappears from the main build call sites
- main build consumers call one CLI/wrapper contract
- docs reference the unified build UX, not multiple competing recipes

## Milestone 3: VM and E2E Orchestration Consolidation

**Objective:** absorb VM lifecycle, SSH/Ansible provisioning, and E2E suite orchestration into the same control-plane tool.

**In scope:**

- model VM lifecycle and remote target configuration
- move Ansible operational assets to their long-term home
- unify `multipass` and `external` VM execution paths behind one adapter layer
- wrap current E2E suites under one scenario runner

**Primary areas:**

- `scripts/ansible/**`
- `scripts/lib/e2e-k3s-common.sh`
- `scripts/e2e-k8s-vm.sh`
- `scripts/e2e-k3s-helm.sh`
- `scripts/e2e-k3s-curl.sh`
- `scripts/e2e-cli*.sh`
- `scripts/e2e-all.sh`

**Acceptance criteria:**

- VM setup and remote sync are driven by the same tool model
- shell scripts are either wrappers or deleted
- one scenario definition can target local, multipass, or external VM backends

## Milestone 4: Function and Scenario Selection

**Objective:** make function selection first-class and parameterizable.

**In scope:**

- define presets for demo functions
- allow explicit function lists
- allow scenario files for registration/invocation/load composition
- normalize runtime kind and function matrix selection

**Acceptance criteria:**

- user can select functions without editing shell env vars
- E2E, demo, and loadtest flows share one scenario model
- the same scenario can be used in TUI and CLI

## Milestone 5: Load Generation and Metrics as First-Class Use Cases

**Objective:** integrate k6, metrics bootstrap, Prometheus queries, and reporting into the unified orchestration model.

**In scope:**

- move tool-specific loadtest assets under the canonical product root
- formalize load profiles
- connect metrics gates, report generation, and scenario metadata

**Acceptance criteria:**

- loadtest no longer feels like an extra script family
- profile, scenario, target environment, and metrics gate are described by one model
- TUI can configure and launch load scenarios without separate execution semantics

## Milestone 6: `nanofaas-cli` Integrated Test Flows

**Objective:** treat CLI validation as part of the orchestration product, not as separate manual scripting.

**In scope:**

- add `cli-test` use cases
- model deployment test modes for host-platform and remote CLI flows
- integrate CLI smoke/deploy flows with scenario selection and VM targets

**Acceptance criteria:**

- CLI validation can be run from the same orchestration product
- CLI tests can reuse the same environment/session state as E2E flows

## Milestone 7: Legacy Retirement and Documentation Cleanup

**Objective:** remove structural duplication once the new product is stable.

**In scope:**

- delete or minimize obsolete shell scripts
- keep only thin compatibility shims where necessary
- align `README.md`, `CLAUDE.md`, quickstart, testing docs, and internal docs
- document migration from old commands to the new UX

**Acceptance criteria:**

- `scripts/` no longer contains core control-plane orchestration logic
- docs show one primary UX
- new contributors can find the orchestration code in one place

## Risks and Controls

### Risk: tool rewrite without migration path

Control:

- keep wrappers during milestones 1-3
- migrate consumers incrementally

### Risk: TUI and CLI diverge again

Control:

- TUI must call the same use cases as CLI
- no direct Gradle/Docker/Ansible execution in TUI-specific code

### Risk: moving files too early causes churn

Control:

- move product code in milestone 1
- move operational assets in milestone 3
- keep shell wrappers stable until consumers are migrated

### Risk: CI and docs lag behind implementation

Control:

- milestone 2 and milestone 7 explicitly cover call-site and docs migration

## Recommended First Execution Slice

Start with Milestone 1 only.

Concrete first slice:

1. Choose the canonical root name for the orchestration product.
2. Move the current TUI package under that root.
3. Introduce the unified non-interactive CLI for `build`, `run`, `image`, `test`.
4. Rewire the TUI to call the same engine.
5. Keep `scripts/controlplane-tool.sh` and add a temporary build wrapper for compatibility.

This gives a real architectural base without prematurely dragging in all VM and loadtest complexity.
