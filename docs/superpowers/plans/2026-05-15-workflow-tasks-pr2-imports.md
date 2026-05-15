# workflow-tasks PR2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove all backward-compat shims from PR1: rewrite `controlplane_tool` imports directly to `workflow_tasks`, move the Rich renderer out of `tui_toolkit`, delete `tui_toolkit/events.py` shim, strip `tui_toolkit/workflow.py` to just `header()`, and enforce the final import-linter contract `tui_toolkit must not import workflow_tasks`.

**Architecture:** All workflow function imports in `controlplane_tool` move from `tui_toolkit` → `workflow_tasks`. The Rich renderer (`_render_event`) moves from `tui_toolkit/workflow.py` to `controlplane_tool/tui/workflow_renderer.py`. `tui_toolkit` becomes pure UI primitives with zero workflow dependency.

**Tech Stack:** Python 3.11+, uv, pytest, ruff, import-linter

**Design spec:** `docs/superpowers/specs/2026-05-15-workflow-tasks-extraction-design.md`

**Branch:** stack on `worktree-workflow-tasks-pr1` — when PR1 merges, rebase on main.

---

## Pre-flight

Before starting, verify the PR1 baseline still holds:

```bash
cd tools/controlplane && uv run pytest -q --tb=no 2>&1 | tail -5
cd tools/tui-toolkit && uv run pytest -q --tb=no 2>&1 | tail -5
cd tools/workflow-tasks && uv run pytest -q --tb=no 2>&1 | tail -5
```

Expected: controlplane 967/968, tui-toolkit 81/81, workflow-tasks 74/74.

---

## File Map

### Created
- `tools/controlplane/src/controlplane_tool/tui/workflow_renderer.py`
- `tools/controlplane/tests/test_workflow_renderer.py`

### Modified — controlplane_tool (rewrite imports)
- `tools/controlplane/src/controlplane_tool/cli/vm_commands.py`
- `tools/controlplane/src/controlplane_tool/cli_validation/cli_host_runner.py`
- `tools/controlplane/src/controlplane_tool/cli_validation/cli_stack_runner.py`
- `tools/controlplane/src/controlplane_tool/core/process_streaming.py`
- `tools/controlplane/src/controlplane_tool/core/shell_backend.py`
- `tools/controlplane/src/controlplane_tool/e2e/container_local_runner.py`
- `tools/controlplane/src/controlplane_tool/e2e/deploy_host_runner.py`
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- `tools/controlplane/src/controlplane_tool/e2e/helm_stack_runner.py`
- `tools/controlplane/src/controlplane_tool/e2e/k3s_curl_runner.py`
- `tools/controlplane/src/controlplane_tool/infra/runtimes/grafana_runtime.py`
- `tools/controlplane/src/controlplane_tool/tui/app.py`
- `tools/controlplane/src/controlplane_tool/tui/workflow.py`
- `tools/controlplane/src/controlplane_tool/tui/workflow_controller.py`
- `tools/controlplane/src/controlplane_tool/workflow/workflow_progress.py`

### Deleted — controlplane_tool tasks/ shims
- `tools/controlplane/src/controlplane_tool/tasks/models.py`
- `tools/controlplane/src/controlplane_tool/tasks/executors.py`
- `tools/controlplane/src/controlplane_tool/tasks/rendering.py`
- `tools/controlplane/src/controlplane_tool/tasks/adapters.py`
- `tools/controlplane/src/controlplane_tool/tasks/__init__.py`

### Modified — controlplane_tool (tasks shim consumers)
- `tools/controlplane/src/controlplane_tool/core/task_shell_adapter.py`
- `tools/controlplane/src/controlplane_tool/infra/vm/vm_cluster_workflows.py`
- `tools/controlplane/src/controlplane_tool/loadtest/loadtest_tasks.py`
- `tools/controlplane/src/controlplane_tool/scenario/components/executor.py`
- `tools/controlplane/src/controlplane_tool/workflow/task_events.py`

### Modified — controlplane_tool workflow/
- `tools/controlplane/src/controlplane_tool/workflow/workflow_models.py`
- `tools/controlplane/src/controlplane_tool/workflow/workflow_events.py`
- `tools/controlplane/.importlinter`

