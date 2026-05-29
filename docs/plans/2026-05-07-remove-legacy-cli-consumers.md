# Remove Legacy CLI Consumers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use `superpowers:executing-plans` to implement this plan task-by-task. Follow `@superpowers:test-driven-development` for every code change and keep commits small.

**Goal:** Remove the legacy CLI VM path and its external wrappers so `cli-stack` is the only supported VM-backed CLI validation route.

**Architecture:** Delete compatibility shims instead of preserving aliases. The canonical surface stays `scripts/controlplane.sh cli-test run cli-stack` for VM-backed CLI validation, plus `host-platform` and `deploy-host` if they remain distinct workflows. The old `vm` route, the `CliVmRunner` shim, and the `scripts/e2e-cli*.sh` wrappers are treated as dead code and removed together so the repo has one CLI validation path per use case, not overlapping names for the same execution model.

**Tech Stack:** Bash, Python, `pytest`, Typer, existing `tools/controlplane` CLI orchestration modules, Markdown docs.

---

## Scope and assumptions

This plan assumes we are removing all external consumers that still depend on the old VM runner surface:

- `scripts/e2e-cli.sh`
- `scripts/e2e-cli-host-platform.sh`
- `scripts/e2e-cli-deploy-host.sh`

It also assumes `cli-test run vm` is no longer a supported public route and that `CliVmRunner` can be deleted once the canonical `cli-stack` path covers the VM-backed CLI workflow.

Keep `cli-stack`, `host-platform`, and `deploy-host` only if they remain distinct supported workflows. Do not keep compatibility aliases for the removed legacy paths.

> **Breaking change notice:** the deletions in this plan are public-surface breaking. Before starting Task 1, confirm with the user that no internal CI workflow, ad-hoc operator script, or external doc still invokes `scripts/e2e-cli*.sh` or `scripts/controlplane.sh cli-test run vm`. Once Task 2 lands, those callers fail with no fallback.

## Non-goals

- no new compatibility shim for `vm`
- no new wrapper script replacing the deleted ones
- no change to the `cli-stack` execution semantics beyond making it the sole VM-backed CLI path
- no broad refactor of unrelated E2E runners

---

### Task 0: Pre-flight verification of the blast radius

**Files:** none (read-only)

**Step 1: Confirm no missed consumers**

Before touching any test or implementation file, dimension the actual surface to be removed and confirm the per-task file lists below are complete. This catches consumers the plan may not have anticipated (CI workflows, ad-hoc scripts, docs of third parties pinned in-tree).

Run:

```bash
rg -n "CliVmRunner|cli-test run vm|e2e-cli\.sh|e2e-cli-host-platform\.sh|e2e-cli-deploy-host\.sh" \
  README.md docs scripts tools/controlplane .github
```

Expected: matches limited to the files already enumerated in Tasks 1–5. Any hit outside that set must be added to the relevant task's "Files" list before continuing.

**Step 2: Confirm the upstream impact of `CliVmRunner`**

Run:

```text
gitnexus_impact({target: "CliVmRunner", direction: "upstream"})
gitnexus_impact({target: "CliTestRunner.plan", direction: "upstream"})
```

Cross-check the returned d=1 callers against the file list in Task 3. If any caller is missing, add it before proceeding. Do not start Task 1 if HIGH/CRITICAL warnings are returned without acknowledgement from the user.

**Step 3: No commit**

This task produces no diff; it only validates the plan. Proceed to Task 1.

---

### Task 1: Lock down the removal with failing tests

**Files:**
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`
- Modify: `tools/controlplane/tests/test_cli_test_catalog.py`
- Modify: `tools/controlplane/tests/test_cli_test_commands.py`
- Modify: `tools/controlplane/tests/test_main_entrypoint.py`
- Modify: `tools/controlplane/tests/test_cli_smoke.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing tests**

Add tests that describe the desired end state before touching implementation:

