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

## Test Conventions

- Every source module has a corresponding `test_<module>.py`.
- Use `RecordingShell` from `shell_backend` to capture subprocess calls in unit tests.
- Integration guards requiring real VMs/K8s live in `conftest.py` markers.