### Deleted — tui_toolkit
- `tools/tui-toolkit/src/tui_toolkit/events.py`
- `tools/tui-toolkit/tests/test_workflow_events.py`
- `tools/tui-toolkit/tests/test_workflow_render.py`

### Modified — tui_toolkit
- `tools/tui-toolkit/src/tui_toolkit/workflow.py`
- `tools/tui-toolkit/src/tui_toolkit/__init__.py`
- `tools/tui-toolkit/pyproject.toml`
- `tools/tui-toolkit/.importlinter`

---

## Task 1: Create `controlplane_tool/tui/workflow_renderer.py`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/tui/workflow_renderer.py`
- Create: `tools/controlplane/tests/test_workflow_renderer.py`

- [ ] **Step 1: Write `workflow_renderer.py`**

Move `_render_event` from `tui_toolkit/workflow.py` to here. Add `RichWorkflowSink` wrapper class.

```python
# tools/controlplane/src/controlplane_tool/tui/workflow_renderer.py
from __future__ import annotations

from contextlib import contextmanager
from typing import Generator

from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.text import Text

import tui_toolkit.console as _console_mod
from tui_toolkit.context import get_ui
from workflow_tasks.workflow.events import WorkflowEvent


def render_event(event: WorkflowEvent) -> None:
    """Render a WorkflowEvent to the active Rich console using the active theme."""
    theme = get_ui().theme
    _con = _console_mod.console

    if event.kind == "log.line":
        prefix = "stderr │ " if event.stream == "stderr" else ""
        _con.print(f"{prefix}{escape(event.line)}")
        return
    if event.kind == "phase.started":
        _con.print()
        _con.print(Rule(f"[{theme.accent_strong}]{escape(event.title)}[/]", style=theme.accent_dim))
        _con.print()
        return
    if event.kind == "task.running":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.completed":
        body = f"[bold {theme.success}]{theme.icon_completed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.success, padding=(0, 2)))
        _con.print()
        return
    if event.kind == "task.warning":
        _con.print(f"  [{theme.warning}]{theme.icon_warning}[/]  [{theme.warning}]{escape(event.title)}[/]")
        return
    if event.kind == "task.updated":
        if event.detail:
            _con.print(
                f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _con.print(f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]")
        return
    if event.kind == "task.skipped":
        _con.print(f"  [{theme.muted}]{theme.icon_skipped}  {escape(event.title)}[/]")
        return
    if event.kind == "task.cancelled":
        body = f"[bold {theme.warning}]{theme.icon_cancelled}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.warning, padding=(0, 2)))
        _con.print()
        return
    if event.kind == "task.failed":
        body = f"[bold {theme.error}]{theme.icon_failed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _con.print()
        _con.print(Panel(body, border_style=theme.error, padding=(0, 2)))
        _con.print()


class RichWorkflowSink:
    """WorkflowSink that renders events to the active Rich console."""

    def emit(self, event: WorkflowEvent) -> None:
        render_event(event)

    @contextmanager
    def status(self, label: str) -> Generator[None, None, None]:
        with _console_mod.console.status(
            f"[{get_ui().theme.accent}]{escape(label)}…[/]", spinner="dots"
        ):
            yield
```

- [ ] **Step 2: Write `test_workflow_renderer.py`**

Adapted from `tools/tui-toolkit/tests/test_workflow_render.py` — verify key event kinds render without crashing (smoke tests only, since Rich output is hard to assert exactly).

