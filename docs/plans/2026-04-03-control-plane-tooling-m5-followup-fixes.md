# Control Plane Tooling Milestone 5 Follow-up Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the remaining Milestone 5 contract gaps so the `loadtest` UX is truthful in dry-run, bad inputs fail with clean CLI errors, and normal loadtest runs do not dirty the repository.

**Architecture:** Keep the current M5 structure and tighten the contracts at the boundaries. Use one shared source of truth for the effective metrics gate so dry-run and runtime cannot drift. Translate request-construction failures into Typer exit code `2` at the command layer. Restore repository hygiene by ignoring the canonical `tools/controlplane/runs/` artifact path introduced by the new runner.

**Tech Stack:** Python, Typer, Pydantic, pytest, `.gitignore`, existing `loadtest_commands.py`, `loadtest_models.py`, `adapters.py`, and wrapper/doc tests.

---

## Review Findings To Fix

1. Dry-run omits the metrics gate that the runtime still enforces for saved profiles with no explicit `metrics.required`.
2. `loadtest run` still crashes with a Typer traceback for missing saved profiles and missing scenario files.
3. M5 run artifacts are written under `tools/controlplane/runs/`, but git still ignores only the old `tooling/runs/` path.

## Fix Strategy

1. Lock the three regressions with focused tests.
2. Centralize effective metrics gate resolution and feed that resolved value into `LoadtestRequest`.
3. Make `loadtest run` translate request-building failures into concise CLI errors with exit code `2`.
4. Update `.gitignore` for the new canonical artifact path and keep the old ignore entry only if it is still needed for compatibility.

