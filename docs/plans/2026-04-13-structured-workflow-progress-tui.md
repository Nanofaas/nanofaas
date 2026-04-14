# Structured Workflow Progress TUI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Follow `@superpowers:test-driven-development` for every code change and use `@rich-terminal-output` when changing the Rich dashboard rendering.

**Goal:** Make the TUI workflow view correct and maintainable by representing execution as a structured hierarchy with stable identities, explicit lifecycle events, and a Rich rendering that never confuses nested work with top-level plan steps.

**Architecture:** Split the current flat progress flow into three layers. First, define a structured workflow event model with stable `task_id` and `parent_task_id` semantics so execution identity does not depend on labels. Second, bind the top-level E2E plan step context inside `E2eRunner` and move nested progress emission in runners such as `k3s_curl_runner` to an explicit reporter API that emits balanced start/complete/fail events. Third, refactor the TUI bridge and Rich dashboard so the left pane renders only the top-level plan order, while nested progress is rendered as child detail, not appended as peer rows.

**Tech Stack:** Python 3.12, `uv`, `pytest`, Rich, Typer, prompt-toolkit, existing workflow event helpers in `tools/controlplane`.

---

## Scope and design invariants

This plan covers the workflow/event path used by `controlplane-tool` TUI and the `k3s-junit-curl` scenario.

Primary invariants:

- The left `Execution Phases` pane must be a faithful projection of `ScenarioPlan.steps`, in plan order only.
- Nested runner activity must never create new top-level rows.
- Progress identity must be keyed by stable IDs, not by user-facing labels.
- Nested steps must emit explicit completion or failure; the dashboard must not infer success from the arrival of other events or by force-closing everything at the end.
- Rich rendering is a pure view over structured workflow state, not a place where ordering or identity is reconstructed heuristically.

Non-goals:

- no rewrite of Prefect integration
- no change to the scenario semantics themselves
- no workaround that hides wrong state without fixing event identity/lifecycle

## Task 1: Lock the execution invariants with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_tui_prefect_bridge.py`
- Modify: `tools/controlplane/tests/test_tui_workflow.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`
- Modify: `tools/controlplane/tests/test_workflow_events.py`
- Modify: `tools/controlplane/tests/test_console_workflow.py`

**Step 1: Write the failing tests**

Add tests for these invariants:

- top-level planned steps remain the only rows in `Execution Phases`
- nested `Verify` work for `Run k3s-junit-curl verification` is attached as child progress, not appended after cleanup rows
- `Teardown VM` cannot appear completed before the top-level verification step completes
- nested child steps do not get auto-greened without explicit completion events
- bridge identity does not rely on repeated labels such as `Verify`

Representative tests:

```python
def test_nested_verify_events_do_not_create_new_top_level_rows(): ...

def test_teardown_row_stays_pending_until_parent_cleanup_step_completes(): ...

def test_child_events_require_explicit_completion(): ...
```

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest \
  tests/test_tui_prefect_bridge.py \
  tests/test_tui_workflow.py \
  tests/test_tui_choices.py \
  tests/test_workflow_events.py \
  tests/test_console_workflow.py -q
```

Expected: FAIL because the current bridge flattens nested rows, uses labels as identity, and the TUI still force-closes running rows on successful return.

**Step 3: Commit the red tests**

```bash
git add tools/controlplane/tests/test_tui_prefect_bridge.py \
        tools/controlplane/tests/test_tui_workflow.py \
        tools/controlplane/tests/test_tui_choices.py \
        tools/controlplane/tests/test_workflow_events.py \
        tools/controlplane/tests/test_console_workflow.py
git commit -m "test: lock workflow progress invariants"
```

## Task 2: Introduce a structured workflow event model with stable IDs

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/workflow_models.py`
- Modify: `tools/controlplane/src/controlplane_tool/workflow_events.py`
- Modify: `tools/controlplane/src/controlplane_tool/console.py`
- Modify: `tools/controlplane/tests/test_workflow_events.py`
- Modify: `tools/controlplane/tests/test_console_workflow.py`

**Step 1: Write the failing test**

Add tests that require:

- `WorkflowEvent` to carry `parent_task_id`
- helper constructors to preserve `task_id`, `parent_task_id`, and context correctly
- structured console helpers to emit paired started/completed/failed child events

Minimal target shape:

```python
event = build_task_event(
    kind="task.running",
    flow_id="e2e.k3s_junit_curl",
    task_id="verify.health",
    parent_task_id="tests.run_k3s_curl_checks",
    title="Verifying control-plane health",
)
assert event.parent_task_id == "tests.run_k3s_curl_checks"
```

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest tests/test_workflow_events.py tests/test_console_workflow.py -q
```

Expected: FAIL because `WorkflowEvent` and the helper constructors do not yet support parent-child identity.

**Step 3: Write minimal implementation**

Make these changes:

- extend `WorkflowEvent` with `parent_task_id`
- extend `WorkflowContext` so nested progress can inherit a parent task
- add a structured progress helper in `console.py` or a new `workflow_progress.py` module using a context manager:

```python
@contextmanager
def workflow_step(task_id: str, title: str, *, parent_task_id: str | None = None):
    emit(task.running)
    try:
        yield
    except Exception as exc:
        emit(task.failed, detail=str(exc))
        raise
    else:
        emit(task.completed)