```python
# tools/controlplane/tests/test_workflow_renderer.py
from __future__ import annotations

import pytest
from rich.console import Console
from unittest.mock import MagicMock, patch

from workflow_tasks.workflow.events import WorkflowEvent
from controlplane_tool.tui.workflow_renderer import RichWorkflowSink, render_event


@pytest.fixture(autouse=True)
def _mock_console(monkeypatch):
    """Replace the Rich console with a StringIO-backed one so tests don't write to stdout."""
    from io import StringIO
    import tui_toolkit.console as console_mod
    mock_console = Console(file=StringIO(), highlight=False)
    monkeypatch.setattr(console_mod, "console", mock_console)
    return mock_console


def test_render_log_line_does_not_crash() -> None:
    event = WorkflowEvent(kind="log.line", flow_id="f", line="hello world")
    render_event(event)  # should not raise


def test_render_phase_started_does_not_crash() -> None:
    event = WorkflowEvent(kind="phase.started", flow_id="f", title="Provisioning")
    render_event(event)


def test_render_task_running_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.running", flow_id="f", title="Deploy VM")
    render_event(event)


def test_render_task_completed_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.completed", flow_id="f", title="Deploy VM")
    render_event(event)


def test_render_task_failed_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.failed", flow_id="f", title="Deploy VM", detail="timeout")
    render_event(event)


def test_render_task_skipped_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.skipped", flow_id="f", title="Optional step")
    render_event(event)


def test_render_task_warning_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.warning", flow_id="f", title="Low disk space")
    render_event(event)


def test_render_task_cancelled_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.cancelled", flow_id="f", title="Deploy VM")
    render_event(event)


def test_render_task_updated_does_not_crash() -> None:
    event = WorkflowEvent(kind="task.updated", flow_id="f", title="Deploy VM", detail="step 2/3")
    render_event(event)


def test_rich_workflow_sink_emit_does_not_crash() -> None:
    sink = RichWorkflowSink()
    sink.emit(WorkflowEvent(kind="task.running", flow_id="f", title="Test"))


def test_rich_workflow_sink_status_does_not_crash() -> None:
    sink = RichWorkflowSink()
    with sink.status("loading"):
        pass
```

- [ ] **Step 3: Run renderer tests**

```bash
cd tools/controlplane && uv run pytest tests/test_workflow_renderer.py -v --no-cov
```

Expected: 11 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/workflow_renderer.py \
        tools/controlplane/tests/test_workflow_renderer.py
