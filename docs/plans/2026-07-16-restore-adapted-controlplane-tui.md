# Restore the Adapted Control-Plane TUI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Restore the tested menu-driven NANOFAAS TUI, adapted to the four current YAML workflows, with a visually invariant ASCII header and the historical live workflow/log dashboard.

**Architecture:** Port the still-compatible presentation components from commit `9af3d5bf`, then connect them directly to the current `_scenario`, `_environment`, `_workflow`, `provision_environment`, and `WorkflowEvent` APIs. Rebuild only the navigation/routing layer; do not restore transition-era product commands, adapters, profiles, or catalogs.

**Tech Stack:** Python 3.11+, Rich, prompt_toolkit, Questionary, local `tui-toolkit`, `workflow_tasks`, pytest.

---

## Reference and safety rules

- Validated design: `docs/plans/2026-07-16-adapted-controlplane-tui-design.md`.
- Historical presentation baseline: `9af3d5bf`.
- Historical full menu app baseline for visual comparison only: `ffdfec78`.
- Use `git show <commit>:<path>` only to read historical files; create or modify working-tree files with `apply_patch`.
- Before modifying an existing class/function/method, run `gitnexus_impact` upstream as required by `AGENTS.md`.
- `WorkflowEvent` has HIGH impact (25 direct dependents); consume it unchanged.
- Keep `ConsoleProgressSink`, `Workflow.run()`, scenario builders, and Typer command semantics unchanged.
- Preserve unrelated worktree changes in `AGENTS.md`, `CLAUDE.md`, `GEMINI.md`, and `pytest.ini`.

### Task 1: Restore the NANOFAAS UI context and local toolkit dependency

**Files:**

- Create: `tools/controlplane/src/controlplane_tool/tui/setup.py`
- Modify: `tools/controlplane/src/controlplane_tool/app/main.py:22`
- Modify: `tools/controlplane/pyproject.toml:6`
- Modify: `tools/controlplane/uv.lock`
- Create: `tools/controlplane/tests/test_tui_setup.py`

**Step 1: Inspect impact before editing `main`**

Run:

```text
gitnexus_impact({target: "main", direction: "upstream", repo: "mcFaas", includeTests: true})
```

Disambiguate with `tools/controlplane/src/controlplane_tool/app/main.py` if needed. If risk is HIGH or CRITICAL, report the changed blast radius before editing.

**Step 2: Write the failing setup test**

```python
from controlplane_tool.tui.setup import NANOFAAS_BRAND, setup_ui
from tui_toolkit.context import get_ui


def test_setup_ui_installs_nanofaas_brand_once() -> None:
    setup_ui()

    assert get_ui().brand is NANOFAAS_BRAND
    assert get_ui().brand.wordmark == "NANOFAAS"
    assert get_ui().brand.ascii_logo.count("NANOFAAS") == 0
    assert len(get_ui().brand.ascii_logo.splitlines()) == 6
```

The test checks the six-line logo without depending on terminal colors.

**Step 3: Run the test and verify RED**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_setup.py -q
```

Expected: FAIL because `controlplane_tool.tui.setup` and the `tui-toolkit` dependency are absent.

**Step 4: Restore the setup module**

Recreate `setup.py` from `9af3d5bf`, retaining only:

```python
from tui_toolkit import AppBrand, Theme, UIContext, init_ui

NANOFAAS_THEME = Theme()
NANOFAAS_BRAND = AppBrand(
    name="nanofaas",
    wordmark="NANOFAAS",
    ascii_logo="""
 ███╗   ██╗ █████╗ ███╗   ██╗ ██████╗ ███████╗ █████╗  █████╗ ███████╗
 ████╗  ██║██╔══██╗████╗  ██║██╔═══██╗██╔════╝██╔══██╗██╔══██╗██╔════╝
 ██╔██╗ ██║███████║██╔██╗ ██║██║   ██║█████╗  ███████║███████║███████╗
 ██║╚██╗██║██╔══██║██║╚██╗██║██║   ██║██╔══╝  ██╔══██║██╔══██║╚════██║
 ██║ ╚████║██║  ██║██║ ╚████║╚██████╔╝██║     ██║  ██║██║  ██║███████║
 ╚═╝  ╚═══╝╚═╝  ╚═╝╚═╝  ╚═══╝ ╚═════╝ ╚═╝     ╚═╝  ╚═╝╚═╝  ╚═╝╚══════╝
""".strip("\n"),
    default_breadcrumb="Main",
    default_footer_hint="Esc back | Ctrl+C exit",
)


