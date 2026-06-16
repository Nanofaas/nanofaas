# Repo Reorganization — Heavy Phases Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the flat repo root a role-based hierarchy — group the helm/k8s delivery assets under `deploy/`, the five language SDKs under `sdks/`, and the runtime services under `platform/` — without breaking the Gradle multi-project build or the Python e2e tooling.

**Architecture:** Each phase is an **independent PR** that physically moves a set of directories with `git mv` (preserving history) and then updates **every** tracked reference to the old paths. Because nanofaas has no CI e2e coverage, the verification gate for each phase is: full `./gradlew build` green **and** the full controlplane + workflow-tasks Python suites green. Frozen historical snapshots under `experiments/control-plane-staging/versions/**/snapshot/**` are **never** touched.

**Tech Stack:** Git, Gradle multi-project (`settings.gradle` with a dynamic `control-plane-modules/` scanner), Java 21, Python `uv` (controlplane_tool + workflow_tasks), Go modules, npm `file:` deps, Helm, Docker multi-stage builds.

---

## Phase 0 (DONE — context)

Already committed on branch `chore/repo-reorg-phase0` (commit `854c7429`):
deleted tracked `arch_report/`, removed untracked `MagicMock/` + `recovery/`, gitignored `MagicMock/`. No source/build paths touched. The phases below build on this.

## Cross-cutting rules (read before any phase)

- **History:** always `git mv`, never delete+recreate.
- **Exclude frozen snapshots:** any path under `experiments/control-plane-staging/versions/` is a decompiled/recovered snapshot with its own self-contained `build.gradle`/`k8s/`/`helm/`. Do **not** edit those — they reference their *own* nested copies, not the root dirs.
- **Docs/plans under `docs/plans/` and `docs/superpowers/plans/`** are historical records. Update *living* docs (`docs/quickstart.md`, `docs/nanofaas-cli.md`, `docs/feature-roadmap.md`, `README.md`, `CLAUDE.md`, `AGENTS.md`) but leave dated historical plan files untouched unless a path appears in a *runnable* test assertion.
- **Docker build context:** several example Dockerfiles `COPY function-sdk-* ...` assuming the build context is the **repo root**. Moving an SDK under `sdks/` changes the in-context path. Each affected Dockerfile COPY source must change too (the build context root does not change — only the source path within it).
- **Authoritative enumeration:** each phase starts by re-running the listed `git grep` so nothing drifted since this plan was written. The hit lists below are the known sites as of 2026-06-14.
- **One PR per phase.** Re-run `npx gitnexus analyze` after merge (the index goes stale on moves).

---

## Phase 1: `helm/` + `k8s/` → `deploy/`

**Goal:** Move delivery assets under a single `deploy/` parent. The chart *directory names* stay (`deploy/helm/nanofaas`, etc.); only the parent prefix changes.

**Decision already taken by the user:** the CLI `--chart` default changes from `helm/nanofaas` to `deploy/helm/nanofaas` (user-facing behavior change, accepted).

**Files:**
- Move: `helm/` → `deploy/helm/`, `k8s/` → `deploy/k8s/`
- Modify (production): `build.gradle`, `nanofaas-cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/platform/PlatformInstallCommand.java`, `scripts/release-manager/release.py`, `tools/controlplane/src/controlplane_tool/e2e/k3s_curl_runner.py`, `tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py`, `tools/workflow-tasks/src/workflow_tasks/components/helm.py`, `tools/workflow-tasks/src/workflow_tasks/components/namespace.py`
- Modify (tests): `control-plane/src/test/java/.../IssueCoverageTest.java`, `control-plane-modules/k8s-deployment-provider/src/test/java/.../K8sE2eDeploymentSpecTest.java`, `nanofaas-cli/src/test/java/.../PlatformCommandTest.java`, and the controlplane/workflow-tasks Python tests asserting `helm/nanofaas*` strings (`test_helm_ops.py`, `test_scenario_tasks.py`, `test_helm_stack_workflow.py`, `test_k3s_junit_curl_workflow.py`, `test_proxmox_prelude_workflow.py`, `test_cli_stack_workflow.py`, `test_e2e_runner.py`, `test_scenario_component_library.py`, `tools/workflow-tasks/tests/components/test_platform_commands.py`)
- Modify (living docs): `docs/quickstart.md`, `docs/nanofaas-cli.md`, `docs/feature-roadmap.md`

- [ ] **Step 1: Branch from main (post Phase 0 merge)**

```bash
git checkout main && git pull
git checkout -b chore/reorg-phase1-deploy
```