```

Keep the existing `phase()/step()/success()` API only as a compatibility layer for callers not yet migrated.

**Step 4: Run test to verify it passes**

Run:

```bash
uv run pytest tests/test_workflow_events.py tests/test_console_workflow.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/workflow_models.py \
        tools/controlplane/src/controlplane_tool/workflow_events.py \
        tools/controlplane/src/controlplane_tool/console.py \
        tools/controlplane/tests/test_workflow_events.py \
        tools/controlplane/tests/test_console_workflow.py
git commit -m "feat: add hierarchical workflow event identity"
```

## Task 3: Bind top-level plan context in `E2eRunner`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/executor.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`

**Step 1: Write the failing test**

Add tests that require each `ScenarioPlanStep` to have a stable machine ID, and that `E2eRunner` binds that ID as the active workflow context when executing the step action.

Example target:

```python
assert plan.steps[19].step_id == "tests.run_k3s_curl_checks"
```

and

```python
assert nested_event.parent_task_id == "tests.run_k3s_curl_checks"
```

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest tests/test_e2e_runner.py -q
```

Expected: FAIL because `ScenarioPlanStep` only carries `summary`, `command`, `env`, and `action`.

**Step 3: Write minimal implementation**

- add `step_id: str` to `ScenarioPlanStep`
- populate `step_id` from `ScenarioOperation.operation_id` in `operation_to_plan_step()`
- assign explicit `step_id` values to the manual `k3s-junit-curl` tail steps in `e2e_runner.py`
- wrap each step execution in `bind_workflow_context(WorkflowContext(...))`

Representative implementation:

```python
with bind_workflow_context(
    WorkflowContext(flow_id=plan.request.scenario, task_id=step.step_id)
):
    step.action()
```

**Step 4: Run test to verify it passes**

Run:

```bash
uv run pytest tests/test_e2e_runner.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_components/executor.py \
        tools/controlplane/src/controlplane_tool/e2e_runner.py \
        tools/controlplane/tests/test_e2e_runner.py
git commit -m "feat: bind workflow context to e2e plan steps"
```

## Task 4: Replace ad-hoc nested console progress in `k3s_curl_runner`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/k3s_curl_runner.py`
- Create: `tools/controlplane/src/controlplane_tool/workflow_progress.py`
- Create: `tools/controlplane/tests/test_workflow_progress.py`
- Modify: `tools/controlplane/tests/test_console_workflow.py`

**Step 1: Write the failing test**

Add tests that require `verify_existing_stack()` to emit balanced nested events:

- `verify.phase`
- `verify.health`
- `verify.function.<fn_key>`
- `verify.prometheus`

Each must emit `running` followed by `completed` or `failed`, and each child must declare `parent_task_id="tests.run_k3s_curl_checks"`.

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest tests/test_workflow_progress.py tests/test_console_workflow.py -q
```

Expected: FAIL because `k3s_curl_runner` currently calls `phase()`, `step()`, and `success()` directly with no explicit completion for substeps.

**Step 3: Write minimal implementation**

Create a reusable reporter API:

```python
@dataclass(frozen=True)
class WorkflowProgressReporter:
    flow_id: str
    parent_task_id: str

    @contextmanager
    def child(self, task_id: str, title: str): ...
```

Migrate `k3s_curl_runner` to:

- start a single child group for verification
- wrap `_verify_health()`, each `_run_function_workflow()`, and `_verify_prometheus_metrics()` in reporter child contexts
- stop using plain `phase()/step()/success()` for nested progress

Do not emit display-only labels with no stable IDs.

**Step 4: Run test to verify it passes**

Run:

```bash
uv run pytest tests/test_workflow_progress.py tests/test_console_workflow.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/k3s_curl_runner.py \
        tools/controlplane/src/controlplane_tool/workflow_progress.py \
        tools/controlplane/tests/test_workflow_progress.py \
        tools/controlplane/tests/test_console_workflow.py
git commit -m "refactor: emit structured nested progress for k3s verification"
```

## Task 5: Refactor the TUI bridge to a hierarchical state model

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_prefect_bridge.py`
- Modify: `tools/controlplane/src/controlplane_tool/workflow_models.py`
- Modify: `tools/controlplane/tests/test_tui_prefect_bridge.py`

**Step 1: Write the failing test**

Add tests that require:

- top-level phases indexed strictly by plan step ID
- child phases stored under their parent, not appended to the top-level list
- repeated labels such as `Verify` to remain distinct when their IDs differ

Example target:

```python
snapshot.top_level[0].task_id == "tests.run_k3s_curl_checks"
snapshot.top_level[0].children[0].task_id == "verify.health"
```

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest tests/test_tui_prefect_bridge.py -q
```

Expected: FAIL because the current bridge stores a single flat list keyed partly by label.

**Step 3: Write minimal implementation**

Change `TuiPrefectBridge` from a flat list to a tree-like snapshot:

```python
@dataclass
class TuiPhaseSnapshot:
    label: str
    task_id: str | None
    parent_task_id: str | None
    children: list["TuiPhaseSnapshot"]
```

Rules:

- planned top-level rows are pre-seeded from `ScenarioPlan.steps`
- an event with `parent_task_id` is attached under that parent
- an event without `parent_task_id` is only top-level if it belongs to the plan
- labels never decide identity when a stable ID exists

Remove label-only placeholder matching once the stable ID path is green.

**Step 4: Run test to verify it passes**

Run:

```bash
uv run pytest tests/test_tui_prefect_bridge.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_prefect_bridge.py \
        tools/controlplane/src/controlplane_tool/workflow_models.py \
        tools/controlplane/tests/test_tui_prefect_bridge.py
git commit -m "refactor: store workflow progress as a hierarchy"
```

## Task 6: Redesign the Rich dashboard as a projection of the hierarchy

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_workflow.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/tests/test_tui_workflow.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write the failing test**

Add tests that require:

- `Execution Phases` to show only top-level plan steps
- nested verification work to appear in a dedicated Rich panel, not as extra numbered rows
- `Raw Command Output` to remain separate from progress structure
- no end-of-run `complete_running_steps()` hack

Representative expectations:

```python
assert "26. Verify" not in left_pane_text
assert "Verifying control-plane health" in nested_panel_text
```

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest tests/test_tui_workflow.py tests/test_tui_choices.py -q
```

Expected: FAIL because the current dashboard flattens phases and `tui_app.py` force-completes all running rows after action return.

**Step 3: Write minimal implementation**

Use `@rich-terminal-output` patterns:

- keep left pane as a `Table` of top-level plan steps only
- render nested work in a separate panel using `rich.tree.Tree` or a dedicated nested `Table`
- keep raw log output in its own panel
- remove:

```python
dashboard.complete_running_steps(state="success")
```

from `tui_app.py`

Minimal rendering sketch:

```python
nested = Tree("[bold cyan]Nested Progress[/]")
for child in active_parent.children:
    nested.add(f"{icon} {child.label}")
```

**Step 4: Run test to verify it passes**

Run:

```bash
uv run pytest tests/test_tui_workflow.py tests/test_tui_choices.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_workflow.py \
        tools/controlplane/src/controlplane_tool/tui_app.py \
        tools/controlplane/tests/test_tui_workflow.py \
        tools/controlplane/tests/test_tui_choices.py
git commit -m "feat: render workflow progress as top-level phases plus nested detail"
```

## Task 7: Migrate remaining runner progress emitters and document the model

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli_stack_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/container_local_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/deploy_host_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_vm_runner.py`
- Modify: `tools/controlplane/README.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Write the failing tests**

Add tests that require:

- all runner modules use the same structured progress API for nested work
- docs describe the left pane as plan-ordered and nested work as detail, not peer phases

**Step 2: Run test to verify it fails**

Run:

```bash
uv run pytest tests/test_docs_links.py -q
```

Expected: FAIL because docs do not yet describe the new workflow progress model.

**Step 3: Write minimal implementation**

- migrate any remaining direct nested `phase()/step()/success()` usage where it would otherwise create ambiguous top-level rows
- document the new model in `tools/controlplane/README.md` and `docs/testing.md`

Keep the migration intentionally narrow: only move emitters that participate in live nested workflow rendering.

**Step 4: Run tests to verify they pass**

Run:

```bash
uv run pytest tests/test_docs_links.py -q
uv run pytest tests/test_tui_prefect_bridge.py tests/test_tui_workflow.py tests/test_tui_choices.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/cli_stack_runner.py \
        tools/controlplane/src/controlplane_tool/cli_host_runner.py \
        tools/controlplane/src/controlplane_tool/container_local_runner.py \
        tools/controlplane/src/controlplane_tool/deploy_host_runner.py \
        tools/controlplane/src/controlplane_tool/cli_vm_runner.py \
        tools/controlplane/README.md \
        docs/testing.md \
        tools/controlplane/tests/test_docs_links.py
git commit -m "docs: align runner progress model and tui documentation"
```

## Final verification

Run the focused suite:

```bash
uv run pytest \
  tests/test_workflow_events.py \
  tests/test_console_workflow.py \
  tests/test_workflow_progress.py \
  tests/test_e2e_runner.py \
  tests/test_tui_prefect_bridge.py \
  tests/test_tui_workflow.py \
  tests/test_tui_choices.py -q
```

Then run the broader tool suite:

```bash
uv run pytest tests -q
```

Manual verification:

```bash
uv run controlplane-tool
```

Manual checks:

- start `k3s-junit-curl`
- verify the left pane stays in plan order only
- verify nested `Verify` work appears as child detail, not as peer numbered rows
- verify `Teardown VM` never appears complete before the verification parent step completes
- verify no row turns green without an explicit completion event