- the `scripts/e2e-cli*.sh` files do not exist
- `controlplane-tool --help` does not advertise a `vm` CLI-test route
- `controlplane_tool.cli.runtime` no longer exposes `CliVmRunner`
- `cli-test` catalogs no longer list `vm` as a supported scenario
- docs no longer mention `scripts/e2e-cli.sh` or `scripts/controlplane.sh cli-test run vm`
- the TUI no longer offers a legacy `vm` runner choice

Use explicit absence assertions so the failures are obvious and localize the missing cleanup.

**Step 2: Run the tests and verify they fail**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  scripts/tests/test_legacy_wrappers_contract.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_main_entrypoint.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_docs_links.py -q
```

Expected: FAIL because the wrapper scripts, `vm` route, and documentation references still exist.

**Step 3: Commit the red tests**

```bash
git add scripts/tests/test_legacy_wrappers_contract.py tools/controlplane/tests/test_cli_runtime.py tools/controlplane/tests/test_cli_test_catalog.py tools/controlplane/tests/test_cli_test_commands.py tools/controlplane/tests/test_main_entrypoint.py tools/controlplane/tests/test_cli_smoke.py tools/controlplane/tests/test_docs_links.py
git commit -m "Add legacy CLI removal regression tests"
```

### Task 2: Delete the external wrapper scripts and update shell-contract tests

**Files:**
- Delete: `scripts/e2e-cli.sh`
- Delete: `scripts/e2e-cli-host-platform.sh`
- Delete: `scripts/e2e-cli-deploy-host.sh`
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`
- Modify: `scripts/tests/test_controlplane_e2e_wrapper_runtime.py` *(added by Task 0: tests wrapper scripts by executing them)*
- Modify: `scripts/tests/test_cli_test_wrapper_runtime.py` *(added by Task 0: tests route mapping of wrapper scripts)*
- Modify: `README.md`
- Modify: `tools/controlplane/README.md` *(added by Task 0: mentions `cli-test run vm`)*
- Modify: `docs/testing.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `docs/tutorial-function.md`

**Step 1: Write the minimal implementation**

Remove the wrapper scripts outright. Update the shell-contract test so it checks that the deleted wrapper files are absent rather than preserved as compatibility shims.

Rewrite the docs so they point at the canonical direct commands:

- `scripts/controlplane.sh cli-test run cli-stack`
- `scripts/controlplane.sh cli-test run host-platform`
- `scripts/controlplane.sh cli-test run deploy-host`

Remove any wording that claims `e2e-cli*.sh` remain available.

**Step 2: Run the targeted tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  scripts/tests/test_legacy_wrappers_contract.py \
  tools/controlplane/tests/test_docs_links.py -q
```

Expected: PASS once the wrapper scripts are deleted and docs assertions match the new canonical surface.

**Step 3: Commit the shell cleanup**

```bash
git add scripts/e2e-cli.sh scripts/e2e-cli-host-platform.sh scripts/e2e-cli-deploy-host.sh scripts/tests/test_legacy_wrappers_contract.py README.md docs/testing.md docs/nanofaas-cli.md docs/tutorial-function.md
git commit -m "Remove legacy CLI wrapper scripts"
```

### Task 3: Remove the Python `vm` runner surface

**Files:**
- Delete: `tools/controlplane/src/controlplane_tool/cli_validation/cli_vm_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/runtime.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/test_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_validation/cli_test_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_validation/cli_test_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_validation/cli_test_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`

> Cross-reference the d=1 callers from Task 0 step 2; if any extra file is reported, add it here before editing.

**Step 1: Write the minimal implementation**

Remove `CliVmRunner` and every import/export that points to it.

Then remove the public `vm` scenario from `cli-test` and the TUI:

- delete the `vm` catalog entry
- delete the `vm` branch in `CliTestRunner.plan()`
- delete the `vm` branch in `scenario_flows.py`
- remove the `vm` menu option from the TUI
- remove `CliVmRunner` from `cli.runtime`

