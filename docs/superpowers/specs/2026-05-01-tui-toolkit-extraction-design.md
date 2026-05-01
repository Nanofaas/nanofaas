# Design — `tui-toolkit` extraction from `tools/controlplane`

**Date:** 2026-05-01
**Status:** Approved (brainstorming complete, ready for implementation plan)
**Scope:** First milestone of a larger library-extraction effort for `tools/controlplane/`

## Context and motivation

The control-plane Python tool (`tools/controlplane/`, ~17K LOC, 113 files) is the
canonical orchestration surface for nanofaas builds, VM provisioning, E2E
scenarios, and load tests. Its terminal UI is split across:

- `tui_widgets.py` (522 LOC) — prompt_toolkit-based pickers (select +
  multi-select with description side panel), with a hardcoded questionary
  `Style` for the cyan palette.
- `tui_chrome.py` (52 LOC) — Rich `render_screen_frame` (header, ASCII logo,
  breadcrumb, footer), with the `NANOFAAS` brand and palette hardcoded.
- `console.py` (343 LOC) — Rich `Console` singleton, workflow event renderer
  (`phase`, `step`, `success`, `warning`, `skip`, `fail`, `status`,
  `workflow_step`), with 12 hardcoded color literals.

There are 63 occurrences of style literals (`border_style="cyan dim"`,
`fg:cyan bold`, `style="bold cyan"`, …) scattered across 6 files. Changing
the primary color, swapping icons, or re-branding the tool requires editing
all of them.

**Problem statement:** the TUI is hard to configure uniformly. Theming lives
in two parallel dialects (questionary `Style` strings + Rich style strings),
branding is coupled to chrome internals, and the public widget API is
nominally private (functions named `_select_value`, `_checkbox_values`).

**Goal:** extract a small, reusable library `tui-toolkit` that owns all
graphical widgets and the workflow event renderer. A single `Theme` controls
both Rich and questionary outputs. A single `AppBrand` controls logo,
wordmark, and default chrome strings. Consumers configure both once at
startup; everything else reads them implicitly.

## Non-goals

- Refactor `WorkflowDashboard` (`tui_workflow.py`) or `tui_app.py` beyond
  changing imports. They remain in `controlplane_tool` as application code.
- Ship multiple built-in themes. The library starts with `DEFAULT_THEME`
  (the current cyan palette). Additional themes only when there is real
  demand.
- Separate the library into a standalone Git repository. It lives in
  `tools/tui-toolkit/` as a sibling local package within the monorepo. A
  future spin-out to its own repository remains possible without API breakage.
- Provide a full live-screen framework (Tree dashboards, Prefect bridges).
  Those concepts stay in the consumer.

## Decisions (resolved during brainstorming)

| # | Decision | Rationale |
|---|---|---|
| D1 | Library scope: pickers + chrome + console workflow renderer (`tui_widgets` + `tui_chrome` + the Rich render layer in `console.py`). `WorkflowDashboard` stays in nanofaas. | Captures the two surfaces where theme/brand literals currently live. `WorkflowDashboard` is application logic, not generic UI. |
| D2 | Theme model: semantic tokens (`accent`, `accent_strong`, `accent_dim`, `success`, `warning`, `error`, `muted`, `text`, `brand`, plus `icon_*`). Built-in preset library deferred (YAGNI). | Solves the "change uniformly" pain with one dictionary. A library-internal adapter resolves Rich style strings into questionary/prompt_toolkit format. |
| D3 | Distribution: sibling local package `tools/tui-toolkit/` with its own `pyproject.toml`. Distributable name `tui-toolkit`, import name `tui_toolkit`. No `nanofaas-` prefix — the package is meant to be reusable. | Forces a clean boundary (no nanofaas imports inside the library) without the logistical cost of a separate repository. |
| D4 | Branding: `AppBrand` is optional with a neutral default (`name="App"`, empty `wordmark`, empty `ascii_logo`). Caller overrides via `UIContext`. Empty strings render as no-ops. | The library must be usable bare (`from tui_toolkit import select`) without ceremony. Nanofaas configures `AppBrand(name="nanofaas", ...)` once in a central `ui_setup.py`. |
| D5 | Migration: shim layer for one PR cycle. PR1 ships the library + thin re-export shims at the old import paths. PR2 rewrites consumers and deletes the shims. | A big-bang rewrite of 25+ consumers is risky given the difficulty of CI-testing terminal UIs. Indefinite shims (option C in brainstorming) leave the surface forever doubled. |

