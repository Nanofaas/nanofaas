# Control Plane Tooling Milestone 4 Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make function and scenario selection first-class in the unified `tools/controlplane/` product, so users can choose demo functions, explicit function sets, and reusable scenario files from the CLI and TUI without editing shell environment variables.

**Architecture:** Add a typed function catalog plus named presets derived from the repository’s real demo functions, then introduce a TOML scenario-spec layer that resolves into one normalized scenario manifest. The CLI, TUI-saved profiles, and E2E backends all consume the same resolved selection model. Keep the existing scenario backends, but stop hardcoding function sets inside them.

**Tech Stack:** Python, Typer, Pydantic, `tomllib`, `tomli_w`, pytest, Bash compatibility backends, existing E2E helpers under `scripts/lib/`.

---

## Scope Guard

**In scope**

- built-in function catalog for the repository demo functions and fixtures
- named function presets such as Java-only, all demos, metrics-smoke
- explicit function list selection from the CLI
- reusable scenario files stored under the tool root
- profile/TUI support for saving default function/scenario selections
- a resolved scenario manifest passed from the Python tool to compatibility backends
- E2E backends honoring selected functions instead of hardcoded demo matrices

**Out of scope**

- redesign of load generation internals
- replacing every shell backend with native Python execution
- changing the underlying function images/examples themselves
- milestone 5 metrics/load reporting integration beyond carrying metadata forward
- milestone 6 `nanofaas-cli` feature redesign

## Milestone 4 Contract

At the end of this milestone, the repository should support this UX:

```text
scripts/controlplane.sh functions list
scripts/controlplane.sh functions show-preset demo-java
scripts/controlplane.sh e2e run k8s-vm --function-preset demo-java --dry-run
scripts/controlplane.sh e2e run helm-stack --functions word-stats-java,json-transform-java
scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml
scripts/controlplane.sh e2e run k3s-curl --saved-profile demo-java
scripts/controlplane.sh tui
```

Functional rules:

- function selection may come from one of:
  - `--function-preset <name>`
  - `--functions <csv>`
  - `--scenario-file <path>`
  - `--saved-profile <name>`
- precedence is explicit CLI override first, then scenario file, then saved profile defaults
- the resolved function set is normalized before execution and passed to backends through one manifest contract
- local single-function scenarios fail fast when a multi-function selection is invalid
- VM/demo/load scenarios use the selected subset instead of an implicit hardcoded matrix

## Recommended Data Model

Use TOML for scenario specs to stay consistent with existing tool profiles and avoid a new parser dependency.

Suggested scenario file shape:

```toml
name = "k8s-demo-java"
base_scenario = "k8s-vm"
runtime = "java"
function_preset = "demo-java"
namespace = "nanofaas-e2e"
local_registry = "localhost:5000"

[invoke]
mode = "smoke"
payload_dir = "payloads"

[payloads]
word-stats-java = "word-stats-sample.json"
json-transform-java = "json-transform-sample.json"

[load]
profile = "quick"
targets = ["word-stats-java", "json-transform-java"]
```

Suggested resolved manifest shape:

```json
{
  "name": "k8s-demo-java",
  "baseScenario": "k8s-vm",
  "runtime": "java",
  "functions": [
    {
      "key": "word-stats-java",
      "family": "word-stats",
      "runtime": "java",
      "image": "localhost:5000/nanofaas/word-stats-java:e2e",
      "payloadPath": "/abs/path/to/payloads/word-stats-sample.json"
    }
  ],
  "load": {
    "profile": "quick",
    "targets": ["word-stats-java", "json-transform-java"]
  }
}
```

### Task 1: Add the function catalog and preset model

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Create: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Create: `tools/controlplane/src/controlplane_tool/function_commands.py`
- Test: `tools/controlplane/tests/test_cli_smoke.py`
- Create: `tools/controlplane/tests/test_function_catalog.py`
- Create: `tools/controlplane/tests/test_function_commands.py`

**Step 1: Write the failing tests**

Add catalog tests that lock the repository’s real function inventory and preset behavior:

