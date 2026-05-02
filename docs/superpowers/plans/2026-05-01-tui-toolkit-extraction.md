# `tui-toolkit` Extraction Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract the questionary/Rich/prompt_toolkit graphical widgets and the workflow event renderer from `tools/controlplane/src/controlplane_tool/` into a reusable local library `tools/tui-toolkit/`, with a unified `Theme` + `AppBrand` model so configuring the TUI uniformly becomes a single-file change.

**Architecture:** Sibling local package `tools/tui-toolkit/` with its own `pyproject.toml`, installed via `[tool.uv.sources]` from `tools/controlplane/`. PR1 ships the library plus thin re-export shims at every legacy import path (no consumer file changes except `main.py`). PR2 rewrites consumer imports, deletes the shims, and updates documentation. Theme has semantic tokens (`accent`, `success`, `error`, …) plus icons; an internal adapter resolves the Rich-style strings into questionary/prompt_toolkit format so callers configure both surfaces with one dictionary.

**Tech Stack:** Python 3.11+, Rich, questionary, prompt_toolkit, pytest, uv. Same as the existing `controlplane-tool` package.

**Source spec:** [`docs/superpowers/specs/2026-05-01-tui-toolkit-extraction-design.md`](../specs/2026-05-01-tui-toolkit-extraction-design.md). Every module's API matches that spec — when in doubt, the spec wins.

**Worktree:** Already in `/.worktrees/ansible-vm-provisioning/` on branch `codex/ansible-vm-provisioning`.

---

## Phase 0 — Bootstrap and capture parity baseline

### Task 0.1: Create `tui-toolkit` package skeleton

**Files:**
- Create: `tools/tui-toolkit/pyproject.toml`
- Create: `tools/tui-toolkit/README.md`
- Create: `tools/tui-toolkit/src/tui_toolkit/__init__.py` (placeholder)
- Create: `tools/tui-toolkit/src/tui_toolkit/py.typed` (empty, marks the package as typed)
- Create: `tools/tui-toolkit/tests/conftest.py` (placeholder)
- Create: `tools/tui-toolkit/tests/test_smoke.py`

- [ ] **Step 1: Create `tools/tui-toolkit/pyproject.toml`**

```toml
[project]
name = "tui-toolkit"
version = "0.1.0"
description = "Reusable terminal UI widgets and workflow event renderer with a unified theme model."
requires-python = ">=3.11"
dependencies = [
    "rich>=13.8",
    "questionary>=2.1.1",
    "prompt-toolkit>=3.0",
]

[build-system]
requires = ["setuptools>=61.0"]
build-backend = "setuptools.build_meta"

[tool.setuptools]
package-dir = {"" = "src"}

[tool.setuptools.packages.find]
where = ["src"]

[tool.setuptools.package-data]
tui_toolkit = ["py.typed"]

[dependency-groups]
dev = [
    "pytest>=8.3.4",
]

[tool.pytest.ini_options]
testpaths = ["tests"]
addopts = "-q"
```

- [ ] **Step 2: Create `tools/tui-toolkit/README.md`**

```markdown
# tui-toolkit

Terminal UI widgets and workflow event renderer extracted from `nanofaas`'s control-plane tooling. Provides:

- **`select` / `multiselect`** — prompt_toolkit pickers with a description side panel.
- **`render_screen_frame`** — Rich screen chrome (header, breadcrumb, footer).
- **`phase` / `step` / `success` / `warning` / `skip` / `fail` / `status` / `workflow_step`** — workflow event renderer.
- **`Theme` + `AppBrand` + `UIContext` + `init_ui`** — single-source-of-truth theming. Change one dataclass, the whole UI follows.

## Quick start

```python
from tui_toolkit import init_ui, UIContext, Theme, AppBrand, select, Choice

init_ui(UIContext(
    theme=Theme(accent="green", accent_strong="bold green", brand="bold green"),
    brand=AppBrand(name="myapp", wordmark="MYAPP"),
))

answer = select(
    "Pick a runtime",
    choices=[
        Choice("k3s", "k3s", "Self-contained Multipass VM"),
        Choice("local", "local", "In-process for testing"),
    ],
)
```

## Status

Pre-1.0. The first consumer is `tools/controlplane/`. See the design doc at `docs/superpowers/specs/2026-05-01-tui-toolkit-extraction-design.md`.
```

- [ ] **Step 3: Create `tools/tui-toolkit/src/tui_toolkit/__init__.py` (placeholder)**

```python
"""tui-toolkit — terminal UI widgets and workflow event renderer."""

__version__ = "0.1.0"
```

- [ ] **Step 4: Create `tools/tui-toolkit/src/tui_toolkit/py.typed`**

Empty file. Marks the package as fully typed for mypy/pyright consumers.

```bash
touch tools/tui-toolkit/src/tui_toolkit/py.typed
```

- [ ] **Step 5: Create `tools/tui-toolkit/tests/conftest.py` (placeholder)**

```python
"""Shared pytest fixtures for tui_toolkit tests."""
```

- [ ] **Step 6: Create `tools/tui-toolkit/tests/test_smoke.py`**

```python
"""Bootstrap smoke test — verifies the package can be imported."""
import tui_toolkit


def test_package_imports():
    assert tui_toolkit.__version__ == "0.1.0"
```

- [ ] **Step 7: Verify the smoke test passes**

```bash
cd tools/tui-toolkit && uv run --with pytest pytest -v
```

Expected: `1 passed`. If `uv run` complains about lock state, run `uv sync` first.

- [ ] **Step 8: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git add tools/tui-toolkit/
git commit -m "tui-toolkit: scaffold package with smoke test"
```

---

### Task 0.2: Wire `tui-toolkit` as a local dependency of `controlplane-tool`

**Files:**
- Modify: `tools/controlplane/pyproject.toml`

The existing pattern in this file uses PEP 508 absolute `file://` URLs (`"shellcraft @ file:///Users/micheleciavotta/shellcraft"`). For an in-monorepo dependency we want a portable relative path, which `uv` supports via `[tool.uv.sources]`. We use that here to keep the lockfile reproducible across machines.

- [ ] **Step 1: Read the current `tools/controlplane/pyproject.toml`**

```bash
cat tools/controlplane/pyproject.toml
```

Note the current `dependencies` list and check whether a `[tool.uv.sources]` table already exists.

- [ ] **Step 2: Add `tui-toolkit` to `dependencies`**

In `tools/controlplane/pyproject.toml`, inside the `[project]` table's `dependencies = [...]` array, add this entry alongside the existing dependencies:

```toml
"tui-toolkit",
```

- [ ] **Step 3: Add the `[tool.uv.sources]` mapping**

After the `[dependency-groups]` block, add:

```toml
[tool.uv.sources]
tui-toolkit = { path = "../tui-toolkit", editable = true }
```

`editable = true` means changes in `tools/tui-toolkit/src/` are reflected immediately without re-installing.

- [ ] **Step 4: Re-lock and verify the dependency resolves**

```bash
cd tools/controlplane && uv sync
```

Expected output includes a line like `+ tui-toolkit==0.1.0 (from file://.../tools/tui-toolkit)`. No errors.

- [ ] **Step 5: Verify the package is importable from controlplane-tool's environment**

```bash
cd tools/controlplane && uv run python -c "import tui_toolkit; print(tui_toolkit.__version__)"
```

Expected: `0.1.0`.

- [ ] **Step 6: Verify existing controlplane tests still pass**

```bash
cd tools/controlplane && uv run pytest -q
```

Expected: all green. The dep addition must not break anything.

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/pyproject.toml tools/controlplane/uv.lock
git commit -m "controlplane: add tui-toolkit local dependency"
```

---

### Task 0.3: Capture golden snapshots from the legacy renderer

We must record byte-for-byte snapshots of the existing renderer output **before** the renderer is changed. These golden files become the parity gate during PR1: the new `tui_toolkit.workflow` renderer with `DEFAULT_THEME` must produce identical output. The same golden files also catch any accidental drift in PR2.

**Files:**
- Create: `tools/controlplane/tests/test_renderer_golden_capture.py` (temporary; deleted at end of PR2)
- Create: `tools/tui-toolkit/tests/golden/legacy_render_completed.txt` (committed)
- Create: `tools/tui-toolkit/tests/golden/legacy_render_failed.txt`
- Create: `tools/tui-toolkit/tests/golden/legacy_render_warning.txt`
- Create: `tools/tui-toolkit/tests/golden/legacy_render_cancelled.txt`
- Create: `tools/tui-toolkit/tests/golden/legacy_render_phase.txt`
- Create: `tools/tui-toolkit/tests/golden/legacy_render_running.txt`
- Create: `tools/tui-toolkit/tests/golden/legacy_render_log_stdout.txt`
- Create: `tools/tui-toolkit/tests/golden/legacy_render_log_stderr.txt`
- Create: `tools/tui-toolkit/tests/golden/legacy_questionary_style.txt`

- [ ] **Step 1: Write the capture script as a one-shot test**

Path: `tools/controlplane/tests/test_renderer_golden_capture.py`

```python
"""Capture golden snapshots of the legacy renderer for tui-toolkit parity tests.

Run once with:
    cd tools/controlplane && uv run pytest tests/test_renderer_golden_capture.py -v -s

After running, verify the files appear under tools/tui-toolkit/tests/golden/.
This file is deleted at the end of PR2.
"""
from __future__ import annotations

from pathlib import Path

from rich.console import Console
from controlplane_tool.console import _render_event
from controlplane_tool.workflow_events import (
    build_log_event,
    build_phase_event,
    build_task_event,
)
from controlplane_tool.workflow_models import WorkflowContext
from controlplane_tool.tui_widgets import _STYLE

GOLDEN_DIR = Path(__file__).resolve().parents[2] / "tui-toolkit" / "tests" / "golden"


def _capture(filename: str, render_callable):
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    rec = Console(record=True, width=80, force_terminal=True, color_system="truecolor")
    # Temporarily swap the module-level `console` in controlplane_tool.console.
    import controlplane_tool.console as ct_console
    original = ct_console.console
    ct_console.console = rec
    try:
        render_callable()
    finally:
        ct_console.console = original
    text = rec.export_text(styles=True)
    (GOLDEN_DIR / filename).write_text(text, encoding="utf-8")


def test_capture_completed():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.completed", title="Build images", detail="2 of 2 ok", context=ctx)
    _capture("legacy_render_completed.txt", lambda: _render_event(event))


def test_capture_failed():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.failed", title="Run E2E", detail="exit code 137", context=ctx)
    _capture("legacy_render_failed.txt", lambda: _render_event(event))


def test_capture_warning():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.warning", title="Image not pinned", context=ctx)
    _capture("legacy_render_warning.txt", lambda: _render_event(event))


def test_capture_cancelled():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.cancelled", title="Provision VM", detail="user cancel", context=ctx)
    _capture("legacy_render_cancelled.txt", lambda: _render_event(event))


def test_capture_phase():
    ctx = WorkflowContext()
    event = build_phase_event("Build phase", context=ctx)
    _capture("legacy_render_phase.txt", lambda: _render_event(event))


def test_capture_running_with_detail():
    ctx = WorkflowContext()
    event = build_task_event(kind="task.running", title="Compile", detail="java 21", context=ctx)
    _capture("legacy_render_running.txt", lambda: _render_event(event))


def test_capture_log_stdout():
    ctx = WorkflowContext()
    event = build_log_event(line="Hello, world", stream="stdout", context=ctx)
    _capture("legacy_render_log_stdout.txt", lambda: _render_event(event))


def test_capture_log_stderr():
    ctx = WorkflowContext()
    event = build_log_event(line="boom", stream="stderr", context=ctx)
    _capture("legacy_render_log_stderr.txt", lambda: _render_event(event))


def test_capture_questionary_style():
    """Serialize the legacy questionary _STYLE so DEFAULT_THEME parity can be verified."""
    GOLDEN_DIR.mkdir(parents=True, exist_ok=True)
    # questionary.Style is a wrapper around prompt_toolkit.styles.Style.
    # The underlying styles are a list of (selector, style_str) tuples.
    pt_style = _STYLE.style  # the wrapped prompt_toolkit Style object
    serialized_lines = []
    for selector, style_str in pt_style.style_rules:
        serialized_lines.append(f"{selector}\t{style_str}")
    (GOLDEN_DIR / "legacy_questionary_style.txt").write_text(
        "\n".join(serialized_lines) + "\n", encoding="utf-8"
    )
```

- [ ] **Step 2: Run the capture**

```bash
cd tools/controlplane && uv run pytest tests/test_renderer_golden_capture.py -v -s
```

Expected: 9 passed. Inspect the produced files:

```bash
ls tools/tui-toolkit/tests/golden/
```

Expected entries: 8 `legacy_render_*.txt` files plus `legacy_questionary_style.txt`.

- [ ] **Step 3: Spot-check one snapshot**

```bash
cat tools/tui-toolkit/tests/golden/legacy_render_completed.txt
```

Expected: contains `✓` and the title `Build images`. Should be a multi-line Rich-rendered panel.

- [ ] **Step 4: Spot-check the questionary style**

```bash
cat tools/tui-toolkit/tests/golden/legacy_questionary_style.txt
```

Expected (one line per selector, the order matters and must match what `_STYLE` declares):

```
brand	fg:cyan bold
breadcrumb	fg:grey
footer	fg:grey
qmark	fg:cyan bold
question	bold
answer	fg:cyan bold
pointer	fg:cyan bold
highlighted	fg:cyan bold
selected	fg:cyan
text	
disabled	fg:grey
separator	fg:grey
instruction	fg:grey
```

If the order or values differ from this list, **stop**: that means the legacy `_STYLE` in `tui_widgets.py` is not what we assumed, and the spec's mapping table needs revisiting.

- [ ] **Step 5: Commit the golden files and the capture script**

```bash
git add tools/tui-toolkit/tests/golden/ tools/controlplane/tests/test_renderer_golden_capture.py
git commit -m "tui-toolkit: capture legacy renderer golden snapshots"
```

---

## Phase 1 — Library core (TDD per module)

The order respects the dependency graph: each module only imports from earlier ones. Tests are written first; implementations are minimal until tests force complexity.

### Task 1.1: `theme.py` — `Theme` dataclass and Rich → prompt_toolkit adapter

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/theme.py`
- Create: `tools/tui-toolkit/tests/test_theme.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_theme.py`

