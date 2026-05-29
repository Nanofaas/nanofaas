# Remove Legacy CLI Consumers Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Remove the legacy CLI wrappers and compatibility surfaces so `cli-stack` and `controlplane.sh` are the only supported entrypoints.

**Architecture:** Keep one canonical CLI path and delete the parallel legacy path instead of aliasing it. Remove the external wrapper scripts first, then collapse the internal compatibility shims that still expose `vm`/legacy runner behavior, and finally update docs and regression tests so they enforce the new surface rather than preserving the old one. Treat any remaining references to `e2e-cli*`, `cli-test run vm`, or `CliVmRunner` as defects to be removed, not adapted.

**Tech Stack:** Python 3.14, `uv`, `pytest`, `basedpyright`, Bash scripts, Markdown docs.

---

### Task 1: Remove external wrapper scripts and lock their absence with tests

**Files:**
- Delete: `scripts/e2e-cli.sh`
- Delete: `scripts/e2e-cli-host-platform.sh`
- Delete: `scripts/e2e-cli-deploy-host.sh`
- Modify: `scripts/tests/test_legacy_wrappers_contract.py`

**Step 1: Write the failing test**

Add a regression test that asserts the legacy wrapper scripts do not exist anymore and that the only supported CLI launch path is `scripts/controlplane.sh cli-test run cli-stack`.

Example:
```python
def test_legacy_cli_wrappers_are_gone():
    assert not Path("scripts/e2e-cli.sh").exists()
    assert not Path("scripts/e2e-cli-host-platform.sh").exists()
    assert not Path("scripts/e2e-cli-deploy-host.sh").exists()
```

**Step 2: Run the focused test to verify it fails**

Run: `uv run pytest -q scripts/tests/test_legacy_wrappers_contract.py`

Expected: FAIL while the wrapper scripts still exist.

**Step 3: Delete the wrappers and update the contract test**

Remove the three compatibility scripts entirely. Rewrite the contract test so it checks for the absence of those files and, if needed, verifies that the supported CLI help still exposes only the canonical command tree.

**Step 4: Run the focused test again**

Run: `uv run pytest -q scripts/tests/test_legacy_wrappers_contract.py`

Expected: PASS.

**Step 5: Commit**

```bash
git add scripts/tests/test_legacy_wrappers_contract.py
git add -u scripts/e2e-cli.sh scripts/e2e-cli-host-platform.sh scripts/e2e-cli-deploy-host.sh
git commit -m "Remove legacy CLI wrapper scripts"
```

### Task 2: Purge legacy references from docs and user-facing guidance

**Files:**
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `docs/tutorial-function.md`
- Create: `tests/test_legacy_cli_surface.py`

**Step 1: Write the failing test**

Add a regression test that scans the user-facing docs for banned legacy strings such as `e2e-cli.sh`, `e2e-cli-host-platform.sh`, `e2e-cli-deploy-host.sh`, and `cli-test run vm`.

Example:
```python
def test_docs_do_not_mention_legacy_cli_wrappers():
    for path in [Path("README.md"), Path("docs/testing.md"), Path("docs/nanofaas-cli.md"), Path("docs/tutorial-function.md")]:
        text = path.read_text()
        assert "cli-test run vm" not in text
        assert "e2e-cli.sh" not in text
```

**Step 2: Run the focused test to verify it fails**

Run: `uv run pytest -q tests/test_legacy_cli_surface.py`

Expected: FAIL because the docs still mention the legacy wrappers.

**Step 3: Rewrite the docs**

Replace legacy wrapper instructions with the canonical path:
- `scripts/controlplane.sh cli-test run cli-stack`
- `scripts/controlplane.sh cli-test run host-platform` only if the scenario still exists as a supported public surface
- any remaining validation examples should use the current scenario names and script layout

**Step 4: Run the docs test again**

Run: `uv run pytest -q tests/test_legacy_cli_surface.py tests/test_docs_links.py`

Expected: PASS.

**Step 5: Commit**