```python
from controlplane_tool.function_catalog import (
    list_function_presets,
    list_functions,
    resolve_function_preset,
)


def test_function_catalog_exposes_demo_families() -> None:
    keys = [function.key for function in list_functions()]
    assert "word-stats-java" in keys
    assert "json-transform-java" in keys
    assert "word-stats-go" in keys
    assert "json-transform-python" in keys
    assert "tool-metrics-echo" in keys


def test_demo_java_preset_contains_only_java_functions() -> None:
    preset = resolve_function_preset("demo-java")
    assert {function.runtime for function in preset.functions} == {"java"}
    assert {function.family for function in preset.functions} == {"word-stats", "json-transform"}


def test_functions_command_lists_known_presets() -> None:
    result = CliRunner().invoke(app, ["functions", "list"])
    assert result.exit_code == 0
    assert "demo-java" in result.stdout
    assert "demo-all" in result.stdout
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_function_commands.py -v
```

Expected: FAIL because the catalog and `functions` command group do not exist yet.

**Step 3: Write minimal implementation**

In `function_catalog.py`, define typed catalog entries:

```python
@dataclass(frozen=True)
class FunctionDefinition:
    key: str
    family: str
    runtime: RuntimeKind
    description: str
    example_dir: Path | None
    default_image: str | None
    default_payload_file: str | None


@dataclass(frozen=True)
class FunctionPreset:
    name: str
    description: str
    functions: tuple[FunctionDefinition, ...]
```

Seed the catalog from real repo functions:

- `word-stats-java`
- `json-transform-java`
- `word-stats-java-lite`
- `json-transform-java-lite`
- `word-stats-go`
- `json-transform-go`
- `word-stats-python`
- `json-transform-python`
- `word-stats-exec`
- `json-transform-exec`
- `tool-metrics-echo`

Add these presets at minimum:

- `demo-java`
- `demo-java-lite`
- `demo-go`
- `demo-python`
- `demo-exec`
- `demo-all`
- `metrics-smoke`

Add `functions` CLI commands:

```text
controlplane-tool functions list
controlplane-tool functions show <key>
controlplane-tool functions show-preset <name>
```

Register the command group in `main.py`.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/models.py \
  tools/controlplane/src/controlplane_tool/function_catalog.py \
  tools/controlplane/src/controlplane_tool/function_commands.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_function_commands.py
git commit -m "feat: add function catalog and preset commands"
```

### Task 2: Introduce the scenario-spec and resolved-manifest layer

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/paths.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_models.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_loader.py`
- Create: `tools/controlplane/scenarios/k8s-demo-java.toml`
- Create: `tools/controlplane/scenarios/k8s-demo-all.toml`
- Create: `tools/controlplane/scenarios/container-local-smoke.toml`
- Create: `tools/controlplane/scenarios/payloads/word-stats-sample.json`
- Create: `tools/controlplane/scenarios/payloads/json-transform-sample.json`
- Create: `tools/controlplane/scenarios/payloads/echo-sample.json`
- Test: `tools/controlplane/tests/test_paths.py`
- Create: `tools/controlplane/tests/test_scenario_loader.py`

**Step 1: Write the failing tests**

Add loader tests for presets, explicit lists, and relative payload resolution:

```python
from pathlib import Path

from controlplane_tool.scenario_loader import load_scenario_file


def test_loader_resolves_function_preset_and_payload_paths() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-java.toml"))
    assert scenario.base_scenario == "k8s-vm"
    assert scenario.function_preset == "demo-java"
    assert scenario.payloads["word-stats-java"].name == "word-stats-sample.json"


def test_loader_rejects_both_functions_and_function_preset() -> None:
    with pytest.raises(ValueError, match="exactly one of"):
        ScenarioSpec(
            name="bad",
            base_scenario="k8s-vm",
            runtime="java",
            function_preset="demo-java",
            functions=["word-stats-java"],
        )
```

Also update `test_paths.py` to assert the existence of `scenarios_dir`.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_paths.py \
  tools/controlplane/tests/test_scenario_loader.py -v
```

Expected: FAIL because the scenario loader and canonical scenario paths do not exist yet.

**Step 3: Write minimal implementation**

Extend `ToolPaths` with:

```python
scenarios_dir: Path
scenario_payloads_dir: Path
```

Create `scenario_models.py` with:

```python
class ScenarioInvokeConfig(BaseModel):
    mode: Literal["smoke", "sync", "async", "parity"] = "smoke"
    payload_dir: str | None = None