## Architecture

### Repository layout after PR1

```
tools/
├── controlplane/
│   ├── pyproject.toml                     # adds: "tui-toolkit @ file:..."
│   └── src/controlplane_tool/
│       ├── console.py                     # PR1: shim re-exporting tui_toolkit
│       ├── tui_widgets.py                 # PR1: shim
│       ├── tui_chrome.py                  # PR1: shim
│       ├── ui_setup.py                    # NEW: NANOFAAS_THEME, NANOFAAS_BRAND, setup_ui()
│       └── main.py                        # MODIFIED: setup_ui() instead of init_ui_width()
│
└── tui-toolkit/                           # NEW LIBRARY
    ├── pyproject.toml                     # name = "tui-toolkit", deps: rich, questionary, prompt_toolkit
    ├── README.md
    ├── tests/
    └── src/tui_toolkit/
        ├── __init__.py                    # curated public re-exports
        ├── theme.py                       # Theme dataclass + DEFAULT_THEME + style adapters
        ├── brand.py                       # AppBrand dataclass + DEFAULT_BRAND
        ├── context.py                     # UIContext, init_ui, get_ui, bind_ui
        ├── console.py                     # Rich Console singleton, content-width helpers
        ├── chrome.py                      # render_screen_frame
        ├── pickers.py                     # select, multiselect, Choice, Separator
        ├── events.py                      # WorkflowEvent, WorkflowContext, WorkflowSink
        └── workflow.py                    # phase/step/success/.../status/workflow_step + renderer
```

### Module responsibilities

#### `tui_toolkit.theme`

```python
@dataclass(frozen=True)
class Theme:
    # Rich-format style strings (e.g., "bold cyan", "cyan dim").
    accent: str = "cyan"
    accent_strong: str = "bold cyan"
    accent_dim: str = "cyan dim"
    success: str = "green"
    warning: str = "yellow"
    error: str = "red"
    muted: str = "dim"
    text: str = ""
    brand: str = "bold cyan"

    icon_running: str = "▸"
    icon_completed: str = "✓"
    icon_failed: str = "✗"
    icon_warning: str = "⚠"
    icon_skipped: str = "⊘"
    icon_updated: str = "↺"
    icon_cancelled: str = "⊘"

    def with_overrides(self, **changes) -> "Theme": ...

DEFAULT_THEME = Theme()

def to_questionary_style(theme: Theme) -> questionary.Style:
    """Map theme tokens to the 13 questionary/prompt_toolkit selectors."""
```

The mapping currently in use:

| questionary key | theme token |
|---|---|
| `brand` | `theme.brand` |
| `breadcrumb` | `theme.muted` |
| `footer` | `theme.muted` |
| `qmark` | `theme.accent_strong` |
| `question` | `"bold"` (literal — not theme-driven) |
| `answer` | `theme.accent_strong` |
| `pointer` | `theme.accent_strong` |
| `highlighted` | `theme.accent_strong` |
| `selected` | `theme.accent` |
| `text` | `theme.text` |
| `disabled` | `theme.muted` |
| `separator` | `theme.muted` |
| `instruction` | `theme.muted` |

The internal helper `_to_pt` converts Rich-format strings (`"bold cyan"`,
`"cyan dim"`) to prompt_toolkit format (`"fg:cyan bold"`, `"fg:cyan"` with
the `dim` flag). It covers the cases that exist today; new color forms
extend it.

