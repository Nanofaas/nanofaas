# Control Plane Tooling Milestone 6 Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the remaining Milestone 6 contract gaps so `cli-test` tells the truth about function selection, the canonical `vm` and `deploy-host` flows honor multi-function manifests, and bad CLI inputs fail with clean exit code `2` instead of a traceback.

**Architecture:** Keep the current `cli-test` surface and tighten the scenario contracts at the boundaries. Preserve function selection only where the underlying workflow can actually execute it. Make `vm` and `deploy-host` genuinely multi-function aware by iterating over the selected manifest set in the legacy shell backends. Make `host-platform` explicitly selection-agnostic instead of pretending to consume manifests it ignores. Centralize request-construction validation so missing profiles and scenario files are translated to a normal Typer CLI error.

**Tech Stack:** Python, Typer, Pydantic, pytest, Bash, existing `scenario-manifest.sh` helpers, existing `cli-test` command group, existing legacy `scripts/lib/e2e-cli-*.sh` backends.

---

## Review Findings To Fix

1. `host-platform` advertises function selection but ignores it at runtime.
2. The canonical `cli-test run --saved-profile demo-java` flow validates only the first selected function.
3. `deploy-host` accepts multi-function presets in dry-run, but the real backend rejects anything except exactly one selected function.
4. `cli-test run` still crashes with a Typer traceback for missing saved profiles and missing scenario files.

## Fix Strategy

1. Lock the four regressions with targeted tests before changing behavior.
2. Narrow the `host-platform` contract: it does not accept function selection, and dry-run/inspect/docs must stop claiming otherwise.
3. Keep `vm` and `deploy-host` selection-aware, but make them truly iterate over all selected functions.
4. Preserve shared saved-profile semantics carefully:
   - explicit `--function-preset`, `--functions`, and `--scenario-file` must still be rejected for `host-platform`
   - shared `scenario.*` defaults from `--saved-profile` must not be auto-applied to `host-platform`
   - `cli_test.default_scenario` from the saved profile must still work
5. Translate `FileNotFoundError` into a clean CLI error with exit code `2`.

### Task 1: Lock the M6 regressions with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_cli_test_catalog.py`
- Modify: `tools/controlplane/tests/test_cli_test_commands.py`
- Modify: `tools/controlplane/tests/test_cli_test_runner.py`
- Modify: `tools/controlplane/tests/test_cli_test_models.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `scripts/tests/test_controlplane_e2e_wrapper_runtime.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing tests**

Add focused regression tests:

```python
def test_cli_test_inspect_host_platform_reports_selection_disabled() -> None:
    result = CliRunner().invoke(app, ["cli-test", "inspect", "host-platform"])
    assert result.exit_code == 0
    assert "Accepts Function Selection: False" in result.stdout


def test_cli_test_run_host_platform_rejects_explicit_function_preset() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "host-platform", "--function-preset", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 2
    assert "does not accept function selection" in result.stdout + result.stderr


def test_cli_test_run_missing_saved_profile_exits_cleanly() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "--saved-profile", "does-not-exist"],
    )
    assert result.exit_code == 2
    assert "Traceback" not in result.stdout + result.stderr


def test_cli_test_run_missing_scenario_file_exits_cleanly() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "vm", "--scenario-file", "tools/controlplane/scenarios/nope.toml"],
    )
    assert result.exit_code == 2
    assert "Traceback" not in result.stdout + result.stderr
```

Add runner/backend tests that prove:

- `vm` expands a multi-function manifest into validation work for every selected function
- `deploy-host` expands a multi-function manifest into one deploy/verify cycle per selected function
- `host-platform` no longer accepts manifest-driven selection

Suggested shell-level assertions:

```python
def test_cli_backend_iterates_all_selected_functions() -> None:
    script = (SCRIPTS_DIR / "lib" / "e2e-cli-backend.sh").read_text(encoding="utf-8")
    assert "scenario_selected_functions" in script


def test_deploy_host_backend_iterates_all_selected_functions() -> None:
    script = (SCRIPTS_DIR / "lib" / "e2e-deploy-host-backend.sh").read_text(encoding="utf-8")
    assert "scenario_selected_functions" in script
    assert "scenario_require_single_function" not in script
```

