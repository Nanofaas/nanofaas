# Controlplane Tooling Conventions

## Module Naming

| Suffix | Role | Example |
|--------|------|---------|
| `*_runner.py` | Orchestrates a complete E2E workflow | `k3s_curl_runner.py` |
| `*_runtime.py` | Manages a long-lived service or process | `grafana_runtime.py` |
| `*_adapter.py` | Bridge to an external system (Ansible, VM) | `vm_adapter.py` |
| `*_ops.py` | Stateless helper functions (build, deploy) | `gradle_ops.py` |
| `*_commands.py` | Thin Typer CLI wiring, zero business logic | `e2e_commands.py` |
| `*_models.py` | Pydantic data classes / validation | `scenario_models.py` |
| `*_catalog.py` | Static registries / lookup tables | `function_catalog.py` |
| `*_helpers.py` | Pure stateless utility functions, shared | `scenario_helpers.py` |

## Architecture Principles

1. **Single Responsibility** — every module has one reason to change.
2. **No duplication** — shared logic lives in `*_helpers.py`; never copy-paste across runners.
3. **No god objects** — classes > 200 LOC are split; methods > 30 LOC are extracted.
4. **Thin CLI layer** — `*_commands.py` files only parse args and call runner methods.
5. **Testability via injection** — runners accept a `shell` / `vm_exec` callable so tests can record calls without spawning processes.
6. **No shell delegation** — runners call Python APIs directly; subprocess is a last resort for external tools (gradle, helm, kubectl).

## File Size Targets

| Category | Max LOC |
|----------|---------|
| Source file (`src/`) | 250 |
| Test file (`tests/`) | 400 |

## Terminal output

All terminal output goes through `tui_toolkit`:

- `from tui_toolkit.console import console` — the Rich `Console` singleton for direct `console.print(...)` calls
- `from tui_toolkit import phase, step, success, warning, skip, fail` — workflow event rendering
- `from tui_toolkit import status, workflow_step, workflow_log` — workflow step/log helpers
- `from tui_toolkit import bind_workflow_sink, bind_workflow_context, has_workflow_sink` — sink/context wiring
- `from tui_toolkit import get_content_width` — terminal width helper
- `from tui_toolkit.pickers import select, multiselect, Choice` — interactive pickers
- `from tui_toolkit import render_screen_frame` — screen chrome

The active theme and brand are configured once in `controlplane_tool.ui_setup.setup_ui()`
and read implicitly by every widget. To override in tests: `with bind_ui(UIContext(theme=...))`.

Never use `print()` or `rich.print()` directly.

## Test Conventions

- Every source module has a corresponding `test_<module>.py`.
- Use `RecordingShell` from `shell_backend` to capture subprocess calls in unit tests.
- Integration guards requiring real VMs/K8s live in `conftest.py` markers.