#### `tui_toolkit.brand`

```python
@dataclass(frozen=True)
class AppBrand:
    name: str = "App"
    wordmark: str = ""
    ascii_logo: str = ""
    default_breadcrumb: str = "Main"
    default_footer_hint: str = "Esc back | Ctrl+C exit"

DEFAULT_BRAND = AppBrand()
```

`AppBrand` carries everything needed to render the chrome header and the
startup banner. Empty `wordmark` or `ascii_logo` causes the renderer to
silently skip that visual element.

#### `tui_toolkit.context`

```python
@dataclass(frozen=True)
class UIContext:
    theme: Theme = DEFAULT_THEME
    brand: AppBrand = DEFAULT_BRAND
    max_content_cols: int = 140
    content_width: int | None = None  # populated by init_ui()

def init_ui(ctx: UIContext | None = None) -> UIContext:
    """Capture terminal width, install the context as the active singleton.
    Replaces the old init_ui_width(). Idempotent."""

def get_ui() -> UIContext:
    """Active context — DEFAULT_BRAND/DEFAULT_THEME if init_ui not called."""

@contextmanager
def bind_ui(ctx: UIContext): ...  # temporary override (tests)
```

The active context lives in a `ContextVar` plus a module-level shared
fallback — the same pattern `console.py` already uses for
`workflow_sink` / `workflow_context`. Compatible with sync, async, and
threaded code.

#### `tui_toolkit.console`

Rich `Console` singleton. Width-cap helpers (`get_content_width`,
`_apply_width` invoked by `init_ui`).

#### `tui_toolkit.chrome`

```python
def render_screen_frame(
    *, title: str, body: RenderableType,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> Panel:
    """Same signature as the legacy version. Reads brand and theme from
    get_ui()."""
```

#### `tui_toolkit.pickers`

```python
@dataclass(frozen=True)
class Choice:
    title: str
    value: str
    description: str = ""

Separator = questionary.Separator  # re-export

def select(
    message: str,
    *, choices: list[Choice | str | questionary.Separator],
    default: str | None = None,
    include_back: bool = False,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> str: ...

def multiselect(
    message: str,
    *, choices: list[Choice],
    default_values: list[str] | None = None,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> list[str]: ...
```

The two functions collapse the four legacy entry points
(`_select_value`, `_select_described_value`, `_checkbox_values`,
`_select_described_checkbox_values`) into two well-named public ones.
Description-panel rendering activates whenever any `Choice` carries a
non-empty `description`; otherwise the picker degrades to a plain
questionary list. Non-TTY fallback (the existing `sys.stdin.isatty()` check)
is preserved.

#### `tui_toolkit.events`

Plain dataclasses extracted from `controlplane_tool/workflow_models.py`:

- `WorkflowEvent` (kind, title, detail, stream, line, task_id, …)
- `WorkflowContext` (flow_id, flow_run_id, task_id, parent_task_id, …)
- `WorkflowSink` (Protocol with `emit(event)` and `status(label)`)

`WorkflowStepState` and other workflow-specific classes stay in nanofaas.

#### `tui_toolkit.workflow`

```python
# event builders
def build_log_event(*, line, stream, context) -> WorkflowEvent: ...
def build_phase_event(label, *, context) -> WorkflowEvent: ...
def build_task_event(*, kind, title, detail, ..., context) -> WorkflowEvent: ...

# active sink/context (replaces the underscored helpers in console.py)
@contextmanager
def bind_workflow_sink(sink: WorkflowSink): ...
@contextmanager
def bind_workflow_context(context: WorkflowContext): ...
def get_workflow_context() -> WorkflowContext | None: ...
def has_workflow_sink() -> bool: ...

# user-facing helpers (unchanged names)
def header(subtitle: str | None = None) -> None: ...
def phase(label: str) -> None: ...
def step(label: str, detail: str = "") -> None: ...
def success(label: str, detail: str = "") -> None: ...
def warning(label: str) -> None: ...
def skip(label: str) -> None: ...
def fail(label: str, detail: str = "") -> None: ...
def workflow_log(message, *, stream="stdout", context=None) -> None: ...

@contextmanager
def status(label: str): ...

@contextmanager
def workflow_step(*, task_id, title, parent_task_id=None, detail="", context=None) -> Generator[WorkflowContext, None, None]: ...
```