- [ ] **Step 2: Capture the authoritative reference list**

Run and save output — this is the edit checklist:
```bash
git grep -nE 'helm/nanofaas(-runtime|-namespace)?' -- ':!experiments/control-plane-staging/**' ':!docs/plans/**' ':!docs/superpowers/plans/**' > /tmp/helm_refs.txt
git grep -nE 'k8s/[a-z0-9-]+\.yaml' -- ':!experiments/control-plane-staging/**' ':!docs/plans/**' ':!docs/superpowers/plans/**' > /tmp/k8s_refs.txt
wc -l /tmp/helm_refs.txt /tmp/k8s_refs.txt
```
Expected: ~30 helm lines + ~12 k8s lines (excluding frozen snapshots and historical plans).

- [ ] **Step 3: Physically move the directories**

```bash
mkdir -p deploy
git mv helm deploy/helm
git mv k8s deploy/k8s
```

- [ ] **Step 4: Update `build.gradle` chart-validation paths**

In `build.gradle`, replace the five `helm/nanofaas/...` literals (lines ~79–103) with `deploy/helm/nanofaas/...`:
```groovy
def chartFile = file('deploy/helm/nanofaas/Chart.yaml')
def valuesFile = file('deploy/helm/nanofaas/values.yaml')
// ...and the GradleException/error strings that mention "helm/nanofaas"
```

- [ ] **Step 5: Update the CLI default + its test**

`PlatformInstallCommand.java`:
```java
@Option(names = {"--chart"}, defaultValue = "deploy/helm/nanofaas", description = "Chart reference/path.")
```
`PlatformCommandTest.java` line ~49: change the expected argv element `"helm/nanofaas"` → `"deploy/helm/nanofaas"`.

- [ ] **Step 6: Update the two Java test path literals**

`K8sE2eDeploymentSpecTest.java`: `"helm/nanofaas"` → `"deploy/helm/nanofaas"`, `"helm/nanofaas-runtime"` → `"deploy/helm/nanofaas-runtime"`.
`IssueCoverageTest.java` lines 36–40: each `root.resolve("k8s/<file>.yaml")` → `root.resolve("deploy/k8s/<file>.yaml")`.

- [ ] **Step 7: Update `release.py` version-bump targets**

`scripts/release-manager/release.py` lines ~237–242: the tuple keys `"helm/nanofaas/Chart.yaml"`, `"helm/nanofaas/values.yaml"`, `"k8s/control-plane-deployment.yaml"` → prefix each with `deploy/`.

- [ ] **Step 8: Update the e2e Python tooling (production)**

`tools/controlplane/src/controlplane_tool/e2e/k3s_curl_runner.py`: `chart="helm/nanofaas"` → `"deploy/helm/nanofaas"`, `chart="helm/nanofaas-runtime"` → `"deploy/helm/nanofaas-runtime"`.
`tools/controlplane/src/controlplane_tool/scenario/scenario_tasks.py`: `"helm/nanofaas-namespace"` → `"deploy/helm/nanofaas-namespace"`.
`tools/workflow-tasks/src/workflow_tasks/components/helm.py`: `"helm/nanofaas"` → `"deploy/helm/nanofaas"`, `"helm/nanofaas-runtime"` → `"deploy/helm/nanofaas-runtime"`.
`tools/workflow-tasks/src/workflow_tasks/components/namespace.py`: `"helm/nanofaas-namespace"` → `"deploy/helm/nanofaas-namespace"`.

- [ ] **Step 9: Update Python test assertions**

For every hit in `/tmp/helm_refs.txt` under `tools/**/tests/**`, replace the `helm/nanofaas*` substring with `deploy/helm/nanofaas*`. Note the remote-path variant in `test_cli_stack_workflow.py` (`/home/ubuntu/nanofaas/helm/nanofaas` → `/home/ubuntu/nanofaas/deploy/helm/nanofaas`) and the `"/repo/helm/nanofaas"` variants in `test_platform_commands.py` (workflow-tasks) and `docs/superpowers/plans/2026-05-29-*` (the latter is a historical plan — **skip**). Apply the same to the `"/repo/helm/nanofaas"` assertion only where it lives in a runnable test (`tools/workflow-tasks/tests/components/test_platform_commands.py`).

- [ ] **Step 10: Update living docs**

`docs/quickstart.md` (the five `kubectl apply -f k8s/...` lines → `deploy/k8s/...`), `docs/nanofaas-cli.md` (`chart: helm/nanofaas` → `deploy/helm/nanofaas`), `docs/feature-roadmap.md` (the `k8s/...yaml` and `kubectl apply -f k8s/` references → `deploy/k8s/...`).

