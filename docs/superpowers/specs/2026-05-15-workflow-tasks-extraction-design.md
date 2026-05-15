# workflow-tasks Extraction Design

## Goal

Extract task execution primitives and workflow event infrastructure from `controlplane_tool` and `tui_toolkit` into a standalone Python library (`workflow-tasks`) with zero external dependencies, following the same two-PR pattern used for `tui-toolkit`.

## Architecture

### Final dependency graph

```
workflow-tasks   (no external dependencies)
      ↑
controlplane-tool  →  tui-toolkit
```

`controlplane_tool` is the integration layer: it knows about both libraries and bridges them. `tui_toolkit` is reduced to pure UI primitives (theme, brand, console, chrome, pickers). Neither knows about the other.

### Intermediate state after PR1

```
workflow-tasks
      ↑
tui-toolkit  (shims → workflow-tasks; keeps Rich renderer temporarily)
      ↑
controlplane-tool  →  workflow-tasks (via shims in controlplane_tool/tasks/)
```

---

## Library Structure: `tools/workflow-tasks/`

```
tools/workflow-tasks/
├── pyproject.toml              # no runtime deps beyond stdlib; Python ≥ 3.11
├── src/
│   └── workflow_tasks/
│       ├── __init__.py         # flat public API (mirrors tui_toolkit pattern)
│       ├── tasks/
│       │   ├── __init__.py
│       │   ├── models.py       # CommandTaskSpec, TaskResult, TaskStatus, ExecutionTarget
│       │   ├── executors.py    # HostCommandTaskExecutor, VmCommandTaskExecutor + Protocol runners
│       │   ├── rendering.py    # render_shell_command, render_task_command
│       │   └── adapters.py     # RemoteCommandOperationLike Protocol, operation_to_task_spec
│       ├── workflow/
│       │   ├── __init__.py
│       │   ├── events.py       # WorkflowEvent, WorkflowContext, WorkflowSink
│       │   ├── models.py       # WorkflowState, WorkflowRun, TaskDefinition, TaskRun
│       │   ├── context.py      # ContextVar plumbing: bind_workflow_sink/context, get_workflow_context, has_workflow_sink
│       │   ├── event_builders.py  # build_task_event, build_phase_event, build_log_event
│       │   └── reporting.py    # phase, step, success, warning, skip, fail, workflow_log, workflow_step, status
│       └── integrations/
│           ├── __init__.py
│           └── prefect.py      # normalize_task_state, PrefectEventBridge
└── tests/
    ├── tasks/
    │   ├── test_models.py
    │   ├── test_executors.py
    │   ├── test_rendering.py
    │   └── test_adapters.py
    └── workflow/
        ├── test_events.py
        ├── test_models.py
        ├── test_context.py
        ├── test_event_builders.py
        ├── test_reporting.py
        └── test_prefect_integration.py
```

### Code movement (no rewrites)

| From | To |
|------|----|
| `controlplane_tool/tasks/models.py` | `workflow_tasks/tasks/models.py` |
| `controlplane_tool/tasks/executors.py` | `workflow_tasks/tasks/executors.py` |
| `controlplane_tool/tasks/rendering.py` | `workflow_tasks/tasks/rendering.py` |
| `controlplane_tool/tasks/adapters.py` | `workflow_tasks/tasks/adapters.py` |
| `tui_toolkit/events.py` | `workflow_tasks/workflow/events.py` |
| `tui_toolkit/workflow.py` (context mgmt) | `workflow_tasks/workflow/context.py` |
| `tui_toolkit/workflow.py` (builders) | `workflow_tasks/workflow/event_builders.py` |
| `tui_toolkit/workflow.py` (helpers) | `workflow_tasks/workflow/reporting.py` |
| `controlplane_tool/workflow/workflow_models.py` (WorkflowRun, TaskDefinition, TaskRun, WorkflowState) | `workflow_tasks/workflow/models.py` |
| `controlplane_tool/workflow/workflow_events.py` (normalize_task_state) | `workflow_tasks/integrations/prefect.py` |
| `controlplane_tool/orchestation/prefect_event_bridge.py` (PrefectEventBridge) | `workflow_tasks/integrations/prefect.py` |

### What stays in `controlplane_tool`