git commit -m "feat(controlplane): add workflow_renderer with RichWorkflowSink"
```

---

## Task 2: Rewrite `tui_toolkit` workflow imports in controlplane_tool

**Files:** 15 source files in `tools/controlplane/src/`

This task rewrites every `from tui_toolkit import [workflow_symbol]` to `from workflow_tasks import [same_symbol]`. UI-only imports (`console`, `pickers`, `chrome`, `theme`, `brand`, `context`, `header`, `render_screen_frame`, `AppBrand`, `Theme`, `UIContext`, `init_ui`) stay pointing to `tui_toolkit`.

- [ ] **Step 1: Rewrite `cli/vm_commands.py`**

```python
# Change:
from tui_toolkit import fail
# To:
from workflow_tasks import fail
```

- [ ] **Step 2: Rewrite `cli_validation/cli_host_runner.py`**

```python
# Change:
from tui_toolkit import phase, success, workflow_log, workflow_step
# To:
from workflow_tasks import phase, success, workflow_log, workflow_step
```

- [ ] **Step 3: Rewrite `cli_validation/cli_stack_runner.py`**

```python
# Change:
from tui_toolkit import phase, success, workflow_step
# To:
from workflow_tasks import phase, success, workflow_step
```

- [ ] **Step 4: Rewrite `core/process_streaming.py`**

```python
# Change:
from tui_toolkit import workflow_log
# To:
from workflow_tasks import workflow_log
```

- [ ] **Step 5: Rewrite `core/shell_backend.py`**

```python
# Change:
from tui_toolkit import has_workflow_sink, workflow_log
# To:
from workflow_tasks import has_workflow_sink, workflow_log
```

- [ ] **Step 6: Rewrite `e2e/container_local_runner.py`**

```python
# Change:
from tui_toolkit import phase, success, status, workflow_log, workflow_step
# To:
from workflow_tasks import phase, success, status, workflow_log, workflow_step
```

- [ ] **Step 7: Rewrite `e2e/deploy_host_runner.py`**

```python
# Change:
from tui_toolkit import phase, success, workflow_log, workflow_step
# To:
from workflow_tasks import phase, success, workflow_log, workflow_step
```

- [ ] **Step 8: Rewrite `e2e/e2e_runner.py`**

```python
# Change:
from tui_toolkit import bind_workflow_context
# To:
from workflow_tasks import bind_workflow_context
```

- [ ] **Step 9: Rewrite `e2e/helm_stack_runner.py`**

```python
# Change:
from tui_toolkit import phase, step, success
# To:
from workflow_tasks import phase, step, success
```

- [ ] **Step 10: Rewrite `e2e/k3s_curl_runner.py`**

```python
# Change:
from tui_toolkit import phase, step, success
# To:
from workflow_tasks import phase, step, success
```

- [ ] **Step 11: Rewrite `infra/runtimes/grafana_runtime.py`**

```python
# Change:
from tui_toolkit import step, skip
# To:
from workflow_tasks import step, skip
```

- [ ] **Step 12: Rewrite `tui/app.py`**

```python
# Change:
from tui_toolkit import fail, header, phase, step, success, warning
# To:
from tui_toolkit import header
from workflow_tasks import fail, phase, step, success, warning
```

- [ ] **Step 13: Rewrite `tui/workflow_controller.py`**

```python
# Change:
from tui_toolkit import bind_workflow_sink
# To:
from workflow_tasks import bind_workflow_sink
```

- [ ] **Step 14: Rewrite `tui/workflow.py` (controlplane's TUI workflow module)**

```python
# Change:
from controlplane_tool.workflow.workflow_models import WorkflowEvent
# To:
from workflow_tasks.workflow.events import WorkflowEvent
```

- [ ] **Step 15: Rewrite `workflow/workflow_progress.py`**

```python
# Change:
from tui_toolkit import get_workflow_context, workflow_step
# To:
from workflow_tasks import get_workflow_context, workflow_step
```

- [ ] **Step 16: Run controlplane test suite**

```bash
cd tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -15
```

Expected: 967 pass (1 pre-existing failure).

- [ ] **Step 17: Commit**

```bash
git add tools/controlplane/src/
git commit -m "refactor(controlplane): rewrite tui_toolkit workflow imports to workflow_tasks"
```

---

## Task 3: Delete `controlplane_tool/tasks/` shims and update consumers

**Files:** 5 shim files deleted, 5 consumer files updated

- [ ] **Step 1: Update `core/task_shell_adapter.py`**

```python
# Change:
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult
# To:
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult
```

- [ ] **Step 2: Update `infra/vm/vm_cluster_workflows.py`**

```python
# Change:
from controlplane_tool.tasks.adapters import operation_to_task_spec
from controlplane_tool.tasks.rendering import render_task_command
# To:
from workflow_tasks.tasks.adapters import operation_to_task_spec
from workflow_tasks.tasks.rendering import render_task_command
```

- [ ] **Step 3: Update `loadtest/loadtest_tasks.py`**

```python
# Change:
from controlplane_tool.tasks.models import CommandTaskSpec
# To:
from workflow_tasks.tasks.models import CommandTaskSpec
```

- [ ] **Step 4: Update `scenario/components/executor.py`**

```python
# Change:
from controlplane_tool.tasks.adapters import operation_to_task_spec
# To:
from workflow_tasks.tasks.adapters import operation_to_task_spec
```

- [ ] **Step 5: Update `workflow/task_events.py`**

```python
# Change:
from controlplane_tool.tasks.models import CommandTaskSpec, TaskResult
# To:
from workflow_tasks.tasks.models import CommandTaskSpec, TaskResult
```

- [ ] **Step 6: Delete shim files**

```bash
git rm tools/controlplane/src/controlplane_tool/tasks/models.py \
       tools/controlplane/src/controlplane_tool/tasks/executors.py \
       tools/controlplane/src/controlplane_tool/tasks/rendering.py \
       tools/controlplane/src/controlplane_tool/tasks/adapters.py \
       tools/controlplane/src/controlplane_tool/tasks/__init__.py
```

- [ ] **Step 7: Verify no remaining references to `controlplane_tool.tasks`**

```bash
grep -r "from controlplane_tool\.tasks\|controlplane_tool\.tasks\." \
     tools/controlplane/src/ --include="*.py"
