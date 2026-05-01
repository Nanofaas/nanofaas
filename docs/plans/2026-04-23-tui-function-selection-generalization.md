# TUI Function Selection Generalization Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Generalize the TUI function-selection pattern already implemented for `k3s-junit-curl` to `cli-stack`, `deploy-host`, and `container-local`, while extracting reusable internal selection helpers so the TUI does not duplicate preset/scenario/profile filtering logic.

**Architecture:** Add a small pure internal library, `controlplane_tool.tui_selection`, that models function-selection targets and produces compatible choices for presets, single functions, scenario files, and saved profiles. Keep request resolution in the existing CLI/E2E resolvers (`e2e_commands._resolve_run_request` and existing scenario runners) instead of reimplementing selection semantics in the TUI. The TUI should only prompt, convert the prompt result into resolver arguments, and pass the resolved request into existing flows. `helm-stack`, `host-platform`, `docker`, and `buildpack` stay out of scope.

**Tech Stack:** Python, Typer, Questionary, Rich, Pydantic, pytest with monkeypatch, GitNexus impact/detect checks.

---

## Review Corrections Applied

- GitNexus MCP currently reports a stale index in this worktree; refresh it before any code edits and before trusting impact results.
- `NanofaasTUI` and `build_scenario_flow` are high-blast-radius symbols. Treat their impact warnings as expected pre-change gates: report them, confirm no HIGH/CRITICAL risk is ignored, then keep edits narrow and covered by targeted tests.
- Avoid duplicating resolver argument mapping in each TUI branch. `TuiSelectionResult` should expose resolver kwargs, and `tui_app.py` should centralize E2E request construction in one private adapter.
- Function buildability must be based on executable catalog metadata, not just runtime string. A buildable selectable function needs a supported runtime, an example directory, and a default/resolved image.

## Direct Dependent Verification Matrix for Tasks 4-6

Before touching Tasks 4-6, record the d=1 dependents from GitNexus for the symbols that will actually change and verify them explicitly instead of relying only on coarse class-level risk warnings.

### Task 4: `cli-stack` TUI selection support

Edited symbol: `NanofaasTUI._cli_e2e_menu`

Direct dependents to verify:
- `tests/test_tui_choices.py::test_tui_cli_e2e_menu_offers_cli_stack_runner`
- `tests/test_tui_choices.py::test_tui_cli_e2e_menu_describes_host_platform_as_compatibility_path`
- `tests/test_tui_choices.py::test_tui_described_selectors_include_back_entries`
- `NanofaasTUI._validation_menu`

Required verification mapping:
- prove the two `cli-stack` menu tests still pass
- prove described-selector/back navigation still passes
- prove `_validation_menu` still routes correctly through `tests/test_tui_choices.py -k "validation_menu or cli_e2e_menu"`

Class-level note:
- `NanofaasTUI` may still show CRITICAL blast radius at class scope; treat that as a guardrail warning, not the primary verification checklist. Use function-level dependents above for execution tracking.

### Task 5: resolved selections passed directly to local runners

Edited symbols:
- `build_scenario_flow`
- `ContainerLocalE2eRunner.run`
- `DeployHostE2eRunner.run`

#### `build_scenario_flow` d=1 dependents

Direct dependent tests:
- `tests/test_scenario_flows.py::test_k3s_junit_curl_flow_uses_reusable_vm_build_and_deploy_tasks`
- `tests/test_scenario_flows.py::test_cli_vm_flow_reuses_build_and_helm_deploy_tasks`
- `tests/test_scenario_flows.py::test_cli_stack_flow_uses_dedicated_cli_stack_task_order`
- `tests/test_scenario_flows.py::test_cli_stack_flow_routes_through_e2e_runner`
- `tests/test_scenario_flows.py::test_cli_stack_flow_task_ids_are_derived_from_the_recipe`
- `tests/test_scenario_flows.py::test_k3s_junit_curl_flow_task_ids_are_derived_from_the_recipe`
- `tests/test_scenario_flows.py::test_cli_stack_flow_no_longer_needs_a_preexisting_vm_request`
- `tests/test_scenario_flows.py::test_k3s_junit_curl_flow_requires_request_for_executable_definition`
- `tests/test_scenario_flows.py::test_request_backed_scenario_flow_forwards_event_listener`
- `tests/test_scenario_flows.py::test_helm_stack_flow_shares_k3s_junit_curl_prefix`
- `tests/test_scenario_flows.py::test_helm_stack_flow_routes_through_python_e2e_runner`
- `tests/test_scenario_flows.py::test_helm_stack_flow_preserves_noninteractive_flag`

