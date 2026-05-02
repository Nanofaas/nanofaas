# shellcraft — Design Spec

**Date:** 2026-04-25
**Status:** Approved

## Goal

Extract the subprocess orchestration layer from `tools/controlplane` into a standalone Python library called `shellcraft`, publishable independently and reusable in any Python DevOps or orchestration project.

## Problem

Direct use of `subprocess.run(...)` in orchestration code creates three recurring problems:

1. Tests must mock `subprocess` or spawn real processes — both fragile.
2. No standard way to intercept output line-by-line during execution.
3. Docker/kubectl invocations are duplicated across projects with minor variations.

## Scope

### Included

| Source module | shellcraft module | Responsibility |
|---|---|---|
| `shell_backend.py` | `shellcraft.backend` | Abstract backend + real and fake implementations |
| `runtime_primitives.py` | `shellcraft.runners` | `CommandRunner`, `PlannedCommand`, `ContainerRuntimeOps`, `KubectlOps` |
| `net_utils.py` | `shellcraft.net` | `is_port_free`, `pick_local_port` |

### Excluded

- `process_streaming.py` — calls `workflow_log` from the project-specific console layer; stays in `controlplane-tool`.

## Architecture

### `shellcraft.backend`

Core abstraction for executing shell commands.

- `ShellExecutionResult` — frozen dataclass: `command`, `return_code`, `stdout`, `stderr`, `dry_run`, `env`.
- `ShellBackend` — abstract base with a single `run(command, *, cwd, env, dry_run)` method.
- `SubprocessShell` — real implementation. Accepts an optional `output_listener: Callable[[str, str], None]` for line-by-line streaming. When no listener is provided and stdout/stderr don't need streaming, uses `subprocess.run` for simplicity.
- `RecordingShell` — test double that records commands without executing them. Returns `return_code=0` for everything.
- `ScriptedShell` — test double that returns pre-configured stdout/stderr/return_code per command tuple.

### `shellcraft.runners`

Higher-level composable runners built on top of `backend`.

- `CommandRunner` — couples a `ShellBackend` with a working-directory root. The canonical injection point: swap in a fake backend for tests.
- `PlannedCommand` — immutable value object `(command, cwd, env)` with a `.run(runner, dry_run)` method.
- `ContainerRuntimeOps` — builds and executes Docker-compatible commands (`build`, `run_container`, `remove`, `push`, `list_containers`). Supports docker/podman/nerdctl via a `runtime` field.
- `KubectlOps` — builds and executes kubectl commands (`apply`, `delete`, `rollout_restart`, `exec`). Accepts optional `kubeconfig` and `namespace`.

### `shellcraft.net`

Pure socket utilities, no subprocess involvement.

- `is_port_free(port)` — returns True if the port can be bound on both IPv4 and IPv6 loopback.
- `pick_local_port(preferred, blocked)` — returns `preferred` if free and not blocked, otherwise an OS-assigned port.

### `shellcraft/__init__.py`

Re-exports the main public API:

```python
from shellcraft.backend import (
    ShellBackend, ShellExecutionResult,
    SubprocessShell, RecordingShell, ScriptedShell,
)
from shellcraft.runners import CommandRunner, PlannedCommand, ContainerRuntimeOps, KubectlOps
from shellcraft.net import is_port_free, pick_local_port
```

## Required Refactor Before Extraction

`SubprocessShell._emit_output` currently calls both `output_listener` and `workflow_log` (imported from `controlplane-tool`'s `console.py`). Since `shellcraft` has no console dependency, `workflow_log` is removed. Callers that need workflow log integration pass an `output_listener` that wraps `workflow_log`. The public contract of `SubprocessShell` is unchanged.

## Dependencies

```
shellcraft → stdlib only (subprocess, socket, pathlib, threading, dataclasses, typing)
```

- Python ≥ 3.11
- No external dependencies

## Package Structure

```
shellcraft/
  __init__.py
  backend.py
  runners.py
  net.py
pyproject.toml
tests/
  test_backend.py
  test_runners.py
  test_net.py
README.md
```

## Migration in controlplane-tool

After extraction:

1. `pyproject.toml` of `controlplane-tool` adds `shellcraft` as a dependency (pinned to the extracted repo, e.g. via git URL or PyPI).
2. Imports in `shell_backend.py`, `runtime_primitives.py`, `net_utils.py` are replaced with imports from `shellcraft`.
3. The original three files are deleted from `controlplane-tool`.
4. `process_streaming.py` is updated to wire `workflow_log` as the `output_listener` of `SubprocessShell` where needed.

## Testing Strategy

Each module has its own test file with no mocking of subprocess — tests use `RecordingShell` and `ScriptedShell` as test doubles. `test_backend.py` includes an integration test that runs a real subprocess (`echo`) to verify `SubprocessShell` captures output correctly. `test_net.py` tests `is_port_free` and `pick_local_port` against real sockets.