```python
"""Tests for tui_toolkit.theme — Theme dataclass and style adapters."""
from __future__ import annotations

from pathlib import Path

import pytest
from tui_toolkit.theme import (
    DEFAULT_THEME,
    Theme,
    _to_pt,
    to_questionary_style,
)

GOLDEN_DIR = Path(__file__).parent / "golden"


def test_default_theme_uses_cyan_palette():
    assert DEFAULT_THEME.accent == "cyan"
    assert DEFAULT_THEME.accent_strong == "bold cyan"
    assert DEFAULT_THEME.accent_dim == "cyan dim"
    assert DEFAULT_THEME.success == "green"
    assert DEFAULT_THEME.warning == "yellow"
    assert DEFAULT_THEME.error == "red"
    assert DEFAULT_THEME.muted == "dim"
    assert DEFAULT_THEME.brand == "bold cyan"


def test_default_theme_default_icons():
    assert DEFAULT_THEME.icon_running == "▸"
    assert DEFAULT_THEME.icon_completed == "✓"
    assert DEFAULT_THEME.icon_failed == "✗"
    assert DEFAULT_THEME.icon_warning == "⚠"
    assert DEFAULT_THEME.icon_skipped == "⊘"
    assert DEFAULT_THEME.icon_updated == "↺"
    assert DEFAULT_THEME.icon_cancelled == "⊘"


def test_with_overrides_returns_new_immutable_theme():
    derived = DEFAULT_THEME.with_overrides(accent="green", success="blue")
    assert derived.accent == "green"
    assert derived.success == "blue"
    # original is unchanged
    assert DEFAULT_THEME.accent == "cyan"
    assert DEFAULT_THEME.success == "green"
    # frozen
    with pytest.raises(AttributeError):
        DEFAULT_THEME.accent = "red"  # type: ignore[misc]


@pytest.mark.parametrize("rich_style, expected_pt", [
    ("cyan", "fg:cyan"),
    ("bold cyan", "fg:cyan bold"),
    ("cyan dim", "fg:cyan"),  # 'dim' is a separate flag, not a fg color
    ("dim", "fg:grey"),  # legacy mapping: dim → grey foreground
    ("grey", "fg:grey"),
    ("green", "fg:green"),
    ("red", "fg:red"),
    ("yellow", "fg:yellow"),
    ("bold", "bold"),
    ("", ""),
])
def test_to_pt_translates_rich_format(rich_style, expected_pt):
    assert _to_pt(rich_style) == expected_pt


def test_to_questionary_style_matches_legacy_byte_for_byte():
    """Parity gate: DEFAULT_THEME must produce the exact same selector→style
    mapping as the legacy `_STYLE` from controlplane_tool.tui_widgets."""
    qs = to_questionary_style(DEFAULT_THEME)
    # Serialize in the same format as the captured golden file.
    actual_lines = [f"{sel}\t{style}" for sel, style in qs.style.style_rules]
    expected = (GOLDEN_DIR / "legacy_questionary_style.txt").read_text(encoding="utf-8")
    expected_lines = [line for line in expected.splitlines() if line]
    assert actual_lines == expected_lines


def test_theme_overrides_propagate_to_questionary_style():
    custom = DEFAULT_THEME.with_overrides(accent_strong="bold green", accent="green")
    qs = to_questionary_style(custom)
    rules = dict(qs.style.style_rules)
    assert rules["pointer"] == "fg:green bold"
    assert rules["selected"] == "fg:green"
```

- [ ] **Step 2: Run the tests, confirm they fail with `ImportError`**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_theme.py -v
```

Expected: `ImportError: cannot import name 'Theme' from 'tui_toolkit.theme'` (the module doesn't exist yet).

- [ ] **Step 3: Implement `theme.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/theme.py`

```python
"""Theme model and Rich → prompt_toolkit style adapter."""
from __future__ import annotations

from dataclasses import dataclass, replace

import questionary


@dataclass(frozen=True, slots=True)
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

    def with_overrides(self, **changes) -> "Theme":
        return replace(self, **changes)


DEFAULT_THEME = Theme()


_DIM_TO_GREY = {"dim", "grey"}


def _to_pt(rich_style: str) -> str:
    """Translate a Rich style string into prompt_toolkit format.

    Examples:
        "bold cyan"   → "fg:cyan bold"
        "cyan dim"    → "fg:cyan"   (the dim flag is ignored — the legacy
                                     selector for 'dim' uses 'fg:grey' directly)
        "dim"         → "fg:grey"   (legacy alias for muted text)
        ""            → ""
    """
    if not rich_style:
        return ""
    tokens = rich_style.split()
    fg: str | None = None
    flags: list[str] = []
    for token in tokens:
        low = token.lower()
        if low in {"bold", "italic", "underline"}:
            flags.append(low)
        elif low == "dim":
            # 'dim' alone is the legacy 'muted' selector → grey foreground.
            # 'dim' as a modifier (e.g., "cyan dim") is dropped — questionary
            # has no faithful equivalent and the legacy code uses fg:cyan in
            # such cases.
            if fg is None:
                fg = "grey"
        else:
            fg = low
    parts: list[str] = []
    if fg is not None:
        parts.append(f"fg:{fg}")
    parts.extend(flags)
    return " ".join(parts)


def to_questionary_style(theme: Theme) -> questionary.Style:
    """Map theme tokens into the 13 questionary/prompt_toolkit selectors."""
    return questionary.Style([
        ("brand",       _to_pt(theme.brand)),
        ("breadcrumb",  _to_pt(theme.muted)),
        ("footer",      _to_pt(theme.muted)),
        ("qmark",       _to_pt(theme.accent_strong)),
        ("question",    "bold"),
        ("answer",      _to_pt(theme.accent_strong)),
        ("pointer",     _to_pt(theme.accent_strong)),
        ("highlighted", _to_pt(theme.accent_strong)),
        ("selected",    _to_pt(theme.accent)),
        ("text",        _to_pt(theme.text)),
        ("disabled",    _to_pt(theme.muted)),
        ("separator",   _to_pt(theme.muted)),
        ("instruction", _to_pt(theme.muted)),
    ])
```

- [ ] **Step 4: Run the tests, confirm they pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_theme.py -v
```

Expected: all green.

If `test_to_questionary_style_matches_legacy_byte_for_byte` fails, the discrepancy is between `_STYLE` selectors in `tui_widgets.py` and the mapping above. Compare:

```bash
diff <(cd tools/tui-toolkit && uv run python -c "from tui_toolkit.theme import to_questionary_style, DEFAULT_THEME; \
    qs = to_questionary_style(DEFAULT_THEME); \
    print(chr(10).join(f'{s}\t{v}' for s,v in qs.style.style_rules))") \
    tools/tui-toolkit/tests/golden/legacy_questionary_style.txt
```

The output of the diff identifies the offending selector. Fix the mapping in `to_questionary_style` (do **not** change the golden file).

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/theme.py tools/tui-toolkit/tests/test_theme.py
git commit -m "tui-toolkit: add Theme dataclass and questionary style adapter"
```

---

### Task 1.2: `brand.py` — `AppBrand` dataclass

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/brand.py`
- Create: `tools/tui-toolkit/tests/test_brand.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_brand.py`

```python
"""Tests for tui_toolkit.brand."""
from __future__ import annotations

import pytest
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND


def test_default_brand_is_neutral():
    assert DEFAULT_BRAND.name == "App"
    assert DEFAULT_BRAND.wordmark == ""
    assert DEFAULT_BRAND.ascii_logo == ""
    assert DEFAULT_BRAND.default_breadcrumb == "Main"
    assert DEFAULT_BRAND.default_footer_hint == "Esc back | Ctrl+C exit"


def test_brand_is_frozen():
    with pytest.raises(AttributeError):
        DEFAULT_BRAND.name = "other"  # type: ignore[misc]


def test_app_brand_constructor_overrides():
    brand = AppBrand(name="myapp", wordmark="MYAPP", ascii_logo="...logo...")
    assert brand.name == "myapp"
    assert brand.wordmark == "MYAPP"
    assert brand.ascii_logo == "...logo..."
    # untouched fields keep defaults
    assert brand.default_breadcrumb == "Main"
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_brand.py -v
```

Expected: `ImportError`.

- [ ] **Step 3: Implement `brand.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/brand.py`

```python
"""AppBrand — application identity passed through UIContext."""
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class AppBrand:
    name: str = "App"
    wordmark: str = ""
    ascii_logo: str = ""
    default_breadcrumb: str = "Main"
    default_footer_hint: str = "Esc back | Ctrl+C exit"


DEFAULT_BRAND = AppBrand()
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_brand.py -v
```

Expected: 3 passed.

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/brand.py tools/tui-toolkit/tests/test_brand.py
git commit -m "tui-toolkit: add AppBrand dataclass"
```

---

### Task 1.3: `events.py` — `WorkflowEvent`, `WorkflowContext`, `WorkflowSink`

We move these three types verbatim from `controlplane_tool/workflow_models.py` (lines 62–91) into the library. The fields and `Protocol` shape must match exactly so that PR1's shim (which re-exports from `tui_toolkit.events`) is backward compatible.

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/events.py`
- Create: `tools/tui-toolkit/tests/test_events.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_events.py`

```python
"""Tests for tui_toolkit.events — WorkflowEvent, WorkflowContext, WorkflowSink."""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, UTC

import pytest
from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink


def test_workflow_context_defaults():
    ctx = WorkflowContext()
    assert ctx.flow_id == "interactive.console"
    assert ctx.flow_run_id is None
    assert ctx.task_id is None
    assert ctx.parent_task_id is None
    assert ctx.task_run_id is None


def test_workflow_context_is_frozen():
    ctx = WorkflowContext()
    with pytest.raises(AttributeError):
        ctx.flow_id = "x"  # type: ignore[misc]


def test_workflow_event_minimal_construction():
    event = WorkflowEvent(kind="task.completed", flow_id="f")
    assert event.kind == "task.completed"
    assert event.flow_id == "f"
    assert event.title == ""
    assert event.detail == ""
    assert event.stream == "stdout"
    assert event.line == ""
    # `at` is auto-populated as a UTC datetime
    assert isinstance(event.at, datetime)
    assert event.at.tzinfo is UTC


def test_workflow_event_is_frozen():
    event = WorkflowEvent(kind="x", flow_id="f")
    with pytest.raises(AttributeError):
        event.kind = "y"  # type: ignore[misc]


class _FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []
        self.statuses: list[str] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        self.statuses.append(label)
        yield


def test_fake_sink_satisfies_workflow_sink_protocol():
    """Structural typing: a class with the right methods passes static checks
    and works as a WorkflowSink at runtime."""
    sink: WorkflowSink = _FakeSink()
    sink.emit(WorkflowEvent(kind="task.completed", flow_id="f"))
    with sink.status("loading"):
        pass
    assert sink.events[0].kind == "task.completed"  # type: ignore[attr-defined]
    assert sink.statuses == ["loading"]  # type: ignore[attr-defined]
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_events.py -v
```

Expected: `ImportError`.

- [ ] **Step 3: Implement `events.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/events.py`

```python
"""Workflow event types — WorkflowEvent, WorkflowContext, WorkflowSink.

These are the data types shared between event producers (runners) and the
event renderer / sink layer. The TUI dashboard, the Rich console renderer,
and test fakes all implement the WorkflowSink Protocol.
"""
from __future__ import annotations

from contextlib import AbstractContextManager
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol


def _utc_now() -> datetime:
    return datetime.now(UTC)


@dataclass(slots=True, frozen=True)
class WorkflowContext:
    flow_id: str = "interactive.console"
    flow_run_id: str | None = None
    task_id: str | None = None
    parent_task_id: str | None = None
    task_run_id: str | None = None


@dataclass(slots=True, frozen=True)
class WorkflowEvent:
    kind: str
    flow_id: str
    at: datetime = field(default_factory=_utc_now)
    flow_run_id: str | None = None
    task_id: str | None = None
    parent_task_id: str | None = None
    task_run_id: str | None = None
    title: str = ""
    detail: str = ""
    stream: str = "stdout"
    line: str = ""


class WorkflowSink(Protocol):
    """Event receiver for workflow progress — implemented by TUI dashboards,
    the Rich console renderer, and test fakes."""

    def emit(self, event: "WorkflowEvent") -> None: ...

    def status(self, label: str) -> AbstractContextManager[None]: ...
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_events.py -v
```

Expected: 5 passed.

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/events.py tools/tui-toolkit/tests/test_events.py
git commit -m "tui-toolkit: add WorkflowEvent, WorkflowContext, WorkflowSink"
```

---

### Task 1.4: `context.py` — `UIContext`, `init_ui`, `get_ui`, `bind_ui`

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/context.py`
- Create: `tools/tui-toolkit/tests/test_context.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_context.py`

```python
"""Tests for tui_toolkit.context — UIContext + init_ui / get_ui / bind_ui."""
from __future__ import annotations

from unittest.mock import patch

import pytest
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.context import UIContext, bind_ui, get_ui, init_ui
from tui_toolkit.theme import DEFAULT_THEME, Theme


@pytest.fixture(autouse=True)
def _reset_ui_singleton():
    """Each test starts from the bare default — init_ui mutates module state."""
    import tui_toolkit.context as ctx_mod
    saved_shared = ctx_mod._ctx_shared
    saved_var_default = ctx_mod._ctx_var.get()
    yield
    ctx_mod._ctx_shared = saved_shared
    # Also restore any token leaks
    if ctx_mod._ctx_var.get() is not saved_var_default:
        ctx_mod._ctx_var.set(saved_var_default)


def test_default_context_is_default_theme_and_default_brand():
    assert get_ui().theme is DEFAULT_THEME
    assert get_ui().brand is DEFAULT_BRAND


def test_init_ui_caps_terminal_width():
    with patch("shutil.get_terminal_size", return_value=__import__("os").terminal_size((200, 24))):
        ui = init_ui(UIContext(max_content_cols=140))
    assert ui.content_width == 140


def test_init_ui_uses_real_width_when_smaller_than_cap():
    with patch("shutil.get_terminal_size", return_value=__import__("os").terminal_size((100, 24))):
        ui = init_ui(UIContext(max_content_cols=140))
    assert ui.content_width == 100


def test_init_ui_idempotent_last_call_wins():
    ui1 = init_ui(UIContext(theme=Theme(accent="green")))
    ui2 = init_ui(UIContext(theme=Theme(accent="red")))
    assert get_ui() is ui2
    assert get_ui().theme.accent == "red"


def test_init_ui_default_argument_uses_defaults():
    ui = init_ui()
    assert ui.theme is DEFAULT_THEME
    assert ui.brand is DEFAULT_BRAND


def test_bind_ui_temporary_override_restores_previous():
    init_ui(UIContext(theme=Theme(accent="cyan")))
    custom = UIContext(theme=Theme(accent="magenta"), brand=AppBrand(name="x"))
    with bind_ui(custom):
        assert get_ui().theme.accent == "magenta"
        assert get_ui().brand.name == "x"
    assert get_ui().theme.accent == "cyan"


def test_init_ui_returns_resolved_context_with_width_populated():
    ui = init_ui(UIContext())
    assert ui.content_width is not None
    assert ui.content_width > 0
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_context.py -v
```

Expected: `ImportError`.

- [ ] **Step 3: Implement `context.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/context.py`