def setup_ui() -> UIContext:
    return init_ui(UIContext(theme=NANOFAAS_THEME, brand=NANOFAAS_BRAND))
```

Do not restore `header()` calls. The active screen owns the logo.

**Step 5: Restore the dependency and startup initialization**

Add to `tools/controlplane/pyproject.toml`:

```toml
dependencies = [
    # existing entries...
    "tui-toolkit",
    "workflow-tasks",
]

[tool.uv.sources]
tui-toolkit = { path = "../tui-toolkit", editable = true }
workflow-tasks = { path = "../workflow-tasks", editable = true }
```

Call `setup_ui()` once at the start of `main()`, before launching either the no-argument TUI or the `tui` subcommand. The Typer `tui()` function must also call `setup_ui()` because tests and direct entry-point calls may bypass `main()`.

Regenerate the lockfile:

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv lock --project tools/controlplane
```

**Step 6: Run setup and entry-point tests**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_setup.py \
         tools/controlplane/tests/test_k3s_e2e_commands.py \
         tools/controlplane/tests/cli/test_command_surface.py -q
```

Expected: PASS; the six product commands and `tui` command remain unchanged.

**Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/setup.py \
  tools/controlplane/src/controlplane_tool/app/main.py \
  tools/controlplane/pyproject.toml tools/controlplane/uv.lock \
  tools/controlplane/tests/test_tui_setup.py
git commit -m "feat: restore the NANOFAAS UI context"
```

### Task 2: Port the historical event aggregator and its tests

**Files:**

- Create: `tools/controlplane/src/controlplane_tool/tui/models.py`
- Create: `tools/controlplane/src/controlplane_tool/tui/event_aggregator.py`
- Create: `tools/controlplane/tests/test_tui_event_aggregator.py`

**Step 1: Restore the historical tests before production code**

Read `tools/controlplane/tests/test_tui_prefect_bridge.py` from `9af3d5bf`. Recreate it as `test_tui_event_aggregator.py` with the same test bodies and these naming-only updates:

- `tui_bridge` -> `event_aggregator` in test names;
- remove any Prefect wording;
- retain real `build_task_event` and `build_log_event` inputs;
- retain all assertions for planned placeholders, nested parent identity, cancellation, updates, and bounded logs.

Do not weaken assertions to match a new implementation.

**Step 2: Run the restored tests and verify RED**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_event_aggregator.py -q
```

Expected: FAIL because `controlplane_tool.tui.event_aggregator` is absent.

**Step 3: Port the snapshot models**

Create `tui/models.py` from the two historical dataclasses in `controlplane_tool/workflow/workflow_models.py` at `9af3d5bf`:

```python
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
```

Use the current `workflow_tasks.workflow.models.WorkflowState` type.

**Step 4: Port `WorkflowEventAggregator`**

Recreate the exact aggregator behavior from `9af3d5bf`, changing only its model import to:

```python
from controlplane_tool.tui.models import TuiPhaseSnapshot, TuiWorkflowSnapshot
```

Keep its existing handling for:

- `log.line` with stderr prefix;
- `phase.started`;
- pending/running/completed/failed/cancelled/updated/warning/skipped tasks;
- parent/child routing through `parent_task_id`;
- dynamic top-level rows;
- bounded logs and log visibility toggling;
- start/finish timestamps.

**Step 5: Run the aggregator tests and verify GREEN**

Run the command from Step 2.

Expected: all restored aggregator tests PASS without changes to `WorkflowEvent` or its builders.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/models.py \
  tools/controlplane/src/controlplane_tool/tui/event_aggregator.py \
  tools/controlplane/tests/test_tui_event_aggregator.py
git commit -m "feat: restore tested TUI event aggregation"
```

### Task 3: Port the dashboard, sink, and live controller

**Files:**