```

Expected: no output.

- [ ] **Step 8: Run tests**

```bash
cd tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -10
```

Expected: 967 pass.

- [ ] **Step 9: Update `.importlinter`**

Remove the `tasks_are_logic_only` contract (the `controlplane_tool.tasks` package no longer exists). In `tools/controlplane/.importlinter`, delete the entire `[importlinter:contract:tasks_are_logic_only]` section.

- [ ] **Step 10: Verify import-linter passes**

```bash
cd tools/controlplane && uv run lint-imports
```

Expected: all remaining contracts kept.

- [ ] **Step 11: Commit**

```bash
git add tools/controlplane/
git commit -m "refactor(controlplane): delete tasks/ shims, import from workflow_tasks directly"
```

---

## Task 4: Update `controlplane_tool/workflow/` modules

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/workflow/workflow_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/workflow/workflow_events.py`

- [ ] **Step 1: Slim down `workflow_models.py`**

Remove re-exports of `WorkflowContext`, `WorkflowEvent`, `WorkflowSink`, `WorkflowRun`, `TaskDefinition`, `TaskRun`, `WorkflowState`. Keep only `TuiPhaseSnapshot` and `TuiWorkflowSnapshot`. Any file that was importing those from `controlplane_tool.workflow.workflow_models` must be updated to import from `workflow_tasks` directly.

First, find consumers:

```bash
grep -r "from controlplane_tool\.workflow\.workflow_models import" \
     tools/controlplane/src/ --include="*.py" | sort
```

For any file importing `WorkflowContext`, `WorkflowEvent`, `WorkflowSink`, `WorkflowRun`, `TaskDefinition`, `TaskRun`, `WorkflowState` from `workflow_models` — update those imports to `from workflow_tasks import [...]` or `from workflow_tasks.workflow.events import [...]`.

After updating consumers, replace `workflow_models.py`:

```python
# tools/controlplane/src/controlplane_tool/workflow/workflow_models.py
from __future__ import annotations

import time
from dataclasses import dataclass, field

from workflow_tasks.workflow.models import WorkflowState


@dataclass(slots=True)
class TuiPhaseSnapshot:
    label: str
    task_id: str | None = None
    parent_task_id: str | None = None
    status: WorkflowState = "pending"
    detail: str = ""
    started_at: float | None = None
    finished_at: float | None = None
    children: list["TuiPhaseSnapshot"] = field(default_factory=list)


@dataclass(slots=True)
class TuiWorkflowSnapshot:
    phases: list[TuiPhaseSnapshot]
    logs: list[str]
    show_logs: bool


__all__ = ["TuiPhaseSnapshot", "TuiWorkflowSnapshot"]
```

- [ ] **Step 2: Slim down `workflow_events.py`**

Keep only `normalize_task_state` (imported directly from `workflow_tasks.integrations.prefect`). Remove `PrefectEventBridge`, `build_*` re-exports, `WorkflowContext`, `WorkflowEvent` re-exports.

```python
# tools/controlplane/src/controlplane_tool/workflow/workflow_events.py
from workflow_tasks.integrations.prefect import normalize_task_state

__all__ = ["normalize_task_state"]
```

Verify no consumer imports `PrefectEventBridge` from this module (it should be importing from `workflow_tasks.integrations.prefect` directly since PR1):

```bash
grep -r "from controlplane_tool\.workflow\.workflow_events import" \
     tools/controlplane/src/ --include="*.py"
```

Update any found import as needed.

- [ ] **Step 3: Run tests**

```bash
cd tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -10
```

Expected: 967 pass.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/workflow/
git commit -m "refactor(controlplane): slim workflow_models and workflow_events to essentials"
```

---

## Task 5: Clean up `tui_toolkit`

**Files:** Delete `events.py`, update `workflow.py`, `__init__.py`, `pyproject.toml`, `.importlinter`; delete `tests/test_workflow_events.py`

- [ ] **Step 1: Delete `tui_toolkit/events.py` shim**

```bash
git rm tools/tui-toolkit/src/tui_toolkit/events.py
```

- [ ] **Step 2: Slim `tui_toolkit/workflow.py` to just `header()`**

Replace the entire file with only the `header()` function and its required imports:

```python
# tools/tui-toolkit/src/tui_toolkit/workflow.py
"""tui-toolkit workflow — startup banner only.

The workflow event types, context management, event builders, and reporting
helpers live in workflow_tasks. The Rich renderer lives in
controlplane_tool/tui/workflow_renderer.py.
"""
from __future__ import annotations

from rich.markup import escape
from rich.panel import Panel
from rich.text import Text

