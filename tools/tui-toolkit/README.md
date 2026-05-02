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

Pre-1.0. The first consumer is `tools/controlplane/`. See the design doc at [`../../docs/superpowers/specs/2026-05-01-tui-toolkit-extraction-design.md`](../../docs/superpowers/specs/2026-05-01-tui-toolkit-extraction-design.md).