- Create: `tools/controlplane/src/controlplane_tool/tui/workflow.py`
- Create: `tools/controlplane/src/controlplane_tool/tui/workflow_controller.py`
- Create: `tools/controlplane/tests/test_tui_workflow.py`
- Create: `tools/controlplane/tests/test_tui_workflow_controller.py`

**Step 1: Restore dashboard tests before implementation**

Recreate `test_tui_workflow.py` from `9af3d5bf` unchanged except for imports required by the new `tui.models` location. Preserve tests for:

- Summary, Execution Phases, Raw Command Output, and breadcrumb rendering;
- nested verification work;
- log visibility toggle;
- cancelled and updated tasks;
- durations and panel alignment;
- stable top-level rows while nested work completes.

**Step 2: Restore focused controller tests**

Recreate only the still-relevant tests from historical `test_tui_workflow_controller.py`:

- action exceptions are converted to a failed event while the sink is active;
- the original exception propagates after the final dashboard render;
- non-TTY execution does not wait for a key;
- `l` toggles log visibility;
- the final `Live.update(..., refresh=True)` occurs.

Do not restore `run_shared_flow`, Prefect orchestration, command-result recursion, or `TuiEventApplier` tests.

**Step 3: Run tests and verify RED**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_workflow.py \
         tools/controlplane/tests/test_tui_workflow_controller.py -q
```

Expected: FAIL because the dashboard/controller modules are absent.

**Step 4: Port `workflow.py`**

Recreate `workflow.py` from `9af3d5bf` with these bounded adaptations:

- import `WorkflowEventAggregator` from the restored module;
- use current `workflow_tasks.workflow.events.WorkflowEvent`;
- keep `WorkflowDashboard`, `WorkflowStepState`, `TuiWorkflowSink`, and `WorkflowKeyListener`;
- keep historical panels, icons, durations, nested tree, error detail, 200-line log buffer, and `l` toggle;
- keep `render_screen_frame` from `tui-toolkit`;
- remove no behavior merely to shorten the file.

**Step 5: Port only the generic live controller**

Recreate `TuiWorkflowController.run_live_workflow()` from `9af3d5bf`. Remove obsolete imports and methods involving:

- `run_local_flow`;
- `TuiEventApplier`;
- command-result traversal.

The resulting controller constructor needs no event-applier argument. Before entering `Live`, call `console.clear()` once so the preceding picker/header cannot remain in scrollback. Retain `transient=False`, error capture while the sink is bound, final acknowledgement, and key-listener cleanup.

**Step 6: Run dashboard and controller tests**

Run the command from Step 3.

Expected: PASS.

**Step 7: Run shared event tests**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_event_aggregator.py \
         tools/controlplane/tests/test_tui_workflow.py \
         tools/controlplane/tests/test_tui_workflow_controller.py \
         tools/controlplane/tests/test_workflow_events.py \
         tools/controlplane/tests/cli/test_progress.py -q
```

Expected: PASS, proving both UI and CLI consume the same unchanged event contract.

**Step 8: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/workflow.py \
  tools/controlplane/src/controlplane_tool/tui/workflow_controller.py \
  tools/controlplane/tests/test_tui_workflow.py \
  tools/controlplane/tests/test_tui_workflow_controller.py
git commit -m "feat: restore the live Rich workflow dashboard"
```

### Task 4: Enforce the single-header visual invariant

**Files:**

- Create: `tools/controlplane/tests/test_tui_chrome.py`
- Modify only if a test proves necessary: `tools/tui-toolkit/src/tui_toolkit/pickers.py`
- Modify only if a test proves necessary: `tools/tui-toolkit/src/tui_toolkit/chrome.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui/workflow_controller.py`

**Step 1: Write failing regression tests for the historical bug**

Use the NANOFAAS `UIContext`, prompt_toolkit `create_pipe_input`, `DummyOutput`, and Rich recording console. Assert:

```python
def assert_single_logo(screen: str) -> None:
    first_logo_line = "███╗   ██╗"
    assert screen.count(first_logo_line) == 1
```

Cover:

- main picker screen;
- submenu picker screen;
- `WorkflowDashboard.render()`;
- a simulated `Main -> Validation -> Back -> Load Testing` sequence, ensuring each captured screen contains one logo and no capture contains concatenated prior screens;
- identical zero-based row index for the first logo line in menu and dashboard captures;
- no standalone call to `tui_toolkit.workflow.header` from `controlplane_tool`.

**Step 2: Run the chrome tests and inspect the exact failure**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_chrome.py -q
```

