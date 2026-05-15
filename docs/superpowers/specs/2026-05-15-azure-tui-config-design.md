# Azure TUI Config — Design Specification

## Overview

Add `azure-vm-loadtest` to the TUI scenario menu. A `tools/controlplane/profiles/azure.toml`
config file stores project-level Azure defaults (resource group, location, VM sizes). When
the user selects the scenario the TUI reads the file, shows a summary, and asks for
confirmation before launching.

## Scope

- New `AzureConfig` Pydantic model in `core/models.py`
- New `workspace/azure_config.py` with reader functions
- `tui/app.py` updated: new entry in `_PLATFORM_VALIDATION_CHOICES`, `_VM_LIFECYCLE_CHOICES`,
  and `_run_vm_e2e_scenario`
- `tools/controlplane/profiles/azure.toml` template (committed with placeholder values)

## Section 1 — Config file + AzureConfig model

### Config file

**Path:** `tools/controlplane/profiles/azure.toml`

```toml
resource_group = "my-rg"
location = "westeurope"
vm_size = "Standard_B2s"
loadgen_vm_size = "Standard_B1s"
# image_urn = ""      # empty = Ubuntu 24.04 LTS default
# ssh_key_path = ""   # empty = auto-discovery ~/.ssh/id_ed25519
# vm_name = ""        # empty = "nanofaas-azure"
# loadgen_name = ""   # empty = "nanofaas-azure-loadgen"
```

`resource_group` and `location` are required — they have no default. If missing, Pydantic
raises `ValidationError` which the TUI catches and reports as a human-readable message.

The file should be added to `.gitignore` if the team uses personal Azure subscriptions.
For shared subscriptions it can be committed as-is.

### AzureConfig model

Added to `tools/controlplane/src/controlplane_tool/core/models.py`:

```python
class AzureConfig(BaseModel):
    resource_group: str
    location: str
    vm_size: str = "Standard_B2s"
    loadgen_vm_size: str = "Standard_B1s"
    image_urn: str | None = None
    ssh_key_path: str | None = None
    vm_name: str = "nanofaas-azure"
    loadgen_name: str = "nanofaas-azure-loadgen"
```

`ssh_key_path = None` → `AzureVmOrchestrator._find_ssh_private_key()` auto-discovers
`~/.ssh/id_ed25519`, `id_rsa`, etc.

## Section 2 — Reader functions

New file: `tools/controlplane/src/controlplane_tool/workspace/azure_config.py`

```python
from __future__ import annotations

import tomllib
from pathlib import Path

from pydantic import ValidationError

from controlplane_tool.core.models import AzureConfig
from controlplane_tool.workspace.paths import default_tool_paths


def azure_config_path(root: Path | None = None) -> Path:
    tool_root = (root or default_tool_paths().tool_root)
    return tool_root / "profiles" / "azure.toml"


def load_azure_config(root: Path | None = None) -> AzureConfig:
    path = azure_config_path(root)
    if not path.exists():
        raise FileNotFoundError(path)
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    return AzureConfig.model_validate(data)


def azure_config_exists(root: Path | None = None) -> bool:
    return azure_config_path(root).exists()
```

Callers handle `FileNotFoundError` (missing file) and `ValidationError` (invalid/missing
required fields) separately to give actionable error messages.

## Section 3 — TUI integration

### `_PLATFORM_VALIDATION_CHOICES`

Add after `two-vm-loadtest`:

```python
_DescribedChoice(
    "azure-vm-loadtest — Two-VM Azure load test with k6",
    "azure-vm-loadtest",
    "Provision two Azure VMs (stack + loadgen) via OpenTofu, run k6 load test, capture "
    "Prometheus snapshots. Reads defaults from profiles/azure.toml.",
),
```

### `_VM_LIFECYCLE_CHOICES`

Add after `external`:

```python
_value_choice(
    "azure",
    "Provision and manage VMs on Azure via OpenTofu. Requires profiles/azure.toml.",
),
```

### `_run_platform_validation` dispatch

```python
if scenario_choice in ("k3s-junit-curl", "helm-stack", "two-vm-loadtest", "azure-vm-loadtest"):
    self._run_vm_e2e_scenario(scenario_choice)
```

### `_run_vm_e2e_scenario` — new azure branch

When `scenario == "azure-vm-loadtest"`:

1. **Load config** — call `load_azure_config()`. On `FileNotFoundError` print:
   `"Missing azure.toml: create {azure_config_path()} with resource_group and location."`
   On `ValidationError` print: `"Invalid azure.toml: {first_error_msg}"`.
   In both cases call `_acknowledge_static_view()` and return.

2. **Show summary panel** — display a `rich.Panel` with:
   - `resource_group`, `location`, `vm_size` (stack) / `loadgen_vm_size` (loadgen)
   - `vm_name` / `loadgen_name`

3. **Confirm** — `questionary.confirm("Proceed with azure-vm-loadtest?", default=True)`.
   If `False`, return without doing anything.

4. **Build request** — call `_resolve_run_request` with:
   - `scenario="azure-vm-loadtest"`, `runtime="java"`, `lifecycle="azure"`
   - `name=cfg.vm_name`, `user="azureuser"`, `cleanup_vm=False`
   - `azure_resource_group=cfg.resource_group`, `azure_location=cfg.location`
   - `azure_vm_size=cfg.vm_size`, `azure_image_urn=cfg.image_urn`
   - `azure_ssh_key_path=cfg.ssh_key_path`
   - `loadgen_name=cfg.loadgen_name`, `loadgen_cpus=2`, `loadgen_memory="2G"`, `loadgen_disk="10G"`

5. **Run workflow** — same pattern as `helm-stack`/`two-vm-loadtest`:
   - Call `plan = E2eRunner(repo_root=repo_root).plan(request)` to get the step list.
   - Define a `_run_azure_loadtest_workflow(dashboard, sink)` closure that calls
     `build_scenario_flow(...)` and `self._controller.run_shared_flow(flow)`.
   - Call `self._controller.run_live_workflow(title="E2E Scenarios", summary_lines=[...], planned_steps=[step.summary for step in plan.steps], action=_run_azure_loadtest_workflow)`.

## Error handling

| Condition | Message shown in TUI |
|---|---|
| `azure.toml` missing | `Missing azure.toml: create <path> with resource_group and location.` |
| `resource_group` missing in file | `Invalid azure.toml: field required [resource_group]` |
| `location` missing in file | `Invalid azure.toml: field required [location]` |
| User declines confirmation | Silent return to scenario menu |

## Testing

- `tests/test_azure_config.py` — unit tests for `load_azure_config`:
  - loads valid file → returns `AzureConfig` with correct fields
  - missing file → raises `FileNotFoundError`
  - missing `resource_group` → raises `ValidationError`
  - optional fields absent → defaults applied (`vm_size="Standard_B2s"`, etc.)
- TUI integration is not unit-tested (existing pattern: TUI not covered by unit tests)

## Out of scope

- Interactive override of config values in the TUI (user uses CLI `--azure-*` flags for
  per-run overrides)
- `e2e_all` support for Azure (separate follow-up)
- Encryption or secrets management for `azure.toml`