| File | Reason |
|------|--------|
| `workflow/task_events.py` | Bridge: CommandTaskSpec + TaskResult → WorkflowEvent |
| `workflow/workflow_progress.py` | Uses tui_toolkit; bridges workflow context to tui_toolkit |
| `workflow/workflow_models.py` | Reduced to TuiPhaseSnapshot, TuiWorkflowSnapshot only |
| `workflow/workflow_events.py` | Reduced to normalize_task_state shim (PR1) then inline import (PR2) |
| `tui/event_aggregator.py` | Renamed from prefect_bridge.py; uses TuiPhaseSnapshot |

---

## Changes to `tui_toolkit`

### PR1 — shim state

`tui_toolkit/events.py` becomes a re-export shim:
```python
from workflow_tasks.workflow.events import WorkflowEvent, WorkflowContext, WorkflowSink
__all__ = ["WorkflowEvent", "WorkflowContext", "WorkflowSink"]
```

`tui_toolkit/workflow.py` retains only the Rich renderer (`_render_event`, `_emit`, `status`). All event types, context management, builders, and helpers are imported from `workflow_tasks`. Public re-exports (`phase`, `step`, `success`, etc.) delegate to `workflow_tasks.workflow.reporting`.

`tui_toolkit/__init__.py` — public API unchanged; consumers see no difference.

### PR2 — final state

- `tui_toolkit/events.py` → **deleted**
- `tui_toolkit/workflow.py` → **deleted** (renderer moves to `controlplane_tool/tui/workflow_renderer.py`)
- `tui_toolkit/__init__.py` → removes all workflow-related exports

**`tui_toolkit` final contents:** `theme`, `brand`, `context` (UIContext), `console`, `chrome`, `pickers`.

---

## Changes to `controlplane_tool`

### PR1 — shim state

Four shim files in `tasks/` re-export from `workflow_tasks`:
```python
# controlplane_tool/tasks/models.py
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult, TaskStatus, ExecutionTarget
__all__ = [...]
```

`workflow/workflow_models.py` — `WorkflowRun`, `TaskDefinition`, `TaskRun`, `WorkflowState` become shims to `workflow_tasks.workflow.models`. `TuiPhaseSnapshot` and `TuiWorkflowSnapshot` remain in this file.

`workflow/workflow_events.py` — `normalize_task_state` shims to `workflow_tasks.integrations.prefect`. Builders re-export from `workflow_tasks.workflow.event_builders`.

`tui/prefect_bridge.py` → renamed `tui/event_aggregator.py`; class renamed `WorkflowEventAggregator`.

`orchestation/prefect_event_bridge.py` → deleted (code moved to `workflow_tasks`).

`pyproject.toml` → adds `workflow-tasks` as a local path dependency.

### PR2 — final state

- Shims in `tasks/` deleted; all consumer imports rewritten to `workflow_tasks` directly
- `tui/workflow_renderer.py` — new file with Rich renderer (moved from `tui_toolkit/workflow.py`)
- `workflow/workflow_models.py` — contains only `TuiPhaseSnapshot` and `TuiWorkflowSnapshot`
- `workflow/workflow_events.py` — contains only `normalize_task_state` (direct import from `workflow_tasks.integrations.prefect`)

---

## Naming Decisions

| Old name | New name | Reason |
|----------|----------|--------|
| `tui_toolkit/workflow.py` helpers section | `workflow_tasks/workflow/reporting.py` | `phase()`, `step()`, `success()` are reporting primitives, not generic helpers |
| `tui_toolkit/workflow.py` builders section | `workflow_tasks/workflow/event_builders.py` | Disambiguates from generic object builders |
| `controlplane_tool/tui/prefect_bridge.py` | `controlplane_tool/tui/event_aggregator.py` | Not Prefect-specific; aggregates any WorkflowEvent into TUI state |
| `TuiPrefectBridge` | `WorkflowEventAggregator` | Same reason |

---

## Test Strategy

### `workflow-tasks` test suite

Tests from `controlplane/tests/test_task_*.py` and workflow-pure tests are moved (not rewritten) into the new library's test suite.

A package boundary test enforces isolation:
```python
def test_no_tui_toolkit_dependency():
    import workflow_tasks
    import sys
    assert "tui_toolkit" not in sys.modules

def test_no_controlplane_dependency():
    import workflow_tasks
    import sys
    assert "controlplane_tool" not in sys.modules
```

### `controlplane_tool` test suite

Tests covering integration logic (`WorkflowEventAggregator`, `WorkflowProgressReporter`, Rich renderer) remain in `controlplane/tests/`. These test the bridge between `workflow-tasks` and `tui-toolkit`.

### CI gate

