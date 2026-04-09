# Control Plane Tooling Milestone 4 Review Fixes Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Fix the M4 review regressions so function/scenario selection is semantically correct: `helm-stack` must have a valid default selection, CLI overrides must preserve scenario-file metadata, and `k8s-vm` must actually consume the selected function set instead of only printing it in dry-run output.

**Architecture:** Keep the M4 direction intact, but tighten the contract in three places. First, move selection resolution to an explicit merge model that overlays CLI/profile choices on top of a scenario spec without discarding payload/load metadata. Second, add scenario-specific capability rules so built-in defaults and validation match what the backends can really run. Third, make `k8s-vm` scenario-aware by carrying the resolved manifest into the VM and teaching the Java E2E path to consume it.

**Tech Stack:** Python, Typer, Pydantic, pytest, Bash compatibility backends, Java/JUnit 5, Jackson or existing JSON support in the test classpath.

---

## Review Findings To Fix

1. `helm-stack` default selection is invalid because the built-in default resolves `demo-all`, but the backend explicitly rejects `go`.
2. `k8s-vm` exposes `--function-preset`, `--functions`, `--scenario-file`, and `--saved-profile`, but the real workflow still runs a fixed `K8sE2eTest` without consuming the manifest.
3. Explicit CLI function overrides currently discard `scenario-file` metadata such as payloads and `load.targets` instead of overriding only the selection layer.

## Scope Guard

**In scope**

- fix the three review findings above
- add tests that lock the corrected selection semantics
- update docs/examples that currently describe the broken behavior

**Out of scope**

- general redesign of the M4 data model
- migration of more shell backends to native Python
- milestone 5 loadtest/metrics redesign

## Fix Strategy

1. Lock each regression with focused tests.
2. Repair selection merging and scenario capability validation in Python first.
3. Make `k8s-vm` scenario-aware through a remote manifest path and Java-side manifest reader.
4. Re-verify CLI dry-runs, wrapper tests, and Java E2E command construction.

### Task 1: Lock the M4 regressions with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `tools/controlplane/tests/test_scenario_loader.py`
- Modify: `tools/controlplane/tests/test_function_catalog.py`
- Modify: `scripts/tests/test_controlplane_e2e_wrapper_runtime.py`
- Create: `control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eScenarioManifestCommandTest.java`

**Step 1: Write the failing tests**

Add three regression assertions on the Python side:

```python
def test_helm_stack_default_selection_uses_supported_loadtest_functions() -> None:
    result = CliRunner().invoke(app, ["e2e", "run", "helm-stack", "--dry-run"])
    assert result.exit_code == 0
    assert "word-stats-go" not in result.stdout
    assert "json-transform-go" not in result.stdout


def test_cli_function_override_preserves_scenario_file_load_targets() -> None:
    req = _resolve_run_request(
        scenario=None,
        runtime=None,
        lifecycle="multipass",
        name=None,
        host=None,
        user="ubuntu",
        home=None,
        cpus=4,
        memory="8G",
        disk="30G",
        keep_vm=False,
        namespace=None,
        local_registry=None,
        function_preset=None,
        functions_csv="word-stats-java",
        scenario_file=Path("tools/controlplane/scenarios/k8s-demo-java.toml"),
        saved_profile=None,
    )
    assert req.resolved_scenario.load.targets == ["word-stats-java"]
    assert "word-stats-java" in req.resolved_scenario.payloads


def test_k8s_vm_plan_exports_remote_manifest_to_test_command() -> None:
    runner = E2eRunner(Path("/repo"), shell=RecordingShell())
    plan = runner.plan(
        E2eRequest(
            scenario="k8s-vm",
            runtime="java",
            function_preset="demo-java",
            resolved_scenario=load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml")),
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        )
    )
    rendered = [" ".join(step.command) for step in plan.steps]
    assert any("nanofaas.e2e.scenarioManifest" in command for command in rendered)
```

Add a Java command-construction test that asserts the generated `K8sE2eTest` invocation now carries a scenario manifest system property.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py -v

python3 -m pytest scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q

./gradlew :control-plane-modules:k8s-deployment-provider:test \
  --tests '*K8sE2eScenarioManifestCommandTest'
