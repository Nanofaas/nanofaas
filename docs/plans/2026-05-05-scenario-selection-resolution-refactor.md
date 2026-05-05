# Scenario Selection Resolution Refactor Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Deduplicate scenario/profile/function-selection resolution across the controlplane CLI surfaces without changing CLI/TUI behavior.

**Architecture:** Add a small shared helper module under `controlplane_tool.scenario` for pure selection-resolution primitives: CSV parsing, workspace path resolution, explicit-selection detection, scenario construction from `ScenarioSelectionConfig`, and scenario overlay with runtime/namespace/registry overrides. Keep command-specific request construction in the CLI modules; do not introduce a large generic resolver object in this pass.

**Tech Stack:** Python 3.12, Typer, Pydantic models, pytest, import-linter, GitNexus MCP impact analysis.

---

## Current Duplication

The first refactor target is semantic duplication in these files:

- `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`
- `tools/controlplane/src/controlplane_tool/cli/test_commands.py`
- `tools/controlplane/src/controlplane_tool/cli/loadtest_commands.py`

Repeated responsibilities:

- parse comma-separated function selections
- resolve optional scenario-file paths relative to the workspace
- detect explicit function/scenario selection
- build a `ResolvedScenario` from `ScenarioSelectionConfig`
- reload a manifest while preserving selected functions but overriding runtime/namespace/registry
- derive a stable source label for resolved scenarios

Do not move VM request creation, Typer command registration, output rendering, or scenario-specific validation into the shared module. Those are command-surface responsibilities.

## Safety Requirements

- Before editing any existing function/class, run GitNexus impact analysis for that symbol and record risk in the implementation notes.
- If GitNexus reports HIGH or CRITICAL risk, stop and report the affected direct callers before editing.
- Use TDD for each new helper and each migration checkpoint.
- Keep commits small and reversible.
- Run `uv run lint-imports` after each migration task that changes imports.

---

### Task 0: Set Up Isolated Worktree and Baseline

**Files:**
- No code changes.

**Step 1: Create a feature worktree**

Run from repository root:

```bash
git fetch origin main
git worktree add .worktrees/scenario-selection-resolution-refactor -b codex/scenario-selection-resolution-refactor origin/main
cd .worktrees/scenario-selection-resolution-refactor/tools/controlplane
```

Expected:

```text
Preparing worktree ...
HEAD is now at ...
```

**Step 2: Run baseline architecture checks**

Run:

```bash
uv run lint-imports
uv run pytest tests/test_package_layout.py tests/test_import_contracts.py tests/test_package_report.py -q
```

Expected:

```text
Contracts: 4 kept, 0 broken.
... passed
```

**Step 3: Run focused baseline tests for the target surfaces**

Run:

```bash
uv run pytest tests/test_e2e_commands.py tests/test_cli_test_commands.py tests/test_loadtest_commands.py -q
```

Expected: all tests pass before refactoring.

---

### Task 1: Add Shared Scenario Selection Helper Module

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/selection_resolution.py`
- Create: `tools/controlplane/tests/test_selection_resolution.py`

**Step 1: Run GitNexus impact analysis before introducing the shared module**

Run through MCP:

```text
gitnexus_impact({target: "resolve_scenario_spec", direction: "upstream", includeTests: true, maxDepth: 2})
gitnexus_impact({target: "overlay_scenario_selection", direction: "upstream", includeTests: true, maxDepth: 2})
```

Expected: note risk and direct callers. This task adds wrapper helpers around these functions; it should not change existing behavior.

**Step 2: Write failing tests**

Create `tools/controlplane/tests/test_selection_resolution.py`:

```python
from __future__ import annotations

from pathlib import Path

import pytest

from controlplane_tool.core.models import ScenarioSelectionConfig
from controlplane_tool.scenario.scenario_loader import resolve_scenario_spec
from controlplane_tool.scenario.scenario_models import ScenarioSpec
from controlplane_tool.scenario.selection_resolution import (
    configured_scenario_path,
    explicit_selection_requested,
    overlay_selected_scenario,
    parse_function_csv,
    resolved_scenario_from_config,
)