```python
"""UIContext — active theme + brand + width, bound via init_ui / bind_ui."""
from __future__ import annotations

import shutil
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import dataclass, replace
from typing import Generator

from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.theme import DEFAULT_THEME, Theme


@dataclass(frozen=True, slots=True)
class UIContext:
    theme: Theme = DEFAULT_THEME
    brand: AppBrand = DEFAULT_BRAND
    max_content_cols: int = 140
    content_width: int | None = None  # populated by init_ui()


_DEFAULT_CTX = UIContext()
_ctx_var: ContextVar[UIContext] = ContextVar("tui_toolkit_ctx", default=_DEFAULT_CTX)
_ctx_shared: UIContext = _DEFAULT_CTX


def init_ui(ctx: UIContext | None = None) -> UIContext:
    """Capture terminal width and install the context as the active singleton.

    Idempotent — the last call wins. Replaces the legacy init_ui_width().
    """
    global _ctx_shared
    base = ctx or UIContext()
    width = min(shutil.get_terminal_size((80, 24)).columns, base.max_content_cols)
    resolved = replace(base, content_width=width)
    _ctx_shared = resolved
    _ctx_var.set(resolved)
    # Apply the width to the Console singleton if it has been instantiated.
    try:
        from tui_toolkit.console import _apply_width
        _apply_width(width)
    except ImportError:
        # console.py not yet built (only relevant during library bootstrap).
        pass
    return resolved


def get_ui() -> UIContext:
    """Active UI context — returns DEFAULT before init_ui is called."""
    value = _ctx_var.get()
    return value if value is not _DEFAULT_CTX or _ctx_shared is _DEFAULT_CTX else _ctx_shared


@contextmanager
def bind_ui(ctx: UIContext) -> Generator[UIContext, None, None]:
    """Temporary override (useful in tests). Reverts on exit."""
    token = _ctx_var.set(ctx)
    try:
        yield ctx
    finally:
        _ctx_var.reset(token)
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_context.py -v
```

Expected: 7 passed. The `console` import inside `init_ui` is allowed to fail silently for now (module not yet built).

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/context.py tools/tui-toolkit/tests/test_context.py
git commit -m "tui-toolkit: add UIContext + init_ui/get_ui/bind_ui"
```

---

### Task 1.5: `console.py` — Rich `Console` singleton + width helpers

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/console.py`
- Create: `tools/tui-toolkit/tests/test_console.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_console.py`

```python
"""Tests for tui_toolkit.console — Rich Console singleton + width helpers."""
from __future__ import annotations

from rich.console import Console

import tui_toolkit.console as console_mod
from tui_toolkit.console import console, get_content_width


def test_console_is_a_rich_console():
    assert isinstance(console, Console)


def test_get_content_width_default_is_max_cap():
    # Before init_ui is called, fall back to the conservative cap.
    width = get_content_width()
    assert width == 140


def test_apply_width_updates_console_and_helpers():
    console_mod._apply_width(100)
    assert get_content_width() == 100
    # Reset to the default for downstream tests.
    console_mod._apply_width(140)
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_console.py -v
```

Expected: `ImportError`.

- [ ] **Step 3: Implement `console.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/console.py`

```python
"""Rich Console singleton with a content-width cap.

`init_ui()` calls `_apply_width()` to lock the width once the TTY size is
available. Until then `console` is a default Rich Console (auto-detected
width). `get_content_width()` returns the cap so prompt_toolkit pickers can
honour the same limit.
"""
from __future__ import annotations

from rich.console import Console

_MAX_CONTENT_COLS = 140

# Singleton shared across all consumers of the library.
console = Console(highlight=False)

_content_width: int = _MAX_CONTENT_COLS


def get_content_width() -> int:
    """Active content width (after _apply_width) or the bare cap."""
    return _content_width


def _apply_width(width: int) -> None:
    """Set the Rich Console width and the cached helper width.

    Called by tui_toolkit.context.init_ui after probing the terminal size.
    """
    global _content_width
    _content_width = width
    console._width = width  # retroactively cap the singleton's reported width
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_console.py -v
```

Expected: 3 passed.

- [ ] **Step 5: Re-run the context tests to verify the lazy import wires up**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_context.py tests/test_console.py -v
```

Expected: all pass. The `from tui_toolkit.console import _apply_width` inside `init_ui` now succeeds.

- [ ] **Step 6: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/console.py tools/tui-toolkit/tests/test_console.py
git commit -m "tui-toolkit: add Rich Console singleton with width cap"
```

---

### Task 1.6: `chrome.py` — `render_screen_frame`

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/chrome.py`
- Create: `tools/tui-toolkit/tests/test_chrome.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_chrome.py`

```python
"""Tests for tui_toolkit.chrome — render_screen_frame."""
from __future__ import annotations

import pytest
from rich.console import Console
from rich.text import Text

from tui_toolkit.brand import AppBrand
from tui_toolkit.chrome import render_screen_frame
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.theme import Theme


def _render(panel) -> str:
    rec = Console(record=True, width=120, force_terminal=True, color_system="truecolor")
    rec.print(panel)
    return rec.export_text(styles=False)


def test_render_screen_frame_with_brand_includes_logo_and_wordmark():
    brand = AppBrand(name="demo", wordmark="DEMO", ascii_logo="◆ DEMO LOGO ◆")
    with bind_ui(UIContext(brand=brand)):
        panel = render_screen_frame(title="Title", body=Text("hello"))
    text = _render(panel)
    assert "DEMO" in text
    assert "◆ DEMO LOGO ◆" in text
    assert "hello" in text
    assert "Title" in text


def test_render_screen_frame_without_brand_does_not_show_empty_lines():
    """Empty wordmark / logo render as no-op, not as blank chrome."""
    with bind_ui(UIContext(brand=AppBrand())):  # default = empty
        panel = render_screen_frame(title="Title", body=Text("body"))
    text = _render(panel)
    assert "body" in text
    # No NANOFAAS or other leftover branding from the legacy module.
    assert "NANOFAAS" not in text


def test_render_screen_frame_uses_default_breadcrumb_from_brand():
    brand = AppBrand(default_breadcrumb="Home")
    with bind_ui(UIContext(brand=brand)):
        panel = render_screen_frame(title="x", body=Text("y"))
    text = _render(panel)
    assert "Home" in text


def test_render_screen_frame_explicit_breadcrumb_overrides_default():
    brand = AppBrand(default_breadcrumb="Home")
    with bind_ui(UIContext(brand=brand)):
        panel = render_screen_frame(title="x", body=Text("y"), breadcrumb="Sub")
    text = _render(panel)
    assert "Sub" in text
    assert "Home" not in text


def test_render_screen_frame_footer_hint_default_and_override():
    brand = AppBrand(default_footer_hint="Esc back")
    with bind_ui(UIContext(brand=brand)):
        default_panel = render_screen_frame(title="x", body=Text("y"))
        custom_panel = render_screen_frame(title="x", body=Text("y"), footer_hint="Q quit")
    assert "Esc back" in _render(default_panel)
    text2 = _render(custom_panel)
    assert "Q quit" in text2
    assert "Esc back" not in text2


def test_render_screen_frame_uses_theme_border_style():
    """Border colour follows theme.accent_dim."""
    theme = Theme(accent_dim="red dim")
    with bind_ui(UIContext(theme=theme, brand=AppBrand())):
        panel = render_screen_frame(title="x", body=Text("y"))
    # The border_style on the Panel object reflects the theme.
    assert panel.border_style == "red dim"
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_chrome.py -v
```

Expected: `ImportError`.

- [ ] **Step 3: Implement `chrome.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/chrome.py`

```python
"""render_screen_frame — Rich screen chrome (header / breadcrumb / footer)."""
from __future__ import annotations

from rich.console import Group, RenderableType
from rich.panel import Panel
from rich.rule import Rule
from rich.table import Table
from rich.text import Text

from tui_toolkit.context import get_ui


def render_screen_frame(
    *,
    title: str,
    body: RenderableType,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> Panel:
    """Wrap `body` in a themed Rich Panel with branded header and footer.

    Reads brand and theme from the active UIContext.
    """
    ui = get_ui()
    theme = ui.theme
    brand = ui.brand
    resolved_breadcrumb = breadcrumb if breadcrumb is not None else brand.default_breadcrumb
    resolved_footer = footer_hint if footer_hint is not None else brand.default_footer_hint

    header = Table.grid(expand=True)
    header.add_column(ratio=1)
    header.add_column(justify="right", no_wrap=True)
    header.add_row(
        Text(brand.wordmark, style=theme.brand) if brand.wordmark else Text(""),
        Text(resolved_breadcrumb, style=theme.muted) if resolved_breadcrumb else Text(""),
    )

    content: list[RenderableType] = []
    if brand.ascii_logo:
        content.append(Text(brand.ascii_logo, style=theme.brand))
    content.append(header)
    content.append(Rule(style=theme.accent_dim))
    content.append(body)
    if resolved_footer:
        content.append(Rule(style=theme.accent_dim))
        content.append(Text(resolved_footer, style=theme.muted))

    return Panel(
        Group(*content),
        title=Text(title, style="bold"),
        border_style=theme.accent_dim,
        padding=(1, 2),
    )
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_chrome.py -v
```

Expected: 6 passed.

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/chrome.py tools/tui-toolkit/tests/test_chrome.py
git commit -m "tui-toolkit: add render_screen_frame with theme + brand integration"
```

---

### Task 1.7: `workflow.py` — event builders, sink/context binding

This task ports the event builder functions and the workflow context plumbing. The renderer + user-facing helpers (`phase`, `step`, `success`, …) come in Task 1.8.

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/workflow.py` (partial — builders only)
- Create: `tools/tui-toolkit/tests/test_workflow_events.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_workflow_events.py`

```python
"""Tests for tui_toolkit.workflow — event builders and sink/context binding."""
from __future__ import annotations

from contextlib import contextmanager

import pytest
from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink
from tui_toolkit.workflow import (
    bind_workflow_context,
    bind_workflow_sink,
    build_log_event,
    build_phase_event,
    build_task_event,
    get_workflow_context,
    has_workflow_sink,
)


class FakeSink:
    def __init__(self) -> None:
        self.events: list[WorkflowEvent] = []

    def emit(self, event: WorkflowEvent) -> None:
        self.events.append(event)

    @contextmanager
    def status(self, label: str):
        yield


def test_build_task_event_minimal():
    event = build_task_event(kind="task.completed", title="x")
    assert event.kind == "task.completed"
    assert event.title == "x"
    assert event.flow_id == "interactive.console"  # default WorkflowContext


def test_build_task_event_inherits_from_context():
    ctx = WorkflowContext(flow_id="my-flow", task_id="t1", parent_task_id="root")
    event = build_task_event(kind="task.running", title="run", context=ctx)
    assert event.flow_id == "my-flow"
    assert event.task_id == "t1"
    assert event.parent_task_id == "root"


def test_build_task_event_explicit_overrides_context():
    ctx = WorkflowContext(flow_id="ctx-flow", task_id="t1")
    event = build_task_event(kind="task.completed", task_id="t2", context=ctx)
    assert event.task_id == "t2"
    assert event.flow_id == "ctx-flow"


def test_build_task_event_falls_back_title_to_task_id():
    event = build_task_event(kind="task.completed", task_id="my-task")
    assert event.title == "my-task"


def test_build_phase_event():
    event = build_phase_event("Provisioning")
    assert event.kind == "phase.started"
    assert event.title == "Provisioning"


def test_build_log_event_default_stream_stdout():
    event = build_log_event(line="hello")
    assert event.kind == "log.line"
    assert event.line == "hello"
    assert event.stream == "stdout"


def test_build_log_event_stderr():
    event = build_log_event(line="boom", stream="stderr")
    assert event.stream == "stderr"


def test_get_workflow_context_default_is_none():
    assert get_workflow_context() is None


def test_bind_workflow_context_makes_it_visible():
    ctx = WorkflowContext(flow_id="bound")
    with bind_workflow_context(ctx):
        assert get_workflow_context() is ctx
    assert get_workflow_context() is None


def test_has_workflow_sink_default_false():
    assert has_workflow_sink() is False


def test_bind_workflow_sink_makes_it_visible():
    sink = FakeSink()
    with bind_workflow_sink(sink):
        assert has_workflow_sink() is True
    assert has_workflow_sink() is False
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_workflow_events.py -v
```

Expected: `ImportError`.

- [ ] **Step 3: Implement the first half of `workflow.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/workflow.py` (renderer + helpers come in Task 1.8)

```python
"""Workflow event builders, sink/context binding, and renderer (split across
Task 1.7 and 1.8).

This file is built in two passes:
  - Task 1.7: event builders + bind_workflow_sink/context + get_workflow_context
  - Task 1.8: header/phase/step/success/warning/skip/fail/status/workflow_step
              + the Rich renderer (_render_event)
"""
from __future__ import annotations

from contextlib import contextmanager
from contextvars import ContextVar
from typing import Generator

from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink


# ── Active sink + context plumbing ────────────────────────────────────────

_workflow_sink_var: ContextVar["WorkflowSink | None"] = ContextVar(
    "tui_toolkit_workflow_sink", default=None,
)
_workflow_sink_shared: "WorkflowSink | None" = None
_workflow_context_var: ContextVar[WorkflowContext | None] = ContextVar(
    "tui_toolkit_workflow_context", default=None,
)
_workflow_context_shared: WorkflowContext | None = None


@contextmanager
def bind_workflow_sink(sink: WorkflowSink) -> Generator[None, None, None]:
    global _workflow_sink_shared
    previous = _workflow_sink_shared
    _workflow_sink_shared = sink
    token = _workflow_sink_var.set(sink)
    try:
        yield
    finally:
        _workflow_sink_var.reset(token)
        _workflow_sink_shared = previous


@contextmanager
def bind_workflow_context(context: WorkflowContext) -> Generator[None, None, None]:
    global _workflow_context_shared
    previous = _workflow_context_shared
    _workflow_context_shared = context
    token = _workflow_context_var.set(context)
    try:
        yield
    finally:
        _workflow_context_var.reset(token)
        _workflow_context_shared = previous


def _active_sink() -> "WorkflowSink | None":
    return _workflow_sink_var.get() or _workflow_sink_shared


def get_workflow_context() -> WorkflowContext | None:
    return _workflow_context_var.get() or _workflow_context_shared


def has_workflow_sink() -> bool:
    return _active_sink() is not None


# ── Event builders ────────────────────────────────────────────────────────

_PREFECT_STATE_TO_EVENT_KIND = {
    "cancelled": "task.cancelled",
    "completed": "task.completed",
    "crashed": "task.failed",
    "failed": "task.failed",
    "pending": "task.pending",
    "running": "task.running",
    "scheduled": "task.pending",
}


def _resolve_context_fields(
    *,
    flow_id: str | None,
    flow_run_id: str | None,
    task_id: str | None,
    parent_task_id: str | None,
    task_run_id: str | None,
    context: WorkflowContext | None,
    inherit_task_id: bool = True,
) -> tuple[str, str | None, str | None, str | None, str | None]:
    active = context or WorkflowContext()
    resolved_task_id = task_id if task_id is not None else (active.task_id if inherit_task_id else None)
    resolved_parent = parent_task_id if parent_task_id is not None else active.parent_task_id
    return (
        flow_id or active.flow_id,
        flow_run_id or active.flow_run_id,
        resolved_task_id,
        resolved_parent,
        task_run_id or active.task_run_id,
    )


def build_task_event(
    *,
    kind: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    title: str = "",
    detail: str = "",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
        parent_task_id=parent_task_id, task_run_id=task_run_id,
        context=context,
    )
    return WorkflowEvent(
        kind=kind,
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        title=title or resolved[2] or kind,
        detail=detail,
    )


def build_phase_event(
    label: str,
    *,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=None,
        parent_task_id=None, task_run_id=None, context=context,
    )
    return WorkflowEvent(
        kind="phase.started",
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        title=label,
    )


def build_log_event(
    *,
    line: str,
    flow_id: str | None = None,
    flow_run_id: str | None = None,
    task_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    stream: str = "stdout",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    resolved = _resolve_context_fields(
        flow_id=flow_id, flow_run_id=flow_run_id, task_id=task_id,
        parent_task_id=parent_task_id, task_run_id=task_run_id,
        context=context,
    )
    return WorkflowEvent(
        kind="log.line",
        flow_id=resolved[0], flow_run_id=resolved[1], task_id=resolved[2],
        parent_task_id=resolved[3], task_run_id=resolved[4],
        stream=stream, line=line,
    )
```

Note: `normalize_task_state` (Prefect-specific) is intentionally **not** moved here. It stays in `controlplane_tool.workflow_events` after PR1 because the `_PREFECT_STATE_TO_EVENT_KIND` mapping is Prefect-specific knowledge.

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_workflow_events.py -v
```

Expected: 11 passed.

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/workflow.py tools/tui-toolkit/tests/test_workflow_events.py
git commit -m "tui-toolkit: add workflow event builders and sink/context binding"
```

---

### Task 1.8: `workflow.py` — renderer + user-facing helpers

This task adds the second half of `workflow.py`: the Rich renderer (`_render_event`) plus the high-level helpers (`header`, `phase`, `step`, `success`, `warning`, `skip`, `fail`, `status`, `workflow_log`, `workflow_step`).

**Files:**
- Modify: `tools/tui-toolkit/src/tui_toolkit/workflow.py` (append renderer + helpers)
- Create: `tools/tui-toolkit/tests/test_workflow_render.py`

- [ ] **Step 1: Write the test file for the renderer + helpers**

Path: `tools/tui-toolkit/tests/test_workflow_render.py`

```python
"""Tests for tui_toolkit.workflow renderer + high-level helpers."""
from __future__ import annotations

from contextlib import contextmanager
from pathlib import Path

import pytest
from rich.console import Console

import tui_toolkit.console as console_mod
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.events import WorkflowEvent
from tui_toolkit.theme import Theme
from tui_toolkit.workflow import (
    _render_event,
    bind_workflow_sink,
    build_log_event,
    build_phase_event,
    build_task_event,
    fail,
    header,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
    workflow_step,
)


GOLDEN_DIR = Path(__file__).parent / "golden"


@pytest.fixture
def recording_console(monkeypatch):
    """Replace tui_toolkit.console.console with a recording one."""
    rec = Console(record=True, width=80, force_terminal=True, color_system="truecolor")
    monkeypatch.setattr(console_mod, "console", rec)
    return rec


def test_render_completed_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.completed", title="Build images", detail="2 of 2 ok")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_completed.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_failed_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.failed", title="Run E2E", detail="exit code 137")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_failed.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_warning_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.warning", title="Image not pinned")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_warning.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_cancelled_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.cancelled", title="Provision VM", detail="user cancel")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_cancelled.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_phase_matches_legacy_golden(recording_console):
    event = build_phase_event("Build phase")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_phase.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_running_matches_legacy_golden(recording_console):
    event = build_task_event(kind="task.running", title="Compile", detail="java 21")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_running.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_log_stdout_matches_legacy_golden(recording_console):
    event = build_log_event(line="Hello, world", stream="stdout")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_log_stdout.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_log_stderr_matches_legacy_golden(recording_console):
    event = build_log_event(line="boom", stream="stderr")
    _render_event(event)
    actual = recording_console.export_text(styles=True)
    expected = (GOLDEN_DIR / "legacy_render_log_stderr.txt").read_text(encoding="utf-8")
    assert actual == expected


