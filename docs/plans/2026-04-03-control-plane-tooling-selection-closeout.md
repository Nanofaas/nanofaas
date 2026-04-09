# Control Plane Tooling Selection Closeout Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Eliminate the last contract mismatch between the canonical `controlplane-tool e2e ...` selection model and the legacy shell backends, so every scenario either:

- truthfully supports the resolved function set end-to-end, or
- rejects unsupported selection shapes before the backend starts.

This closes the final gap in the Milestone 3-7 tooling migration and makes the integrated branch behaviorally consistent with the UX it advertises.

## Final Architecture Decision

Use an explicit per-scenario selection contract instead of relying on implicit backend behavior.

- `container-local` becomes an explicitly **single-function** scenario.
  - Rationale: its backend is a focused managed-deployment lifecycle check with build, provision, invoke, scale-up, cleanup, and container-count assertions around one function at a time.
  - We should not pretend it is multi-function capable if the scenario semantics are actually single-function verification.
- `k3s-curl` becomes a true **multi-function** scenario.
  - Rationale: this is a curl-driven smoke path where validating multiple selected functions is natural and already implied by the current planner UX.
  - The backend must iterate the selected functions instead of collapsing to the first one.

This mixed strategy is the smallest change set that makes the product truthful and removes the current dry-run/runtime mismatch.

## Scope

### In scope