class ScenarioLoadConfig(BaseModel):
    profile: LoadProfile | None = None
    targets: list[str] = Field(default_factory=list)


class ScenarioSpec(BaseModel):
    name: str
    base_scenario: ScenarioName
    runtime: RuntimeKind = "java"
    function_preset: str | None = None
    functions: list[str] = Field(default_factory=list)
    namespace: str | None = None
    local_registry: str | None = None
    payloads: dict[str, str] = Field(default_factory=dict)
    invoke: ScenarioInvokeConfig = Field(default_factory=ScenarioInvokeConfig)
    load: ScenarioLoadConfig = Field(default_factory=ScenarioLoadConfig)
```

Validation rules:

- exactly one of `function_preset` or `functions`
- every named function must exist in the catalog
- payload files resolve relative to the scenario file directory or its payload dir
- `load.targets` must be a subset of the selected functions

Create `scenario_loader.py` that:

- loads TOML via `tomllib`
- validates into `ScenarioSpec`
- resolves payload paths to absolute `Path`s
- can produce a `ResolvedScenario` object with catalog-enriched function metadata

Create initial scenario files:

- `k8s-demo-java.toml`
- `k8s-demo-all.toml`
- `container-local-smoke.toml`

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/paths.py \
  tools/controlplane/src/controlplane_tool/scenario_models.py \
  tools/controlplane/src/controlplane_tool/scenario_loader.py \
  tools/controlplane/scenarios/k8s-demo-java.toml \
  tools/controlplane/scenarios/k8s-demo-all.toml \
  tools/controlplane/scenarios/container-local-smoke.toml \
  tools/controlplane/scenarios/payloads/word-stats-sample.json \
  tools/controlplane/scenarios/payloads/json-transform-sample.json \
  tools/controlplane/scenarios/payloads/echo-sample.json \
  tools/controlplane/tests/test_paths.py \
  tools/controlplane/tests/test_scenario_loader.py
git commit -m "feat: add reusable scenario specs for controlplane e2e flows"
```

### Task 3: Thread selection through CLI and E2E request models

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Test: `tools/controlplane/tests/test_e2e_models.py`
- Modify: `tools/controlplane/tests/test_e2e_commands.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Write the failing tests**

Add selection-precedence and render tests:

```python
def test_e2e_request_accepts_function_preset() -> None:
    request = E2eRequest(
        scenario="k8s-vm",
        runtime="java",
        function_preset="demo-java",
        vm=VmRequest(lifecycle="multipass"),
    )
    assert request.function_preset == "demo-java"