```

Expected: FAIL on the three known regressions.

**Step 3: Commit**

```bash
git add \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py \
  control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eScenarioManifestCommandTest.java
git commit -m "test: lock m4 selection regressions"
```

### Task 2: Replace destructive selection precedence with metadata-preserving overlays

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_loader.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/tests/test_scenario_loader.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`

**Step 1: Introduce an explicit overlay helper**

Add a helper in `scenario_loader.py` with a shape like:

```python
def overlay_scenario_selection(
    base: ResolvedScenario,
    *,
    function_preset: str | None,
    functions: list[str],
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    ...
```

Rules:

- start from the loaded `ResolvedScenario`
- override only the selection layer
- preserve `invoke`, `payloads`, `load.profile`, `namespace`, and source path unless explicitly overridden
- shrink `load.targets` to the selected subset instead of clearing them blindly
- shrink `payloads` to the selected subset while preserving per-function payload mapping

**Step 2: Update `_resolve_run_request()`**

Change the explicit CLI branch in `e2e_commands.py`:

- when `--scenario-file` is present and `--functions`/`--function-preset` are also present, use the loaded scenario as the base and overlay the explicit selection
- when only `--saved-profile` contributes a scenario file, apply the same overlay logic
- only fall back to `_resolved_from_config()` when there is no scenario file/profile scenario to inherit from

**Step 3: Add validation for empty post-filter results**

If overlaying selection makes `load.targets` empty for a scenario that requires load targets, fail with a clear validation error instead of silently degrading.

Suggested error:

```text
selected functions do not satisfy load targets for scenario 'helm-stack'
```

**Step 4: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_e2e_commands.py -v
```

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_models.py \
  tools/controlplane/src/controlplane_tool/scenario_loader.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_e2e_commands.py
git commit -m "fix: preserve scenario metadata when overriding function selection"
```

### Task 3: Add scenario capability rules and fix the `helm-stack` default

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/scenarios/k8s-demo-all.toml`
- Modify: `tools/controlplane/tests/test_function_catalog.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: `scripts/lib/e2e-helm-stack-backend.sh`

**Step 1: Model supported function/runtime families per scenario**

Add a small policy layer, for example:

```python
SCENARIO_FUNCTION_RUNTIME_ALLOWLIST = {
    "helm-stack": {"java", "java-lite", "python", "exec"},
}
```

Validation rules:

- `helm-stack` must reject unsupported runtimes like `go` before the backend starts
- the error should appear in CLI validation, not only inside the shell backend

**Step 2: Replace the invalid built-in default**

Do not leave `helm-stack -> demo-all`. Pick one of these clean options and document it in code:

- preferred: add a dedicated preset such as `demo-loadtest`
- acceptable: add a scenario-specific preset such as `helm-supported`

The preset should contain only the functions that the Helm/loadtest stack can actually exercise by default.

Also update `k8s-demo-all.toml` if its current name/contents imply unsupported Go coverage.

**Step 3: Keep backend-side guardrails**

Retain the shell-side protection in `e2e-helm-stack-backend.sh`, but treat it as a defense-in-depth check. It should never be the first place the user discovers an invalid default.

**Step 4: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_e2e_commands.py -v

uv run --project tools/controlplane --locked controlplane-tool e2e run helm-stack --dry-run
```

Expected:

- tests pass
- dry-run no longer resolves `go` functions by default

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/function_catalog.py \
  tools/controlplane/src/controlplane_tool/scenario_models.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/scenarios/k8s-demo-all.toml \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_e2e_commands.py \
  scripts/lib/e2e-helm-stack-backend.sh
git commit -m "fix: align helm-stack defaults with supported function matrix"
```