Expected: at least the cross-surface position/transition test fails before the invariant is enforced. Confirm the failure is duplicate/offset chrome, not a test harness error.

**Step 3: Apply the smallest root-cause fix**

The allowed fix is:

- no startup `header()` print;
- exactly one active-screen owner of `ascii_logo`;
- clear before switching from picker to Rich static/live surface;
- picker and dashboard source the same `NANOFAAS_BRAND` and reserve the same six logo rows;
- no extra ASCII logo inside Summary, body panels, errors, or footer.

Do not introduce a persistent Textual app or a second chrome abstraction unless the regression test demonstrates that the existing shared `AppBrand` cannot satisfy the invariant.

**Step 4: Run chrome, picker, and dashboard tests**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_chrome.py \
         tools/controlplane/tests/test_tui_workflow.py \
         tools/tui-toolkit/tests/test_pickers.py \
         tools/tui-toolkit/tests/test_chrome.py -q
```

Expected: PASS with one logo per screen and no picker/chrome regressions.

**Step 5: Commit**

```bash
git add tools/controlplane/tests/test_tui_chrome.py \
  tools/controlplane/src/controlplane_tool/tui/workflow_controller.py \
  tools/tui-toolkit/src/tui_toolkit/pickers.py \
  tools/tui-toolkit/src/tui_toolkit/chrome.py
git commit -m "fix: keep one invariant TUI header"
```

Stage the two `tui-toolkit` source files only if they actually changed.

### Task 5: Replace the picker-only app with adapted menu navigation

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py:1`
- Modify: `tools/controlplane/src/controlplane_tool/tui/__init__.py`
- Replace: `tools/controlplane/tests/test_tui_app.py`
- Create: `tools/controlplane/tests/test_tui_navigation.py`

**Step 1: Inspect `NanofaasTUI` impact**

Run:

```text
gitnexus_impact({target: "NanofaasTUI", direction: "upstream", repo: "mcFaas", includeTests: true})
```

Expected: MEDIUM risk with direct callers in `app.main`, `tui.__init__`, and TUI tests. If HIGH/CRITICAL, stop and report before editing.

**Step 2: Write menu-contract tests**

Define the expected top-level values exactly:

```python
def test_main_menu_contains_only_supported_product_sections() -> None:
    assert [choice.value for choice in NanofaasTUI.MAIN_MENU] == [
        "validation",
        "cli",
        "loadtest",
        "tools",
        "exit",
    ]
```

Add tests for the exact routes:

```python
EXPECTED_SCENARIOS = {
    ("validation", "container"): "validate-container.yaml",
    ("validation", "kubernetes"): "validate-k8s.yaml",
    ("cli", "validate"): "cli.yaml",
    ("loadtest", "run"): "loadtest.yaml",
}
```

Also test Back and `KeyboardInterrupt` at every navigation level.

**Step 3: Run navigation tests and verify RED**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_navigation.py -q
```

Expected: FAIL because the current app exposes only a flat scenario/environment picker.

**Step 4: Implement the adapted menu constants**

Use `tui_toolkit.Choice` with concise English descriptions:

```python
MAIN_MENU = [
    Choice("Validation", "validation", "Validate container and Kubernetes execution paths."),
    Choice("CLI", "cli", "Validate the nanofaas CLI against a selected environment."),
    Choice("Load Testing", "loadtest", "Run the current k6 and autoscaling workflow."),
    Choice("Tools", "tools", "Inspect scenarios and check local prerequisites."),
    Choice("Exit", "exit", "Leave the interactive control-plane tool."),
]
```

Add matching submenu constants and a `_SCENARIO_FILES` mapping. Do not inspect directories to invent menu entries: the adapted navigation is a deliberate stable product surface over the four supported YAML files.

**Step 5: Implement the navigation loop**

Use the toolkit's full-screen `select(..., include_back=True)` and inject a chooser in tests. `NanofaasTUI.run()` owns only navigation and dispatch:

```python
while True:
    section = self._choose("What would you like to do?", self.MAIN_MENU)
    if section == "exit":
        return
    try:
        self._dispatch_section(section)
    except KeyboardInterrupt:
        continue