The renderer reads icons and colors from `get_ui().theme`. The 12 hardcoded
color literals and 6 hardcoded glyphs in the current `_render_event`
disappear.

### Public API surface (`tui_toolkit.__init__`)

```python
# theming and setup
from tui_toolkit.theme import Theme, DEFAULT_THEME
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.context import UIContext, init_ui, get_ui, bind_ui

# rendering primitives
from tui_toolkit.console import console, get_content_width
from tui_toolkit.chrome import render_screen_frame
from tui_toolkit.pickers import select, multiselect, Choice, Separator

# workflow events
from tui_toolkit.workflow import (
    header, phase, step, success, warning, skip, fail, status,
    workflow_log, workflow_step,
    bind_workflow_sink, bind_workflow_context,
    get_workflow_context, has_workflow_sink,
    build_log_event, build_phase_event, build_task_event,
)
from tui_toolkit.events import WorkflowEvent, WorkflowContext, WorkflowSink
```

The event builders are part of the public API because non-renderer consumers
(notably `prefect_event_bridge.py` in nanofaas) build events themselves and
push them into a `WorkflowSink` without going through the high-level
`phase`/`step`/`success` helpers.

### Nanofaas-side setup (`controlplane_tool.ui_setup`)

```python
from tui_toolkit import Theme, AppBrand, UIContext, init_ui

NANOFAAS_THEME = Theme()  # default cyan palette is what we already use

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
)

def setup_ui() -> None:
    init_ui(UIContext(theme=NANOFAAS_THEME, brand=NANOFAAS_BRAND))
```

`controlplane_tool/main.py` calls `setup_ui()` exactly once. Changing the
nanofaas palette becomes a single-file edit.

## Migration plan

### PR1 — "Extract `tui-toolkit` with shim layer"

**Objective:** library exists, is installed as a local dependency, old
modules are transparent shims, no consumer files modified except
`main.py`, all tests pass.

1. Create `tools/tui-toolkit/` with `pyproject.toml`, `README.md`,
   `src/tui_toolkit/`, `tests/`.
2. Move and normalize:
   - `controlplane_tool/console.py` → split between `tui_toolkit/console.py`
     and `tui_toolkit/workflow.py`.
   - `controlplane_tool/tui_widgets.py` → `tui_toolkit/pickers.py`,
     internally renamed to `select` / `multiselect`.
   - `controlplane_tool/tui_chrome.py` → `tui_toolkit/chrome.py`, with
     parametric branding.
   - `WorkflowEvent`, `WorkflowContext`, `WorkflowSink` extracted from
     `controlplane_tool/workflow_models.py` → `tui_toolkit/events.py`.
     Other classes (`WorkflowStepState`, …) stay in nanofaas.
   - Event builders extracted from `controlplane_tool/workflow_events.py` →
     `tui_toolkit/workflow.py`.
   - New code: `tui_toolkit/theme.py`, `tui_toolkit/brand.py`,
     `tui_toolkit/context.py`.
3. Replace all 63 hardcoded style literals in the moved code with
   references to `get_ui().theme.<token>`.
4. Add `tui-toolkit` as a local dependency in
   `tools/controlplane/pyproject.toml` (verify the exact `uv`-compatible
   syntax during implementation — likely `"tui-toolkit"` in
   `[tool.uv.sources]` pointing to `path = "../tui-toolkit"`).
5. Replace the three legacy modules with thin re-export shims that preserve
   every name in active use (including underscored ones such as
   `_workflow_context`, `_workflow_sink`, `_select_value`,
   `_checkbox_values`, `APP_ASCII_LOGO`, `APP_WORDMARK`, …). The shims also
   provide a back-compat `init_ui_width()` that delegates to `init_ui()`.