- [ ] **Step 11: Verify no stale references remain**

```bash
git grep -nE '(^|[^/a-zA-Z._-])(helm|k8s)/nanofaas|[^/a-zA-Z._-]k8s/[a-z0-9-]+\.yaml' -- ':!deploy/**' ':!experiments/control-plane-staging/**' ':!docs/plans/**' ':!docs/superpowers/plans/**'
```
Expected: no output (every live reference now points under `deploy/`).

- [ ] **Step 12: Build + test gate**

```bash
./gradlew build
uv run --project tools/controlplane pytest tools/controlplane/tests
uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests
```
Expected: all green. The Java `IssueCoverageTest`, `K8sE2eDeploymentSpecTest`, `PlatformCommandTest` exercise the moved paths; the Python helm/scenario tests exercise the chart strings.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "refactor: move helm/ and k8s/ under deploy/

Group delivery assets under a single deploy/ parent. CLI --chart default
becomes deploy/helm/nanofaas (user-facing path change). All gradle, CLI,
release, e2e-tooling and test references repointed.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

- [ ] **Step 14: Re-index** — `npx gitnexus analyze` after merge.

---

## Phase 2: `function-sdk-*` → `sdks/`

**Goal:** Group the five SDKs under `sdks/` with short internal names: `sdks/java`, `sdks/java-lite`, `sdks/go`, `sdks/python`, `sdks/javascript`.

**Risk note:** This is the trickiest move because of **relative `file:`/`replace` paths** in examples and **Docker build-context COPY paths**. The examples sit at `examples/<lang>/<name>/` and reach the SDK via `../../../function-sdk-go`. After the move the target is `../../../sdks/go`, so the relative depth stays 3 but the tail changes.

**Files:**
- Move: `function-sdk-java` → `sdks/java`, `function-sdk-java-lite` → `sdks/java-lite`, `function-sdk-go` → `sdks/go`, `function-sdk-python` → `sdks/python`, `function-sdk-javascript` → `sdks/javascript`
- Modify (gradle): `settings.gradle` (lines 23, 27 — the two java SDK includes), every `examples/java/*/build.gradle` (`project(':function-sdk-java')` → `project(':sdks:java')`), `examples/java/*/settings.gradle.docker`
- Modify (go): `examples/go/*/go.mod` (`require`/`replace`), `examples/go/*/main.go` + `main_test.go` import paths, `examples/go/*/Dockerfile`
- Modify (js): `examples/javascript/*/package.json`, `package-lock.json`, `Dockerfile`
- Modify (python): `.github/workflows/gitops.yml` (`cd function-sdk-python`), any `pyproject`/CI referencing it
- Modify (tooling): the image-build matrix in `tools/workflow-tasks/src/workflow_tasks/components/images.py` and any `tools/controlplane/assets` referencing SDK build contexts
- Modify (gradle project paths): Gradle project coordinates change `:function-sdk-java` → `:sdks:java` and `:function-sdk-java-lite` → `:sdks:java-lite`; every dependent `project(':function-sdk-java*')` must change too

- [ ] **Step 1: Branch**

```bash
git checkout main && git pull
git checkout -b chore/reorg-phase2-sdks
```

- [ ] **Step 2: Authoritative reference list**

```bash
git grep -nE 'function-sdk-(java|java-lite|go|python|javascript)' -- ':!experiments/control-plane-staging/**' ':!function-sdk-*/**' ':!docs/plans/**' ':!docs/superpowers/plans/**' > /tmp/sdk_refs.txt
grep -nE ':function-sdk-java' settings.gradle
```

- [ ] **Step 3: Move directories**

```bash
mkdir -p sdks
git mv function-sdk-java       sdks/java
git mv function-sdk-java-lite  sdks/java-lite
git mv function-sdk-go         sdks/go
git mv function-sdk-python     sdks/python
git mv function-sdk-javascript sdks/javascript
```

- [ ] **Step 4: Gradle includes + project coordinates**

`settings.gradle`:
```groovy
include(':sdks:java');        project(':sdks:java').projectDir = file('sdks/java')
include(':sdks:java-lite');   project(':sdks:java-lite').projectDir = file('sdks/java-lite')
```
(Replace the old `include('function-sdk-java')` / `include('function-sdk-java-lite')` lines. The explicit `projectDir` keeps the Gradle path `:sdks:java` while the folder is `sdks/java`.)