import tui_toolkit.console as _console_mod
from tui_toolkit.context import get_ui


def header(subtitle: str | None = None) -> None:
    """Startup banner — uses the brand from the active UIContext."""
    ui = get_ui()
    _con = _console_mod.console
    if ui.brand.ascii_logo:
        _con.print()
        _con.print(Text(ui.brand.ascii_logo, style=ui.theme.brand, justify="center"))
    if subtitle:
        _con.print(
            Panel(
                f"[{ui.theme.muted}]{escape(subtitle)}[/]",
                border_style=ui.theme.accent_dim,
                padding=(0, 4),
            )
        )
    _con.print()
```

- [ ] **Step 3: Update `tui_toolkit/__init__.py`**

Remove all workflow event exports. Keep `header`. Remove: `WorkflowContext`, `WorkflowEvent`, `WorkflowSink`, `bind_workflow_context`, `bind_workflow_sink`, `build_log_event`, `build_phase_event`, `build_task_event`, `fail`, `get_workflow_context`, `has_workflow_sink`, `phase`, `skip`, `status`, `step`, `success`, `warning`, `workflow_log`, `workflow_step`.

```python
# tools/tui-toolkit/src/tui_toolkit/__init__.py
"""tui-toolkit — terminal UI widgets with unified theming.

Workflow event types and reporting helpers live in workflow_tasks.
"""
from __future__ import annotations

__version__ = "0.1.0"

# theming + setup
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.context import UIContext, bind_ui, get_ui, init_ui
from tui_toolkit.theme import DEFAULT_THEME, Theme

# rendering primitives
from tui_toolkit.chrome import render_screen_frame
import tui_toolkit.console as console  # noqa: F401
from tui_toolkit.console import get_content_width

# pickers
from tui_toolkit.pickers import Choice, Separator, multiselect, select

# startup banner
from tui_toolkit.workflow import header

__all__ = [
    "__version__",
    # theming + setup
    "AppBrand", "DEFAULT_BRAND",
    "UIContext", "bind_ui", "get_ui", "init_ui",
    "DEFAULT_THEME", "Theme",
    # rendering primitives
    "render_screen_frame",
    "console", "get_content_width",
    # pickers
    "Choice", "Separator", "multiselect", "select",
    # startup banner
    "header",
]
```

- [ ] **Step 4: Delete workflow test files from tui-toolkit**

`test_workflow_events.py` covers `bind_workflow_context`, `bind_workflow_sink`, builders — all moved to `workflow_tasks` and already tested there.
`test_workflow_render.py` covers `_render_event` — moved to `controlplane_tool/tui/workflow_renderer.py` and already tested in `test_workflow_renderer.py`.

```bash
git rm tools/tui-toolkit/tests/test_workflow_events.py \
       tools/tui-toolkit/tests/test_workflow_render.py
```

- [ ] **Step 5: Remove `workflow-tasks` from `tui_toolkit/pyproject.toml`**

Remove `"workflow-tasks"` from `[project] dependencies` and remove the `[tool.uv.sources]` entry for `workflow-tasks`:

```toml
[project]
dependencies = [
    "rich>=13.8",
    "questionary>=2.1.1",
    "prompt-toolkit>=3.0",
]
```

Remove completely:
```toml
[tool.uv.sources]
workflow-tasks = { path = "../workflow-tasks", editable = true }
```

Then re-sync:

```bash
cd tools/tui-toolkit && uv sync
```

- [ ] **Step 6: Update `tui_toolkit/.importlinter`** — add final isolation contract

```ini
# tools/tui-toolkit/.importlinter
[importlinter]
root_package = tui_toolkit

[importlinter:contract:no_controlplane_dep]
name = tui_toolkit must not import controlplane_tool
type = forbidden
source_modules = tui_toolkit
forbidden_modules =
    controlplane_tool
include_external_packages = True

[importlinter:contract:no_workflow_tasks_dep]
name = tui_toolkit must not import workflow_tasks
type = forbidden
source_modules = tui_toolkit
forbidden_modules =
    workflow_tasks
