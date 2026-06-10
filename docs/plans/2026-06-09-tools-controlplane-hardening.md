# Tools Controlplane Hardening Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make `tools/controlplane` pass its quality gate again, fix the confirmed container-local error-reporting bug, and only then address simplifications with isolated, behavior-preserving commits.

**Architecture:** Execute this in two mandatory phases and one optional phase. Phase 1 restores static quality with minimal edits. Phase 2 fixes the confirmed runtime bug with a regression test. Phase 3 is optional cleanup/refactor work and must not be mixed into the bugfix commits.

**Tech Stack:** Python 3.11+, uv, pytest, ruff, basedpyright, import-linter, Typer, Textual, httpx, GitNexus impact analysis.

---

## Scope Decision

Complete these first:

1. Fix the 9 current `ruff` diagnostics.
2. Fix the `ContainerLocalE2eRunner` delete-verification message bug.
3. Run the full `tools/controlplane` quality gate.

Stop there if the objective is to repair correctness and CI quickly.

Only after that, decide whether to continue with:

4. Replacing repeated `curl` subprocess calls in `container_local_runner.py`.
5. Splitting oversized modules such as `tui/app.py`, `cli/e2e_commands.py`, and `scenario/loadtest_adapter.py`.

Do not combine mandatory fixes and structural refactors in the same commit.

---

## Pre-Flight

### Task 0: Establish baseline and safety checks

**Files:**
- Read: `tools/controlplane/pyproject.toml`
- Read: `tools/controlplane/README.md`
- Read: `tools/controlplane/CONVENTIONS.md`
- Read: `tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py`
- Read: `tools/controlplane/tests/test_cli_runtime.py`
- Read: `tools/controlplane/diagnose_proxmox_ssh.py`

**Step 1: Create or switch to the work branch**

Run from repository root:

```bash
git switch -c codex/tools-controlplane-hardening
```

If the branch already exists:

```bash
git switch codex/tools-controlplane-hardening
```

Expected: work happens on a dedicated branch.

**Step 2: Check the worktree**

Run:

```bash
git status --short
```

Expected: only known user changes or plan files are present. Do not revert unrelated changes.

**Step 3: Run current baseline from `tools/controlplane`**

Run:

```bash
cd tools/controlplane
uv run pytest
uv run lint-imports
uv run basedpyright
uv run ruff check .
uv run controlplane-quality
```

Expected baseline from the previous analysis:

- `uv run pytest`: passes, previously `1188 passed`.
- `uv run lint-imports`: passes, all 5 contracts kept.
- `uv run basedpyright`: passes with 0 errors.
- `uv run ruff check .`: fails with 9 diagnostics.
- `uv run controlplane-quality`: fails because the `ruff` check fails.

**Step 4: GitNexus impact rule**

Before editing any function, class, or method, run the GitNexus MCP impact tool when available:

```text
gitnexus_impact({target: "<symbol>", direction: "upstream"})
```

If the index is reported stale, run:

```bash
npx gitnexus analyze
```

If GitNexus cannot resolve a Python script or module-level symbol, record that limitation and use local dependency checks as fallback:

```bash
rg "<symbol_or_module_name>" tools/controlplane tools/workflow-tasks
```

If impact is HIGH or CRITICAL, stop and report the blast radius before editing.

---

## Phase 1: Restore the quality gate

### Task 1: Remove unused imports in tests

**Files:**
- Modify: `tools/controlplane/tests/tasks/test_loadtest_tasks.py`
- Modify: `tools/controlplane/tests/test_ansible_playbooks.py`
- Modify: `tools/controlplane/tests/test_azure_vm_loadtest_components.py`
- Modify: `tools/controlplane/tests/test_proxmox_vm_request.py`
- Modify: `tools/controlplane/tests/test_registry_runtime.py`

**Step 1: Confirm RED lint state**

Run:

```bash
cd tools/controlplane
uv run ruff check tests/tasks/test_loadtest_tasks.py tests/test_ansible_playbooks.py tests/test_azure_vm_loadtest_components.py tests/test_proxmox_vm_request.py tests/test_registry_runtime.py
```

Expected: FAIL with only unused import diagnostics:

- `F401 pytest` in `tests/tasks/test_loadtest_tasks.py`
- `F401 Path` in `tests/test_ansible_playbooks.py`
- `F401 patch` and `F401 pytest` in `tests/test_azure_vm_loadtest_components.py`
- `F401 pytest` in `tests/test_proxmox_vm_request.py`
- `F401 Path` in `tests/test_registry_runtime.py`