def test_render_uses_theme_icons(recording_console):
    """When the theme overrides icon_completed, the render uses the new glyph."""
    with bind_ui(UIContext(theme=Theme(icon_completed="OK"))):
        event = build_task_event(kind="task.completed", title="x")
        _render_event(event)
    out = recording_console.export_text(styles=False)
    assert "OK" in out
    assert "✓" not in out


def test_render_uses_theme_colors(recording_console):
    """When theme.success changes, the rendered style does too."""
    with bind_ui(UIContext(theme=Theme(success="blue"))):
        event = build_task_event(kind="task.completed", title="x")
        _render_event(event)
    styled = recording_console.export_text(styles=True)
    assert "blue" in styled


# ── Helpers (high-level API) ─────────────────────────────────────────────


def test_phase_emits_event_to_active_sink():
    class CaptureSink:
        def __init__(self):
            self.events: list[WorkflowEvent] = []

        def emit(self, event):
            self.events.append(event)

        @contextmanager
        def status(self, label):
            yield

    sink = CaptureSink()
    with bind_workflow_sink(sink):
        phase("Build")
    assert len(sink.events) == 1
    assert sink.events[0].kind == "phase.started"
    assert sink.events[0].title == "Build"


def test_step_renders_to_console_when_no_sink(recording_console):
    step("Compile", detail="java 21")
    out = recording_console.export_text(styles=False)
    assert "Compile" in out
    assert "java 21" in out


def test_workflow_step_emits_running_then_completed():
    events = []

    class Sink:
        def emit(self, e):
            events.append(e)

        @contextmanager
        def status(self, label):
            yield

    with bind_workflow_sink(Sink()):
        with workflow_step(task_id="t1", title="run"):
            pass
    assert [e.kind for e in events] == ["task.running", "task.completed"]
    assert all(e.task_id == "t1" for e in events)


def test_workflow_step_emits_running_then_failed_on_exception():
    events = []

    class Sink:
        def emit(self, e):
            events.append(e)

        @contextmanager
        def status(self, label):
            yield

    with bind_workflow_sink(Sink()):
        with pytest.raises(RuntimeError):
            with workflow_step(task_id="t1", title="run"):
                raise RuntimeError("boom")
    assert [e.kind for e in events] == ["task.running", "task.failed"]
    assert events[1].detail == "boom"


def test_workflow_log_routes_to_sink():
    events = []

    class Sink:
        def emit(self, e):
            events.append(e)

        @contextmanager
        def status(self, label):
            yield

    with bind_workflow_sink(Sink()):
        workflow_log("hello", stream="stderr")
    assert len(events) == 1
    assert events[0].kind == "log.line"
    assert events[0].stream == "stderr"
    assert events[0].line == "hello"


def test_status_yields_when_no_sink(recording_console):
    """Without a sink, status falls back to console.status spinner."""
    with status("loading"):
        pass
    # Shouldn't raise; the spinner stops immediately on exit.


def test_status_routes_to_sink_when_present():
    sink_started: list[str] = []

    class Sink:
        def emit(self, e):
            pass

        @contextmanager
        def status(self, label):
            sink_started.append(label)
            yield

    with bind_workflow_sink(Sink()):
        with status("loading"):
            pass
    assert sink_started == ["loading"]


def test_header_renders_with_brand(recording_console):
    from tui_toolkit.brand import AppBrand

    with bind_ui(UIContext(brand=AppBrand(ascii_logo="LOGO"))):
        header("subtitle")
    out = recording_console.export_text(styles=False)
    assert "LOGO" in out
    assert "subtitle" in out


def test_header_with_empty_brand_skips_logo(recording_console):
    from tui_toolkit.brand import AppBrand

    with bind_ui(UIContext(brand=AppBrand())):
        header("subtitle")
    out = recording_console.export_text(styles=False)
    assert "LOGO" not in out  # nothing leaked
    assert "subtitle" in out
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_workflow_render.py -v
```

Expected: `ImportError` for `_render_event`, `header`, etc. (only the builders + binders exist so far).

- [ ] **Step 3: Append the renderer + helpers to `workflow.py`**

Append at the end of `tools/tui-toolkit/src/tui_toolkit/workflow.py`:

```python


# ── Renderer ──────────────────────────────────────────────────────────────

from contextlib import contextmanager as _contextmanager  # explicit alias

from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.text import Text

from tui_toolkit.console import console as _console
from tui_toolkit.context import get_ui


def _render_event(event: WorkflowEvent) -> None:
    """Render a WorkflowEvent to the active Rich console using the active theme."""
    theme = get_ui().theme

    if event.kind == "log.line":
        prefix = "stderr │ " if event.stream == "stderr" else ""
        _console.print(f"{prefix}{escape(event.line)}")
        return

    if event.kind == "phase.started":
        _console.print()
        _console.print(Rule(f"[{theme.accent_strong}]{escape(event.title)}[/]", style=theme.accent_dim))
        _console.print()
        return

    if event.kind == "task.running":
        if event.detail:
            _console.print(
                f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _console.print(f"  [{theme.accent}]{theme.icon_running}[/] [bold]{escape(event.title)}[/]")
        return

    if event.kind == "task.completed":
        body = f"[bold {theme.success}]{theme.icon_completed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _console.print()
        _console.print(Panel(body, border_style=theme.success, padding=(0, 2)))
        _console.print()
        return

    if event.kind == "task.warning":
        _console.print(f"  [{theme.warning}]{theme.icon_warning}[/]  [{theme.warning}]{escape(event.title)}[/]")
        return

    if event.kind == "task.updated":
        if event.detail:
            _console.print(
                f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]  "
                f"[{theme.muted}]{escape(event.detail)}[/]"
            )
        else:
            _console.print(f"  [{theme.accent}]{theme.icon_updated}[/] [bold]{escape(event.title)}[/]")
        return

    if event.kind == "task.skipped":
        _console.print(f"  [{theme.muted}]{theme.icon_skipped}  {escape(event.title)}[/]")
        return

    if event.kind == "task.cancelled":
        body = f"[bold {theme.warning}]{theme.icon_cancelled}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _console.print()
        _console.print(Panel(body, border_style=theme.warning, padding=(0, 2)))
        _console.print()
        return

    if event.kind == "task.failed":
        body = f"[bold {theme.error}]{theme.icon_failed}  {escape(event.title)}[/]"
        if event.detail:
            body += f"\n\n[{theme.muted}]{escape(event.detail)}[/]"
        _console.print()
        _console.print(Panel(body, border_style=theme.error, padding=(0, 2)))
        _console.print()


def _emit(event: WorkflowEvent) -> None:
    sink = _active_sink()
    if sink is not None:
        sink.emit(event)
    else:
        _render_event(event)


# ── User-facing helpers ───────────────────────────────────────────────────


def header(subtitle: str | None = None) -> None:
    """Startup banner — uses the brand from the active UIContext."""
    ui = get_ui()
    if ui.brand.ascii_logo:
        _console.print()
        _console.print(Text(ui.brand.ascii_logo, style=ui.theme.brand, justify="center"))
    if subtitle:
        _console.print(
            Panel(
                f"[{ui.theme.muted}]{escape(subtitle)}[/]",
                border_style=ui.theme.accent_dim,
                padding=(0, 4),
            )
        )
    _console.print()


def phase(label: str) -> None:
    _emit(build_phase_event(label, context=get_workflow_context()))


def step(label: str, detail: str = "") -> None:
    _emit(build_task_event(
        kind="task.running", title=label, detail=detail,
        context=get_workflow_context(),
    ))


def success(label: str, detail: str = "") -> None:
    _emit(build_task_event(
        kind="task.completed", title=label, detail=detail,
        context=get_workflow_context(),
    ))


def warning(label: str) -> None:
    _emit(build_task_event(kind="task.warning", title=label, context=get_workflow_context()))


def skip(label: str) -> None:
    _emit(build_task_event(kind="task.skipped", title=label, context=get_workflow_context()))


def fail(label: str, detail: str = "") -> None:
    _emit(build_task_event(
        kind="task.failed", title=label, detail=detail,
        context=get_workflow_context(),
    ))


def workflow_log(message: str, *, stream: str = "stdout", context: WorkflowContext | None = None) -> None:
    _emit(build_log_event(line=message, stream=stream, context=context or get_workflow_context()))


def _child_context(*, task_id: str, parent_task_id: str | None, context: WorkflowContext | None) -> WorkflowContext:
    active = context or get_workflow_context() or WorkflowContext()
    resolved_parent = parent_task_id
    if resolved_parent is None:
        resolved_parent = active.task_id or active.parent_task_id
    return WorkflowContext(
        flow_id=active.flow_id,
        flow_run_id=active.flow_run_id,
        task_id=task_id,
        parent_task_id=resolved_parent,
        task_run_id=active.task_run_id,
    )


@_contextmanager
def status(label: str):
    """Spinner context — routes to active sink's status if present, else
    falls back to Rich's console.status."""
    sink = _active_sink()
    if sink is not None:
        with sink.status(label):
            yield
        return
    with _console.status(f"[{get_ui().theme.accent}]{escape(label)}…[/]", spinner="dots"):
        yield


@_contextmanager
def workflow_step(
    *,
    task_id: str,
    title: str,
    parent_task_id: str | None = None,
    detail: str = "",
    context: WorkflowContext | None = None,
):
    """Emit running → completed (or failed) around a block of work."""
    child = _child_context(task_id=task_id, parent_task_id=parent_task_id, context=context)
    _emit(build_task_event(
        kind="task.running", task_id=task_id, parent_task_id=child.parent_task_id,
        title=title, detail=detail, context=child,
    ))
    with bind_workflow_context(child):
        try:
            yield child
        except Exception as exc:
            _emit(build_task_event(
                kind="task.failed", task_id=task_id, parent_task_id=child.parent_task_id,
                title=title, detail=detail or str(exc), context=child,
            ))
            raise
        else:
            _emit(build_task_event(
                kind="task.completed", task_id=task_id, parent_task_id=child.parent_task_id,
                title=title, detail=detail, context=child,
            ))
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_workflow_render.py -v
```

Expected: 16 passed.

If any of the 8 golden-snapshot tests fails, the actual difference reveals a fidelity gap between the new renderer and the legacy one. Capture the actual output:

```bash
cd tools/tui-toolkit && uv run pytest tests/test_workflow_render.py::test_render_completed_matches_legacy_golden -v
```

The pytest `assert actual == expected` diff shows the offending characters. Fix the renderer until the snapshot matches.

- [ ] **Step 5: Run all library tests so far**

```bash
cd tools/tui-toolkit && uv run pytest -v
```

Expected: all 50+ green.

- [ ] **Step 6: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/workflow.py tools/tui-toolkit/tests/test_workflow_render.py
git commit -m "tui-toolkit: add workflow renderer + user-facing helpers"
```