Direct dependent runtime/TUI callers:
- `NanofaasTUI._run_helm_stack_workflow`
- `NanofaasTUI._run_k8s_vm_workflow`
- `NanofaasTUI._run_container_local_workflow`
- `NanofaasTUI._run_deploy_host_workflow`
- `NanofaasTUI._run_generic_e2e_workflow`
- `NanofaasTUI._run_cli_vm_workflow`
- `NanofaasTUI._run_cli_stack_workflow`
- `NanofaasTUI._run_cli_host_workflow`
- `resolve_flow_definition`

Required verification mapping:
- run the targeted `tests/test_scenario_flows.py` cases listed above
- run `tests/test_cli_runtime.py` resolved-scenario cases for local runners
- run `tests/test_tui_choices.py -k "k3s or helm_stack or cli_stack or container_local or deploy_host"` to cover the direct TUI callers that delegate into `build_scenario_flow`
- run `tests/test_flow_catalog.py -k "flow_catalog.*e2e or requestless_runtime_scenario_definition or requestless_loadtest_definition"` to cover `resolve_flow_definition`

#### `ContainerLocalE2eRunner.run` d=1 dependents

Direct dependents:
- `tests/test_cli_runtime.py::test_container_local_runner_emits_balanced_top_level_phase_events_and_verify_children`
- `tests/test_cli_runtime.py::test_container_local_runner_builds_javascript_function_images`
- `build_scenario_flow`
- importer `tests/test_cli_runtime.py`
- importer `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- importer `tools/controlplane/src/controlplane_tool/local_e2e_runner.py`

Required verification mapping:
- both targeted `test_cli_runtime.py` cases must pass
- `build_scenario_flow` verification above must pass
- test collection/import for `tests/test_cli_runtime.py` and `tests/test_scenario_flows.py` must succeed

#### `DeployHostE2eRunner.run` d=1 dependents

Direct dependents:
- `tests/test_cli_runtime.py::test_deploy_host_runner_emits_balanced_top_level_phase_events_and_verify_children`
- `build_scenario_flow`
- importer `tests/test_cli_runtime.py`
- importer `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- importer `tools/controlplane/src/controlplane_tool/local_e2e_runner.py`

Required verification mapping:
- targeted `test_cli_runtime.py` deploy-host case must pass
- `build_scenario_flow` verification above must pass
- test collection/import for `tests/test_cli_runtime.py` and `tests/test_scenario_flows.py` must succeed

### Task 6: `deploy-host` TUI selection support

Edited symbol: `NanofaasTUI._run_deploy_host`

Direct dependents to verify:
- `NanofaasTUI._validation_menu`
- `NanofaasTUI._platform_validation_menu`

Indirect-but-near tests to keep in the Task 6 verification set:
- `tests/test_tui_choices.py::test_validation_menu_contains_platform_cli_and_host_paths`
- `tests/test_tui_choices.py::test_validation_menu_routes_host_path_to_deploy_host`
- `tests/test_tui_choices.py::test_platform_validation_menu_returns_to_scenario_picker_after_dry_run`
- `tests/test_tui_choices.py::test_tui_described_selectors_include_back_entries`
- `tests/test_tui_choices.py::test_tui_e2e_menu_marks_vm_scenarios_as_self_bootstrapping`

Required verification mapping:
- run `tests/test_tui_choices.py -k "deploy_host or validation_menu or platform_validation_menu"`
- ensure the new `deploy-host` source-selection tests pass together with existing routing/navigation tests

Execution rule:
- Do not mark Tasks 4-6 complete until each dependent group above has an explicit passing verification command or an explicit documented rationale for non-applicability.

### Task 7: `container-local` TUI single-function selection support

Edited symbol: `NanofaasTUI._run_container_local`

Direct dependents to verify:
- `NanofaasTUI._platform_validation_menu`
- `NanofaasTUI`

Indirect-but-near tests to keep in the Task 7 verification set:
- `tests/test_tui_choices.py::test_tui_e2e_menu_marks_vm_scenarios_as_self_bootstrapping`
- `tests/test_tui_choices.py::test_tui_described_selectors_include_back_entries`
- `tests/test_tui_choices.py::test_platform_validation_menu_returns_to_scenario_picker_after_dry_run`
- `tests/test_tui_choices.py::test_validation_menu_contains_platform_cli_and_host_paths`

