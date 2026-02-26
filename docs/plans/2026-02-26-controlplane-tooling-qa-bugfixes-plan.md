# Controlplane Tooling QA Bugfixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix QA-blocking issues in the local control-plane tooling: non-zero exit behavior, user-friendly profile loading errors, and incorrect k6 base URL wiring.

**Architecture:** Keep changes minimal and local to the Python tooling package. Add focused regression tests first, then implement small targeted fixes in CLI orchestration and adapter command construction. Preserve existing run artifacts (`summary.json` and `report.html`) while correcting process semantics and diagnostics.

**Tech Stack:** Python 3.12, Typer, Pydantic, Pytest, uv.

---

### Task 1: Fix CLI exit semantics and profile error handling

**Files:**
- Modify: `tooling/controlplane_tui/src/controlplane_tool/main.py`
- Create: `tooling/controlplane_tui/tests/test_cli_run_behavior.py`

**Step 1: Write the failing test**

```python
def test_run_returns_nonzero_when_pipeline_failed(...):
    ...
    assert result.exit_code == 1


def test_run_missing_profile_is_user_friendly(...):
    ...
    assert result.exit_code == 2
    assert "Profile not found" in result.stdout
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_cli_run_behavior.py -v`  
Expected: FAIL because CLI currently exits 0 on failed run and leaks traceback for missing profile.

**Step 3: Write minimal implementation**

- Catch `FileNotFoundError` and `ValidationError` in CLI run command.
- Emit concise user-facing error text with profile name.
- Return `typer.Exit(code=2)` for invalid/missing profile.
- Return `typer.Exit(code=1)` when pipeline result is not `passed`.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_cli_run_behavior.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/main.py tooling/controlplane_tui/tests/test_cli_run_behavior.py
git commit -m "fix(tooling): return proper exit codes and friendly profile errors"
```

### Task 2: Fix k6 base URL wiring in metrics step

**Files:**
- Modify: `tooling/controlplane_tui/src/controlplane_tool/adapters.py`
- Create: `tooling/controlplane_tui/tests/test_adapters_k6_url.py`

**Step 1: Write the failing test**

```python
def test_metrics_k6_uses_control_plane_base_url(...):
    ...
    assert "NANOFAAS_URL=http://localhost:8080" in command
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_adapters_k6_url.py -v`  
Expected: FAIL because adapter currently sets URL with extra `/function/word-stats` path.

**Step 3: Write minimal implementation**

- Replace hardcoded k6 env base URL with `http://localhost:8080` default.
- Keep command shape unchanged otherwise.
- Do not change function target naming in k6 scripts.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_adapters_k6_url.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/adapters.py tooling/controlplane_tui/tests/test_adapters_k6_url.py
git commit -m "fix(tooling): use correct base URL for k6 metrics load"
```

### Task 3: Update docs and add regression guardrails

**Files:**
- Modify: `docs/quickstart.md`
- Modify: `docs/testing.md`
- Modify: `tooling/controlplane_tui/tests/test_docs_links.py`

**Step 1: Write the failing test**

```python
def test_docs_describe_exit_codes_and_k6_base_url():
    assert "exit code" in quickstart
    assert "NANOFAAS_URL=http://localhost:8080" in testing
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_docs_links.py -v`  
Expected: FAIL until docs are updated.

**Step 3: Write minimal implementation**

- Document non-zero exit behavior for failed runs.
- Document k6 base URL requirement and expected local endpoint.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_docs_links.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add docs/quickstart.md docs/testing.md tooling/controlplane_tui/tests/test_docs_links.py
git commit -m "docs(tooling): document exit codes and k6 base URL expectations"
```

### Task 4: Full verification and QA replay

**Files:**
- Modify: `tooling/controlplane_tui/tests/test_profiles.py` (only if needed for new behavior)

**Step 1: Write the failing test**

- If behavior changed in profile defaults, add a targeted failing assertion first.

**Step 2: Run test to verify it fails**

- Run only the targeted test (if created).

**Step 3: Write minimal implementation**

- Apply only the smallest profile-related fix required by failing test.

**Step 4: Run tests to verify everything passes**

Run:
- `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests -v`
- `./gradlew :control-plane:test --tests '*MockK8sDeploymentReplicaSetFlowTest'`
- `scripts/controlplane-tool.sh --help`

Expected: all PASS/BUILD SUCCESSFUL and help output available.

**Step 5: Commit**

```bash
git add -A
git commit -m "test(tooling): add regression coverage for QA bugfixes"
```