---

### Task 1.9: `pickers.py` — `select`, `multiselect`, `Choice`, `Separator`

This task ports `tui_widgets.py` (522 LOC). The internal builders (`_build_described_select_application`, `_build_described_checkbox_application`) move verbatim — their logic is correct already; the change is that they now read the questionary `Style` from `to_questionary_style(get_ui().theme)` and the brand strings from `get_ui().brand`.

**Files:**
- Create: `tools/tui-toolkit/src/tui_toolkit/pickers.py`
- Create: `tools/tui-toolkit/tests/test_pickers.py`

- [ ] **Step 1: Write the test file**

Path: `tools/tui-toolkit/tests/test_pickers.py`

```python
"""Tests for tui_toolkit.pickers — select, multiselect, Choice, Separator."""
from __future__ import annotations

from unittest.mock import patch

import pytest
import questionary

from tui_toolkit.brand import AppBrand
from tui_toolkit.context import UIContext, bind_ui
from tui_toolkit.pickers import Choice, Separator, multiselect, select
from tui_toolkit.theme import Theme


def test_choice_dataclass_basic():
    c = Choice(title="Title", value="v", description="desc")
    assert c.title == "Title"
    assert c.value == "v"
    assert c.description == "desc"


def test_choice_default_description_is_empty():
    c = Choice(title="t", value="v")
    assert c.description == ""


def test_separator_re_export():
    assert Separator is questionary.Separator


def test_select_non_tty_falls_back_to_questionary(monkeypatch):
    """When stdin is not a TTY, select() uses questionary.select instead of
    the prompt_toolkit description-panel app."""
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)
    captured: dict = {}

    def fake_select(message, **kwargs):
        captured["message"] = message
        captured["choices"] = kwargs.get("choices")
        captured["default"] = kwargs.get("default")
        captured["style"] = kwargs.get("style")
        class _Q:
            def ask(self):
                return "v1"
        return _Q()

    monkeypatch.setattr(questionary, "select", fake_select)

    result = select(
        "Pick one",
        choices=[Choice("Title 1", "v1", "desc1"), Choice("Title 2", "v2", "desc2")],
    )
    assert result == "v1"
    assert captured["message"] == "Pick one"
    assert captured["style"] is not None  # the theme-derived questionary Style was passed


def test_select_with_back_choice_appends_back_option(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    captured: dict = {}

    def fake_select(message, **kwargs):
        captured["choices"] = kwargs["choices"]
        class _Q:
            def ask(self):
                return "v1"
        return _Q()

    monkeypatch.setattr(questionary, "select", fake_select)
    select(
        "Pick one",
        choices=[Choice("Title 1", "v1")],
        include_back=True,
    )
    # Last entry must be the back choice (after a Separator).
    last = captured["choices"][-1]
    # questionary.Choice has .value — check a 'back' value somewhere
    values = [getattr(c, "value", None) for c in captured["choices"]]
    assert "back" in values


def test_multiselect_non_tty_falls_back_to_questionary(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    captured: dict = {}

    def fake_checkbox(message, **kwargs):
        captured["message"] = message
        captured["default"] = kwargs.get("default")
        class _Q:
            def ask(self):
                return ["v1", "v2"]
        return _Q()

    monkeypatch.setattr(questionary, "checkbox", fake_checkbox)
    result = multiselect(
        "Pick many",
        choices=[Choice("T1", "v1"), Choice("T2", "v2")],
        default_values=["v1"],
    )
    assert result == ["v1", "v2"]
    assert captured["default"] == ["v1"]


def test_select_empty_choices_raises():
    with pytest.raises(ValueError, match="choices"):
        select("x", choices=[])


def test_multiselect_empty_choices_raises():
    with pytest.raises(ValueError, match="choices"):
        multiselect("x", choices=[])


def test_select_keyboard_interrupt_when_questionary_returns_none(monkeypatch):
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    class _Q:
        def ask(self):
            return None  # user pressed Ctrl-C

    monkeypatch.setattr(questionary, "select", lambda *a, **kw: _Q())
    with pytest.raises(KeyboardInterrupt):
        select("x", choices=[Choice("t", "v")])


def test_select_uses_theme_via_to_questionary_style(monkeypatch):
    """Theme override must propagate into the questionary Style used by select."""
    monkeypatch.setattr("sys.stdin.isatty", lambda: False)
    monkeypatch.setattr("sys.stdout.isatty", lambda: False)

    captured: dict = {}

    def fake_select(message, **kwargs):
        captured["style"] = kwargs["style"]
        class _Q:
            def ask(self):
                return "v"
        return _Q()

    monkeypatch.setattr(questionary, "select", fake_select)
    with bind_ui(UIContext(theme=Theme(accent="green", accent_strong="bold green"))):
        select("x", choices=[Choice("t", "v")])

    rules = dict(captured["style"].style.style_rules)
    assert rules["selected"] == "fg:green"
    assert rules["pointer"] == "fg:green bold"
```

- [ ] **Step 2: Run, confirm failure**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_pickers.py -v
```

Expected: `ImportError`.

- [ ] **Step 3: Implement `pickers.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/pickers.py`

The implementation closely follows `tools/controlplane/src/controlplane_tool/tui_widgets.py` — the prompt_toolkit picker code is unchanged in structure. The differences are:
- `_STYLE` is replaced by `to_questionary_style(get_ui().theme)`.
- `APP_ASCII_LOGO` / `APP_WORDMARK` are replaced by `get_ui().brand.ascii_logo` / `get_ui().brand.wordmark`.
- The four legacy entry points collapse into two public functions: `select` and `multiselect`.
- `_DescribedChoice` is replaced by the public `Choice` dataclass.

```python
"""Pickers — select() and multiselect() with a description side panel.

Ported from tools/controlplane/src/controlplane_tool/tui_widgets.py. The
prompt_toolkit-driven full-screen picker is preserved verbatim; the
adapter layer reads theme + brand from the active UIContext.

Non-TTY environments fall back to plain questionary prompts.
"""
from __future__ import annotations

import sys
from dataclasses import dataclass
from typing import Any

import questionary
from prompt_toolkit.application import Application
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.keys import Keys
from prompt_toolkit.layout import Dimension, Layout
from prompt_toolkit.layout.containers import HSplit, VSplit, Window
from prompt_toolkit.layout.controls import FormattedTextControl
from prompt_toolkit.widgets import CheckboxList, Frame
from questionary.prompts.common import Choice as _QChoice, InquirerControl

from tui_toolkit.console import get_content_width
from tui_toolkit.context import get_ui
from tui_toolkit.theme import to_questionary_style


@dataclass(frozen=True, slots=True)
class Choice:
    title: str
    value: str
    description: str = ""


Separator = questionary.Separator

_BACK_VALUE = "back"
_SELECTOR_MIN_WIDTH = 48
_DESCRIPTION_MIN_WIDTH = 40
_PANEL_WEIGHT = 1


# ── normalization ─────────────────────────────────────────────────────────


def _normalize_choice(choice: Any) -> Any:
    if isinstance(choice, Choice):
        return questionary.Choice(choice.title, choice.value, description=choice.description)
    if isinstance(choice, questionary.Separator):
        return choice
    if isinstance(choice, _QChoice):
        return choice
    return _QChoice.build(choice)


def _normalize_choices(choices: list[Any]) -> list[Any]:
    return [_normalize_choice(c) for c in choices]


def _back_choice() -> questionary.Choice:
    return questionary.Choice("back — return to previous menu", _BACK_VALUE)


def _with_back(choices: list[Any]) -> list[Any]:
    if any(getattr(c, "value", c) == _BACK_VALUE for c in choices):
        return choices
    return [*choices, questionary.Separator(), _back_choice()]


# ── header / footer fragments ─────────────────────────────────────────────


def _screen_title(message: str) -> str:
    return message.rstrip(" ?:") or "Menu"


def _screen_breadcrumb(screen_title: str, default_breadcrumb: str) -> str:
    return f"{default_breadcrumb} / {screen_title}"


def _screen_footer(default_footer_hint: str) -> str:
    return default_footer_hint


def _select_prompt_fragments(message: str) -> list[tuple[str, str]]:
    return [
        ("class:qmark", "?"),
        ("class:question", f" {message} "),
        ("class:instruction", "(Use arrow keys, Enter to confirm)"),
    ]


def _description_fragments(control: InquirerControl) -> list[tuple[str, str]]:
    current = control.get_pointed_at()
    return [("class:text", getattr(current, "description", "") or "")]


def _checkbox_description_fragments(checkbox_list: CheckboxList, choices: list[Any]) -> list[tuple[str, str]]:
    selected_index = getattr(checkbox_list, "_selected_index", 0)
    current = choices[selected_index]
    description = getattr(current, "description", "") or ""
    selected_count = len(getattr(checkbox_list, "current_values", []))
    return [
        ("class:text", description),
        ("", "\n\n"),
        ("class:instruction", f"Space toggle | Enter confirm | Selected: {selected_count}"),
    ]


def _build_header_block(*, message: str, screen_title: str, screen_breadcrumb: str) -> HSplit:
    brand = get_ui().brand
    ascii_logo = brand.ascii_logo
    wordmark = brand.wordmark
    logo_lines = ascii_logo.count("\n") + 1 if ascii_logo else 0

    children: list[Window] = []
    if ascii_logo:
        children.append(Window(
            height=logo_lines,
            content=FormattedTextControl(lambda: [("class:brand", ascii_logo)]),
        ))
    children.append(Window(
        height=1,
        content=FormattedTextControl(lambda: [
            ("class:brand", wordmark),
            ("class:text", f"  {screen_title}" if wordmark else screen_title),
        ]),
    ))
    children.append(Window(
        height=1,
        content=FormattedTextControl(lambda: [("class:breadcrumb", screen_breadcrumb)]),
    ))
    children.append(Window(
        height=1,
        content=FormattedTextControl(lambda: _select_prompt_fragments(message)),
    ))
    return HSplit(children)


# ── full-screen select application ────────────────────────────────────────


def _build_select_application(
    message: str,
    choices: list[Any],
    *,
    default: str | None,
    title: str | None,
    breadcrumb: str | None,
    footer_hint: str | None,
    input=None,
    output=None,
) -> Application:
    brand = get_ui().brand
    style = to_questionary_style(get_ui().theme)
    normalized = _normalize_choices(choices)
    screen_title = title or _screen_title(message)
    screen_breadcrumb = breadcrumb or _screen_breadcrumb(screen_title, brand.default_breadcrumb)
    screen_footer = footer_hint or _screen_footer(brand.default_footer_hint)

    control = InquirerControl(
        normalized, default=default, initial_choice=default,
        use_indicator=False, show_selected=False, show_description=False,
        use_arrow_keys=True,
    )

    def _down() -> None:
        control.select_next()
        while not control.is_selection_valid():
            control.select_next()

    def _up() -> None:
        control.select_previous()
        while not control.is_selection_valid():
            control.select_previous()

    bindings = KeyBindings()

    @bindings.add(Keys.ControlQ, eager=True)
    @bindings.add(Keys.ControlC, eager=True)
    @bindings.add("escape", eager=True)
    def _cancel(event):
        event.app.exit(result=None)

    @bindings.add(Keys.Down, eager=True)
    @bindings.add("j", eager=True)
    @bindings.add(Keys.ControlN, eager=True)
    def _go_down(event):
        _down()

    @bindings.add(Keys.Up, eager=True)
    @bindings.add("k", eager=True)
    @bindings.add(Keys.ControlP, eager=True)
    def _go_up(event):
        _up()

    @bindings.add(Keys.ControlM, eager=True)
    def _accept(event):
        event.app.exit(result=control.get_pointed_at().value)

    @bindings.add(Keys.Any)
    def _ignore(event):
        pass

    header_block = _build_header_block(
        message=message, screen_title=screen_title, screen_breadcrumb=screen_breadcrumb,
    )
    selector = Window(
        content=control, dont_extend_height=True,
        width=Dimension(weight=_PANEL_WEIGHT, min=_SELECTOR_MIN_WIDTH),
    )
    description = Frame(
        Window(
            content=FormattedTextControl(lambda: _description_fragments(control)),
            wrap_lines=True, dont_extend_height=False,
        ),
        title="Description",
        width=Dimension(weight=_PANEL_WEIGHT, min=_DESCRIPTION_MIN_WIDTH),
    )
    body = VSplit(
        [selector, description],
        padding=2, width=Dimension(preferred=get_content_width()),
    )

    return Application(
        layout=Layout(
            HSplit([
                header_block, body,
                Window(height=1, content=FormattedTextControl(lambda: [("class:footer", screen_footer)])),
            ]),
            focused_element=selector,
        ),
        key_bindings=bindings,
        full_screen=True,
        style=style,
        mouse_support=False,
        input=input,
        output=output,
    )


# ── full-screen multiselect application ───────────────────────────────────