New direct-selection tests required:
- single-function source accepts `word-stats-javascript`
- scenario-file source only accepts compatible single-function manifests
- saved-profile source hides multi-function profiles and accepts compatible single-function profiles
- no compatible saved profiles warns and reprompts

Required verification mapping:
- run `tests/test_tui_choices.py -k "container_local"` for the new selection-source tests
- run the routing/navigation tests above to prove `_platform_validation_menu` and adjacent navigation still behave correctly
- if `build_scenario_flow` is affected through the new request wiring, confirm the `container-local` path still works through the existing Task 5 runtime/flow verification set

### Task 1: Lock the pure selection library contract with failing tests

**Files:**
- Create: `tools/controlplane/tests/test_tui_selection.py`
- Create later: `tools/controlplane/src/controlplane_tool/tui_selection.py`

**Step 1: Write tests for target source choices**

Create `tools/controlplane/tests/test_tui_selection.py` and add tests for these target configs:

```python
from controlplane_tool.tui_selection import (
    TuiSelectionTarget,
    selection_source_choices,
)


def test_multi_function_target_exposes_default_preset_scenario_and_profile_sources() -> None:
    target = TuiSelectionTarget(
        key="cli-stack",
        label="cli-stack",
        resolver_scenario="cli-stack",
        selection_mode="multi",
        allow_default=True,
        allow_presets=True,
        allow_single_functions=False,
        allow_scenario_files=True,
        allow_saved_profiles=True,
    )

    assert [choice.value for choice in selection_source_choices(target)] == [
        "default",
        "preset",
        "scenario-file",
        "saved-profile",
    ]


def test_single_function_target_exposes_function_not_preset_source() -> None:
    target = TuiSelectionTarget(
        key="container-local",
        label="container-local",
        resolver_scenario="container-local",
        selection_mode="single",
        allow_default=True,
        allow_presets=False,
        allow_single_functions=True,
        allow_scenario_files=True,
        allow_saved_profiles=True,
    )

    assert [choice.value for choice in selection_source_choices(target)] == [
        "default",
        "function",
        "scenario-file",
        "saved-profile",
    ]
```

**Step 2: Write tests for preset and function filtering**

Lock these rules:
- multi-function targets can show multi-function presets such as `demo-javascript`
- single-function targets do not show multi-function presets
- single-function targets show buildable individual functions such as `word-stats-javascript`
- fixture functions such as `tool-metrics-echo` are not shown for `container-local`

```python
from controlplane_tool.tui_selection import function_choices, preset_choices


def test_cli_stack_preset_choices_include_demo_javascript() -> None:
    values = [choice.value for choice in preset_choices(cli_stack_target())]
    assert "demo-javascript" in values


def test_container_local_function_choices_include_javascript_functions_not_fixtures() -> None:
    values = [choice.value for choice in function_choices(container_local_target())]
    assert "word-stats-javascript" in values
    assert "json-transform-javascript" in values
    assert "tool-metrics-echo" not in values
```

Also lock the request handoff shape so every TUI branch uses one mapping from selection result to resolver kwargs:

```python
from pathlib import Path

from controlplane_tool.tui_selection import TuiSelectionResult


def test_selection_result_exposes_resolver_kwargs() -> None:
    result = TuiSelectionResult(
        source="scenario-file",
        scenario_file=Path("tools/controlplane/scenarios/k8s-demo-javascript.toml"),
    )

    assert result.as_resolver_kwargs() == {
        "function_preset": None,
        "functions_csv": None,
        "scenario_file": Path("tools/controlplane/scenarios/k8s-demo-javascript.toml"),
        "saved_profile": None,
    }
```

**Step 3: Write tests for scenario-file filtering**

Lock these rules:
- `k3s-junit-curl` keeps its current strict behavior: only scenario files whose resolved `base_scenario == "k3s-junit-curl"` are shown
- `cli-stack` and `deploy-host` can reuse any scenario file with at least one buildable selected function, because their resolvers can overlay the selected function set onto the target scenario
- `container-local` only shows scenario files that resolve to exactly one buildable selected function
- invalid TOML files are skipped, not surfaced as crashes

Example test skeleton:

```python
def test_container_local_scenario_file_choices_require_exactly_one_function(monkeypatch) -> None:
    import controlplane_tool.tui_selection as selection

    monkeypatch.setattr(selection, "default_tool_paths", lambda: fake_paths)
    monkeypatch.setattr(selection, "load_scenario_file", fake_load_scenario_file)

    values = [choice.value for choice in selection.scenario_file_choices(container_local_target())]

    assert values == ["tools/controlplane/scenarios/single-word-stats-javascript.toml"]
```