Then in every `examples/java/*/build.gradle`:
```groovy
implementation project(':sdks:java')       // was ':function-sdk-java'
implementation project(':sdks:java-lite')  // was ':function-sdk-java-lite'
```
And `examples/java/*/settings.gradle.docker`: `include('function-sdk-java-lite')` → `include(':sdks:java-lite')` with matching `projectDir`.

- [ ] **Step 5: Go modules + imports + Dockerfiles**

For each `examples/go/*`:
- `go.mod`: `replace github.com/miciav/nanofaas/function-sdk-go => ../../../sdks/go` (module import path **keeps** `function-sdk-go` only if the Go module name is unchanged — see note). 
- **Note:** the Go module's own name is declared in `sdks/go/go.mod` (`module github.com/miciav/nanofaas/function-sdk-go`). Decide: keep the module name (least churn — only the `replace` path changes, imports stay `.../function-sdk-go/nanofaas`) **or** rename the module to `.../sdks/go`. Recommended: **keep the module name**, change only the `replace` right-hand side. Then `main.go`/`main_test.go` imports stay untouched.
- `Dockerfile`: `COPY function-sdk-go /src/function-sdk-go` → `COPY sdks/go /src/function-sdk-go` (build context is repo root; only the source path changes).

- [ ] **Step 6: JavaScript `file:` deps + Dockerfiles**

For each `examples/javascript/*`:
- `package.json`: `"nanofaas-function-sdk": "file:../../../sdks/javascript"` and the `prebuild`/`pretest` `npm --prefix ../../../sdks/javascript ...`.
- `package-lock.json`: regenerate via `npm install` rather than hand-editing the resolved paths.
- `Dockerfile`: `COPY function-sdk-javascript ./function-sdk-javascript` → `COPY sdks/javascript ./function-sdk-javascript` (and matching `--from` COPY).

- [ ] **Step 7: CI + tooling**

`.github/workflows/gitops.yml`: `cd function-sdk-python` → `cd sdks/python`.
`tools/workflow-tasks/src/workflow_tasks/components/images.py` and any image-matrix asset: repoint SDK build-context paths; update the corresponding test expectations.

- [ ] **Step 8: Verify no stale references**

```bash
git grep -nE 'function-sdk-(java|java-lite|python|javascript)/' -- ':!experiments/control-plane-staging/**' ':!sdks/**' ':!docs/plans/**' ':!docs/superpowers/plans/**'
```
Expected: only Go `function-sdk-go` module-name occurrences (intentionally kept), nothing else.

- [ ] **Step 9: Build + test gate**

```bash
./gradlew build
(cd examples/go/word-stats && go build ./... && go test ./...)
(cd examples/javascript/word-stats && npm install && npm test)
uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests
```
Expected: all green.

- [ ] **Step 10: Commit + re-index** (`npx gitnexus analyze`).

---

## Phase 3: `platform/` (runtime services)

**Goal:** Group the runtime services: `platform/common`, `platform/control-plane`, `platform/function-runtime`, and `platform/modules` (renamed from `control-plane-modules`). `nanofaas-cli` is a *client*, not a service — move it to `clients/cli` in this same PR (small, related).

**Risk note:** This is the highest-churn move. `settings.gradle` has a **dynamic scanner** that walks `control-plane-modules/` at configuration time (`modulesRootDir = new File(settingsDir, 'control-plane-modules')`). That scanner path must change to `platform/modules`. Also `scripts/controlplane.sh` and the entire controlplane_tool assume `tools/controlplane` and `:control-plane` gradle coordinates; the gradle **project coordinate** `:control-plane` can be preserved via explicit `projectDir` even though the folder moves.

**Files:**
- Move: `common` → `platform/common`, `control-plane` → `platform/control-plane`, `function-runtime` → `platform/function-runtime`, `control-plane-modules` → `platform/modules`, `nanofaas-cli` → `clients/cli`
- Modify: `settings.gradle` (all includes + the `modulesRootDir` scanner + `projectDir` overrides to keep coordinates `:common`, `:control-plane`, `:function-runtime`, `:nanofaas-cli`, `:control-plane-modules:*`), `build.gradle` (any `project(':...')` or `file('control-plane/...')` paths), `scripts/controlplane.sh`, gradle `:control-plane`/`:function-runtime` references across tooling, CI workflows, living docs (`CLAUDE.md`, `AGENTS.md`, `README.md`).

- [ ] **Step 1: Branch**

```bash
git checkout main && git pull
git checkout -b chore/reorg-phase3-platform
```