def _build_multiselect_application(
    message: str,
    choices: list[Any],
    *,
    default_values: list[str] | None,
    title: str | None,
    breadcrumb: str | None,
    footer_hint: str | None,
    input=None,
    output=None,
) -> Application:
    brand = get_ui().brand
    style = to_questionary_style(get_ui().theme)
    normalized = _normalize_choices(choices)
    if not normalized:
        raise ValueError("choices must not be empty")

    screen_title = title or _screen_title(message)
    screen_breadcrumb = breadcrumb or _screen_breadcrumb(screen_title, brand.default_breadcrumb)
    screen_footer = footer_hint or "Space toggle | Enter confirm | Esc cancel | Ctrl+C exit"

    checkbox_list = CheckboxList(
        values=[(c.value, c.title) for c in normalized],
        default_values=default_values,
    )
    bindings = KeyBindings()

    def _move(delta: int) -> None:
        total = len(checkbox_list.values)
        checkbox_list._selected_index = (checkbox_list._selected_index + delta) % total

    def _toggle() -> None:
        cv = checkbox_list.values[checkbox_list._selected_index][0]
        if cv in checkbox_list.current_values:
            checkbox_list.current_values = [v for v in checkbox_list.current_values if v != cv]
            return
        checkbox_list.current_values = [*checkbox_list.current_values, cv]

    @bindings.add(Keys.ControlQ, eager=True)
    @bindings.add(Keys.ControlC, eager=True)
    @bindings.add("escape", eager=True)
    def _cancel(event):
        event.app.exit(result=None)

    @bindings.add(Keys.Down, eager=True)
    @bindings.add("j", eager=True)
    @bindings.add(Keys.ControlN, eager=True)
    def _down(event):
        _move(1)

    @bindings.add(Keys.Up, eager=True)
    @bindings.add("k", eager=True)
    @bindings.add(Keys.ControlP, eager=True)
    def _up(event):
        _move(-1)

    @bindings.add(" ", eager=True)
    def _toggle_key(event):
        _toggle()

    @bindings.add(Keys.ControlM, eager=True)
    def _accept(event):
        ordered = [v for v, _ in checkbox_list.values if v in checkbox_list.current_values]
        event.app.exit(result=ordered)

    header_block = _build_header_block(
        message=message, screen_title=screen_title, screen_breadcrumb=screen_breadcrumb,
    )
    selector = Frame(
        checkbox_list, title="Select",
        width=Dimension(weight=_PANEL_WEIGHT, min=_SELECTOR_MIN_WIDTH),
    )
    description = Frame(
        Window(
            content=FormattedTextControl(lambda: _checkbox_description_fragments(checkbox_list, normalized)),
            wrap_lines=True, dont_extend_height=False,
        ),
        title="Description",
        width=Dimension(weight=_PANEL_WEIGHT, min=_DESCRIPTION_MIN_WIDTH),
    )
    body = VSplit(
        [selector, description],
        padding=2, width=Dimension(preferred=get_content_width()),
    )

    return Application(
        layout=Layout(
            HSplit([
                header_block, body,
                Window(height=1, content=FormattedTextControl(lambda: [("class:footer", screen_footer)])),
            ]),
            focused_element=checkbox_list,
        ),
        key_bindings=bindings,
        full_screen=True,
        style=style,
        mouse_support=False,
        input=input,
        output=output,
    )


# ── public API ────────────────────────────────────────────────────────────


def _ask(prompt_fn):
    result = prompt_fn()
    if result is None:
        raise KeyboardInterrupt
    return result


def select(
    message: str,
    *,
    choices: list[Any],
    default: str | None = None,
    include_back: bool = False,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> str:
    """Interactive single-select picker with a description side panel.

    Falls back to questionary.select() when stdin or stdout is not a TTY.
    Pressing Ctrl-C / Esc raises KeyboardInterrupt.
    """
    if include_back:
        choices = _with_back(list(choices))
    if not choices:
        raise ValueError("choices must not be empty")

    style = to_questionary_style(get_ui().theme)

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return _ask(lambda: questionary.select(
            message, choices=_normalize_choices(choices), default=default,
            style=style, show_description=True,
        ).ask())

    app = _build_select_application(
        message, choices,
        default=default, title=title, breadcrumb=breadcrumb, footer_hint=footer_hint,
    )
    result = app.run()
    if result is None:
        raise KeyboardInterrupt
    return result


def multiselect(
    message: str,
    *,
    choices: list[Any],
    default_values: list[str] | None = None,
    title: str | None = None,
    breadcrumb: str | None = None,
    footer_hint: str | None = None,
) -> list[str]:
    """Interactive multi-select picker with a description side panel."""
    if not choices:
        raise ValueError("choices must not be empty")

    style = to_questionary_style(get_ui().theme)

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return _ask(lambda: questionary.checkbox(
            message, choices=_normalize_choices(choices),
            default=default_values, style=style,
        ).ask())

    app = _build_multiselect_application(
        message, choices,
        default_values=default_values, title=title, breadcrumb=breadcrumb, footer_hint=footer_hint,
    )
    result = app.run()
    if result is None:
        raise KeyboardInterrupt
    return result
```

- [ ] **Step 4: Run, confirm pass**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_pickers.py -v
```

Expected: 9 passed.

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/pickers.py tools/tui-toolkit/tests/test_pickers.py
git commit -m "tui-toolkit: add select / multiselect pickers with theme integration"
```

---

### Task 1.10: `__init__.py` — public API surface

**Files:**
- Modify: `tools/tui-toolkit/src/tui_toolkit/__init__.py`
- Create: `tools/tui-toolkit/tests/test_public_api.py`

- [ ] **Step 1: Write the test**

Path: `tools/tui-toolkit/tests/test_public_api.py`

```python
"""Verify the curated public API of tui_toolkit is complete and importable."""


def test_public_api_imports():
    import tui_toolkit as tt

    # theming + setup
    assert callable(tt.init_ui)
    assert callable(tt.get_ui)
    assert tt.UIContext is not None
    assert tt.Theme is not None
    assert tt.DEFAULT_THEME is not None
    assert tt.AppBrand is not None
    assert tt.DEFAULT_BRAND is not None
    assert callable(tt.bind_ui)

    # rendering primitives
    assert tt.console is not None
    assert callable(tt.get_content_width)
    assert callable(tt.render_screen_frame)

    # pickers
    assert callable(tt.select)
    assert callable(tt.multiselect)
    assert tt.Choice is not None
    assert tt.Separator is not None

    # workflow events + helpers
    assert callable(tt.header)
    assert callable(tt.phase)
    assert callable(tt.step)
    assert callable(tt.success)
    assert callable(tt.warning)
    assert callable(tt.skip)
    assert callable(tt.fail)
    assert callable(tt.workflow_log)
    assert callable(tt.workflow_step)
    assert callable(tt.status)
    assert callable(tt.bind_workflow_sink)
    assert callable(tt.bind_workflow_context)
    assert callable(tt.get_workflow_context)
    assert callable(tt.has_workflow_sink)
    assert callable(tt.build_log_event)
    assert callable(tt.build_phase_event)
    assert callable(tt.build_task_event)
    assert tt.WorkflowEvent is not None
    assert tt.WorkflowContext is not None
    assert tt.WorkflowSink is not None
```

- [ ] **Step 2: Replace the placeholder `__init__.py`**

Path: `tools/tui-toolkit/src/tui_toolkit/__init__.py`

```python
"""tui-toolkit — terminal UI widgets and workflow event renderer.

Single-source-of-truth theming via Theme + AppBrand + UIContext. Configure
once at startup with init_ui(); every widget reads the active context.
"""
from __future__ import annotations

__version__ = "0.1.0"

# theming + setup
from tui_toolkit.brand import AppBrand, DEFAULT_BRAND
from tui_toolkit.context import UIContext, bind_ui, get_ui, init_ui
from tui_toolkit.theme import DEFAULT_THEME, Theme

# rendering primitives
from tui_toolkit.chrome import render_screen_frame
from tui_toolkit.console import console, get_content_width

# pickers
from tui_toolkit.pickers import Choice, Separator, multiselect, select

# workflow events
from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink
from tui_toolkit.workflow import (
    bind_workflow_context,
    bind_workflow_sink,
    build_log_event,
    build_phase_event,
    build_task_event,
    fail,
    get_workflow_context,
    has_workflow_sink,
    header,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
    workflow_step,
)

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
    # workflow events
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
    "bind_workflow_context", "bind_workflow_sink",
    "build_log_event", "build_phase_event", "build_task_event",
    "fail", "get_workflow_context", "has_workflow_sink",
    "header", "phase", "skip", "status", "step", "success", "warning",
    "workflow_log", "workflow_step",
]
```

- [ ] **Step 3: Run all library tests**

```bash
cd tools/tui-toolkit && uv run pytest -v
```

Expected: every test green, including the new `test_public_api.py`.

- [ ] **Step 4: Verify no smoke test was deleted**

```bash
cd tools/tui-toolkit && uv run pytest tests/test_smoke.py -v
```

Expected: 1 passed.

- [ ] **Step 5: Commit**

```bash
git add tools/tui-toolkit/src/tui_toolkit/__init__.py tools/tui-toolkit/tests/test_public_api.py
git commit -m "tui-toolkit: curate public API in __init__.py"
```

---

## Phase 2 — Nanofaas integration via shims (PR1 close-out)

### Task 2.1: Create `controlplane_tool/ui_setup.py`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/ui_setup.py`

This is the only place in nanofaas after PR2 that knows the brand and theme.

- [ ] **Step 1: Read the legacy ASCII logo from `tui_chrome.py` for reference**

```bash
cat tools/controlplane/src/controlplane_tool/tui_chrome.py
```

Note the literal `APP_ASCII_LOGO` block — it is the 6-line nanofaas logo that goes verbatim into `NANOFAAS_BRAND.ascii_logo`.

- [ ] **Step 2: Create `ui_setup.py`**

Path: `tools/controlplane/src/controlplane_tool/ui_setup.py`

```python
"""Theme + brand configuration for the nanofaas control-plane tool.

This is the single source of truth for the visual identity. Every widget
in tui-toolkit reads from the active UIContext set up by setup_ui().
"""
from __future__ import annotations

from tui_toolkit import AppBrand, Theme, UIContext, init_ui

NANOFAAS_THEME = Theme()  # the cyan default already matches the historical palette

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
    """Install the nanofaas theme and brand. Idempotent. Call once at startup."""
    return init_ui(UIContext(theme=NANOFAAS_THEME, brand=NANOFAAS_BRAND))
```

- [ ] **Step 3: Verify imports resolve**

```bash
cd tools/controlplane && uv run python -c "from controlplane_tool.ui_setup import setup_ui, NANOFAAS_BRAND; print(NANOFAAS_BRAND.wordmark)"
```

Expected: `NANOFAAS`.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/ui_setup.py
git commit -m "controlplane: add ui_setup with NANOFAAS_THEME, NANOFAAS_BRAND"
```

---

### Task 2.2: Shim `controlplane_tool/tui_chrome.py`

The legacy file becomes a thin re-export. Existing consumers that import `APP_ASCII_LOGO`, `APP_WORDMARK`, `APP_BRAND`, `DEFAULT_BREADCRUMB`, `DEFAULT_FOOTER_HINT`, or `render_screen_frame` keep working unchanged.

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_chrome.py` (full rewrite)

- [ ] **Step 1: Read the current file to confirm exported names**

```bash
cat tools/controlplane/src/controlplane_tool/tui_chrome.py
```

- [ ] **Step 2: Rewrite `tui_chrome.py` as a shim**

Path: `tools/controlplane/src/controlplane_tool/tui_chrome.py`

```python
"""SHIM — moved to tui_toolkit.chrome and controlplane_tool.ui_setup.

This file will be deleted in PR2. New code should import from tui_toolkit
directly, and brand strings from controlplane_tool.ui_setup.NANOFAAS_BRAND.
"""
from __future__ import annotations

from tui_toolkit import render_screen_frame

from controlplane_tool.ui_setup import NANOFAAS_BRAND

APP_WORDMARK = NANOFAAS_BRAND.wordmark
APP_ASCII_LOGO = NANOFAAS_BRAND.ascii_logo
APP_BRAND = APP_WORDMARK
DEFAULT_BREADCRUMB = NANOFAAS_BRAND.default_breadcrumb
DEFAULT_FOOTER_HINT = NANOFAAS_BRAND.default_footer_hint

__all__ = [
    "APP_ASCII_LOGO", "APP_WORDMARK", "APP_BRAND",
    "DEFAULT_BREADCRUMB", "DEFAULT_FOOTER_HINT",
    "render_screen_frame",
]
```

- [ ] **Step 3: Verify importable**

```bash
cd tools/controlplane && uv run python -c "from controlplane_tool.tui_chrome import APP_ASCII_LOGO, APP_WORDMARK, render_screen_frame; print(APP_WORDMARK)"
```

Expected: `NANOFAAS`.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_chrome.py
git commit -m "controlplane: shim tui_chrome through tui_toolkit"
```

---

### Task 2.3: Shim `controlplane_tool/tui_widgets.py`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_widgets.py` (full rewrite)

The legacy file exports four underscored "private" helpers that 25+ consumers actually use as public API: `_select_value`, `_checkbox_values`, `_select_described_value`, `_select_described_checkbox_values`. The shim aliases them to the new names. PR2 renames the call sites.

- [ ] **Step 1: List the names that consumers import**

```bash
grep -rn "from controlplane_tool.tui_widgets import" tools/controlplane/src tools/controlplane/tests --include="*.py" | grep -v __pycache__
```

Confirm the names in use are some subset of: `_select_value`, `_checkbox_values`, `_select_described_value`, `_select_described_checkbox_values`, `_DescribedChoice`.

- [ ] **Step 2: Rewrite `tui_widgets.py` as a shim**

Path: `tools/controlplane/src/controlplane_tool/tui_widgets.py`

```python
"""SHIM — moved to tui_toolkit.pickers.

This file will be deleted in PR2. New code should import select/multiselect
from tui_toolkit directly.
"""
from __future__ import annotations

from tui_toolkit import Choice as _DescribedChoice  # legacy alias
from tui_toolkit import multiselect as _checkbox_values
from tui_toolkit import select as _select_value
from tui_toolkit.pickers import multiselect as _select_described_checkbox_values
from tui_toolkit.pickers import select as _select_described_value

__all__ = [
    "_select_value",
    "_checkbox_values",
    "_select_described_value",
    "_select_described_checkbox_values",
    "_DescribedChoice",
]
```

Note: `_select_value` and `_select_described_value` are the same function in the new world (the legacy "described" variant collapsed into the unified `select`). Same for `_checkbox_values` / `_select_described_checkbox_values`. Aliasing both to `select` / `multiselect` is intentional — the legacy callers that pass description-bearing choices already get the description panel; the legacy callers that don't, don't.

- [ ] **Step 3: Verify importable**

```bash
cd tools/controlplane && uv run python -c "from controlplane_tool.tui_widgets import _select_value, _checkbox_values, _DescribedChoice; print(_DescribedChoice('t', 'v', 'd'))"
```

Expected: prints a `Choice(title='t', value='v', description='d')` object without errors.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_widgets.py
git commit -m "controlplane: shim tui_widgets through tui_toolkit"
```

---

### Task 2.4: Shim `controlplane_tool/console.py`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/console.py` (full rewrite)

The legacy `console.py` is the most heavily consumed module — 25+ callers across runners, tests, and the TUI. The shim re-exports every public symbol plus the underscored helpers (`_workflow_context`, `_workflow_sink`) and the back-compat `init_ui_width`.

- [ ] **Step 1: Inspect the current public surface to make sure nothing is missed**

```bash
grep -n "^def \|^class \|^[A-Z_]* = " tools/controlplane/src/controlplane_tool/console.py
```

Expected output names (from the spec section): `init_ui_width`, `get_content_width`, `bind_workflow_sink`, `bind_workflow_context`, `has_workflow_sink`, `_workflow_sink`, `_workflow_context`, `workflow_log`, `header`, `phase`, `step`, `workflow_step`, `success`, `warning`, `skip`, `fail`, `status`, `console`, `_render_event`, `_emit_workflow_event`, `_child_workflow_context`.

- [ ] **Step 2: Rewrite `console.py` as a shim**

Path: `tools/controlplane/src/controlplane_tool/console.py`