Also update doc-link tests so they stop expecting selection-aware `host-platform` examples if docs currently contain them.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_docs_links.py -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q
```

Expected:
- `host-platform` still reports `Accepts Function Selection: True`
- explicit host-platform selection still dry-runs successfully
- missing profile and missing scenario file still crash with traceback
- backends still reveal single-function assumptions

**Step 3: Commit**

```bash
git add \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_docs_links.py \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py
git commit -m "test: lock m6 cli-test contract regressions"
```

### Task 2: Narrow `host-platform` to a truthful non-selection scenario

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_runner.py`
- Modify: `tools/controlplane/tests/test_cli_test_catalog.py`
- Modify: `tools/controlplane/tests/test_cli_test_models.py`
- Modify: `tools/controlplane/tests/test_cli_test_commands.py`

**Step 1: Flip the catalog contract**

Set:

```python
CliTestScenarioDefinition(
    name="host-platform",
    accepts_function_selection=False,
    ...
)
```

and remove `"host-platform"` from `CLI_TEST_FUNCTION_SELECTION_SCENARIOS`.

**Step 2: Make request resolution distinguish explicit vs inherited selection**

Refactor `_resolve_run_request()` so it tracks:

- explicit CLI selection:
  - `--function-preset`
  - `--functions`
  - `--scenario-file`
- inherited saved-profile selection:
  - `profile.scenario.function_preset`
  - `profile.scenario.functions`
  - `profile.scenario.scenario_file`

Rules:

- if the target scenario is not selection-aware:
  - reject explicit selection inputs with `ValueError`
  - ignore inherited saved-profile selection defaults instead of applying them
  - still honor `saved_profile` for `cli_test.default_scenario`, runtime, namespace, registry, and VM defaults

Minimal helper shape:

```python
def _scenario_accepts_selection(definition: CliTestScenarioDefinition) -> bool:
    return definition.accepts_function_selection


def _has_explicit_selection(...) -> bool:
    ...
```

**Step 3: Keep the runner and dry-run output honest**

Ensure:

- `cli-test inspect host-platform` prints `Accepts Function Selection: False`
- `cli-test run host-platform --saved-profile demo-java --dry-run` does not show `Resolved Functions`
- `cli-test run host-platform --function-preset demo-java --dry-run` exits `2`

No shell backend change is required here because the workflow itself is already platform-only; the fix is to stop promising manifest-driven behavior.

**Step 4: Run focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_test_runner.py -v
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/cli_test_catalog.py \
  tools/controlplane/src/controlplane_tool/cli_test_models.py \
  tools/controlplane/src/controlplane_tool/cli_test_commands.py \
  tools/controlplane/src/controlplane_tool/cli_test_runner.py \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_test_runner.py
git commit -m "fix: narrow host-platform cli-test contract"
```

### Task 3: Make the VM `cli-test` backend validate every selected function

**Files:**
- Modify: `scripts/lib/e2e-cli-backend.sh`
- Modify: `scripts/lib/scenario-manifest.sh`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `tools/controlplane/tests/test_cli_test_runner.py`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/README.md`

**Step 1: Add the failing backend/runtime tests**

Add tests that force the backend contract to expose iteration over all selected functions.

Examples:

```python
def test_cli_backend_uses_selected_function_iteration() -> None:
    script = (SCRIPTS_DIR / "lib" / "e2e-cli-backend.sh").read_text(encoding="utf-8")
    assert "mapfile -t selected_functions" in script
    assert "for FUNCTION_NAME in" in script


def test_cli_test_runner_vm_manifest_plan_still_passes_manifest_env() -> None:
    ...
```

**Step 2: Refactor the shell backend to iterate**

Change `e2e-cli-backend.sh` from single global function state to a per-function loop:

- add one helper to collect selected functions:

```bash
resolve_selected_functions() {
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        SELECTED_FUNCTIONS=("${FUNCTION_NAME}")
        return 0
    fi
    mapfile -t SELECTED_FUNCTIONS < <(scenario_selected_functions)
}
```

- move function-specific resolution into a per-function helper:

```bash
select_function_context() {
    local function_key=$1
    FUNCTION_NAME="${function_key}"
    FUNCTION_IMAGE=$(scenario_function_image "${function_key}" || echo "${RUNTIME_IMAGE}")
    FUNCTION_PAYLOAD_FILE=$(scenario_function_payload_path "${function_key}" || true)
}
```

- loop the function lifecycle tests:

```bash
for function_key in "${SELECTED_FUNCTIONS[@]}"; do
    select_function_context "${function_key}"
    build_primary_function_image
    test_cli_function_flow
    test_cli_k8s_commands
done
```

Keep platform lifecycle coverage once per scenario, outside the per-function loop.

If repeated image builds are expensive, make the loop skip rebuilds for duplicate images only if the optimization is trivial and tested; otherwise prefer correctness over optimization.

**Step 3: Make the docs truthful about multi-function validation**

Update the docs so the canonical saved-profile path is described as validating the whole selected function set, not only the first function.

**Step 4: Run focused tests**

Run:

```bash
python3 -m pytest scripts/tests/test_e2e_runtime_runners.py -q

uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_docs_links.py -v

uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run vm --saved-profile demo-java --dry-run
```

Expected:
- backend/runtime tests PASS
- dry-run still shows the multi-function manifest
- docs no longer over-promise a single-function flow

**Step 5: Commit**

```bash
git add \
  scripts/lib/e2e-cli-backend.sh \
  scripts/lib/scenario-manifest.sh \
  scripts/tests/test_e2e_runtime_runners.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  docs/testing.md \
  tools/controlplane/README.md
git commit -m "fix: validate all selected functions in vm cli-test flow"
```

### Task 4: Make `deploy-host` genuinely multi-function aware

**Files:**
- Modify: `scripts/lib/e2e-deploy-host-backend.sh`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `tools/controlplane/tests/test_cli_test_commands.py`
- Modify: `docs/testing.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `README.md`

**Step 1: Add the failing tests**

Add tests that force `deploy-host` to support the documented multi-function preset flow.

Examples:

```python
def test_cli_test_run_deploy_host_dry_run_accepts_demo_java_preset() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "deploy-host", "--function-preset", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "Resolved Functions: word-stats-java, json-transform-java" in result.stdout
```

Shell/runtime assertion:

```python
def test_deploy_host_backend_iterates_selected_functions() -> None:
    script = (SCRIPTS_DIR / "lib" / "e2e-deploy-host-backend.sh").read_text(encoding="utf-8")
    assert "scenario_require_single_function" not in script
    assert "mapfile -t selected_functions" in script
```

**Step 2: Refactor the backend from single function to loop**

Replace the single-function global setup with one selected-function array and a per-function iteration loop.

Target structure:

```bash
resolve_selected_functions() {
    if [[ -z "${NANOFAAS_SCENARIO_PATH:-}" ]]; then
        SELECTED_FUNCTIONS=("${FUNCTION_NAME}")
        return 0
    fi
    mapfile -t SELECTED_FUNCTIONS < <(scenario_selected_functions)
}

select_function_context() {
    local function_key=$1
    FUNCTION_NAME="${function_key}"
    IMAGE_REPOSITORY="nanofaas/${FUNCTION_NAME}"
    FUNCTION_EXAMPLE_DIR=$(scenario_function_example_dir "${FUNCTION_NAME}" || true)
    FUNCTION_DOCKERFILE="${FUNCTION_EXAMPLE_DIR}/Dockerfile"
}

for function_key in "${SELECTED_FUNCTIONS[@]}"; do
    select_function_context "${function_key}"
    write_test_function
    run_deploy
    verify_registry_push
    verify_register_request