```

Submenus return to their caller on Back. Do not restore old aliases such as `building`, `environment`, `catalog`, `vm`, `registry`, or `e2e`.

**Step 6: Run navigation and command-entry tests**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_navigation.py \
         tools/controlplane/tests/test_tui_app.py \
         tools/controlplane/tests/cli/test_command_surface.py -q
```

Expected: PASS.

**Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/app.py \
  tools/controlplane/src/controlplane_tool/tui/__init__.py \
  tools/controlplane/tests/test_tui_app.py \
  tools/controlplane/tests/test_tui_navigation.py
git commit -m "feat: restore adapted TUI menu navigation"
```

### Task 6: Wire plan, run, provisioning, and cleanup actions

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Modify: `tools/controlplane/tests/test_tui_app.py`

**Step 1: Write failing action-routing tests**

Use injected chooser answers and monkeypatch the existing helpers. Cover:

- `plan` calls `_scenario`, `_environment`, `_workflow`, and a branded plan view, but never `workflow.run()`;
- `run` passes `workflow.phase_titles` to `TuiWorkflowController.run_live_workflow()`;
- `loadtest` calls `resolve_loadtest_urls` before `_workflow`;
- a local environment never prompts for or enters provisioning;
- a non-local environment with provision selected enters `provision_environment`;
- `keep` sets both provisioning `keep=True` and `workflow.keep_infrastructure=True`;
- failures return to the previous submenu after the final dashboard acknowledgement.

**Step 2: Run the tests and verify RED**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_app.py -q
```

Expected: FAIL because navigation does not yet execute actions through the dashboard.

**Step 3: Add the common workflow flow**

Implement one shared method for all four YAML routes:

```python
def _workflow_menu(self, scenario_name: str) -> None:
    scenario_path = default_tool_paths().tool_root / "scenarios-v2" / scenario_name
    environment_path = self._select_environment()
    action = self._select_action()
    scenario = _scenario(scenario_path)
    environment = _environment(environment_path)
    # resolve load-test endpoints when required
    workflow = _workflow(scenario, environment, ...)
    # plan -> branded static view
    # run -> optional provisioning + live controller
```

The environment list contains committed `*.yaml` files only. Do not offer `.example` files as executable configurations.

For `plan`, build a Rich table from `workflow.tasks`, wrap it with `render_screen_frame`, clear once, render once, acknowledge, and clear before returning.

For `run`, use:

```python
self._controller.run_live_workflow(
    title=section_title,
    summary_lines=[
        f"Scenario: {scenario_path.name}",
        f"Environment: {environment_path.name}",
        f"Provision: {'yes' if provision else 'no'}",
        f"Cleanup: {'keep' if keep else 'cleanup'}",
    ],
    planned_steps=workflow.phase_titles,
    action=lambda dashboard, sink: workflow.run(),
)
```

Wrap the live call with the existing `provision_environment(...)` context only when requested. Apply `keep` to both provisioning and workflow cleanup.

**Step 4: Run action, provisioning, and dashboard tests**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_app.py \
         tools/controlplane/tests/test_tui_navigation.py \
         tools/controlplane/tests/test_tui_workflow.py \
         tools/controlplane/tests/test_tui_workflow_controller.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/app.py \
  tools/controlplane/tests/test_tui_app.py
git commit -m "feat: run current workflows from the restored TUI"
```

### Task 7: Add adapted Tools views and documentation

**Files:**

- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Modify: `tools/controlplane/tests/test_tui_app.py`
- Modify: `tools/controlplane/README.md:43`
- Modify: `docs/quickstart.md:94`
- Modify if required by existing assertions: `tools/controlplane/tests/test_product_docs.py`

**Step 1: Write failing Tools tests**

Cover:

- Inspect lets the user select one of the four current YAML scenarios and renders its validated JSON inside the shared chrome;
- Doctor renders `ok` or the existing missing executable list inside the shared chrome;
- both views acknowledge and return to Tools;
- neither view prints a second logo.

**Step 2: Run and verify RED**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_app.py -q
```