```python
"""SHIM — moved to tui_toolkit.

This file will be deleted in PR2. New code should import directly:

    from tui_toolkit import console, phase, step, success, warning, skip, fail
    from tui_toolkit import status, workflow_log, workflow_step, header
    from tui_toolkit import bind_workflow_sink, bind_workflow_context
    from tui_toolkit import get_workflow_context, has_workflow_sink
    from tui_toolkit import get_content_width
"""
from __future__ import annotations

from tui_toolkit import (
    bind_workflow_context,
    bind_workflow_sink,
    console,
    fail,
    get_content_width,
    get_workflow_context,
    has_workflow_sink,
    header,
    init_ui,
    phase,
    skip,
    status,
    step,
    success,
    warning,
    workflow_log,
    workflow_step,
)
from tui_toolkit.workflow import _active_sink as _workflow_sink_getter
from tui_toolkit.workflow import _emit as _emit_workflow_event
from tui_toolkit.workflow import _render_event

# ── back-compat aliases for the old underscored helpers ──────────────────


def _workflow_context():
    """Legacy alias — returns the active workflow context (or None)."""
    return get_workflow_context()


def _workflow_sink():
    """Legacy alias — returns the active workflow sink (or None)."""
    return _workflow_sink_getter()


def init_ui_width() -> None:
    """Legacy alias — calls tui_toolkit.init_ui() with the active UIContext.

    The brand and theme are picked up from controlplane_tool.ui_setup once
    main() has called setup_ui(). When init_ui_width() is called *before*
    setup_ui() (e.g., by a unit test), this falls back to the default theme
    and brand.
    """
    init_ui()


__all__ = [
    "console",
    "get_content_width",
    "init_ui_width",
    "header", "phase", "step", "success", "warning", "skip", "fail",
    "status", "workflow_log", "workflow_step",
    "bind_workflow_sink", "bind_workflow_context",
    "has_workflow_sink",
    "_workflow_sink", "_workflow_context",
    "_render_event", "_emit_workflow_event",
]
```

- [ ] **Step 3: Verify importable**

```bash
cd tools/controlplane && uv run python -c "from controlplane_tool.console import console, phase, step, success, _workflow_context, _workflow_sink, init_ui_width; print('ok')"
```

Expected: `ok`.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/console.py
git commit -m "controlplane: shim console through tui_toolkit"
```

---

### Task 2.5: Shim `controlplane_tool/workflow_models.py`

The non-UI dataclasses (`TuiPhaseSnapshot`, `TuiWorkflowSnapshot`, `WorkflowRun`, `TaskDefinition`, `TaskRun`, `WorkflowState`, `utc_now`) stay here. The three moved types (`WorkflowEvent`, `WorkflowContext`, `WorkflowSink`) are re-exported from `tui_toolkit.events`.

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/workflow_models.py`

- [ ] **Step 1: Edit the file to drop the moved types and re-export from tui_toolkit**

Path: `tools/controlplane/src/controlplane_tool/workflow_models.py`

Replace the entire file content with:

```python
"""Workflow data models for the nanofaas control-plane tool.

Generic UI-side types (WorkflowEvent, WorkflowContext, WorkflowSink) live in
tui_toolkit.events and are re-exported here for backward compatibility. They
will continue to be re-exported indefinitely — the shimmed modules console.py,
tui_widgets.py, tui_chrome.py are the ones removed in PR2.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Literal

from tui_toolkit.events import WorkflowContext, WorkflowEvent, WorkflowSink


def utc_now() -> datetime:
    return datetime.now(UTC)


WorkflowState = Literal["pending", "running", "success", "failed", "cancelled"]


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


@dataclass(slots=True, frozen=True)
class WorkflowRun:
    flow_id: str
    flow_run_id: str
    status: str = "pending"
    orchestrator_backend: str = "none"
    started_at: datetime | None = None
    finished_at: datetime | None = None


@dataclass(slots=True, frozen=True)
class TaskDefinition:
    task_id: str
    title: str = ""
    detail: str = ""


@dataclass(slots=True, frozen=True)
class TaskRun:
    flow_id: str
    task_id: str
    task_run_id: str
    status: str = "pending"
    title: str = ""
    detail: str = ""


__all__ = [
    "utc_now",
    "WorkflowState",
    "TuiPhaseSnapshot", "TuiWorkflowSnapshot",
    "WorkflowRun", "TaskDefinition", "TaskRun",
    # re-exported from tui_toolkit.events
    "WorkflowContext", "WorkflowEvent", "WorkflowSink",
]
```

Note: PR2 keeps this re-export — the goal of PR2 is to delete the three legacy UI shims (`console`, `tui_widgets`, `tui_chrome`), not `workflow_models` (which still owns nanofaas-specific types).

- [ ] **Step 2: Verify importable**

```bash
cd tools/controlplane && uv run python -c "from controlplane_tool.workflow_models import WorkflowEvent, WorkflowContext, WorkflowSink, WorkflowRun, TaskRun, TuiPhaseSnapshot; print('ok')"
```

Expected: `ok`.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/workflow_models.py
git commit -m "controlplane: re-export WorkflowEvent/Context/Sink from tui_toolkit"
```

---

### Task 2.6: Shim `controlplane_tool/workflow_events.py`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/workflow_events.py`

The three moved builders re-export from `tui_toolkit.workflow`. `normalize_task_state` (Prefect-specific) stays here.

- [ ] **Step 1: Rewrite the file**

Path: `tools/controlplane/src/controlplane_tool/workflow_events.py`

```python
"""Workflow event helpers for the control-plane tool.

The generic builders (build_task_event, build_phase_event, build_log_event)
are re-exported from tui_toolkit.workflow. The Prefect-specific
normalize_task_state stays here because the Prefect state→event mapping is
domain knowledge.
"""
from __future__ import annotations

from tui_toolkit.workflow import build_log_event, build_phase_event, build_task_event

from controlplane_tool.workflow_models import WorkflowContext, WorkflowEvent

_PREFECT_STATE_TO_EVENT_KIND = {
    "cancelled": "task.cancelled",
    "completed": "task.completed",
    "crashed": "task.failed",
    "failed": "task.failed",
    "pending": "task.pending",
    "running": "task.running",
    "scheduled": "task.pending",
}


def normalize_task_state(
    *,
    flow_id: str,
    task_id: str,
    state_name: str,
    flow_run_id: str | None = None,
    parent_task_id: str | None = None,
    task_run_id: str | None = None,
    title: str | None = None,
    detail: str = "",
    context: WorkflowContext | None = None,
) -> WorkflowEvent:
    """Map a Prefect state name to a WorkflowEvent kind, then build the event."""
    kind = _PREFECT_STATE_TO_EVENT_KIND.get(state_name.strip().lower(), "task.updated")
    active = context or WorkflowContext()
    resolved_flow_id = flow_id or active.flow_id
    resolved_flow_run_id = flow_run_id or active.flow_run_id
    resolved_task_id = task_id if task_id is not None else active.task_id
    resolved_parent_task_id = parent_task_id if parent_task_id is not None else active.parent_task_id
    resolved_task_run_id = task_run_id or active.task_run_id
    return WorkflowEvent(
        kind=kind,
        flow_id=resolved_flow_id,
        flow_run_id=resolved_flow_run_id,
        task_id=resolved_task_id,
        parent_task_id=resolved_parent_task_id,
        task_run_id=resolved_task_run_id,
        title=title or resolved_task_id or task_id,
        detail=detail or state_name,
    )


__all__ = [
    "build_log_event", "build_phase_event", "build_task_event",
    "normalize_task_state",
]
```

- [ ] **Step 2: Verify importable**

```bash
cd tools/controlplane && uv run python -c "from controlplane_tool.workflow_events import build_log_event, build_phase_event, build_task_event, normalize_task_state; print('ok')"
```

Expected: `ok`.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/workflow_events.py
git commit -m "controlplane: re-export event builders from tui_toolkit, keep normalize_task_state"
```

---

### Task 2.7: Switch `main.py` from `init_ui_width()` to `setup_ui()`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/main.py`

This is the only consumer file modified in PR1.

- [ ] **Step 1: Read the current main.py**

```bash
cat tools/controlplane/src/controlplane_tool/main.py
```

Locate the `def main()` body. The current code calls `init_ui_width()`.

- [ ] **Step 2: Replace `init_ui_width()` with `setup_ui()`**

In `tools/controlplane/src/controlplane_tool/main.py`, find:

```python
def main() -> None:
    from controlplane_tool.console import init_ui_width
    init_ui_width()
```

Replace with:

```python
def main() -> None:
    from controlplane_tool.ui_setup import setup_ui
    setup_ui()
```

Leave the rest of `main()` unchanged.

- [ ] **Step 3: Verify the CLI still launches**

```bash
cd tools/controlplane && uv run controlplane-tool --help | head -10
```

Expected: a `Typer`-style help output listing subcommands. No traceback.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/main.py
git commit -m "controlplane: bootstrap UI via tui_toolkit setup_ui"
```

---

### Task 2.8: Run all controlplane-tool tests

This is the main acceptance gate for PR1.

- [ ] **Step 1: Run the full test suite**

```bash
cd tools/controlplane && uv run pytest -v
```

Expected: all green. The shims must not change any observable behaviour for existing tests.

If a test fails, the failure message identifies the gap. Common categories:

| Failure | Likely cause | Fix |
|---|---|---|
| `ImportError: cannot import _select_value` | Shim missing a name | Add the name to the shim re-exports in `tui_widgets.py` |
| `AssertionError` on a Rich snapshot | Renderer drift between legacy and new theme | Re-record the golden snapshot — but only if the visible output is genuinely correct |
| `AttributeError: 'NoneType' has no attribute …` | `setup_ui()` hadn't been called when an event was rendered | Add a `setup_ui()` call in the test's fixture, OR rely on `DEFAULT_THEME` if the test doesn't care about brand |

- [ ] **Step 2: Run the tui-toolkit test suite too**

```bash
cd tools/tui-toolkit && uv run pytest -v
```

Expected: all green.

- [ ] **Step 3: No commit needed for this task** — it's a verification gate.

---

### Task 2.9: Manual smoke test of the TUI and dry-run scenarios

- [ ] **Step 1: Launch the interactive TUI and confirm visual identity**

```bash
cd tools/controlplane && uv run controlplane-tool tui
```

Expected: the cyan NANOFAAS banner appears at the top, menu navigation with arrow keys works, descriptions appear on the right panel, Esc exits cleanly.

Press Esc / Ctrl+C to exit.

- [ ] **Step 2: Run a dry-run scenario through the CLI**

```bash
cd tools/controlplane && uv run controlplane-tool e2e run k3s-junit-curl --dry-run
```

Expected: a structured plan output (Rich panels with `▸` step indicators, `✓` success markers, no traceback).

- [ ] **Step 3: Run a build dry-run**

```bash
cd tools/controlplane && uv run controlplane-tool build --profile core --dry-run
```

Expected: green panels, cyan accents, no failures.

- [ ] **Step 4: Verify a theme override works end-to-end**

Save this to a scratch file (do **not** commit it):

```python
# /tmp/theme_smoke.py
from tui_toolkit import Theme, AppBrand, UIContext, init_ui, success, fail, header
from controlplane_tool.ui_setup import NANOFAAS_BRAND

init_ui(UIContext(
    theme=Theme(accent="green", accent_strong="bold green", accent_dim="green dim",
                brand="bold green", success="blue"),
    brand=NANOFAAS_BRAND,
))
header("theme smoke")
success("everything green")
fail("but errors are red still", detail="this should be red")
```

Run it:

```bash
cd tools/controlplane && uv run python /tmp/theme_smoke.py
```

Expected: the NANOFAAS banner is **green** (not cyan), the success panel is **blue**, the failure panel is **red**. This proves the theme override propagates uniformly.

- [ ] **Step 5: Clean up the scratch file**

```bash
rm /tmp/theme_smoke.py
```

- [ ] **Step 6: No commit needed** — manual verification.

---

### Task 2.10: PR1 close-out — sanity check, then ready for review

- [ ] **Step 1: Verify the diff makes sense**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git log --oneline main..HEAD
```

Expected: a tidy sequence of commits, each scoped to one module or one shim.

- [ ] **Step 2: Verify no consumer file outside of `main.py`, `ui_setup.py`, the shims, and `workflow_models.py`/`workflow_events.py` has been touched**

```bash
git diff main..HEAD --stat tools/controlplane/src/controlplane_tool/ | grep -v '^\(.*console\.py\|.*tui_widgets\.py\|.*tui_chrome\.py\|.*workflow_models\.py\|.*workflow_events\.py\|.*ui_setup\.py\|.*main\.py\)'
```

Expected: empty output. If there are files listed, audit them and revert any unintended changes.

- [ ] **Step 3: Run gitnexus impact analysis on the symbols the shims expose**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
npx gitnexus analyze
```

Then in the conversation, ask GitNexus:
- `gitnexus_impact({target: "select", direction: "upstream"})` — confirm direct callers all live inside `tui_toolkit` or under `controlplane_tool/tui_*` (which are still on the legacy names).
- `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` — confirm the change set matches the expected files.

- [ ] **Step 4: Push the branch and open PR1**

```bash
git push -u origin codex/ansible-vm-provisioning
gh pr create --title "tui-toolkit extraction (PR1: library + shims)" --body "$(cat <<'EOF'
## Summary
- Extract questionary/Rich/prompt_toolkit widgets and the workflow event renderer from `tools/controlplane/` into a new local library `tools/tui-toolkit/`.
- Unify theming via a single `Theme` + `AppBrand` model. The cyan palette is preserved as `DEFAULT_THEME`.
- The legacy import paths `controlplane_tool.console`, `tui_widgets`, `tui_chrome` are kept as thin re-export shims; no consumer code outside `main.py` is modified in this PR.
- `setup_ui()` in `controlplane_tool.ui_setup` is now the single point of UI configuration. Changing the palette is a one-file edit.

## Test plan
- [ ] `cd tools/tui-toolkit && uv run pytest` — all green
- [ ] `cd tools/controlplane && uv run pytest` — all green (shims keep legacy contracts)
- [ ] Manual: `controlplane-tool tui` launches with cyan NANOFAAS banner, no visual regression
- [ ] Manual: `controlplane-tool e2e run k3s-junit-curl --dry-run` renders correctly
- [ ] Manual: theme override smoke (`Theme(accent="green", ...)`) propagates uniformly to pickers, chrome, and workflow events

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Return the PR URL once it's open.

---

## Phase 3 — PR2: rewrite consumers, delete shims

PR2 is opened only **after PR1 has merged**.

### Task 3.1: Branch off main and audit consumers