def test_parse_function_csv_trims_empty_items() -> None:
    assert parse_function_csv(" word-stats-java, ,json-transform-java ") == [
        "word-stats-java",
        "json-transform-java",
    ]


def test_configured_scenario_path_resolves_workspace_relative_path() -> None:
    result = configured_scenario_path("tools/controlplane/scenarios/k8s-demo-java.toml")

    assert result is not None
    assert result.is_absolute()
    assert result.name == "k8s-demo-java.toml"


def test_configured_scenario_path_keeps_none_empty() -> None:
    assert configured_scenario_path(None) is None
    assert configured_scenario_path("") is None


def test_explicit_selection_requested_detects_preset_functions_or_file() -> None:
    assert explicit_selection_requested(
        function_preset="demo-java",
        functions=[],
        scenario_file=None,
    )
    assert explicit_selection_requested(
        function_preset=None,
        functions=["word-stats-java"],
        scenario_file=None,
    )
    assert explicit_selection_requested(
        function_preset=None,
        functions=[],
        scenario_file=Path("scenario.toml"),
    )
    assert not explicit_selection_requested(
        function_preset=None,
        functions=[],
        scenario_file=None,
    )


def test_resolved_scenario_from_config_uses_default_base_scenario() -> None:
    scenario = resolved_scenario_from_config(
        ScenarioSelectionConfig(function_preset="demo-java"),
        name="cli-test-selection",
        base_scenario="k3s-junit-curl",
        runtime="java",
        namespace="demo",
        local_registry="localhost:5000",
    )

    assert scenario.name == "cli-test-selection"
    assert scenario.base_scenario == "k3s-junit-curl"
    assert scenario.runtime == "java"
    assert scenario.namespace == "demo"
    assert scenario.local_registry == "localhost:5000"
    assert scenario.function_preset == "demo-java"


def test_overlay_selected_scenario_preserves_manifest_functions_with_overrides() -> None:
    original = resolve_scenario_spec(
        ScenarioSpec(
            name="manifest",
            base_scenario="k3s-junit-curl",
            runtime="java",
            functions=["word-stats-java"],
            namespace="original",
            local_registry="registry:5000",
        )
    )

    updated = overlay_selected_scenario(
        original,
        base_scenario="cli-stack",
        runtime="python",
        namespace="override",
        local_registry="localhost:5001",
    )

    assert updated.base_scenario == "cli-stack"
    assert updated.runtime == "python"
    assert updated.namespace == "override"
    assert updated.local_registry == "localhost:5001"
    assert updated.function_keys == ["word-stats-java"]
```

**Step 3: Run tests to verify they fail**

Run:

```bash
uv run pytest tests/test_selection_resolution.py -q
```

Expected:

```text
ModuleNotFoundError: No module named 'controlplane_tool.scenario.selection_resolution'
```

**Step 4: Add minimal implementation**

Create `tools/controlplane/src/controlplane_tool/scenario/selection_resolution.py`:

```python
from __future__ import annotations

from pathlib import Path

from controlplane_tool.core.models import ScenarioSelectionConfig
from controlplane_tool.scenario.scenario_loader import (
    overlay_scenario_selection,
    resolve_scenario_spec,
)
from controlplane_tool.scenario.scenario_models import ResolvedScenario, ScenarioSpec
from controlplane_tool.workspace.paths import resolve_workspace_path


def parse_function_csv(value: str | None) -> list[str]:
    if not value:
        return []
    return [item.strip() for item in value.split(",") if item.strip()]


def configured_scenario_path(path: str | Path | None) -> Path | None:
    if path is None:
        return None
    text = str(path).strip()
    if not text:
        return None
    return resolve_workspace_path(Path(text))


def explicit_selection_requested(
    *,
    function_preset: str | None,
    functions: list[str],
    scenario_file: Path | None,
) -> bool:
    return bool(function_preset or functions or scenario_file is not None)