6. Create `controlplane_tool/ui_setup.py` with `NANOFAAS_THEME`,
   `NANOFAAS_BRAND`, `setup_ui()`. The full ASCII logo lives here.
7. Modify `controlplane_tool/main.py` to call `setup_ui()` instead of
   `init_ui_width()` (the only consumer modification in PR1).
8. Tests:
   - New unit tests in `tools/tui-toolkit/tests/` (see Testing section).
   - All existing tests in `tools/controlplane/tests/` must pass
     unchanged — proves the shims are faithful.
9. Document `tools/tui-toolkit/README.md` with a minimal usage example,
   theming guide, and migration note pointing at PR2.

**Acceptance:**
- `cd tools/controlplane && pytest` passes;
- `cd tools/tui-toolkit && pytest` passes;
- `controlplane-tool tui` starts and is visually identical to before
  (manual smoke check);
- A theme override (`init_ui(UIContext(theme=Theme(accent="green",
  accent_strong="bold green", accent_dim="green dim", brand="bold green")))`)
  applied in a scratch script changes the cyan palette uniformly across
  pickers, chrome, and workflow events.

### PR2 — "Remove shims, migrate imports"

**Objective:** all 25+ consumers import directly from `tui_toolkit`; the
three shim files are deleted.

1. Mechanical find-and-replace, one consumer file per commit (small
   reviewable commits):
   - `from controlplane_tool.console import …` →
     `from tui_toolkit import …` (public names);
     `_workflow_context` → `from tui_toolkit.workflow import get_workflow_context`.
   - `from controlplane_tool.tui_widgets import _select_value` →
     `from tui_toolkit import select`. **Rename call sites accordingly:**
     `_select_value(...)` → `select(...)`; same for `_checkbox_values` →
     `multiselect`.
   - `from controlplane_tool.tui_chrome import APP_ASCII_LOGO, APP_WORDMARK` →
     `from controlplane_tool.ui_setup import NANOFAAS_BRAND` plus access via
     `NANOFAAS_BRAND.ascii_logo` / `NANOFAAS_BRAND.wordmark` (the two or
     three sites that genuinely need the nanofaas-specific brand).
   - `from controlplane_tool.tui_chrome import render_screen_frame` →
     `from tui_toolkit import render_screen_frame`.
2. Delete the three shim files:
   - `controlplane_tool/console.py`
   - `controlplane_tool/tui_widgets.py`
   - `controlplane_tool/tui_chrome.py`
3. Delete the back-compat `init_ui_width` shim once no consumer references
   it.
4. Verify: `pytest`, launch the TUI, run a dry-run scenario.
5. Update `tools/controlplane/CONVENTIONS.md`: the section on terminal
   output points at the `tui-toolkit` documentation.

**Acceptance:**
- No remaining occurrence of `from controlplane_tool.console`,
  `from controlplane_tool.tui_widgets`, or `from controlplane_tool.tui_chrome`
  inside `tools/controlplane/src/` (or `tests/`).
- All tests pass.
- TUI visually unchanged.

### Out of scope for both PRs

- `WorkflowDashboard` and the rest of `tui_workflow.py` are not refactored
  (only their imports change in PR2).
- `tui_app.py` is not restructured beyond import rewrites.
- No additional themes are introduced. Adding `THEMES["green"]` etc.
  is a follow-up only if real demand surfaces.
- `workflow_models.py` keeps everything except the three classes moved to
  `tui_toolkit.events`.

## Risks and mitigations