If `host-platform` and `deploy-host` are still valid product surfaces, keep them and do not collapse them into `cli-stack`. The goal is removal of the dead `vm` path, not a broad semantic rewrite of every remaining CLI validation mode.

**Step 2: Run the targeted tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_scenario_flows.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_main_entrypoint.py -q
```

Expected: PASS once the legacy `vm` path is gone and the canonical paths still work.

**Step 3: Commit the Python cleanup**

```bash
git add tools/controlplane/src/controlplane_tool/cli_validation/cli_vm_runner.py tools/controlplane/src/controlplane_tool/cli/runtime.py tools/controlplane/src/controlplane_tool/cli_validation/cli_test_catalog.py tools/controlplane/src/controlplane_tool/cli_validation/cli_test_models.py tools/controlplane/src/controlplane_tool/cli_validation/cli_test_runner.py tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py tools/controlplane/src/controlplane_tool/tui/app.py tools/controlplane/src/controlplane_tool/cli/test_commands.py
git commit -m "Remove legacy vm CLI runner"
```

### Task 4: Clean up the command help and package contract

**Files:**
- Modify: `tools/controlplane/tests/test_package_layout.py`
- Modify: `tools/controlplane/tests/test_main_entrypoint.py`
- Modify: `tools/controlplane/tests/test_cli_smoke.py`
- Modify: `tools/controlplane/tests/test_canonical_entrypoints.py`
- Modify: `tools/controlplane/tests/test_milestone_gates.py`
- Modify: `tools/controlplane/tests/test_legacy_wrappers_contract.py`

**Step 1: Write the minimal implementation**

Update the help- and contract-level tests to assert the new steady state:

- `vm` is gone from the public CLI surface
- the deleted wrapper scripts are gone from the repo
- the remaining canonical entrypoints are still present
- package layout tests no longer import `CliVmRunner`
- milestone gates no longer describe the removed path as pending migration

**Step 2: Run the targeted tests**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_package_layout.py \
  tools/controlplane/tests/test_main_entrypoint.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_canonical_entrypoints.py \
  tools/controlplane/tests/test_milestone_gates.py \
  scripts/tests/test_legacy_wrappers_contract.py -q
```

Expected: PASS with no remaining `vm` help text, wrapper expectations, or migration placeholders.

**Step 3: Commit the contract cleanup**

```bash
git add tools/controlplane/tests/test_package_layout.py tools/controlplane/tests/test_main_entrypoint.py tools/controlplane/tests/test_cli_smoke.py tools/controlplane/tests/test_canonical_entrypoints.py tools/controlplane/tests/test_milestone_gates.py scripts/tests/test_legacy_wrappers_contract.py
git commit -m "Clean up legacy CLI contract tests"
```

### Task 5: Final verification and documentation sweep

**Files:**
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `docs/tutorial-function.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Run the full verification suite**

Run:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane pytest -q
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane controlplane-quality
```

Expected: all green, with no mentions of `CliVmRunner`, `scripts/e2e-cli.sh`, or `cli-test run vm` in the supported docs/tests.

**Step 2: Run a repository-wide grep check**

Run:

```bash
rg -n "CliVmRunner|e2e-cli\.sh|e2e-cli-host-platform\.sh|e2e-cli-deploy-host\.sh|cli-test run vm" \
  README.md docs scripts tools/controlplane .github
```

Expected: no matches outside historical plan documents, if those remain intentionally archived.

Then confirm the GitNexus index reflects the new state:

```text
gitnexus_detect_changes({scope: "all"})
```

Expected: only the files touched by Tasks 2–5 appear; no unexpected symbols mutated.

**Step 3: Commit the final sweep**

```bash
git add README.md docs/testing.md docs/nanofaas-cli.md docs/tutorial-function.md tools/controlplane/tests/test_docs_links.py
git commit -m "Remove legacy CLI consumer references"
```