- `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- `tools/controlplane/src/controlplane_tool/e2e_runner.py` only if needed for metadata/rendering
- `scripts/lib/e2e-container-local-backend.sh`
- `scripts/lib/e2e-k3s-curl-backend.sh`
- `scripts/lib/scenario-manifest.sh` if small helper additions are useful
- targeted tests for planner, backend contract, wrapper/runtime behavior, and docs
- docs/help text that currently overstate scenario selection support

### Out of scope

- broad redesign of `cli-test`
- further E2E scenario catalog cleanup unrelated to selection contract
- removal of shell compatibility backends
- adding a brand-new `e2e inspect` command unless required by a test gap

## Milestone Closeout Contract

At the end of this fix:

- `controlplane-tool e2e run container-local --saved-profile demo-java` fails early with a clean validation error before invoking the backend
- `controlplane-tool e2e run container-local --functions word-stats-java --dry-run` remains valid
- `controlplane-tool e2e run k3s-curl --saved-profile demo-java --dry-run` continues to show both selected functions
- the real `k3s-curl` backend uses the full selected function set instead of only the first function
- docs and list output no longer imply that `container-local` accepts arbitrary multi-function presets

## Task 1: Lock the closeout contract with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Modify: `scripts/tests/test_e2e_runtime_contract.py`

### Step 1: Add planner-facing tests

Add tests that lock the intended scenario capabilities:

```python
def test_container_local_rejects_multi_function_saved_profile() -> None:
    result = CliRunner().invoke(
        app,
        ["e2e", "run", "container-local", "--saved-profile", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 2
    assert "container-local" in result.stdout + result.stderr
    assert "exactly one selected function" in result.stdout + result.stderr


def test_container_local_accepts_single_explicit_function() -> None:
    result = CliRunner().invoke(
        app,
        ["e2e", "run", "container-local", "--functions", "word-stats-java", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "Resolved Functions: word-stats-java" in result.stdout


def test_k3s_curl_dry_run_preserves_multi_function_selection() -> None:
    result = CliRunner().invoke(
        app,
        ["e2e", "run", "k3s-curl", "--saved-profile", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "word-stats-java" in result.stdout
    assert "json-transform-java" in result.stdout
```

### Step 2: Add backend contract tests

Lock the shell-side contract with text-level/runtime tests:

- `scripts/lib/e2e-container-local-backend.sh` must remain explicitly single-function
- `scripts/lib/e2e-k3s-curl-backend.sh` must no longer depend on `scenario_first_function_key()` as the only selected target path
- wrapper/runtime tests should prove that `k3s-curl` still routes through the real backend and not a placeholder path

### Step 3: Run tests to verify current failure

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py -q
```

Expected:

- the `container-local` validation test fails because multi-function saved profiles are still accepted by the planner
- the `k3s-curl` contract test fails because the backend still reduces selection to the first function

## Task 2: Make scenario selection capability explicit in the planner

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`

### Step 1: Extend the catalog model

Add a per-scenario capability field, for example:

```python
SelectionMode = Literal["none", "single", "multi"]


@dataclass(frozen=True)
class ScenarioDefinition:
    ...
    selection_mode: SelectionMode = "multi"
```

Suggested assignments:

- `docker` -> `none`
- `buildpack` -> `none`
- `container-local` -> `single`
- `k3s-curl` -> `multi`
- `k8s-vm` -> `multi`
- `deploy-host` -> `multi`
- `helm-stack` -> `multi`
- keep the legacy `cli` / `cli-host` values aligned with current behavior if they remain listed under `e2e`

### Step 2: Centralize selection validation

In `e2e_commands.py`, add one helper that validates the resolved scenario against the selected scenario definition:

- `none`: reject any resolved function selection that did not come from an internal default-free path
- `single`: require exactly one selected function
- `multi`: allow multiple functions

Use this helper after scenario resolution and before runner planning.

### Step 3: Keep the default selection truthful

Retain the current default single function for `container-local`:

```python
ScenarioSelectionConfig(base_scenario="container-local", functions=["word-stats-java"])
```

Do not broaden `container-local` defaults to `demo-java`.

### Step 4: Improve user-facing error quality

The validation error should be explicit and scenario-specific, for example:

```text
scenario 'container-local' supports exactly one selected function, got 2
```

Keep it on the normal `exit 2` validation path already used by `e2e_commands.py`.

## Task 3: Implement real multi-function execution for `k3s-curl`

**Files:**
- Modify: `scripts/lib/e2e-k3s-curl-backend.sh`
- Modify: `scripts/lib/scenario-manifest.sh` only if helper additions are useful
- Modify: `scripts/tests/test_e2e_runtime_contract.py`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`

### Step 1: Replace first-function collapse with iteration

Stop doing this as the only selection path:

```bash
FUNCTION_NAME=$(scenario_first_function_key)
```

Instead:

- read the selected keys into an array using `scenario_selected_functions`
- when no manifest is present, preserve the existing single-function legacy behavior
- when a manifest is present, iterate the selected functions

### Step 2: Iterate the real workflow per function

For each selected function in manifest mode:

- resolve `image`, `runtime`, `family`, and `payloadPath`
- build the selected function image
- push it to the in-VM registry
- register the function against the control plane
- execute the sync invoke check
- execute the async invoke check

Cleanup can remain at scenario scope or happen per function, but the full selected set must be exercised.

### Step 3: Preserve old non-manifest behavior

If `NANOFAAS_SCENARIO_PATH` is unset, keep the legacy single default function path unchanged.

### Step 4: Add regression tests

Add tests that fail if:

- the backend still references only `scenario_first_function_key()` for manifest-driven selection
- the runtime dry-run or command rendering silently ignores the second function in a multi-function preset

## Task 4: Close the `container-local` contract at the planner boundary

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: docs where needed

### Step 1: Reject unsupported multi-function manifests before the backend

Make `container-local` fail in the CLI layer when the resolved scenario contains more than one selected function.

This is preferable to backend failure because:

- dry-run becomes truthful
- saved-profile mismatch is visible immediately
- the shell backend remains focused on runtime orchestration instead of contract policing

### Step 2: Make the restriction discoverable

Update the scenario description or relevant docs so `container-local` is described as a single-function managed deployment verification path, not as a generic multi-function scenario.

Good places:

- `tools/controlplane/src/controlplane_tool/e2e_catalog.py`
- `tools/controlplane/README.md`
- `docs/testing.md`

### Step 3: Ensure examples are truthful

If any docs or tests currently show `container-local --saved-profile demo-java` as valid, replace them with a single-function example such as:

```bash
scripts/controlplane.sh e2e run container-local --functions word-stats-java --dry-run
```

## Task 5: Final docs and verification sweep

**Files:**
- Modify: `tools/controlplane/README.md`
- Modify: `docs/testing.md`
- Modify any scenario-selection docs/examples that still imply the old mismatch

### Step 1: Run focused verification

Run:

```bash
uv run --project tools/controlplane --locked pytest \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py \
  tools/controlplane/tests/test_docs_links.py -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_contract.py \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q
```

### Step 2: Run contract-level command checks

Run:

```bash
uv run --project tools/controlplane --locked controlplane-tool e2e run container-local --saved-profile demo-java --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run container-local --functions word-stats-java --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run k3s-curl --saved-profile demo-java --dry-run
```

Expected:

- first command exits `2` with a clean validation error
- second command succeeds
- third command succeeds and still renders multiple selected functions

### Step 3: Full confidence gate

Before declaring the tooling migration "perfectly closed", rerun:

```bash
./gradlew test
uv run --project tools/controlplane --locked pytest tools/controlplane/tests -q
python3 -m pytest scripts/tests -q
```

Only then can the branch be considered behaviorally aligned with the advertised M1-M7 tooling UX.