- [ ] **Step 1: Pull main and create a fresh branch**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
git fetch origin && git checkout main && git pull
git checkout -b codex/tui-toolkit-pr2-imports
```

- [ ] **Step 2: List every consumer of the three legacy modules**

```bash
grep -rn "from controlplane_tool.console\|from controlplane_tool.tui_widgets\|from controlplane_tool.tui_chrome" tools/controlplane --include="*.py" | grep -v __pycache__
```

Expected: roughly 25 hits across `src/` and `tests/`. Save this list — it is the work list for the next tasks.

- [ ] **Step 3: List underscore-style imports that need rename at call sites**

```bash
grep -rn "_select_value\|_checkbox_values\|_select_described_value\|_select_described_checkbox_values\|_DescribedChoice" tools/controlplane --include="*.py" | grep -v __pycache__
```

These are the call-site renames to apply during PR2.

---

### Task 3.2: Rewrite `controlplane_tool.console` imports (workflow + chrome)

Most consumers import a subset of `console`, `phase`, `step`, `success`, `warning`, `skip`, `fail`, `status`, `workflow_log`, `workflow_step`, `header`, `bind_workflow_sink`, `bind_workflow_context`, `has_workflow_sink`, `_workflow_context`, `get_content_width`. All of these come from `tui_toolkit` directly after PR2.

**Files (all `from controlplane_tool.console import ...`):**
- Modify: `tools/controlplane/src/controlplane_tool/e2e_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/function_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/shell_backend.py`
- Modify: `tools/controlplane/src/controlplane_tool/process_streaming.py`
- Modify: `tools/controlplane/src/controlplane_tool/container_local_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/grafana_runtime.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_vm_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/src/controlplane_tool/k3s_curl_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_host_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/helm_stack_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/vm_commands.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui_workflow_controller.py`
- Modify: `tools/controlplane/src/controlplane_tool/workflow_progress.py`
- Modify: `tools/controlplane/src/controlplane_tool/deploy_host_runner.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli_stack_runner.py`

- [ ] **Step 1: Rewrite imports in each of the 16 files above**

For every occurrence of `from controlplane_tool.console import X, Y, Z`, change the prefix:

```python
# before
from controlplane_tool.console import phase, step, success, workflow_step

# after
from tui_toolkit import phase, step, success, workflow_step
```

For `_workflow_context` (the underscored helper), use the public name:

```python
# before
from controlplane_tool.console import _workflow_context
ctx = _workflow_context()

# after
from tui_toolkit import get_workflow_context
ctx = get_workflow_context()
```

- [ ] **Step 2: Run the controlplane test suite to catch typos**

```bash
cd tools/controlplane && uv run pytest -q
```

Expected: all green. If a test fails on `ImportError`, the rename was incomplete in that file.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/
git commit -m "controlplane: import console helpers from tui_toolkit"
```

---

### Task 3.3: Rewrite `controlplane_tool.tui_widgets` imports + rename call sites

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Modify: `tools/controlplane/src/controlplane_tool/tui.py`
- (Plus any others that grep returns.)

- [ ] **Step 1: Find every call site for the legacy underscored names**

```bash
grep -rn "_select_value\|_checkbox_values\|_select_described_value\|_select_described_checkbox_values\|_DescribedChoice" tools/controlplane/src --include="*.py" | grep -v __pycache__
```

- [ ] **Step 2: Apply two transforms in each file**

Imports:

```python
# before
from controlplane_tool.tui_widgets import _select_value, _checkbox_values, _DescribedChoice

# after
from tui_toolkit import select, multiselect, Choice
```

Call sites:

```python
# before
chosen = _select_value(message, choices=choices, default=default)
picks = _checkbox_values(message, choices=choices, default_values=defaults)
items = [_DescribedChoice("title", "value", "desc"), ...]

# after
chosen = select(message, choices=choices, default=default)
picks = multiselect(message, choices=choices, default_values=defaults)
items = [Choice("title", "value", "desc"), ...]
```

Be careful: `_select_described_value` and `_select_described_checkbox_values` (the longer names) also rename to `select` / `multiselect` — they are the same functions in the new world.

- [ ] **Step 3: Run the controlplane test suite**

```bash
cd tools/controlplane && uv run pytest -q
```

Expected: all green.

- [ ] **Step 4: Manual smoke**

```bash
cd tools/controlplane && uv run controlplane-tool tui
```

Navigate a couple of menus to confirm the pickers still work. Press Esc to exit.

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/
git commit -m "controlplane: import select/multiselect from tui_toolkit, rename call sites"
```

---

### Task 3.4: Rewrite `controlplane_tool.tui_chrome` imports

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_workflow.py`
- Modify: any other file that imports `render_screen_frame`, `APP_ASCII_LOGO`, `APP_WORDMARK`, `DEFAULT_BREADCRUMB`, or `DEFAULT_FOOTER_HINT`.

- [ ] **Step 1: Find every consumer**

```bash
grep -rn "from controlplane_tool.tui_chrome import" tools/controlplane/src --include="*.py" | grep -v __pycache__
```

- [ ] **Step 2: Rewrite imports**

For `render_screen_frame`:

```python
# before
from controlplane_tool.tui_chrome import render_screen_frame

# after
from tui_toolkit import render_screen_frame
```

For brand constants — replace by reading from `NANOFAAS_BRAND`:

```python
# before
from controlplane_tool.tui_chrome import APP_ASCII_LOGO, APP_WORDMARK

# after
from controlplane_tool.ui_setup import NANOFAAS_BRAND

APP_ASCII_LOGO = NANOFAAS_BRAND.ascii_logo  # only if the local module re-exposes them
APP_WORDMARK = NANOFAAS_BRAND.wordmark
```

If the importing file uses `APP_ASCII_LOGO` directly inside its body, replace each usage with `NANOFAAS_BRAND.ascii_logo` and remove the local aliases.

- [ ] **Step 3: Run tests**

```bash
cd tools/controlplane && uv run pytest -q
```

Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/
git commit -m "controlplane: import render_screen_frame from tui_toolkit, brand from ui_setup"
```

---

### Task 3.5: Rewrite test imports

The same patterns apply in `tests/`. The conftest already imports from `controlplane_tool.workflow_models` (which still re-exports), so that's fine; only the three deleted-shim modules require attention.

**Files:**
- Modify: every file under `tools/controlplane/tests/` that imports from `controlplane_tool.console`, `controlplane_tool.tui_widgets`, or `controlplane_tool.tui_chrome`.

- [ ] **Step 1: Find them**

```bash
grep -rn "from controlplane_tool.console\|from controlplane_tool.tui_widgets\|from controlplane_tool.tui_chrome" tools/controlplane/tests --include="*.py"
```

- [ ] **Step 2: Apply the same import rewrites as Tasks 3.2 / 3.3 / 3.4**

For each test file, replace:
- `from controlplane_tool.console import X` → `from tui_toolkit import X`
- `from controlplane_tool.tui_widgets import _select_value` → `from tui_toolkit import select`
- `from controlplane_tool.tui_chrome import render_screen_frame` → `from tui_toolkit import render_screen_frame`

Also rename underscored call sites where applicable.

- [ ] **Step 3: Run tests**

```bash
cd tools/controlplane && uv run pytest -q
```

Expected: all green.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/tests/
git commit -m "controlplane tests: import from tui_toolkit"
```

---

### Task 3.6: Delete the three shim files

**Files:**
- Delete: `tools/controlplane/src/controlplane_tool/console.py`
- Delete: `tools/controlplane/src/controlplane_tool/tui_widgets.py`
- Delete: `tools/controlplane/src/controlplane_tool/tui_chrome.py`

- [ ] **Step 1: Confirm no remaining imports of these modules**

```bash
grep -rn "from controlplane_tool.console\b\|from controlplane_tool.tui_widgets\b\|from controlplane_tool.tui_chrome\b" tools/controlplane --include="*.py" | grep -v __pycache__
```

Expected: empty output.

- [ ] **Step 2: Delete the files**

```bash
git rm tools/controlplane/src/controlplane_tool/console.py
git rm tools/controlplane/src/controlplane_tool/tui_widgets.py
git rm tools/controlplane/src/controlplane_tool/tui_chrome.py
```

- [ ] **Step 3: Run tests**

```bash
cd tools/controlplane && uv run pytest -q
```

Expected: all green. If a test fails on `ImportError: No module named 'controlplane_tool.console'`, an import was missed in the previous tasks. Fix and re-run.

- [ ] **Step 4: Commit**

```bash
git commit -m "controlplane: delete tui shims (console, tui_widgets, tui_chrome)"
```

---

### Task 3.7: Delete the legacy capture script

**Files:**
- Delete: `tools/controlplane/tests/test_renderer_golden_capture.py`

- [ ] **Step 1: Delete the file**

```bash
git rm tools/controlplane/tests/test_renderer_golden_capture.py
```

The golden files themselves stay in `tools/tui-toolkit/tests/golden/` because the snapshot tests still use them as a regression guard.

- [ ] **Step 2: Run tests**

```bash
cd tools/controlplane && uv run pytest -q
cd tools/tui-toolkit && uv run pytest -q
```

Expected: both green.

- [ ] **Step 3: Commit**

```bash
git commit -m "tools: drop one-shot golden capture script"
```

---

### Task 3.8: Update `tools/controlplane/CONVENTIONS.md`

**Files:**
- Modify: `tools/controlplane/CONVENTIONS.md`

- [ ] **Step 1: Read the current file to find the section about console output**

```bash
grep -n -A5 -i "console\|output\|tui" tools/controlplane/CONVENTIONS.md | head -40
```

- [ ] **Step 2: Edit the relevant section**

Find the section that currently describes terminal output via `controlplane_tool.console` or the `tui_widgets` helpers. Replace it with a pointer to `tui_toolkit`:

```markdown
## Terminal output

All terminal output goes through `tui_toolkit`:

- `from tui_toolkit import console, phase, step, success, warning, skip, fail` for workflow event rendering.
- `from tui_toolkit import select, multiselect, Choice` for interactive pickers.
- `from tui_toolkit import render_screen_frame` for chrome.
- The active theme and brand are configured once in `controlplane_tool.ui_setup.setup_ui()` and read implicitly by every helper.

Never call `print()` or `rich.print()` directly. To override the theme in
tests, use `with bind_ui(UIContext(theme=Theme(...))):`.
```

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/CONVENTIONS.md
git commit -m "controlplane: document tui_toolkit-based output conventions"
```

---

### Task 3.9: Final verification — full test suite, manual smoke, gitnexus

- [ ] **Step 1: Run both test suites**

```bash
cd tools/controlplane && uv run pytest -v
cd tools/tui-toolkit && uv run pytest -v
```

Expected: all green.

- [ ] **Step 2: Manual TUI smoke test**

```bash
cd tools/controlplane && uv run controlplane-tool tui
```

Expected: cyan NANOFAAS banner, navigation works, exit cleanly. Visually identical to PR1.

- [ ] **Step 3: Verify no shim is left behind**

```bash
grep -rn "from controlplane_tool.console\b\|from controlplane_tool.tui_widgets\b\|from controlplane_tool.tui_chrome\b" tools/controlplane --include="*.py" | grep -v __pycache__
ls tools/controlplane/src/controlplane_tool/console.py tools/controlplane/src/controlplane_tool/tui_widgets.py tools/controlplane/src/controlplane_tool/tui_chrome.py 2>&1
```

Expected:
- First grep: empty.
- Second `ls`: three "No such file or directory" errors.

- [ ] **Step 4: Refresh GitNexus index**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning
npx gitnexus analyze
```

Then in the conversation: `gitnexus_detect_changes({scope: "compare", base_ref: "main"})` — confirm only the expected files changed.

- [ ] **Step 5: Push the branch and open PR2**

```bash
git push -u origin codex/tui-toolkit-pr2-imports
gh pr create --title "tui-toolkit (PR2: rewrite imports, delete shims)" --body "$(cat <<'EOF'
## Summary
- Rewrite all consumers in `tools/controlplane/` to import directly from `tui_toolkit`.
- Delete the three legacy shim modules (`console.py`, `tui_widgets.py`, `tui_chrome.py`).
- Rename underscored call sites (`_select_value` → `select`, `_checkbox_values` → `multiselect`, `_DescribedChoice` → `Choice`).
- Update `CONVENTIONS.md` to point at `tui_toolkit` as the single output surface.

## Test plan
- [ ] `cd tools/tui-toolkit && uv run pytest` — all green
- [ ] `cd tools/controlplane && uv run pytest` — all green
- [ ] No remaining `from controlplane_tool.console / tui_widgets / tui_chrome` imports
- [ ] Manual: `controlplane-tool tui` launches cleanly, visually unchanged
- [ ] Manual: a dry-run scenario renders correctly

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

Return the PR URL.

---

## Verification matrix (overall acceptance gates)

| Gate | Command | Expected outcome |
|---|---|---|
| Library tests | `cd tools/tui-toolkit && uv run pytest -v` | All green; ~60+ tests |
| Controlplane tests (PR1) | `cd tools/controlplane && uv run pytest -v` | All green, **with shims still in place** |
| Controlplane tests (PR2) | `cd tools/controlplane && uv run pytest -v` | All green, **after shims deleted** |
| Snapshot parity | `cd tools/tui-toolkit && uv run pytest tests/test_workflow_render.py -v` | All 8 golden-snapshot tests pass against `DEFAULT_THEME` |
| Theme parity | `cd tools/tui-toolkit && uv run pytest tests/test_theme.py::test_to_questionary_style_matches_legacy_byte_for_byte -v` | Pass — DEFAULT_THEME matches legacy `_STYLE` byte-for-byte |
| Manual TUI smoke | `controlplane-tool tui` | Cyan NANOFAAS banner; navigation; clean exit |
| Theme override smoke | scratch script (see Task 2.9) | Banner / panels / icons all reflect the override |
| No shim references after PR2 | `grep -r "from controlplane_tool.console\b" tools/controlplane` | Empty |

---

## Notes for the executor

- **GitNexus impact analysis** is required by the project CLAUDE.md before editing any symbol. Run `gitnexus_impact({target: "<symbol>", direction: "upstream"})` before each Task that modifies a symbol with external callers (e.g., `select`, `phase`, `render_screen_frame`). For Tasks creating brand-new files inside `tools/tui-toolkit/`, the impact analysis is trivially "no callers yet" — skip it for new code, run it before each rewrite in Phase 3.
- **Idempotent commits.** Every task ends with a commit. If a step fails midway, fix it and continue — do not amend earlier commits.
- **Shellcraft pattern.** The pyproject.toml currently uses `"shellcraft @ file:///abs/path"` (absolute). For `tui-toolkit`, prefer the `[tool.uv.sources]` table with a relative path (`path = "../tui-toolkit"`) so the lockfile is portable across machines.
- **Not signing.** This repo's commits are not GPG-signed. Use plain `git commit` (no `--no-gpg-sign` flag).
- **Worktree.** The plan runs entirely inside `/Users/micheleciavotta/Downloads/mcFaas/.worktrees/ansible-vm-provisioning/` on branch `codex/ansible-vm-provisioning` (PR1) and a fresh branch off main `codex/tui-toolkit-pr2-imports` (PR2).