include_external_packages = True
```

- [ ] **Step 7: Run tui_toolkit tests**

```bash
cd tools/tui-toolkit && uv run pytest -v --no-cov
```

Expected: tests pass. `test_workflow_events.py` is gone, `test_workflow_render.py` is gone (rendering moved to controlplane). The remaining tests (`test_brand.py`, `test_chrome.py`, `test_console.py`, `test_context.py`, `test_pickers.py`, `test_public_api.py`, `test_smoke.py`, `test_theme.py`) should all pass.

Update `tests/test_public_api.py` to remove assertions about workflow symbols (`WorkflowEvent`, `phase`, `workflow_step`, `bind_workflow_sink`, etc.). Run it to confirm it passes with the slimmed `__init__.py`.

- [ ] **Step 8: Run import-linter**

```bash
cd tools/tui-toolkit && uv run lint-imports
```

Expected: both contracts pass. If `workflow_tasks` is still referenced anywhere in tui_toolkit source, this will catch it.

- [ ] **Step 9: Commit**

```bash
git add tools/tui-toolkit/
git commit -m "feat(tui-toolkit): remove workflow_tasks dependency, keep only header()"
```

---

## Task 6: Final verification

- [ ] **Step 1: Run all three test suites**

```bash
cd tools/workflow-tasks && uv run pytest -q 2>&1 | tail -5
cd tools/tui-toolkit && uv run pytest -q 2>&1 | tail -5
cd tools/controlplane && uv run pytest -q --tb=no 2>&1 | tail -5
```

Expected:
- workflow-tasks: 74 pass, 95% coverage
- tui-toolkit: ≥ 50 pass (reduced from 81 — workflow event tests removed), coverage ≥ 80%
- controlplane: 967+ pass (new renderer tests added)

- [ ] **Step 2: Verify final dependency graph**

```bash
cd tools/controlplane && uv run python -c "
import grimp
graph = grimp.build_graph('controlplane_tool', 'tui_toolkit', 'workflow_tasks')
chain = graph.find_shortest_chain(importer='workflow_tasks', imported='tui_toolkit')
print('workflow_tasks -> tui_toolkit:', chain or 'NONE (OK)')
chain = graph.find_shortest_chain(importer='tui_toolkit', imported='workflow_tasks')
print('tui_toolkit -> workflow_tasks:', chain or 'NONE (OK)')
chain = graph.find_shortest_chain(importer='tui_toolkit', imported='controlplane_tool')
print('tui_toolkit -> controlplane_tool:', chain or 'NONE (OK)')
"
```

Expected: all three `NONE (OK)`.

- [ ] **Step 3: Run full quality suite**

```bash
cd tools/controlplane && uv run controlplane-quality
```

Expected: `Quality checks passed`

- [ ] **Step 4: Open PR2**

```bash
git push origin HEAD
gh pr create \
  --title "workflow-tasks PR2: remove shims, clean up tui_toolkit" \
  --base worktree-workflow-tasks-pr1 \
  --body "$(cat <<'EOF'
## Summary

- All 15 `controlplane_tool` files now import workflow symbols directly from `workflow_tasks`
- Rich renderer moved from `tui_toolkit/workflow.py` → `controlplane_tool/tui/workflow_renderer.py`
- `controlplane_tool/tasks/` shim files deleted; 5 consumers updated to `workflow_tasks.tasks.*`
- `workflow_models.py` reduced to `TuiPhaseSnapshot` + `TuiWorkflowSnapshot` only
- `tui_toolkit/events.py` shim deleted
- `tui_toolkit/workflow.py` reduced to `header()` only
- `tui_toolkit/__init__.py` no longer exports any workflow symbols
- `tui_toolkit` no longer depends on `workflow_tasks` — final isolation achieved

## Final dependency graph

```
workflow-tasks   (no external dependencies)
      ↑
controlplane-tool  →  tui-toolkit
```

## Test Plan

- [ ] workflow-tasks pytest passes (74+ tests, ≥ 90% coverage)
- [ ] tui-toolkit pytest passes (≥ 50 tests, ≥ 80% coverage)
- [ ] controlplane-tool pytest passes (967+ tests including new renderer tests)
- [ ] grimp: workflow_tasks→tui_toolkit NONE, tui_toolkit→workflow_tasks NONE
- [ ] controlplane-quality passes

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```