| Risk | Mitigation |
|---|---|
| The shim cannot re-export underscored helpers (`_workflow_context`, `_workflow_sink`) cleanly. | Re-bind explicitly: `_workflow_context = get_workflow_context` and `_workflow_sink = _get_active_sink` inside the shim module. |
| `uv`'s local-path dependency syntax does not behave as assumed. | Smoke-test the local install in isolation as the very first PR1 step before moving any code. |
| Workflow-event rendering shifts pixel-for-pixel after the migration to themes. | Snapshot tests on the four panel states (`completed`, `failed`, `warning`, `cancelled`) — golden output recorded against the legacy renderer first, then compared after migration. |
| Tests that depend on the idempotency of `init_ui_width()` break. | `init_ui()` is idempotent by design; the back-compat shim simply delegates. |
| Branding logo shows up empty if `setup_ui()` is forgotten. | `header()` and `render_screen_frame` skip silently when `ascii_logo == ""` / `wordmark == ""`. The default brand is valid — never crashes. |

## Testing

### `tools/tui-toolkit/tests/`

Approximately 30 tests, ~400 LOC.

- **`test_theme.py`** — default theme produces the legacy questionary
  `_STYLE` byte-for-byte (parity gate); `with_overrides` is immutable;
  `_to_pt` covers the Rich-to-prompt_toolkit cases currently in use;
  icons are themeable.
- **`test_context.py`** — `init_ui` caps width; `get_ui()` returns default
  before `init_ui`; `init_ui` is idempotent; `bind_ui` overrides
  temporarily.
- **`test_pickers.py`** — non-TTY fallback (mocked questionary);
  `Choice` normalization; `include_back=True` adds the back option;
  `multiselect` honors `default_values`. Interactive prompt_toolkit paths
  are not exercised (no pty in CI).
- **`test_chrome.py`** — `render_screen_frame` includes the brand when
  set; skips silently when empty; uses themed border styles.
- **`test_workflow.py`** — phase/task event renders match snapshots;
  `workflow_step` emits the expected sequence (`running` → `completed`,
  `running` → `failed` on exception); theme overrides change colors and
  icons in the rendered output.
- **`test_snapshots.py`** — golden-file parity test. The golden files are
  recorded once at the start of PR1 by capturing the legacy renderer's
  output for each of the four panel states (`completed`, `failed`,
  `warning`, `cancelled`) using `console.export_text(styles=True)`. They
  are committed alongside the test. The test then runs the new renderer
  with `DEFAULT_THEME` and asserts byte-for-byte equality. The test stays
  in PR2 (it remains valuable as a regression guard against accidental
  theme drift); only the legacy capture script is removed.

### `tools/controlplane/tests/`

Untouched in PR1. Pass-as-is is the proof that the shims are faithful.

## Open questions for the implementation phase

- Exact `uv` syntax for the local-path dependency (`[tool.uv.sources]`
  table vs. PEP 508 `file://` URL). Resolve in the first PR1 step.
- Whether to expose the per-question `prompt_fragments` builder used inside
  `pickers.py` as a public hook for callers who want a custom hint line.
  Default answer: no, until a real consumer asks. Keep it internal.
- Python version pinning for `tui-toolkit` `pyproject.toml`: match
  `controlplane-tool` (`requires-python = ">=3.11"`) for now. Can be
  loosened if the library is later spun out for other consumers.
- Whether `tui_toolkit.events.WorkflowSink` should be defined as
  `@runtime_checkable` (the current `controlplane_tool.workflow_models`
  version is plain `Protocol`). Default: keep it plain to match current
  behavior; add `@runtime_checkable` only if a test or runtime check needs
  `isinstance(x, WorkflowSink)`.

## References

- Source files inspected: `tools/controlplane/src/controlplane_tool/`
  `console.py`, `tui_widgets.py`, `tui_chrome.py`, `tui_workflow.py`,
  `tui_app.py`, `workflow_models.py`, `workflow_events.py`,
  `process_streaming.py`, `shell_backend.py`, `tui_workflow_controller.py`,
  `workflow_progress.py`, `prefect_event_bridge.py`.
- Style-literal census: 63 occurrences across 6 files (`console.py`,
  `tui_chrome.py`, `tui_widgets.py`, `tui_app.py`, `tui_workflow.py`,
  `function_commands.py`).