done
```

Make sure request capture does not become racy:

- either reset `REQUEST_BODY_PATH` per function before each deploy
- or verify the request body immediately after each deploy, before the next iteration overwrites it

Do not keep the old `scenario_require_single_function()` guard.

**Step 3: Re-run the documented dry-run**

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run deploy-host --function-preset demo-java --dry-run
```

Expected: still dry-runs successfully, and now the runtime backend contract matches that dry-run.

**Step 4: Run focused tests**

Run:

```bash
python3 -m pytest scripts/tests/test_e2e_runtime_runners.py -q

uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_docs_links.py -v
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  scripts/lib/e2e-deploy-host-backend.sh \
  scripts/tests/test_e2e_runtime_runners.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  docs/testing.md \
  docs/nanofaas-cli.md \
  README.md
git commit -m "fix: support multi-function deploy-host cli-test flow"
```

### Task 5: Harden `cli-test run` CLI error handling

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli_test_commands.py`
- Modify: `tools/controlplane/tests/test_cli_test_commands.py`

**Step 1: Extend the command-layer validation boundary**

Update `_handle_validation()` so it also catches `FileNotFoundError` and translates it into a concise CLI error plus `typer.Exit(code=2)`.

Suggested minimal shape:

```python
def _handle_validation(action) -> None:
    try:
        action()
    except FileNotFoundError as exc:
        typer.echo(f"Invalid cli-test request: {exc}", err=True)
        raise typer.Exit(code=2) from exc
    except ValidationError as exc:
        ...
    except ValueError as exc:
        ...
```

If the raw `exc` message is too path-heavy, normalize it:

- `Profile not found: <name>`
- `Scenario file not found: <path>`

Prefer a small helper if that keeps the wording deterministic for tests.

**Step 2: Reuse the same boundary for all `cli-test` commands**

Do not add one-off `try/except` blocks inside `cli_test_run()`. Keep one command-layer error boundary so `inspect` and future subcommands can reuse it if needed.

**Step 3: Run focused tests**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_cli_test_commands.py -v

uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run --saved-profile does-not-exist

uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run vm --scenario-file tools/controlplane/scenarios/nope.toml
```

Expected:
- pytest PASS
- both commands exit `2`
- no traceback output

**Step 4: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/cli_test_commands.py \
  tools/controlplane/tests/test_cli_test_commands.py
git commit -m "fix: harden cli-test command error handling"
```

### Task 6: Final docs alignment and milestone verification

**Files:**
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Align the docs to the repaired contracts**

Make the docs say exactly this:

- `vm` validates the full selected function set
- `deploy-host` supports multi-function preset/scenario-file flows
- `host-platform` is platform-only and does not consume function selection
- missing profile/scenario errors return a normal CLI validation failure

Remove or rewrite any example that still implies:

- `host-platform --function-preset ...`
- `host-platform --scenario-file ...`
- `host-platform` consumes `Resolved Functions`

Keep the canonical examples minimal and truthful.

**Step 2: Run full M6 verification**

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_cli_test_catalog.py \
  tools/controlplane/tests/test_cli_test_models.py \
  tools/controlplane/tests/test_cli_test_runner.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_wrapper_docs.py -q

python3 -m pytest \
  scripts/tests/test_cli_test_wrapper_runtime.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  scripts/tests/test_e2e_runtime_runners.py -q

./gradlew :nanofaas-cli:test

uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run unit --dry-run

uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run vm --saved-profile demo-java --dry-run

uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run deploy-host --function-preset demo-java --dry-run

uv run --project tools/controlplane --locked controlplane-tool \
  cli-test run host-platform --saved-profile demo-java --dry-run
```

Expected:
- all tests PASS
- `unit` shows only the Gradle test step
- `vm` dry-run shows the selected functions and the VM workflow
- `deploy-host` dry-run still accepts `demo-java`
- `host-platform` dry-run succeeds with the saved profile but does not render a selection-driven plan

**Step 3: Commit**

```bash
git add \
  tools/controlplane/README.md \
  README.md \
  docs/testing.md \
  docs/nanofaas-cli.md \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_wrapper_docs.py
git commit -m "docs: align cli-test contracts with m6 fixes"
```