def test_e2e_run_dry_run_renders_resolved_functions() -> None:
    result = CliRunner().invoke(
        app,
        ["e2e", "run", "k8s-vm", "--function-preset", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "word-stats-java" in result.stdout
    assert "json-transform-java" in result.stdout


def test_e2e_explicit_functions_override_saved_profile_defaults() -> None:
    ...
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_e2e_models.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py -v
```

Expected: FAIL because `E2eRequest` does not yet model function selection and the commands cannot resolve it.

**Step 3: Write minimal implementation**

Extend `E2eRequest` with:

```python
function_preset: str | None = None
functions: list[str] = Field(default_factory=list)
scenario_file: Path | None = None
saved_profile: str | None = None
```

Add a small resolver layer in `e2e_commands.py`:

1. load saved profile defaults when `--saved-profile` is present
2. load scenario file when `--scenario-file` is present
3. apply explicit `--function-preset` or `--functions` last
4. build one resolved scenario object before handing off to `E2eRunner`

CLI surface to add:

- `--function-preset <name>`
- `--functions <csv>`
- `--scenario-file <path>`
- `--saved-profile <name>`

Update dry-run rendering so it prints:

- scenario source
- resolved function keys
- runtime
- load targets, when present

Do not let `e2e_runner.py` parse raw CSVs or file paths; it should receive an already normalized request/resolved selection.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/e2e_models.py \
  tools/controlplane/src/controlplane_tool/e2e_commands.py \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/models.py \
  tools/controlplane/tests/test_e2e_models.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_e2e_runner.py
git commit -m "feat: add first-class function selection to e2e commands"
```

### Task 4: Pass one resolved scenario manifest into the compatibility backends

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario_manifest.py`
- Create: `scripts/lib/scenario-manifest.sh`
- Modify: `scripts/lib/e2e-container-local-backend.sh`
- Modify: `scripts/lib/e2e-deploy-host-backend.sh`
- Modify: `scripts/lib/e2e-k3s-curl-backend.sh`
- Modify: `scripts/lib/e2e-cli-backend.sh`
- Modify: `scripts/lib/e2e-cli-host-backend.sh`
- Modify: `scripts/lib/e2e-helm-stack-backend.sh`
- Modify: `scripts/tests/test_e2e_runtime_runners.py`
- Create: `tools/controlplane/tests/test_scenario_manifest.py`

**Step 1: Write the failing tests**

Lock the new contract:

```python
def test_runner_writes_manifest_and_exports_it_to_backend(tmp_path: Path) -> None:
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(
        E2eRequest(
            scenario="k3s-curl",
            runtime="java",
            function_preset="demo-java",
            vm=VmRequest(lifecycle="multipass"),
        )
    )
    backend_step = plan.steps[-1]
    assert backend_step.env["NANOFAAS_SCENARIO_PATH"].endswith(".json")


def test_container_local_backend_uses_manifest_selected_function() -> None:
    ...
```

Add shell-side tests that the backends no longer rely only on hardcoded defaults when `NANOFAAS_SCENARIO_PATH` is set.

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_manifest.py \
  tools/controlplane/tests/test_e2e_runner.py -v

python3 -m pytest scripts/tests/test_e2e_runtime_runners.py -q
```

Expected: FAIL because the runner does not yet materialize a manifest and the backends do not read one.

**Step 3: Write minimal implementation**

Create `scenario_manifest.py` with helpers to:

- serialize `ResolvedScenario` to JSON
- write it under `tools/controlplane/runs/manifests/` or a temp run subdir
- keep paths absolute

In `E2eRunner`, write the manifest before building the backend step and pass:

```python
env = {"NANOFAAS_SCENARIO_PATH": str(manifest_path)}
```

Add `scripts/lib/scenario-manifest.sh` with helper functions like:

```bash
scenario_json_get() { python3 - "$NANOFAAS_SCENARIO_PATH" "$1" <<'PY' ... PY; }
scenario_selected_functions() { ... }
scenario_payload_path() { ... }
```

Update each backend:

- `container-local`: require exactly one selected function and derive `FUNCTION_NAME`/payload from the manifest
- `deploy-host`: same single-function rule
- `k3s-curl`: register/invoke the selected functions instead of always `echo-test`
- `cli` and `cli-host`: drive the selected function specs instead of a hardcoded suite
- `helm-stack`: use manifest `load.targets` and selected functions to choose which demos/load workloads to exercise

Fail fast with a clear message when the chosen scenario cannot support the selected function count or runtime mix.

**Step 4: Run tests to verify they pass**

Run the same commands from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/e2e_runner.py \
  tools/controlplane/src/controlplane_tool/scenario_manifest.py \
  scripts/lib/scenario-manifest.sh \
  scripts/lib/e2e-container-local-backend.sh \
  scripts/lib/e2e-deploy-host-backend.sh \
  scripts/lib/e2e-k3s-curl-backend.sh \
  scripts/lib/e2e-cli-backend.sh \
  scripts/lib/e2e-cli-host-backend.sh \
  scripts/lib/e2e-helm-stack-backend.sh \
  scripts/tests/test_e2e_runtime_runners.py \
  tools/controlplane/tests/test_scenario_manifest.py
git commit -m "feat: route e2e backends through resolved scenario manifests"
```

### Task 5: Save scenario/function defaults in profiles and surface them in the TUI

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/profiles.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui.py`
- Modify: `tools/controlplane/src/controlplane_tool/main.py`
- Test: `tools/controlplane/tests/test_profiles.py`
- Test: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_cli_smoke.py`

**Step 1: Write the failing tests**

Add profile roundtrip and TUI-choice coverage:

```python
def test_profile_roundtrip_with_e2e_selection(tmp_path: Path) -> None:
    profile = Profile(
        name="demo-java",
        control_plane=...,
        scenario=ScenarioSelectionConfig(
            base_scenario="k8s-vm",
            function_preset="demo-java",
            namespace="nanofaas-e2e",
        ),
    )
    save_profile(profile, root=tmp_path)
    assert load_profile("demo-java", root=tmp_path).scenario.function_preset == "demo-java"


def test_tui_can_save_default_function_preset(monkeypatch) -> None:
    ...
```

**Step 2: Run tests to verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_cli_smoke.py -v
```

Expected: FAIL because profiles do not yet carry scenario defaults and the TUI does not ask for them.

**Step 3: Write minimal implementation**

Add a `ScenarioSelectionConfig` section to `Profile`, for example:

```python
class ScenarioSelectionConfig(BaseModel):
    base_scenario: ScenarioName | None = None
    function_preset: str | None = None
    functions: list[str] = Field(default_factory=list)
    scenario_file: str | None = None
    namespace: str | None = None
    local_registry: str | None = None
```

Update the TUI wizard:

- ask whether the profile should carry default E2E selection
- allow choosing either:
  - a scenario file
  - a preset
  - an explicit function CSV
- save the result in the profile

Do not make the E2E section mandatory; build/test-only profiles should remain valid unchanged.

`e2e_commands.py` should read these defaults when `--saved-profile` is passed.

**Step 4: Run tests to verify they pass**

Run the same command from Step 2.

Expected: PASS.

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/models.py \
  tools/controlplane/src/controlplane_tool/profiles.py \
  tools/controlplane/src/controlplane_tool/tui.py \
  tools/controlplane/src/controlplane_tool/main.py \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_tui_choices.py \
  tools/controlplane/tests/test_cli_smoke.py
git commit -m "feat: store default e2e selection in tool profiles"
```

### Task 6: Update docs and run end-to-end selection verification

**Files:**
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/e2e-tutorial.md`
- Modify: `tools/controlplane/tests/test_wrapper_docs.py`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Update docs**

Document:

- built-in function presets
- scenario-file location under `tools/controlplane/scenarios/`
- precedence rules among CLI override, scenario file, and saved profile
- examples for:
  - `functions list`
  - `e2e run --function-preset demo-java`
  - `e2e run --scenario-file ...`
  - `e2e run --saved-profile demo-java`

**Step 2: Run verification suites**

Run:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q

uv run --project tools/controlplane --locked controlplane-tool functions list
uv run --project tools/controlplane --locked controlplane-tool functions show-preset demo-java
uv run --project tools/controlplane --locked controlplane-tool e2e run k8s-vm --function-preset demo-java --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
```

Expected:

- all tool tests pass
- wrapper/runtime tests pass
- dry-run output includes resolved function names and scenario source
- no shell env-var editing is required to choose the function set

**Step 3: Commit**

```bash
git add \
  tools/controlplane/README.md \
  README.md \
  docs/testing.md \
  docs/e2e-tutorial.md \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_docs_links.py
git commit -m "docs: document function and scenario selection for controlplane"
```

## Final Verification

Run this full set before claiming M4 complete:

```bash
uv run --project tools/controlplane pytest tools/controlplane/tests -v

python3 -m pytest \
  scripts/tests/test_e2e_runtime_runners.py \
  scripts/tests/test_controlplane_e2e_wrapper_runtime.py -q

uv run --project tools/controlplane --locked controlplane-tool functions list
uv run --project tools/controlplane --locked controlplane-tool functions show word-stats-java
uv run --project tools/controlplane --locked controlplane-tool functions show-preset demo-all
uv run --project tools/controlplane --locked controlplane-tool e2e run k3s-curl --function-preset demo-java --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-java.toml --dry-run
uv run --project tools/controlplane --locked controlplane-tool e2e run k8s-vm --saved-profile demo-java --dry-run
```

Expected:

- function catalog and presets are discoverable from the CLI
- scenario specs resolve correctly from TOML files
- TUI-saved profiles can supply default function/scenario selection
- backends receive one normalized manifest instead of relying on hardcoded function lists