def resolved_scenario_from_config(
    config: ScenarioSelectionConfig,
    *,
    name: str,
    base_scenario: str,
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    return resolve_scenario_spec(
        ScenarioSpec(
            name=name,
            base_scenario=config.base_scenario or base_scenario,
            runtime=runtime,
            function_preset=config.function_preset,
            functions=list(config.functions),
            namespace=namespace if namespace is not None else config.namespace,
            local_registry=local_registry or config.local_registry,
        )
    )


def overlay_selected_scenario(
    scenario: ResolvedScenario,
    *,
    base_scenario: str | None = None,
    runtime: str,
    namespace: str | None,
    local_registry: str,
) -> ResolvedScenario:
    source = (
        scenario.model_copy(update={"base_scenario": base_scenario})
        if base_scenario is not None
        else scenario
    )
    return overlay_scenario_selection(
        source,
        function_preset=scenario.function_preset,
        functions=[] if scenario.function_preset else list(scenario.function_keys),
        runtime=runtime,
        namespace=namespace,
        local_registry=local_registry,
    )
```

**Step 5: Run helper tests**

Run:

```bash
uv run pytest tests/test_selection_resolution.py -q
```

Expected:

```text
6 passed
```

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/scenario/selection_resolution.py tools/controlplane/tests/test_selection_resolution.py
git commit -m "Add shared scenario selection helpers"
```

---

### Task 2: Migrate E2E CLI Resolution to Shared Helpers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`
- Test: `tools/controlplane/tests/test_e2e_commands.py`
- Test: `tools/controlplane/tests/test_selection_resolution.py`

**Step 1: Run GitNexus impact analysis**

Run through MCP:

```text
gitnexus_impact({target: "_resolve_run_request", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/e2e_commands.py"})
gitnexus_impact({target: "_parse_csv", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/e2e_commands.py"})
gitnexus_impact({target: "_configured_scenario_path", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/e2e_commands.py"})
gitnexus_impact({target: "_resolved_from_config", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/e2e_commands.py"})
gitnexus_impact({target: "_reload_with_overrides", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/e2e_commands.py"})
```

Expected: `_resolve_run_request` will likely be MEDIUM/HIGH because CLI tests cover it. Review direct callers before editing.

**Step 2: Add a regression test that exercises the migrated path**

If not already present, add this to `tools/controlplane/tests/test_e2e_commands.py`:

```python
def test_e2e_request_applies_cli_override_to_saved_profile_scenario_file(tmp_path: Path) -> None:
    from controlplane_tool.cli import e2e_commands
    from controlplane_tool.core.models import Profile, ScenarioSelectionConfig
    from controlplane_tool.workspace.profiles import save_profile

    scenario_file = tmp_path / "scenario.toml"
    scenario_file.write_text(
        """
name = "custom"
base_scenario = "k3s-junit-curl"
runtime = "java"
function_preset = "demo-java"
namespace = "from-file"
local_registry = "registry:5000"
""",
        encoding="utf-8",
    )
    profile = Profile(
        name="saved",
        scenario=ScenarioSelectionConfig(scenario_file=str(scenario_file)),
    )
    save_profile(profile, root=tmp_path)

    request = e2e_commands._resolve_run_request(
        scenario=None,
        runtime="python",
        lifecycle="external",
        name=None,
        host="127.0.0.1",
        user="ubuntu",
        home=None,
        cpus=2,
        memory="2G",
        disk="10G",
        cleanup_vm=True,
        namespace="override",
        local_registry="localhost:5001",
        function_preset="metrics-smoke",
        functions_csv=None,
        scenario_file=None,
        saved_profile="saved",
    )

    assert request.resolved_scenario is not None
    assert request.resolved_scenario.runtime == "python"
    assert request.resolved_scenario.namespace == "override"
    assert request.resolved_scenario.local_registry == "localhost:5001"
    assert request.resolved_scenario.function_preset == "metrics-smoke"
```

If this exact setup conflicts with existing profile directory behavior, use `monkeypatch` to point `controlplane_tool.workspace.profiles.profiles_dir` at `tmp_path`, matching existing local test patterns.

**Step 3: Run the regression test before implementation**

Run:

```bash
uv run pytest tests/test_e2e_commands.py::test_e2e_request_applies_cli_override_to_saved_profile_scenario_file -q
```

Expected: pass if behavior already exists. This is acceptable for migration work; it protects behavior before moving code.

**Step 4: Replace local helper implementations with imports**

Modify `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`.

Remove imports no longer needed:

```python
from controlplane_tool.workspace.paths import default_tool_paths, resolve_workspace_path
from controlplane_tool.scenario.scenario_loader import (
    load_scenario_file,
    overlay_scenario_selection,
    resolve_scenario_spec,
)
from controlplane_tool.scenario.scenario_models import ResolvedScenario, ScenarioSpec
```

Keep `resolve_workspace_path` only if another function still uses it outside migrated helpers.

Add:

```python
from controlplane_tool.scenario.selection_resolution import (
    configured_scenario_path,
    overlay_selected_scenario,
    parse_function_csv,
    resolved_scenario_from_config,
)
```

Then update local calls:

```python
explicit_functions = parse_function_csv(functions_csv)
```

```python
profile_file_scenario = (
    load_scenario_file(configured_scenario_path(profile_selection.scenario_file))
    if configured_scenario_path(profile_selection.scenario_file) is not None
    else None
)
```

Replace `_resolved_from_config(...)` with:

```python
resolved_scenario_from_config(
    ScenarioSelectionConfig(...),
    name=f"{effective_scenario}-cli",
    base_scenario=effective_scenario,
    runtime=effective_runtime,
    namespace=effective_namespace,
    local_registry=effective_registry,
)
```

Replace `_reload_with_overrides(...)` with:

```python
overlay_selected_scenario(
    profile_file_scenario,
    base_scenario=effective_scenario,
    runtime=effective_runtime,
    namespace=effective_namespace,
    local_registry=effective_registry,
)
```

Delete the local functions:

```python
def _parse_csv(...)
def _configured_scenario_path(...)
def _resolved_from_config(...)
def _reload_with_overrides(...)
```

**Step 5: Run E2E CLI tests**

Run:

```bash
uv run pytest tests/test_e2e_commands.py tests/test_selection_resolution.py -q
uv run lint-imports
```

Expected:

```text
... passed
Contracts: 4 kept, 0 broken.
```

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/cli/e2e_commands.py tools/controlplane/tests/test_e2e_commands.py
git commit -m "Reuse shared selection helpers in e2e CLI"
```

---

### Task 3: Migrate CLI-Test Resolution to Shared Helpers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/test_commands.py`
- Test: `tools/controlplane/tests/test_cli_test_commands.py`
- Test: `tools/controlplane/tests/test_selection_resolution.py`

**Step 1: Run GitNexus impact analysis**

Run through MCP:

```text
gitnexus_impact({target: "_resolve_run_request", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/test_commands.py"})
gitnexus_impact({target: "_parse_csv", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/test_commands.py"})
gitnexus_impact({target: "_configured_scenario_path", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/test_commands.py"})
gitnexus_impact({target: "_resolved_from_config", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/test_commands.py"})
gitnexus_impact({target: "_reload_with_overrides", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/test_commands.py"})
```

Expected: review direct callers. This is command-resolution code and should have direct test coverage.

**Step 2: Add a regression test for function-selection rejection**

If not already covered, add this to `tools/controlplane/tests/test_cli_test_commands.py`:

```python
def test_cli_test_rejects_function_selection_for_nonselectable_scenario() -> None:
    import pytest
    from controlplane_tool.cli.test_commands import _resolve_run_request

    with pytest.raises(ValueError, match="does not accept function selection"):
        _resolve_run_request(
            scenario="vm",
            runtime="java",
            lifecycle="external",
            name=None,
            host="127.0.0.1",
            user="ubuntu",
            home=None,
            cpus=2,
            memory="2G",
            disk="10G",
            keep_vm=True,
            namespace=None,
            local_registry=None,
            function_preset="demo-java",
            functions_csv=None,
            scenario_file=None,
            saved_profile=None,
        )
```

**Step 3: Run the regression test**

Run:

```bash
uv run pytest tests/test_cli_test_commands.py::test_cli_test_rejects_function_selection_for_nonselectable_scenario -q
```

Expected: pass before migration if behavior already exists.

**Step 4: Replace local helper implementations with shared helpers**

Modify `tools/controlplane/src/controlplane_tool/cli/test_commands.py`.

Add:

```python
from controlplane_tool.scenario.selection_resolution import (
    configured_scenario_path,
    explicit_selection_requested,
    overlay_selected_scenario,
    parse_function_csv,
    resolved_scenario_from_config,
)
```

Update:

```python
explicit_functions = parse_function_csv(functions_csv)
```

Replace `_has_explicit_selection(...)` with:

```python
explicit_selection_requested(
    function_preset=function_preset,
    functions=explicit_functions,
    scenario_file=explicit_file_path,
)
```

Replace `_resolved_from_config(...)` with `resolved_scenario_from_config(...)`.

Replace `_reload_with_overrides(...)` with:

```python
overlay_selected_scenario(
    explicit_file_scenario,
    runtime=effective_runtime,
    namespace=effective_namespace,
    local_registry=effective_registry,
)
```

Delete the local helpers now covered by shared functions:

```python
def _parse_csv(...)
def _configured_scenario_path(...)
def _resolved_from_config(...)
def _reload_with_overrides(...)
def _has_explicit_selection(...)
```

Keep `_load_profile_or_raise` and `_load_scenario_or_raise` local for now because they format command-specific error messages.

**Step 5: Run CLI-test tests**

Run:

```bash
uv run pytest tests/test_cli_test_commands.py tests/test_selection_resolution.py -q
uv run lint-imports
```

Expected:

```text
... passed
Contracts: 4 kept, 0 broken.
```

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/cli/test_commands.py tools/controlplane/tests/test_cli_test_commands.py
git commit -m "Reuse shared selection helpers in cli-test commands"
```

---

### Task 4: Migrate Loadtest Scenario Path Resolution

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli/loadtest_commands.py`
- Test: `tools/controlplane/tests/test_loadtest_commands.py`
- Test: `tools/controlplane/tests/test_selection_resolution.py`

**Step 1: Run GitNexus impact analysis**

Run through MCP:

```text
gitnexus_impact({target: "build_loadtest_request", direction: "upstream", includeTests: true, maxDepth: 2})
gitnexus_impact({target: "_resolve_scenario", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/loadtest_commands.py"})
gitnexus_impact({target: "_configured_scenario_path", direction: "upstream", includeTests: true, maxDepth: 2, file_path: "tools/controlplane/src/controlplane_tool/cli/loadtest_commands.py"})
```

Expected: `build_loadtest_request` is likely MEDIUM because it is used by command tests and TUI/loadtest paths. Review d=1 callers.

**Step 2: Add regression test for scenario-file precedence**

If not already covered, add this to `tools/controlplane/tests/test_loadtest_commands.py`:

```python
def test_loadtest_request_cli_scenario_file_overrides_profile_scenario_file(tmp_path: Path) -> None:
    from controlplane_tool.cli.loadtest_commands import build_loadtest_request
    from controlplane_tool.core.models import Profile, ScenarioSelectionConfig

    profile_scenario = tmp_path / "profile.toml"
    profile_scenario.write_text(
        """
name = "profile-scenario"
base_scenario = "k3s-junit-curl"
runtime = "java"
function_preset = "demo-java"
""",
        encoding="utf-8",
    )
    cli_scenario = tmp_path / "cli.toml"
    cli_scenario.write_text(
        """
name = "cli-scenario"
base_scenario = "k3s-junit-curl"
runtime = "java"
function_preset = "metrics-smoke"
""",
        encoding="utf-8",
    )

    request = build_loadtest_request(
        profile=Profile(
            name="profile",
            scenario=ScenarioSelectionConfig(scenario_file=str(profile_scenario)),
        ),
        scenario_file=cli_scenario,
    )

    assert request.scenario.name == "cli-scenario"
    assert request.scenario.function_preset == "metrics-smoke"
```

**Step 3: Run regression test**

Run:

```bash
uv run pytest tests/test_loadtest_commands.py::test_loadtest_request_cli_scenario_file_overrides_profile_scenario_file -q
```

Expected: pass before migration if behavior already exists.

**Step 4: Replace path helper**

Modify `tools/controlplane/src/controlplane_tool/cli/loadtest_commands.py`.

Add:

```python
from controlplane_tool.scenario.selection_resolution import configured_scenario_path
```

Delete local:

```python
def _configured_scenario_path(...)
```

Replace calls:

```python
configured_loadtest_scenario = configured_scenario_path(profile.loadtest.scenario_file)
configured_profile_scenario = configured_scenario_path(profile.scenario.scenario_file)
```

Keep `_scenario_selection_for`, `_default_profile_for_scenario`, `_resolve_scenario`, and `build_loadtest_request` in this file. They are loadtest-specific and should not be generalized in this pass.

**Step 5: Run loadtest command tests**

Run:

```bash
uv run pytest tests/test_loadtest_commands.py tests/test_selection_resolution.py -q
uv run lint-imports
```

Expected:

```text
... passed
Contracts: 4 kept, 0 broken.
```

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/src/controlplane_tool/cli/loadtest_commands.py tools/controlplane/tests/test_loadtest_commands.py
git commit -m "Reuse shared selection path resolution in loadtest commands"
```

---

### Task 5: Remove Residual Duplicate Helpers and Verify Imports

**Files:**
- Modify if needed: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py`
- Modify if needed: `tools/controlplane/src/controlplane_tool/cli/test_commands.py`
- Modify if needed: `tools/controlplane/src/controlplane_tool/cli/loadtest_commands.py`

**Step 1: Search for duplicate helper names**

Run:

```bash
rg -n "def (_parse_csv|_configured_scenario_path|_resolved_from_config|_reload_with_overrides|_has_explicit_selection)" tools/controlplane/src/controlplane_tool/cli
```

Expected:

```text
no output
```

If output remains, inspect whether it is truly duplicate. Remove only helpers covered by `controlplane_tool.scenario.selection_resolution`.

**Step 2: Search for direct duplicated path logic**

Run:

```bash
rg -n "resolve_workspace_path\\(Path\\(|split\\(\",\"\\)|overlay_scenario_selection\\(" tools/controlplane/src/controlplane_tool/cli
```

Expected: no duplicated scenario-selection utility logic in the three target command files. Legitimate command-specific uses may remain; inspect before editing.

**Step 3: Run focused checks**

Run:

```bash
uv run pytest tests/test_selection_resolution.py tests/test_e2e_commands.py tests/test_cli_test_commands.py tests/test_loadtest_commands.py -q
uv run lint-imports
```

Expected:

```text
... passed
Contracts: 4 kept, 0 broken.
```

**Step 4: Commit any cleanup**

If Step 1 or Step 2 required edits:

```bash
git add tools/controlplane/src/controlplane_tool/cli
git commit -m "Remove duplicate CLI selection helpers"
```

If no edits were required, skip this commit.

---

### Task 6: Document the Shared Selection Resolution Boundary

**Files:**
- Modify: `tools/controlplane/README.md`
- Modify: `docs/plans/2026-05-04-package-architecture-checks.md`
- Test: `tools/controlplane/tests/test_architecture_docs.py`

**Step 1: Write failing documentation assertion**

Modify `tools/controlplane/tests/test_architecture_docs.py`:

```python
def test_tool_readme_documents_shared_selection_resolution_boundary() -> None:
    readme = resolve_workspace_path(Path("tools/controlplane/README.md")).read_text(
        encoding="utf-8"
    )

    assert "controlplane_tool.scenario.selection_resolution" in readme
    assert "scenario/profile selection precedence" in readme
```

**Step 2: Run doc test and verify failure**

Run:

```bash
uv run pytest tests/test_architecture_docs.py::test_tool_readme_documents_shared_selection_resolution_boundary -q
```

Expected:

```text
FAILED ... AssertionError
```

**Step 3: Update README**

Append a short paragraph under `## Package architecture checks` in `tools/controlplane/README.md`:

```markdown
`controlplane_tool.scenario.selection_resolution` owns shared scenario/profile
selection precedence helpers used by CLI command surfaces. Command modules should
keep Typer options, error rendering, and request construction local, but should not
reimplement CSV parsing, workspace scenario path resolution, or manifest overlay
helpers.
```

**Step 4: Update package plan document**

In `docs/plans/2026-05-04-package-architecture-checks.md`, update the scenario package description:

```text
controlplane_tool.scenario        scenario models, planner, component library, shared selection-resolution helpers
```

**Step 5: Run doc test**

Run:

```bash
uv run pytest tests/test_architecture_docs.py -q
```

Expected:

```text
... passed
```

**Step 6: Commit**

Run:

```bash
git add tools/controlplane/README.md tools/controlplane/tests/test_architecture_docs.py docs/plans/2026-05-04-package-architecture-checks.md
git commit -m "Document shared scenario selection boundary"
```

---

### Task 7: Final Verification, GitNexus Detection, Push

**Files:**
- No intended code changes unless verification exposes a narrow fix.

**Step 1: Run package report**

Run:

```bash
cd tools/controlplane
uv run controlplane-package-report
```

Expected: report prints a table. Do not assert exact numbers, but inspect that `controlplane_tool.scenario` outgoing/incoming changes are plausible and `core`, `workflow`, `workspace`, and `app` boundaries remain coherent.

**Step 2: Run import contracts**

Run:

```bash
uv run lint-imports
```

Expected:

```text
Contracts: 4 kept, 0 broken.
```

**Step 3: Run focused tests**

Run:

```bash
uv run pytest tests/test_selection_resolution.py tests/test_e2e_commands.py tests/test_cli_test_commands.py tests/test_loadtest_commands.py tests/test_architecture_docs.py -q
```

Expected: all pass.

**Step 4: Run full controlplane suite**

Run:

```bash
uv run pytest -q
```

Expected: all pass. Current recent baseline is `868 passed`; exact count may increase after new tests.

**Step 5: Run GitNexus staged/compare detection**

Run through MCP:

```text
gitnexus_detect_changes({scope: "compare", base_ref: "main"})
```

Expected: no HIGH/CRITICAL unexpected affected flows. If GitNexus reports broad impact, inspect direct changed symbols and confirm it matches the planned CLI scenario-resolution refactor.

**Step 6: Update GitNexus index after final commit**

Run:

```bash
npx gitnexus analyze
```

Expected: repository indexed successfully or already up to date.

If `npx gitnexus analyze` edits `AGENTS.md` or `CLAUDE.md` only to rewrite the worktree repo name, restore those generated docs before push:

```bash
git restore AGENTS.md CLAUDE.md
```

**Step 7: Push branch**

Run:

```bash
git push -u origin codex/scenario-selection-resolution-refactor
```

Expected: branch is pushed and GitHub prints a PR creation URL.

---

## Completion Checklist

- Shared helper module exists at `controlplane_tool.scenario.selection_resolution`.
- `e2e_commands.py`, `test_commands.py`, and `loadtest_commands.py` no longer duplicate `_configured_scenario_path`.
- `e2e_commands.py` and `test_commands.py` no longer duplicate CSV parsing or scenario overlay helpers.
- Command-specific validation and Typer rendering remain in command modules.
- `uv run lint-imports` passes.
- Focused tests for selection resolution, E2E commands, CLI-test commands, and loadtest commands pass.
- Full `uv run pytest -q` passes.
- GitNexus impact was run before each existing-symbol edit.
- GitNexus detect changes was reviewed before final push.

## Non-Goals

- Do not refactor TUI selection in this pass.
- Do not create an abstract base class for CLI command handlers.
- Do not change CLI option names, output wording, or exit codes except where an existing test already defines the behavior.
- Do not move loadtest-specific profile construction out of `cli/loadtest_commands.py`.