**Step 2: Minimal GREEN edit**

Remove only the unused imports. Do not reformat unrelated code.

**Step 3: Verify lint slice**

Run:

```bash
cd tools/controlplane
uv run ruff check tests/tasks/test_loadtest_tasks.py tests/test_ansible_playbooks.py tests/test_azure_vm_loadtest_components.py tests/test_proxmox_vm_request.py tests/test_registry_runtime.py
```

Expected: PASS.

**Step 4: Verify affected tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/tasks/test_loadtest_tasks.py tests/test_ansible_playbooks.py tests/test_azure_vm_loadtest_components.py tests/test_proxmox_vm_request.py tests/test_registry_runtime.py
```

Expected: PASS.

**Step 5: Commit**

Run from repository root:

```bash
git add tools/controlplane/tests/tasks/test_loadtest_tasks.py tools/controlplane/tests/test_ansible_playbooks.py tools/controlplane/tests/test_azure_vm_loadtest_components.py tools/controlplane/tests/test_proxmox_vm_request.py tools/controlplane/tests/test_registry_runtime.py
git commit -m "Fix tools controlplane test lint"
```

### Task 2: Fix `diagnose_proxmox_ssh.py` lint surface

**Files:**
- Modify: `tools/controlplane/diagnose_proxmox_ssh.py`
- Modify: `tools/controlplane/pyproject.toml`

**Decision:**

Treat `diagnose_proxmox_ssh.py` as a maintained diagnostic script, but not as production library code. The lowest-risk fix is:

- fix the placeholder-free f-string directly;
- add a targeted `SLF001` per-file ignore for this diagnostic script;
- do not introduce new public APIs in `workflow_tasks` just to support a debug script.

This matches the existing project style: `pyproject.toml` already has targeted `SLF001` ignores for files that intentionally inspect private orchestration internals.

**Step 1: Confirm RED lint state**

Run:

```bash
cd tools/controlplane
uv run ruff check diagnose_proxmox_ssh.py
```

Expected: FAIL with:

- `SLF001` for `_routing_manager`
- `F541` for f-string without placeholders
- `SLF001` for `_execute_step`

**Step 2: Minimal GREEN edit**

In `tools/controlplane/diagnose_proxmox_ssh.py`, replace the placeholder-free f-string with a normal string.

In `tools/controlplane/pyproject.toml`, add this entry under the existing `[tool.ruff.lint.per-file-ignores]` table:

```toml
"diagnose_proxmox_ssh.py" = ["SLF001"]
```

Do not add a global ignore. Do not ignore `F541`.

**Step 3: Verify diagnostic lint**

Run:

```bash
cd tools/controlplane
uv run ruff check diagnose_proxmox_ssh.py
```

Expected: PASS.

**Step 4: Verify full lint and quality**

Run:

```bash
cd tools/controlplane
uv run ruff check .
uv run controlplane-quality
```

Expected: PASS.

**Step 5: Commit**

Run from repository root:

```bash
git add tools/controlplane/diagnose_proxmox_ssh.py tools/controlplane/pyproject.toml
git commit -m "Fix tools controlplane diagnostic lint"
```

---

## Phase 2: Fix confirmed container-local bug

### Task 3: Add regression test for actual delete status

**Files:**
- Modify: `tools/controlplane/tests/test_cli_runtime.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py`

**Bug:**

`ContainerLocalE2eRunner.run()` stores the post-delete HTTP status in `http_status`, but the error message uses `{status}`. In this file `status` is the imported `workflow_tasks.status` helper, so failures report the wrong value and hide the actual HTTP response code.

**Step 1: Run impact**

Use GitNexus MCP:

```text
gitnexus_impact({target: "ContainerLocalE2eRunner", direction: "upstream"})
```

Fallback if GitNexus cannot resolve the Python class:

```bash
rg "ContainerLocalE2eRunner|container_local_runner" tools/controlplane/src tools/controlplane/tests
```

Expected direct dependents from local search include:

- `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py`
- `tools/controlplane/tests/test_cli_runtime.py`
- `tools/controlplane/tests/test_milestone_gates.py`
- `tools/controlplane/tests/test_scenario_flows.py`

If GitNexus reports HIGH or CRITICAL, stop and report before editing.

**Step 2: Add a focused failing test**

In `tools/controlplane/tests/test_cli_runtime.py`, add a test next to `test_container_local_runner_emits_balanced_top_level_phase_events_and_verify_children`.

The test should reuse the same monkeypatch pattern, but make `httpx.get` return status `500` after cleanup verification.

Expected assertion:

```python
with pytest.raises(RuntimeError, match="Expected 404 after function delete, got 500"):
    runner.run()