Expected: FAIL because Tools dispatch is not implemented.

**Step 3: Implement the two static views**

Reuse `_scenario(path).model_dump(by_alias=True)` for Inspect. For Doctor, reuse the same executable names as the CLI (`docker`, `ssh`) and render the result; do not add a second diagnostic engine.

Both use one `_show_static(title, breadcrumb, body)` helper that clears, renders `render_screen_frame(...)`, waits for acknowledgement, and clears before returning.

**Step 4: Update documentation**

Document:

```bash
scripts/controlplane.sh tui
```

Describe adapted menu sections, environment/action selection, invariant branded header, and the live workflow/log dashboard. Explicitly avoid documenting removed Build, Environment, Catalog, saved-profile, or image-publishing menus.

**Step 5: Run focused docs and TUI tests**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests/test_tui_app.py \
         tools/controlplane/tests/test_tui_navigation.py \
         tools/controlplane/tests/test_tui_chrome.py \
         tools/controlplane/tests/test_product_docs.py -q
```

Expected: PASS.

**Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/app.py \
  tools/controlplane/tests/test_tui_app.py \
  tools/controlplane/README.md docs/quickstart.md \
  tools/controlplane/tests/test_product_docs.py
git commit -m "docs: describe the adapted control-plane TUI"
```

Stage `test_product_docs.py` only if it changed.

### Task 8: Full verification and scope audit

**Files:** No intended source changes.

**Step 1: Run all controlplane and toolkit tests**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  pytest tools/controlplane/tests -q
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/tui-toolkit --locked \
  pytest tools/tui-toolkit/tests -q
```

Expected: both suites PASS.

**Step 2: Run quality gates**

```bash
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/controlplane --locked \
  controlplane-quality
env UV_CACHE_DIR=/tmp/codex-uv-cache uv run --project tools/tui-toolkit --locked \
  ruff check tools/tui-toolkit
```

Expected: Ruff, basedpyright, import-linter, entrypoint imports, and cross-project coupling PASS.

**Step 3: Run terminal smoke checks**

From a real TTY:

```bash
./scripts/controlplane.sh tui
```

Verify manually:

1. logo appears once at the top of Main;
2. Main -> Validation -> Back -> Load Testing leaves no duplicate logo;
3. Validation -> Container -> local -> plan renders the branded plan view;
4. a safe dry/local run shows live phase and log boxes;
5. `l` hides/restores logs;
6. `Esc` returns and `Ctrl+C` exits cleanly.

**Step 4: Check diff hygiene**

```bash
git diff --check
git status --short
```

Confirm unrelated pre-existing edits are not staged.

**Step 5: Run GitNexus scope detection**

```text
gitnexus_detect_changes({scope: "all", repo: "mcFaas"})
```

Expected affected scope: TUI entry point, menu navigation, presentation, local toolkit chrome, tests, and docs. No changes should affect `WorkflowEvent`, `Workflow`, CLI progress, scenario builders, platform Java modules, or execution APIs.

**Step 6: Final commit if verification required fixes**

Commit only files changed to address verified failures, with a narrow imperative message. Do not squash unrelated user changes into the TUI work.

**Step 7: Refresh GitNexus after the final commit**

Inspect `.gitnexus/meta.json` and preserve embeddings if present:

```bash
npx gitnexus analyze
```

Use `--embeddings` only when `stats.embeddings` is nonzero.

## Acceptance criteria

- The main TUI contains Validation, CLI, Load Testing, Tools, and Exit only.
- Menu routes map exactly to the four current `scenarios-v2/*.yaml` files.
- Every submenu supports Back; navigation interruption behaves consistently.
- Every visible TTY screen contains exactly one six-line NANOFAAS logo at the same top position.
- Menu transitions do not accumulate or scroll repeated logos.
- Plan, static Tools views, and live workflow screens share the same brand and chrome.
- The historical Summary, Execution Phases, Nested Verification Work, Raw Command Output, Error Detail, durations, and log toggle behavior are restored.
- Historical aggregator/dashboard tests are reused rather than replaced by weaker equivalents.
- Current workflow events, workflow executor, CLI progress, and command surface remain unchanged.
- Build, Environment management, Catalog, profiles, publishing, and obsolete adapters are absent.