**Step 4: Write tests for saved-profile filtering**

Lock these rules:
- a saved profile is shown only when it contributes a compatible function selection through `profile.scenario.function_preset`, `profile.scenario.functions`, or `profile.scenario.scenario_file`
- generic profiles with no function selection are not shown as a `saved-profile` selection source
- `cli-stack` can show `demo-javascript` even though that profile's `[scenario] base_scenario` is `k3s-junit-curl`, because the target resolver will overlay the selected functions onto `cli-stack`
- `container-local` only shows saved profiles that resolve to exactly one buildable selected function

**Step 5: Run the new tests and confirm they fail**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_selection.py -v
```

Expected: FAIL because `controlplane_tool.tui_selection` does not exist yet.

**Step 6: Commit the red baseline**

```bash
git add tools/controlplane/tests/test_tui_selection.py
git commit -m "test(tui): lock reusable selection helper contract"
```

### Task 2: Implement `tui_selection.py` as a reusable internal library

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/tui_selection.py`
- Modify: `tools/controlplane/tests/test_tui_selection.py`

**Step 1: Refresh GitNexus and run impact checks before editing symbols**

Run:

```bash
gitnexus analyze
```

Expected: the GitNexus index is refreshed for the current worktree before impact analysis. If the command reports that embeddings existed before the refresh, re-run with the embedding-preserving option used by this repository.

Run GitNexus impact analysis before modifying existing symbols in later tasks:

```text
gitnexus_impact({target: "NanofaasTUI", direction: "upstream"})
gitnexus_impact({target: "build_scenario_flow", direction: "upstream"})
gitnexus_impact({target: "ContainerLocalE2eRunner.run", direction: "upstream"})
gitnexus_impact({target: "DeployHostE2eRunner.run", direction: "upstream"})
```

Expected: report blast radius before implementation. Current review already found `NanofaasTUI` as CRITICAL and `build_scenario_flow` as HIGH because they are central TUI/flow entrypoints. Stop and warn the user before proceeding with those edits; proceed only after the warning is acknowledged and the test scope below is kept.

**Step 2: Add the core dataclasses**

Implement this public internal API in `tui_selection.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
from typing import Literal

import questionary

SelectionMode = Literal["single", "multi"]
SelectionSource = Literal["default", "preset", "function", "scenario-file", "saved-profile"]


@dataclass(frozen=True, slots=True)
class TuiSelectionTarget:
    key: str
    label: str
    resolver_scenario: str
    selection_mode: SelectionMode
    allow_default: bool = True
    allow_presets: bool = True
    allow_single_functions: bool = False
    allow_scenario_files: bool = True
    allow_saved_profiles: bool = True
    strict_base_scenarios: frozenset[str] | None = None


@dataclass(frozen=True, slots=True)
class TuiSelectionResult:
    source: SelectionSource
    function_preset: str | None = None
    functions_csv: str | None = None
    scenario_file: Path | None = None
    saved_profile: str | None = None

    @property
    def summary_lines(self) -> list[str]:
        ...

    def as_resolver_kwargs(self) -> dict[str, object]:
        return {
            "function_preset": self.function_preset,
            "functions_csv": self.functions_csv,
            "scenario_file": self.scenario_file,
            "saved_profile": self.saved_profile,
        }
```

**Step 3: Implement choice helpers**

Implement these functions:

```python
def selection_source_choices(target: TuiSelectionTarget) -> list[questionary.Choice]: ...
def preset_choices(target: TuiSelectionTarget) -> list[questionary.Choice]: ...
def function_choices(target: TuiSelectionTarget) -> list[questionary.Choice]: ...
def scenario_file_choices(target: TuiSelectionTarget) -> list[questionary.Choice]: ...
def saved_profile_choices(target: TuiSelectionTarget) -> list[questionary.Choice]: ...
```

Implementation rules:
- use `list_function_presets()`, `list_functions()`, `list_profiles()`, `load_profile()`, and `load_scenario_file()`
- keep all filtering pure and deterministic
- catch invalid scenario/profile reads and skip those entries
- never prompt inside this module
- never import `NanofaasTUI`

**Step 4: Implement buildability and cardinality helpers**

Add internal helpers:

```python
BUILDABLE_FUNCTION_RUNTIMES = frozenset({"java", "java-lite", "go", "python", "javascript", "exec"})


def _is_buildable_function(function) -> bool:
    image = getattr(function, "default_image", None) or getattr(function, "image", None)
    return (
        function.runtime in BUILDABLE_FUNCTION_RUNTIMES
        and getattr(function, "example_dir", None) is not None
        and bool(image)
    )


def _matches_cardinality(target: TuiSelectionTarget, functions: list[object]) -> bool:
    buildable = [function for function in functions if _is_buildable_function(function)]
    if target.selection_mode == "single":
        return len(buildable) == 1
    return len(buildable) >= 1
```

Apply `target.strict_base_scenarios` only when it is not `None`. This preserves strict behavior for `k3s-junit-curl` while allowing `cli-stack` and `deploy-host` to reuse existing `k3s` demo scenario files. `container-local` must still pass cardinality and buildability checks after resolving/overlaying the scenario.

**Step 5: Run tests and make them pass**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_selection.py -v
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_selection.py tools/controlplane/tests/test_tui_selection.py
git commit -m "feat(tui): add reusable function selection helpers"
```

### Task 3: Refactor existing `k3s-junit-curl` TUI selection onto the shared helper

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write/update tests proving behavior stays the same**

Update existing `k3s` tests so they assert behavior through the shared helper path:
- default source still resolves `demo-java`
- preset source `demo-javascript` still resolves `word-stats-javascript` and `json-transform-javascript`
- scenario file source still preserves `request.scenario_file`
- saved profile source still preserves `request.saved_profile`
- empty scenario/profile choice lists still warn and reprompt

Also update any tests that expected a generic saved profile with no function selection to appear. Under the generalized contract, saved profiles without function selection are not valid function-selection sources and should not be shown.

**Step 2: Replace `k3s`-specific helper constants**

Remove these `tui_app.py` symbols:

```python
_K3S_SELECTION_SOURCE_CHOICES
_k3s_scenario_file_choices
_k3s_saved_profile_choices
```

Replace them with a target config:

```python
from controlplane_tool.tui_selection import (
    TuiSelectionTarget,
    function_choices,
    preset_choices,
    saved_profile_choices,
    scenario_file_choices,
    selection_source_choices,
)

K3S_SELECTION_TARGET = TuiSelectionTarget(
    key="k3s-junit-curl",
    label="k3s-junit-curl",
    resolver_scenario="k3s-junit-curl",
    selection_mode="multi",
    allow_default=True,
    allow_presets=True,
    allow_single_functions=False,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=frozenset({"k3s-junit-curl"}),
)
```

**Step 3: Extract a reusable prompt function in `tui_app.py`**

Add a private TUI adapter function that handles prompting and warnings:

```python
def _prompt_function_selection(target: TuiSelectionTarget) -> TuiSelectionResult:
    while True:
        source = _ask(lambda: _select_described_value(
            "Selection source:",
            choices=selection_source_choices(target),
        ))
        if source == "default":
            return TuiSelectionResult(source="default")
        if source == "preset":
            choices = preset_choices(target)
            if not choices:
                warning(f"No compatible function presets found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="preset",
                function_preset=_ask(lambda: _select_described_value("Function preset:", choices=choices)),
            )
        if source == "function":
            choices = function_choices(target)
            if not choices:
                warning(f"No compatible functions found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="function",
                functions_csv=_ask(lambda: _select_described_value("Function:", choices=choices)),
            )
        if source == "scenario-file":
            choices = scenario_file_choices(target)
            if not choices:
                warning(f"No compatible scenario files found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="scenario-file",
                scenario_file=Path(_ask(lambda: _select_described_value("Scenario file:", choices=choices))),
            )
        if source == "saved-profile":
            choices = saved_profile_choices(target)
            if not choices:
                warning(f"No compatible saved profiles found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="saved-profile",
                saved_profile=_ask(lambda: _select_described_value("Saved profile:", choices=choices)),
            )
```

**Step 4: Use the prompt result in the existing `k3s` branch**

Replace local `function_preset`, `scenario_file`, and `saved_profile` variables with:

```python
selection = _prompt_function_selection(K3S_SELECTION_TARGET)
...
request = _resolve_run_request(
    scenario="k3s-junit-curl",
    ...
    function_preset=selection.function_preset,
    functions_csv=selection.functions_csv,
    scenario_file=selection.scenario_file,
    saved_profile=selection.saved_profile,
)
```

Append `selection.summary_lines` to workflow summary lines.

**Step 5: Run targeted tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_selection.py tests/test_tui_choices.py -k "k3s or selection" -v
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py tools/controlplane/tests/test_tui_choices.py
git commit -m "refactor(tui): reuse selection helpers for k3s"
```