```bash
git add README.md docs/testing.md docs/nanofaas-cli.md docs/tutorial-function.md tests/test_legacy_cli_surface.py
git commit -m "Remove legacy CLI references from docs"
```

### Task 3: Remove internal legacy runner surfaces that keep the wrappers alive

**Files:**
- Modify: `src/controlplane_tool/cli/runtime.py`
- Modify: `src/controlplane_tool/cli_validation/cli_test_runner.py`
- Modify: `src/controlplane_tool/cli_validation/cli_test_catalog.py`
- Modify: `src/controlplane_tool/cli_validation/cli_test_models.py`
- Modify: `src/controlplane_tool/scenario/scenario_flows.py`
- Modify: `src/controlplane_tool/tui/app.py`
- Modify: `src/controlplane_tool/cli/test_commands.py`
- Modify: `tests/test_cli_runtime.py`
- Modify: `tests/test_cli_test_runner.py`
- Modify: `tests/test_cli_test_catalog.py`
- Modify: `tests/test_tui_choices.py`
- Modify: `tests/test_main_entrypoint.py`
- Modify: `tests/test_package_layout.py`
- Modify: `tests/test_milestone_gates.py`
- Modify: `tests/test_canonical_entrypoints.py`

**Step 1: Write the failing tests**

Add or update tests so they fail while the legacy surfaces still exist:
- `CliVmRunner` is no longer importable from `controlplane_tool.cli.runtime`
- `vm` is no longer a valid CLI-test scenario
- the TUI no longer advertises the legacy VM runner
- scenario construction no longer routes through the legacy VM runner

Example:
```python
def test_cli_runtime_no_longer_reexports_legacy_runner():
    with pytest.raises(ImportError):
        from controlplane_tool.cli.runtime import CliVmRunner
```

**Step 2: Run the focused test slice to verify it fails**

Run: `uv run pytest -q tests/test_cli_runtime.py tests/test_cli_test_runner.py tests/test_cli_test_catalog.py tests/test_tui_choices.py tests/test_canonical_entrypoints.py`

Expected: FAIL while the compatibility layer still exposes `vm` and `CliVmRunner`.

**Step 3: Remove the compatibility code at the source**

Delete the `CliVmRunner` path, stop exporting it from `cli.runtime`, remove the `legacy_e2e_scenario` bridge, and make the catalog/model layer reject legacy scenario names. Keep only the canonical scenario names and keep the implementation shared where it already is shared.

**Step 4: Run static checks and the focused test slice again**

Run:
```bash
uv run basedpyright
uv run pytest -q tests/test_cli_runtime.py tests/test_cli_test_runner.py tests/test_cli_test_catalog.py tests/test_tui_choices.py tests/test_canonical_entrypoints.py
```

Expected: `basedpyright` clean, tests PASS.

**Step 5: Commit**

```bash
git add src/controlplane_tool/cli/runtime.py src/controlplane_tool/cli_validation/cli_test_runner.py src/controlplane_tool/cli_validation/cli_test_catalog.py src/controlplane_tool/cli_validation/cli_test_models.py src/controlplane_tool/scenario/scenario_flows.py src/controlplane_tool/tui/app.py src/controlplane_tool/cli/test_commands.py tests/test_cli_runtime.py tests/test_cli_test_runner.py tests/test_cli_test_catalog.py tests/test_tui_choices.py tests/test_main_entrypoint.py tests/test_package_layout.py tests/test_milestone_gates.py tests/test_canonical_entrypoints.py
git commit -m "Remove legacy CLI runner surfaces"
```

### Task 4: Re-run the full control-plane quality gate and capture the final scope

**Files:**
- No new code changes expected

**Step 1: Run the full verification suite**

Run:
```bash
uv run controlplane-quality
uv run pytest -q
```

Expected: both commands PASS with no legacy wrapper references left in code, docs, or tests.

**Step 2: Check the blast radius before finalizing**

Run: `gitnexus_detect_changes({scope: "all"})`

Expected: only the intended CLI surface files, docs, and tests are changed.

**Step 3: Commit the cleanup if anything remained staged**

```bash
git add -u
git commit -m "Remove legacy CLI consumers"
```