- [ ] **Step 2: Decide coordinate strategy (CRITICAL)**

To avoid touching hundreds of `:control-plane` / `:function-runtime` gradle references in tooling, **keep the Gradle project coordinates unchanged** and only move the folders, using explicit `projectDir`. Verify the assumption first:
```bash
git grep -nE ":(control-plane|function-runtime|common|nanofaas-cli)([:'\" ]|$)" -- ':!experiments/**' | wc -l
git grep -nE 'tools/controlplane|:control-plane\b' scripts/controlplane.sh
```
If the count is large (expected), the keep-coordinates strategy is justified.

- [ ] **Step 3: Move directories**

```bash
mkdir -p platform clients
git mv common              platform/common
git mv control-plane       platform/control-plane
git mv function-runtime    platform/function-runtime
git mv control-plane-modules platform/modules
git mv nanofaas-cli        clients/cli
```

- [ ] **Step 4: Rewrite `settings.gradle`**

Keep coordinates, point `projectDir` at new folders:
```groovy
include('common');            project(':common').projectDir = file('platform/common')
include('control-plane');     project(':control-plane').projectDir = file('platform/control-plane')
include('function-runtime');  project(':function-runtime').projectDir = file('platform/function-runtime')
include('nanofaas-cli');      project(':nanofaas-cli').projectDir = file('clients/cli')
```
And the module scanner:
```groovy
def modulesRootDir = new File(settingsDir, 'platform/modules')
// keep ":control-plane-modules:${dir.name}" coordinate; set projectDir = dir (already absolute from eachDir)
```
The examples-java includes (`examples:java:*`) are unaffected by this phase.

- [ ] **Step 5: Update `build.gradle` file() paths**

Any `file('control-plane/...')`, `file('common/...')`, etc. in the root `build.gradle` → `file('platform/control-plane/...')`. (The helm path was already repointed in Phase 1.)

- [ ] **Step 6: Update `scripts/controlplane.sh` and tooling assumptions**

Audit `scripts/controlplane.sh` for hardcoded `control-plane/`, `function-runtime/`, `common/` filesystem paths (gradle coordinates `:control-plane` are unchanged, so most invocations keep working). Repoint any `file`-system path (e.g. reading `control-plane/src/main/resources/application.yml`).

- [ ] **Step 7: Update living docs**

`CLAUDE.md`, `AGENTS.md`, `README.md`: update the architecture section paths (`control-plane/`, `control-plane-modules/`, `function-runtime/`, `common/`, `nanofaas-cli/`) to their new homes. Do **not** rewrite historical `docs/plans/*`.

- [ ] **Step 8: Verify + gate**

```bash
git grep -nE "(^|[\"' (])(control-plane|function-runtime|common|nanofaas-cli|control-plane-modules)/" -- ':!platform/**' ':!clients/**' ':!experiments/**' ':!docs/plans/**' ':!docs/superpowers/plans/**'
./gradlew build
uv run --project tools/controlplane pytest tools/controlplane/tests
```
Expected: grep shows only intended residue (e.g. gradle coordinates, not filesystem paths); build + tests green.

- [ ] **Step 9: Commit + re-index** (`npx gitnexus analyze`).

---

## Phase 4 — DROPPED (user decision 2026-06-14)

Originally proposed `examples/` + `experiments/` → `testing/` and `python-runtime` → `runtimes/`. **Cancelled:**
- `examples/` stays at the repo root — it is **product code**, not test material: real function implementations using each SDK, and the destination directory for `fn-init` scaffolding. Burying it under `testing/` would be semantically wrong.
- `experiments/` stays as-is — it will be **reimplemented inside `tools/controlplane`**, so moving it now is wasted churn.
- `python-runtime` (deprecated) — left at root for now (no longer worth an isolated phase on its own).

No Phase 4 work to do. The reorg is considered complete at Phase 3.

---

## Self-Review checklist (run before executing each phase)

1. **Re-run the phase's `git grep`** — the hit lists in this plan are dated 2026-06-14; confirm nothing drifted.
2. **Frozen snapshots excluded?** Confirm no edit touched `experiments/control-plane-staging/versions/**`.
3. **Gradle coordinates preserved?** Phases 2–3 rely on keeping `:control-plane`, `:sdks:java`, etc. stable via explicit `projectDir`; verify `./gradlew projects` lists the expected coordinates.
4. **Docker build context** repointed for every moved SDK COPY (Phase 2).
5. **Verification gate is green output, not assumption** — paste the `./gradlew build` + pytest tail before claiming a phase done.