### Task 1: Lock the regressions with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_loadtest_commands.py`
- Modify: `tools/controlplane/tests/test_loadtest_models.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Write the failing tests**

Add focused regression tests:

```python
def test_loadtest_run_dry_run_shows_effective_metrics_gate_for_saved_profile() -> None:
    result = CliRunner().invoke(
        app,
        ["loadtest", "run", "--saved-profile", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "Metrics gate:" in result.stdout
    assert "function_dispatch_total" in result.stdout


def test_loadtest_run_missing_saved_profile_exits_with_clean_cli_error() -> None:
    result = CliRunner().invoke(
        app,
        ["loadtest", "run", "--saved-profile", "does-not-exist"],
    )
    assert result.exit_code == 2
    assert "Profile not found" in result.stdout or result.stderr
    assert "Traceback" not in result.stdout + result.stderr


def test_loadtest_run_missing_scenario_file_exits_with_clean_cli_error() -> None:
    result = CliRunner().invoke(
        app,
        ["loadtest", "run", "--scenario-file", "tools/controlplane/scenarios/nope.toml", "--dry-run"],
    )
    assert result.exit_code == 2
    assert "Scenario file not found" in result.stdout + result.stderr
    assert "Traceback" not in result.stdout + result.stderr


def test_gitignore_includes_controlplane_runs_dir() -> None:
    gitignore = Path(".gitignore").read_text(encoding="utf-8")
    assert "tools/controlplane/runs/" in gitignore
```

Keep the tests narrow:
- one CLI test for the truthful dry-run contract
- two CLI error-path tests
- one repository hygiene assertion

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_wrapper_docs.py -v
```

Expected:
- dry-run gate test fails because no `Metrics gate:` line is rendered
- missing-profile and missing-scenario tests fail because a traceback is emitted
- `.gitignore` test fails because `tools/controlplane/runs/` is absent

**Step 3: Commit**

```bash
git add \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_wrapper_docs.py
git commit -m "test: lock m5 follow-up regressions"
```

### Task 2: Make dry-run and runtime share one effective metrics gate

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/adapters.py`
- Modify: `tools/controlplane/tests/test_loadtest_models.py`
- Modify: `tools/controlplane/tests/test_loadtest_commands.py`

**Step 1: Extract one pure helper for effective required metrics**

Introduce one shared helper instead of duplicating the fallback logic in the adapter only.

Minimal target shape:

```python
def effective_required_metrics(profile: Profile) -> list[str]:
    configured = list(profile.metrics.required)
    if profile.metrics.strict_required:
        return configured
    if not configured:
        return list(CORE_REQUIRED_METRICS)
    if set(configured) == set(LEGACY_STRICT_REQUIRED_METRICS):
        return list(CORE_REQUIRED_METRICS)
    return configured
```

Keep it in a pure module that both request construction and runtime evaluation can import without creating adapter side effects.

**Step 2: Hydrate `LoadtestRequest.metrics_gate` with the effective gate**

Update `LoadtestRequest` construction so that when the caller does not explicitly provide `required_metrics`, the request stores the effective gate, not an empty list.

Rules:
- dry-run must show the same gate the runtime will enforce
- `metrics_gate.mode` still comes from the profile
- `strict_required=true` must preserve explicit configured metrics exactly

**Step 3: Make the runtime reuse the same helper**

Replace the adapter-local fallback logic with the shared helper so there is only one source of truth.

Do not change runtime semantics; only remove the dry-run/runtime drift.

**Step 4: Run focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py -v
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_models.py \
  tools/controlplane/src/controlplane_tool/loadtest_commands.py \
  tools/controlplane/src/controlplane_tool/adapters.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py
git commit -m "fix: align loadtest dry-run with effective metrics gate"
```

### Task 3: Harden `loadtest run` error handling

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/loadtest_commands.py`
- Modify: `tools/controlplane/tests/test_loadtest_commands.py`

**Step 1: Add a small request-resolution boundary**

Wrap `build_loadtest_request()` usage in a command-layer helper that catches:

- `FileNotFoundError`
- `ValueError`
- `ValidationError`

and converts them to a concise CLI error plus `typer.Exit(code=2)`.

Suggested shape:

```python
def _build_request_or_exit(...) -> LoadtestRequest:
    try:
        return build_loadtest_request(...)
    except FileNotFoundError as exc:
        typer.echo(f"Scenario file not found: {path}", err=True)
        raise typer.Exit(code=2) from exc
    except ValueError as exc:
        typer.echo(str(exc), err=True)
        raise typer.Exit(code=2) from exc
    except ValidationError as exc:
        ...
```

For the missing saved profile case, preserve the wording style already used elsewhere in the tool, e.g. `Profile not found: <name>`.

**Step 2: Reuse that boundary in `run_command()`**

Do not duplicate error translation in multiple commands. If `inspect` or future commands need the same behavior later, the helper should already be reusable.

**Step 3: Run focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_loadtest_commands.py -v
```

Expected: PASS with no traceback in stderr/output for the error-path cases.

**Step 4: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/loadtest_commands.py \
  tools/controlplane/tests/test_loadtest_commands.py
git commit -m "fix: handle invalid loadtest cli inputs cleanly"
```

### Task 4: Restore repository hygiene for M5 artifacts

**Files:**
- Modify: `.gitignore`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Ignore the canonical run artifact path**

Update `.gitignore` so it ignores:

```gitignore
tools/controlplane/runs/
```

Decision:
- keep `tooling/runs/` too if there is any remaining legacy producer
- do not remove old ignore entries unless verified dead

**Step 2: Verify the worktree no longer dirties on normal use**

Use a real post-fix sanity check:

```bash
git status --short
```

Expected: no `?? tools/controlplane/runs/` from prior M5 executions after cleanup or ignore update.

If old generated files are still present locally, remove them before the verification step:

```bash
rm -rf tools/controlplane/runs
git status --short
```

**Step 3: Run the hygiene test**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_wrapper_docs.py -v
```

Expected: PASS.

**Step 4: Commit**

```bash
git add .gitignore tools/controlplane/tests/test_wrapper_docs.py
git commit -m "fix: ignore controlplane run artifacts"
```

### Task 5: Final verification for the full follow-up fix set

**Files:**
- No new files

**Step 1: Run the focused M5 verification bundle**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_loadtest_catalog.py \
  tools/controlplane/tests/test_loadtest_commands.py \
  tools/controlplane/tests/test_loadtest_models.py \
  tools/controlplane/tests/test_loadtest_runner.py \
  tools/controlplane/tests/test_pipeline.py \
  tools/controlplane/tests/test_adapters_k6_url.py \
  tools/controlplane/tests/test_adapters_metrics_prometheus_bootstrap.py \
  tools/controlplane/tests/test_wrapper_docs.py -v

python3 -m pytest \
  scripts/tests/test_loadtest_wrapper_runtime.py -q
```

Expected:
- all M5-focused tool tests PASS
- wrapper compatibility tests PASS

**Step 2: Run manual smoke checks for the exact regressions**

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool loadtest run --saved-profile demo-java --dry-run
uv run --project tools/controlplane --locked controlplane-tool loadtest run --saved-profile does-not-exist
uv run --project tools/controlplane --locked controlplane-tool loadtest run --scenario-file tools/controlplane/scenarios/nope.toml --dry-run
git status --short
```

Expected:
- dry-run shows `Metrics gate: ...`
- invalid inputs return concise CLI errors without traceback
- `git status` does not show `?? tools/controlplane/runs/`

**Step 3: Commit**

```bash
git add -A
git commit -m "fix: close remaining m5 loadtest review gaps"
```