### Task 4: Add selection-source support to `cli-stack`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write failing TUI tests for `cli-stack`**

Add tests under `tools/controlplane/tests/test_tui_choices.py` that lock:
- default source keeps current behavior
- preset source `demo-javascript` resolves `word-stats-javascript` and `json-transform-javascript`
- saved profile source `demo-javascript` resolves the same JavaScript functions
- scenario-file source can reuse `tools/controlplane/scenarios/k8s-demo-javascript.toml`
- request is passed into `build_scenario_flow("cli-stack", request=...)`

Expected assertions:

```python
assert called["scenario"] == "cli-stack"
assert called["request"].scenario == "cli-stack"
assert called["request"].function_preset == "demo-javascript"
assert called["request"].resolved_scenario.function_keys == [
    "word-stats-javascript",
    "json-transform-javascript",
]
```

**Step 2: Add the target config**

In `tui_app.py`, add:

```python
CLI_STACK_SELECTION_TARGET = TuiSelectionTarget(
    key="cli-stack",
    label="cli-stack",
    resolver_scenario="cli-stack",
    selection_mode="multi",
    allow_default=True,
    allow_presets=True,
    allow_single_functions=False,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=None,
)
```

`strict_base_scenarios=None` is intentional: `cli-stack` can reuse a `k3s` demo manifest as a function-selection source because the E2E resolver overlays selected functions onto the requested `cli-stack` scenario.

**Step 3: Resolve a real `E2eRequest` for `cli-stack`**

Inside the `runner_choice == "cli-stack"` branch, import and use `controlplane_tool.e2e_commands._resolve_run_request`:

```python
selection = _prompt_function_selection(CLI_STACK_SELECTION_TARGET)
cli_stack_request = _resolve_run_request(
    scenario="cli-stack",
    runtime="java",
    lifecycle="multipass",
    name="nanofaas-e2e",
    host=None,
    user="ubuntu",
    home=None,
    cpus=4,
    memory="12G",
    disk="30G",
    cleanup_vm=False,
    namespace=None,
    local_registry=None,
    function_preset=selection.function_preset,
    functions_csv=selection.functions_csv,
    scenario_file=selection.scenario_file,
    saved_profile=selection.saved_profile,
)
```

Use this request both for planning and for `build_scenario_flow(..., request=cli_stack_request, event_listener=...)`.

**Step 4: Preserve existing live workflow behavior**

Keep event listener integration and planned steps from `E2eRunner(repo_root).plan(cli_stack_request)`.

Add summary lines:

```python
summary_lines=[
    "Runner: cli-stack",
    "Mode: canonical self-bootstrapping VM-backed CLI stack",
    *selection.summary_lines,
]
```

**Step 5: Run targeted tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py -k "cli_stack" -v
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py tools/controlplane/tests/test_tui_choices.py
git commit -m "feat(tui): add cli-stack function selection"
```

### Task 5: Make local scenario flows accept resolved selections directly

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- Modify: `tools/controlplane/src/controlplane_tool/container_local_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/deploy_host_runner.py`
- Modify: `tools/controlplane/tests/test_scenario_flows.py`
- Modify: `tools/controlplane/tests/test_cli_runtime.py`

**Step 1: Write failing flow tests**

Add tests proving that `build_scenario_flow()` passes resolved selections directly to local runners instead of losing them through nested CLI/env indirection.

Required cases:
- `container-local` with a request containing `resolved_scenario` calls `ContainerLocalE2eRunner.run(resolved_scenario=...)`
- `deploy-host` with a request containing `resolved_scenario` calls `DeployHostE2eRunner.run(resolved_scenario=...)`

Test shape:

```python
def test_container_local_flow_passes_resolved_scenario_to_runner(monkeypatch) -> None:
    captured = {}

    class FakeRunner:
        def __init__(self, *args, **kwargs): ...
        def run(self, scenario_file=None, *, resolved_scenario=None):
            captured["resolved_scenario"] = resolved_scenario

    monkeypatch.setattr(scenario_flows, "ContainerLocalE2eRunner", FakeRunner)

    request = E2eRequest(
        scenario="container-local",
        resolved_scenario=make_resolved_scenario(["word-stats-javascript"]),
    )
    flow = build_scenario_flow("container-local", repo_root=ROOT, request=request)
    flow.run()

    assert captured["resolved_scenario"] is request.resolved_scenario