```

Keep the fake `subprocess.run`, fake process, fake waiters, and fake cleanup local to this test or extract a small test helper only if it removes obvious duplication.

**Step 3: Confirm RED**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_cli_runtime.py::test_container_local_runner_reports_actual_delete_http_status -q
```

Expected: FAIL because the exception message does not contain `500`.

**Step 4: Minimal GREEN edit**

In `tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py`, change:

```python
f"Expected 404 after function delete, got {status}"
```

to:

```python
f"Expected 404 after function delete, got {http_status}"
```

Do not refactor the surrounding workflow in this commit.

**Step 5: Verify targeted tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_cli_runtime.py::test_container_local_runner_reports_actual_delete_http_status -q
uv run pytest tests/test_cli_runtime.py::test_container_local_runner_emits_balanced_top_level_phase_events_and_verify_children -q
```

Expected: PASS.

**Step 6: Verify related slice**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_cli_runtime.py tests/test_scenario_flows.py tests/test_milestone_gates.py -q
uv run ruff check src/controlplane_tool/e2e/container_local_runner.py tests/test_cli_runtime.py
uv run basedpyright
```

Expected: PASS.

**Step 7: Commit**

Run from repository root:

```bash
git add tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py tools/controlplane/tests/test_cli_runtime.py
git commit -m "Report container-local delete status correctly"
```

---

## Mandatory Final Verification

### Task 4: Prove mandatory fixes are complete

**Files:**
- All files changed in Tasks 1-3

**Step 1: Run full tools/controlplane checks**

Run:

```bash
cd tools/controlplane
uv run pytest
uv run ruff check .
uv run basedpyright
uv run lint-imports
uv run controlplane-quality
```

Expected: all pass.

**Step 2: Run script-level smoke checks**

Run from repository root:

```bash
./scripts/controlplane.sh functions show-preset
```

Expected: command completes without traceback.

Run:

```bash
./scripts/controlplane.sh e2e list
```

Expected: command completes without traceback.

**Step 3: Detect change scope**

Use GitNexus MCP if available:

```text
gitnexus_detect_changes()
```

Fallback:

```bash
git diff --stat
git diff --check
```

Expected:

- changed files match Tasks 1-3;
- no whitespace errors;
- no unexpected module coupling.

**Step 4: Stop point**

If all checks pass, stop and report:

- exact files changed;
- exact verification commands and outcomes;
- whether GitNexus impact/change detection succeeded or had Python-index limitations.

Do not start optional refactors unless explicitly approved.

---

## Optional Phase 3: Simplification and refactor work

Run this phase only after mandatory fixes are green.

### Task 5: Replace repeated container-local curl calls

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`

**Step 1: Characterize current behavior**

Add or extend tests in `tools/controlplane/tests/test_cli_runtime.py` to pin:

- register request payload;
- invoke request payload;
- scale request payload;
- delete request behavior;
- failure messages for non-success HTTP status.

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_cli_runtime.py -q
```

Expected: PASS before refactor.

**Step 2: Extract one private helper**

Add a small private HTTP helper inside `ContainerLocalE2eRunner` or a package-local helper near it. Keep behavior equivalent. Do not introduce a broad API client unless several call sites genuinely benefit.

**Step 3: Replace one subprocess call at a time**

After each replacement, run:

```bash
cd tools/controlplane
uv run pytest tests/test_cli_runtime.py -q
```

Expected: PASS after each small edit.

**Step 4: Verify and commit**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_cli_runtime.py tests/test_scenario_flows.py tests/test_milestone_gates.py -q
uv run ruff check src/controlplane_tool/e2e/container_local_runner.py tests/test_cli_runtime.py
uv run basedpyright
```

Expected: PASS.

Commit:

```bash
git add tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py tools/controlplane/tests/test_cli_runtime.py
git commit -m "Simplify container-local API calls"
```

### Task 6: Extract pure TUI selection helpers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Possible create: `tools/controlplane/src/controlplane_tool/tui/menu_models.py`
- Possible create: `tools/controlplane/src/controlplane_tool/tui/selection_flows.py`
- Modify only if needed: `tools/controlplane/tests/test_tui_choices.py`
- Modify only if needed: `tools/controlplane/tests/test_tui_selection.py`

**Rule:**

Do not rewrite `NanofaasTUI`. Extract only pure helpers already covered by tests. Keep the original import surface stable when tests or callers import names from `app.py`.

**Step 1: Impact and baseline**

Use GitNexus MCP:

```text
gitnexus_impact({target: "NanofaasTUI", direction: "upstream"})
```

Then run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py tests/test_tui_selection.py -q
```