### Task 4: Make `k8s-vm` actually consume the resolved scenario manifest

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_manifest.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_adapter.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`
- Modify: `control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eTest.java`
- Create: `control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eScenarioManifest.java`
- Create: `control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eScenarioManifestTest.java`
- Modify: `control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eDeploymentSpecTest.java`

**Step 1: Give VM-backed scenarios a remote manifest path**

Today the manifest is written locally under `tools/controlplane/runs/manifests`, but `k8s-vm` executes inside the VM. Add a helper in `E2eRunner` that maps a host manifest path into the synced remote repo path, for example:

```python
def _remote_manifest_path(self, request: VmRequest, local_manifest: Path) -> str: ...
```

Then update the `k8s-vm` command to pass a JVM system property:

```text
-Dnanofaas.e2e.scenarioManifest=/home/ubuntu/nanofaas/tools/controlplane/runs/manifests/<file>.json
```

This path must match the synced project root in the VM.

**Step 2: Add a small Java-side manifest reader**

Create `K8sE2eScenarioManifest` that:

- reads the manifest path from `System.getProperty("nanofaas.e2e.scenarioManifest")`
- parses the function list, payload path, runtime, namespace, and load targets
- exposes helpers like:

```java
List<SelectedFunction> selectedFunctions()
Optional<K8sE2eScenarioManifest> loadFromSystemProperty()
```

Use Jackson if already on the test classpath; do not add a new JSON library.

**Step 3: Teach `K8sE2eTest` to use it**

At minimum:

- when the manifest is absent, keep current legacy behavior
- when the manifest is present, register/invoke the selected functions from the manifest instead of always `k8s-echo`
- if more than one function is selected and the current test method only supports one path, either:
  - iterate over the selected functions, or
  - fail early with a clear assertion and update the CLI scenario policy accordingly

The important point is that the runtime path must change according to the selected scenario, not only the dry-run text.

**Step 4: Add focused tests**

Add:

- Python test asserting `k8s-vm` command carries the remote manifest property
- Java unit test asserting the manifest loader parses the JSON format emitted by `scenario_manifest.py`
- Java test asserting the fallback path still works when the property is absent

**Step 5: Run focused tests**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_runner.py -v

./gradlew :control-plane-modules:k8s-deployment-provider:test \
  --tests '*K8sE2eScenarioManifestTest' \
  --tests '*K8sE2eDeploymentSpecTest' \
  --tests '*K8sE2eScenarioManifestCommandTest'
```

Expected: PASS.

**Step 6: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/scenario_manifest.py \
  tools/controlplane/src/controlplane_tool/vm_adapter.py \
  tools/controlplane/tests/test_e2e_runner.py \
  control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eTest.java \
  control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eScenarioManifest.java \
  control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eScenarioManifestTest.java \
  control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eDeploymentSpecTest.java \
  control-plane-modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eScenarioManifestCommandTest.java
git commit -m "fix: make k8s vm e2e consume scenario manifests"
```

### Task 5: Update docs and re-verify the repaired M4 contract

**Files:**
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Update docs**

Correct the documentation to match the repaired contract:

- `helm-stack` default uses a backend-supported preset only
- explicit CLI selection overrides preserve scenario-file metadata
- `k8s-vm` selection affects the executed workflow, not just dry-run output

Update examples accordingly.

**Step 2: Run the verification suite**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q

./gradlew :control-plane-modules:k8s-deployment-provider:test

uv run --project tools/controlplane --locked controlplane-tool e2e run helm-stack --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --functions word-stats-java --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run k8s-vm --function-preset demo-java --dry-run
```

Expected:

- no invalid `go` functions in the `helm-stack` default dry-run
- scenario-file override retains payload/load metadata for the selected subset
- `k8s-vm` plan shows the selection and the runtime command now carries a scenario manifest path

**Step 3: Commit**

```bash
git add \
  tools/controlplane/README.md \
  README.md \
  docs/testing.md \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_wrapper_docs.py
git commit -m "docs: align m4 selection docs with repaired behavior"
```

## Final Verification

Run this full set before calling the fixes complete:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q

./gradlew :control-plane-modules:k8s-deployment-provider:test

uv run --project tools/controlplane --locked controlplane-tool functions list
uv run --project tools/controlplane --locked controlplane-tool e2e run helm-stack --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --functions word-stats-java --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run k8s-vm --function-preset demo-java --dry-run
```

Expected:

- `helm-stack` defaults are backend-valid
- selection precedence is non-destructive
- `k8s-vm` selection changes the real execution path rather than only dry-run presentation