`workflow-tasks` runs `pytest` independently — without `controlplane_tool` or `tui_toolkit` installed. This is the primary isolation guarantee.

---

## Tooling & Quality

### Stack (identical across all three libraries)

| Tool | Purpose |
|------|---------|
| `ruff` | Linter — rules F + SLF, line-length 100, target py311 |
| `basedpyright` | Type checker — `typeCheckingMode = "basic"` |
| `import-linter` | Architectural import contracts (intra-library) |
| `pytest` + `pytest-cov` | Tests with coverage threshold enforcement |
| `grimp` | Import graph analysis for coupling metrics |
| `pydeps` | Dependency visualisation |
| `devtools/quality.py` | Single script running all checks (same pattern as `controlplane-tool`) |

`tui-toolkit` currently has only `pytest` in dev deps — it gains ruff, basedpyright, import-linter, grimp, pydeps as part of this work.

### Coverage thresholds

| Library | Threshold | Rationale |
|---------|-----------|-----------|
| `workflow-tasks` | 90% | Mostly moved code with existing tests |
| `tui-toolkit` | 80% | UI/rendering harder to unit-test |

### Import-linter contracts for `workflow-tasks`

```ini
[importlinter]
root_package = workflow_tasks

[importlinter:contract:tasks_are_independent]
name = tasks must not import workflow or integrations
type = forbidden
source_modules = workflow_tasks.tasks
forbidden_modules =
    workflow_tasks.workflow
    workflow_tasks.integrations

[importlinter:contract:pure_types_are_logic_free]
name = pure type modules must not import logic modules
type = forbidden
source_modules =
    workflow_tasks.workflow.events
    workflow_tasks.workflow.models
    workflow_tasks.workflow.context
forbidden_modules =
    workflow_tasks.workflow.reporting
    workflow_tasks.workflow.event_builders

[importlinter:contract:no_external_deps]
name = workflow_tasks must not import tui_toolkit or controlplane_tool
type = forbidden
source_modules = workflow_tasks
forbidden_modules =
    tui_toolkit
    controlplane_tool
```

### Import-linter contracts for `tui-toolkit` (post PR2)

```ini
[importlinter]
root_package = tui_toolkit

[importlinter:contract:no_external_project_deps]
name = tui_toolkit must not import workflow_tasks or controlplane_tool
type = forbidden
source_modules = tui_toolkit
forbidden_modules =
    workflow_tasks
    controlplane_tool
```

### Cross-project coupling with `grimp`

`grimp` supports multi-root analysis: when all three packages are installed in the same virtualenv, a single script can build the full import graph and verify no paths exist between projects that violate the architecture.

Added as an extra step in `controlplane-tool/devtools/quality.py` (the integration layer — the only project with all three packages installed):

```python
import grimp

graph = grimp.build_graph("controlplane_tool", "tui_toolkit", "workflow_tasks")

# workflow_tasks must not reach tui_toolkit
assert not graph.find_shortest_chain(
    importer="workflow_tasks", imported="tui_toolkit"
), "workflow_tasks must not import tui_toolkit"

# tui_toolkit must not reach workflow_tasks
assert not graph.find_shortest_chain(
    importer="tui_toolkit", imported="workflow_tasks"
), "tui_toolkit must not import workflow_tasks"
```

This gives a living cross-project coupling gate that fails CI if the boundaries are violated.

## Execution Plan

Two PRs, same pattern as `tui-toolkit` extraction:

**PR1 — `codex/workflow-tasks-pr1-library`**
- Scaffold `tools/workflow-tasks/` with `pyproject.toml`, package structure, tests
- Move code (no rewrites): tasks, workflow domain, events, context, builders, reporting, integrations
- Add shims in `tui_toolkit` and `controlplane_tool/tasks/`
- Rename `prefect_bridge.py` → `event_aggregator.py`, `TuiPrefectBridge` → `WorkflowEventAggregator`
- All existing tests pass; new lib's tests pass in isolation

**PR2 — `codex/workflow-tasks-pr2-imports`**
- Rewrite all consumer imports in `controlplane_tool` directly to `workflow_tasks`
- Move Rich renderer from `tui_toolkit/workflow.py` to `controlplane_tool/tui/workflow_renderer.py`
- Delete shims in `controlplane_tool/tasks/` and `tui_toolkit/events.py`, `tui_toolkit/workflow.py`
- Update `tui_toolkit/__init__.py` — remove workflow exports
- All tests pass; `tui_toolkit` has zero workflow imports