Expected: PASS.

**Step 2: Move one helper group**

Move one cohesive group at a time:

- menu choice model helpers;
- selection source helpers;
- saved profile choice helpers.

Do not move runtime workflow execution in the first TUI refactor.

**Step 3: Verify after each move**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py tests/test_tui_selection.py -q
uv run ruff check src/controlplane_tool/tui tests/test_tui_choices.py tests/test_tui_selection.py
uv run basedpyright
uv run lint-imports
```

Expected: PASS.

**Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui tools/controlplane/tests/test_tui_choices.py tools/controlplane/tests/test_tui_selection.py
git commit -m "Extract TUI selection helpers"
```

### Task 7: Thin `cli/e2e_commands.py`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`
- Possible create: `tools/controlplane/src/controlplane_tool/e2e/request_factory.py`
- Modify only if needed: CLI/e2e tests under `tools/controlplane/tests`

**Rule:**

Move pure request/profile construction only. Keep Typer command declarations, option definitions, prompts, and user-facing rendering in `cli/e2e_commands.py`.

**Step 1: Baseline**

Run:

```bash
cd tools/controlplane
uv run pytest tests -q -k "e2e and cli"
```

Expected: PASS.

**Step 2: Extract pure construction logic**

Create `request_factory.py` only if at least two command paths use the same construction logic. Otherwise keep the code in place and skip this task.

**Step 3: Verify**

Run:

```bash
cd tools/controlplane
uv run pytest tests -q -k "e2e and cli"
uv run ruff check src/controlplane_tool/cli/e2e_commands.py src/controlplane_tool/e2e tests
uv run basedpyright
uv run lint-imports
```

Expected: PASS.

**Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/cli/e2e_commands.py tools/controlplane/src/controlplane_tool/e2e tools/controlplane/tests
git commit -m "Extract E2E request construction"
```

### Task 8: Split loadtest adapters by provider

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
- Possible create: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapters/__init__.py`
- Possible create: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapters/multipass.py`
- Possible create: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapters/proxmox.py`
- Possible create: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapters/azure.py`
- Modify only if needed: loadtest tests under `tools/controlplane/tests`

**Rule:**

Preserve the existing import surface from `loadtest_adapter.py` until all callers are updated. Split provider-specific code one provider at a time.

**Step 1: Baseline**

Run:

```bash
cd tools/controlplane
uv run pytest tests -q -k "loadtest or adapter"
```

Expected: PASS.

**Step 2: Extract one provider**

Move only one provider implementation into the new package. Re-export from `loadtest_adapter.py` if necessary.

**Step 3: Verify after each provider**

Run:

```bash
cd tools/controlplane
uv run pytest tests -q -k "loadtest or adapter"
uv run ruff check src/controlplane_tool/scenario tests
uv run basedpyright
uv run lint-imports
```

Expected: PASS.

**Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario tools/controlplane/tests
git commit -m "Split loadtest adapters by provider"
```

---

## Final Definition of Done

Mandatory work is done when:

- `uv run pytest` passes from `tools/controlplane`.
- `uv run ruff check .` passes from `tools/controlplane`.
- `uv run basedpyright` passes from `tools/controlplane`.
- `uv run lint-imports` passes from `tools/controlplane`.
- `uv run controlplane-quality` passes from `tools/controlplane`.
- The container-local delete-status bug has a regression test in `tools/controlplane/tests/test_cli_runtime.py`.
- `git diff --check` passes.
- GitNexus impact was attempted before edits, and any HIGH/CRITICAL result was reported before proceeding.

Optional cleanup is done only when the same checks pass after each cleanup commit.

## Recommended Commit Order

Mandatory:

1. `Fix tools controlplane test lint`
2. `Fix tools controlplane diagnostic lint`
3. `Report container-local delete status correctly`

Optional:

4. `Simplify container-local API calls`
5. `Extract TUI selection helpers`
6. `Extract E2E request construction`
7. `Split loadtest adapters by provider`