```

**Step 2: Update runner signatures**

Change:

```python
def run(self, scenario_file: Path | None = None) -> None:
    resolved = _resolve_scenario_file(scenario_file)
```

to:

```python
def run(
    self,
    scenario_file: Path | None = None,
    *,
    resolved_scenario: ResolvedScenario | None = None,
) -> None:
    resolved = resolved_scenario if resolved_scenario is not None else _resolve_scenario_file(scenario_file)
```

Apply this to:
- `ContainerLocalE2eRunner.run`
- `DeployHostE2eRunner.run`

**Step 3: Update `build_scenario_flow()` local special cases**

Move local runner special-cases before the generic `if request is not None: E2eRunner(...).run(request)` branch for:
- `container-local`
- `deploy-host`

Pass:

```python
resolved_scenario=getattr(request, "resolved_scenario", None)
```

Keep the existing `scenario_file` path behavior when no request is provided.

**Step 4: Run targeted tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_scenario_flows.py tests/test_cli_runtime.py -k "container_local or deploy_host or resolved_scenario" -v
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_flows.py tools/controlplane/src/controlplane_tool/container_local_runner.py tools/controlplane/src/controlplane_tool/deploy_host_runner.py tools/controlplane/tests/test_scenario_flows.py tools/controlplane/tests/test_cli_runtime.py
git commit -m "refactor(scenarios): pass resolved selections to local runners"
```

### Task 6: Add selection-source support to `deploy-host`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write failing TUI tests for `deploy-host`**

Add tests covering:
- preset `demo-javascript`
- scenario file `tools/controlplane/scenarios/k8s-demo-javascript.toml`
- saved profile `demo-javascript`

Expected assertions:

```python
assert called["scenario"] == "deploy-host"
assert called["request"].scenario == "deploy-host"
assert called["request"].function_preset == "demo-javascript"
assert called["request"].resolved_scenario.function_keys == [
    "word-stats-javascript",
    "json-transform-javascript",
]
```

**Step 2: Add the target config**

```python
DEPLOY_HOST_SELECTION_TARGET = TuiSelectionTarget(
    key="deploy-host",
    label="deploy-host",
    resolver_scenario="deploy-host",
    selection_mode="multi",
    allow_default=True,
    allow_presets=True,
    allow_single_functions=False,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=None,
)
```

**Step 3: Resolve an `E2eRequest` in `_run_deploy_host()`**

Use `_prompt_function_selection(DEPLOY_HOST_SELECTION_TARGET)` and `e2e_commands._resolve_run_request(...)` with:

```python
scenario="deploy-host"
runtime="java"
lifecycle="multipass"
name=None
cleanup_vm=True
```

Then pass `request=request` to `build_scenario_flow("deploy-host", ...)`.

**Step 4: Preserve host compatibility summary**

Add summary lines:

```python
summary_lines=[
    "Scenario: deploy-host",
    "Mode: host-side build/push/register compatibility path",
    *selection.summary_lines,
]
```

**Step 5: Run targeted tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py -k "deploy_host" -v
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py tools/controlplane/tests/test_tui_choices.py
git commit -m "feat(tui): add deploy-host function selection"
```

### Task 7: Add single-function selection-source support to `container-local`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write failing TUI tests for `container-local`**

Add tests covering:
- JavaScript single function selection, e.g. `word-stats-javascript`
- scenario-file source rejects or hides multi-function manifests
- saved-profile source hides multi-function profiles such as `demo-javascript`
- no compatible saved profiles warns and reprompts

Expected assertions for single function:

```python
assert called["scenario"] == "container-local"
assert called["request"].scenario == "container-local"
assert called["request"].functions == ["word-stats-javascript"]
assert called["request"].resolved_scenario.function_keys == ["word-stats-javascript"]
```

**Step 2: Add the target config**

```python
CONTAINER_LOCAL_SELECTION_TARGET = TuiSelectionTarget(
    key="container-local",
    label="container-local",
    resolver_scenario="container-local",
    selection_mode="single",
    allow_default=True,
    allow_presets=False,
    allow_single_functions=True,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=None,
)
```

`allow_presets=False` is intentional. `container-local` is single-function; offering multi-function presets such as `demo-javascript` would only send the user into validation errors. Individual function selection is the correct UX.

**Step 3: Resolve an `E2eRequest` in `_run_container_local()`**

Use `_prompt_function_selection(CONTAINER_LOCAL_SELECTION_TARGET)` and `_resolve_run_request(...)` with:

```python
scenario="container-local"
runtime="java"
function_preset=selection.function_preset  # normally None
functions_csv=selection.functions_csv
scenario_file=selection.scenario_file
saved_profile=selection.saved_profile
```

Then pass `request=request` to `build_scenario_flow("container-local", ...)`.

**Step 4: Add summary lines**

```python
summary_lines=[
    "Scenario: container-local",
    "Mode: local managed DEPLOYMENT path",
    *selection.summary_lines,
]
```

**Step 5: Run targeted tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py -k "container_local" -v
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py tools/controlplane/tests/test_tui_choices.py
git commit -m "feat(tui): add container-local single function selection"
```

### Task 8: Document the generalized TUI selection model

**Files:**
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/README.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Update docs**

Document that the TUI now supports function-selection sources in:
- `Validation -> platform -> k3s-junit-curl`
- `Validation -> cli -> cli-stack`
- `Validation -> host -> deploy-host`
- `Validation -> platform -> container-local`

Mention that:
- `cli-stack` and `deploy-host` support preset, scenario file, and saved profile sources
- `container-local` supports single function, compatible single-function scenario files, and compatible saved profiles
- `helm-stack` remains excluded because its runtime allowlist intentionally does not include JavaScript

**Step 2: Add docs-link assertions**

Add assertions such as:

```python
assert "Validation -> cli -> cli-stack" in testing
assert "Validation -> host -> deploy-host" in testing
assert "container-local` supports single function selection" in tool_readme
assert "`helm-stack` remains excluded" in tool_readme
```

**Step 3: Run docs tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_docs_links.py -v
```

Expected: PASS.

**Step 4: Commit**

```bash
git add README.md docs/testing.md tools/controlplane/README.md tools/controlplane/tests/test_docs_links.py
git commit -m "docs(tui): document generalized function selection"
```

### Task 9: Final verification and GitNexus scope check

**Files:**
- No new product files

**Step 1: Run targeted verification**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_selection.py tests/test_tui_choices.py tests/test_scenario_flows.py tests/test_cli_runtime.py tests/test_e2e_commands.py tests/test_cli_test_commands.py tests/test_docs_links.py -v
```

Expected: PASS.

**Step 2: Run smoke dry-runs through CLI surfaces**

Run:

```bash
./scripts/controlplane.sh e2e run cli-stack --function-preset demo-javascript --dry-run
./scripts/controlplane.sh e2e run deploy-host --function-preset demo-javascript --dry-run
./scripts/controlplane.sh e2e run container-local --functions word-stats-javascript --dry-run
```

Expected:
- `cli-stack` and `deploy-host` show JavaScript functions in resolved output
- `container-local` shows exactly `word-stats-javascript`

**Step 3: Run GitNexus detect changes before committing final fixes**

Run:

```text
gitnexus_detect_changes({scope: "staged"})
```

Expected: changed symbols and flows are limited to:
- TUI selection helpers
- TUI validation/CLI menus
- local scenario flow request propagation
- tests and docs

**Step 4: Confirm diff scope**

Run:

```bash
git diff --stat
```

Expected changes are limited to:
- `tools/controlplane/src/controlplane_tool/tui_selection.py`
- `tools/controlplane/src/controlplane_tool/tui_app.py`
- `tools/controlplane/src/controlplane_tool/scenario_flows.py`
- `tools/controlplane/src/controlplane_tool/container_local_runner.py`
- `tools/controlplane/src/controlplane_tool/deploy_host_runner.py`
- tests and docs

**Step 5: Commit final verification-only fixes if needed**

```bash
git add -A
git commit -m "test(tui): verify generalized function selection"
```

## Out of Scope

- enabling JavaScript in `helm-stack`
- changing `host-platform` / `cli-host`, which are platform-only
- changing `docker` / `buildpack`, which are not catalog-driven function selection scenarios
- adding a scenario TOML editor to the TUI
- changing CLI command semantics for existing scripted users

## Compatibility decisions

- `k3s-junit-curl` remains strict: scenario-file choices must have `base_scenario == "k3s-junit-curl"`.
- `cli-stack` and `deploy-host` are flexible: they may reuse existing scenario manifests and saved profiles as function-selection sources, then overlay the selected functions onto their target scenario.
- `container-local` is single-function only: do not offer multi-function presets; offer individual buildable functions and compatible single-function scenario/profile selections.
- Saved profiles shown as function-selection sources must contribute an actual function selection. Profiles containing only build/runtime defaults are hidden from this prompt.
