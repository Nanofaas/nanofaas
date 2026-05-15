# Azure TUI Config Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `azure-vm-loadtest` to the TUI scenario menu, backed by a `profiles/azure.toml` config file that stores Azure defaults, with a summary + confirmation dialog before launching.

**Architecture:** New `AzureConfig` Pydantic model in `core/models.py` + dedicated reader in `workspace/azure_config.py`. The TUI reads the config, shows a `rich.Panel` summary, asks confirmation, then builds the `E2eRequest` and runs the workflow identical to `helm-stack`/`two-vm-loadtest`. A committed `profiles/azure.toml` template holds placeholder values.

**Tech Stack:** Python 3.11+, Pydantic v2, tomllib (stdlib), Rich, questionary, uv/pytest

---

## File Map

| Status | Path | Role |
|---|---|---|
| modify | `tools/controlplane/src/controlplane_tool/core/models.py` | Add `AzureConfig` model |
| create | `tools/controlplane/src/controlplane_tool/workspace/azure_config.py` | Reader: `load_azure_config`, `azure_config_path`, `azure_config_exists` |
| create | `tools/controlplane/tests/test_azure_config.py` | Unit tests for the reader |
| modify | `tools/controlplane/src/controlplane_tool/tui/app.py` | Add azure entry to choices + azure branch in `_run_vm_e2e_scenario` |
| modify | `tools/controlplane/tests/test_tui_choices.py` | Assert azure-vm-loadtest in platform validation choices |
| create | `tools/controlplane/profiles/azure.toml` | Template config with placeholder values |

---

## Task 1: AzureConfig model + azure_config.py reader

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/core/models.py`
- Create: `tools/controlplane/src/controlplane_tool/workspace/azure_config.py`
- Create: `tools/controlplane/tests/test_azure_config.py`

- [ ] **Step 1.1: Write the failing tests**

Create `tools/controlplane/tests/test_azure_config.py`:

```python
from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

from controlplane_tool.workspace.azure_config import (
    azure_config_exists,
    azure_config_path,
    load_azure_config,
)


def _write_azure_toml(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


# ----------------------------------------------------------------- load_azure_config

def test_load_azure_config_parses_required_fields(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "my-rg"\nlocation = "westeurope"\n')

    cfg = load_azure_config(tmp_path)

    assert cfg.resource_group == "my-rg"
    assert cfg.location == "westeurope"


def test_load_azure_config_applies_defaults(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "rg"\nlocation = "eastus"\n')

    cfg = load_azure_config(tmp_path)

    assert cfg.vm_size == "Standard_B2s"
    assert cfg.loadgen_vm_size == "Standard_B1s"
    assert cfg.image_urn is None
    assert cfg.ssh_key_path is None
    assert cfg.vm_name == "nanofaas-azure"
    assert cfg.loadgen_name == "nanofaas-azure-loadgen"


def test_load_azure_config_reads_optional_fields(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, '''
resource_group = "my-rg"
location = "westeurope"
vm_size = "Standard_D2s_v3"
loadgen_vm_size = "Standard_B2s"
image_urn = "Canonical:ubuntu:24_04:latest"
ssh_key_path = "/home/user/.ssh/id_ed25519"
vm_name = "custom-stack"
loadgen_name = "custom-loadgen"
''')

    cfg = load_azure_config(tmp_path)

    assert cfg.vm_size == "Standard_D2s_v3"
    assert cfg.loadgen_vm_size == "Standard_B2s"
    assert cfg.image_urn == "Canonical:ubuntu:24_04:latest"
    assert cfg.ssh_key_path == "/home/user/.ssh/id_ed25519"
    assert cfg.vm_name == "custom-stack"
    assert cfg.loadgen_name == "custom-loadgen"


def test_load_azure_config_raises_when_file_missing(tmp_path):
    with pytest.raises(FileNotFoundError):
        load_azure_config(tmp_path)


def test_load_azure_config_raises_when_resource_group_missing(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'location = "westeurope"\n')

    with pytest.raises(ValidationError):
        load_azure_config(tmp_path)


def test_load_azure_config_raises_when_location_missing(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "my-rg"\n')

    with pytest.raises(ValidationError):
        load_azure_config(tmp_path)


# ----------------------------------------------------------------- azure_config_exists

def test_azure_config_exists_returns_true_when_present(tmp_path):
    cfg_path = tmp_path / "profiles" / "azure.toml"
    _write_azure_toml(cfg_path, 'resource_group = "rg"\nlocation = "west"\n')

    assert azure_config_exists(tmp_path) is True


def test_azure_config_exists_returns_false_when_absent(tmp_path):
    assert azure_config_exists(tmp_path) is False


# ----------------------------------------------------------------- azure_config_path

def test_azure_config_path_points_to_profiles_dir(tmp_path):
    path = azure_config_path(tmp_path)
    assert path == tmp_path / "profiles" / "azure.toml"
```

- [ ] **Step 1.2: Run tests to verify they fail**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_config.py -v
```

Expected: `FAILED` — `ImportError: cannot import name 'load_azure_config'`.

- [ ] **Step 1.3: Add AzureConfig to core/models.py**

In `tools/controlplane/src/controlplane_tool/core/models.py`, after the `Profile` class (end of file), add:

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

- [ ] **Step 1.4: Create workspace/azure_config.py**

Create `tools/controlplane/src/controlplane_tool/workspace/azure_config.py`:

```python
from __future__ import annotations

import tomllib
from pathlib import Path

from controlplane_tool.core.models import AzureConfig
from controlplane_tool.workspace.paths import default_tool_paths


def azure_config_path(root: Path | None = None) -> Path:
    tool_root = Path(root) if root is not None else default_tool_paths().tool_root
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

- [ ] **Step 1.5: Run tests to verify they pass**

```bash
cd tools/controlplane && uv run pytest tests/test_azure_config.py -v
```

Expected: all 9 tests PASS.

- [ ] **Step 1.6: Run full suite for regressions**

```bash
cd tools/controlplane && uv run pytest tests/ --ignore=tests/integration -x -q 2>&1 | tail -5
```

Expected: 997+ passed, only the pre-existing `test_remote_k6` failure.

- [ ] **Step 1.7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/core/models.py \
  tools/controlplane/src/controlplane_tool/workspace/azure_config.py \
  tools/controlplane/tests/test_azure_config.py
git commit -m "feat: add AzureConfig model and azure_config reader"
```

---

## Task 2: TUI integration

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Modify: `tools/controlplane/tests/test_tui_choices.py`

- [ ] **Step 2.1: Write the failing TUI choices test**

In `tools/controlplane/tests/test_tui_choices.py`, add at the bottom:

```python
def test_platform_validation_choices_includes_azure_vm_loadtest() -> None:
    from controlplane_tool.tui.app import _PLATFORM_VALIDATION_CHOICES

    values = [c.value for c in _PLATFORM_VALIDATION_CHOICES]
    assert "azure-vm-loadtest" in values


def test_vm_lifecycle_choices_includes_azure() -> None:
    from controlplane_tool.tui.app import _VM_LIFECYCLE_CHOICES

    values = [c.value for c in _VM_LIFECYCLE_CHOICES]
    assert "azure" in values
```

- [ ] **Step 2.2: Run tests to verify they fail**

```bash
cd tools/controlplane && uv run pytest tests/test_tui_choices.py::test_platform_validation_choices_includes_azure_vm_loadtest tests/test_tui_choices.py::test_vm_lifecycle_choices_includes_azure -v
```

Expected: both FAILED — `AssertionError`.

- [ ] **Step 2.3: Add azure-vm-loadtest to _PLATFORM_VALIDATION_CHOICES**

In `tools/controlplane/src/controlplane_tool/tui/app.py`, find `_PLATFORM_VALIDATION_CHOICES` (around line 504). Add after the `two-vm-loadtest` entry:

```python
    _DescribedChoice(
        "azure-vm-loadtest — Two-VM Azure load test with k6",
        "azure-vm-loadtest",
        "Provision two Azure VMs (stack + loadgen) via OpenTofu, run k6 load test, capture "
        "Prometheus snapshots. Reads defaults from profiles/azure.toml.",
    ),
```

- [ ] **Step 2.4: Add azure to _VM_LIFECYCLE_CHOICES**

In `tools/controlplane/src/controlplane_tool/tui/app.py`, find `_VM_LIFECYCLE_CHOICES` (around line 485). Add after the `external` entry:

```python
    _value_choice(
        "azure",
        "Provision and manage VMs on Azure via OpenTofu. Requires profiles/azure.toml.",
    ),
```

- [ ] **Step 2.5: Run choices tests to verify they pass**

```bash
cd tools/controlplane && uv run pytest tests/test_tui_choices.py::test_platform_validation_choices_includes_azure_vm_loadtest tests/test_tui_choices.py::test_vm_lifecycle_choices_includes_azure -v
```

Expected: both PASS.

- [ ] **Step 2.6: Update _run_platform_validation dispatch**

In `tools/controlplane/src/controlplane_tool/tui/app.py`, find line ~951:
```python
            if scenario_choice in ("k3s-junit-curl", "helm-stack", "two-vm-loadtest"):
                self._run_vm_e2e_scenario(scenario_choice)
```

Change to:
```python
            if scenario_choice in ("k3s-junit-curl", "helm-stack", "two-vm-loadtest", "azure-vm-loadtest"):
                self._run_vm_e2e_scenario(scenario_choice)
```

- [ ] **Step 2.7: Add azure branch in _run_vm_e2e_scenario**

In `tools/controlplane/src/controlplane_tool/tui/app.py`, find `_run_vm_e2e_scenario` (line 961). The method currently has:
```python
        if scenario in {"helm-stack", "two-vm-loadtest"}:
            ...
        else:  # k3s-junit-curl
            ...
```

Add a new `elif` branch between the two, before `else:  # k3s-junit-curl`:

```python
        elif scenario == "azure-vm-loadtest":
            from pydantic import ValidationError
            from controlplane_tool.workspace.azure_config import (
                azure_config_path,
                load_azure_config,
            )
            from controlplane_tool.cli.e2e_commands import _resolve_run_request
            from controlplane_tool.e2e.e2e_runner import E2eRunner

            try:
                cfg = load_azure_config()
            except FileNotFoundError:
                warning(
                    f"Missing azure.toml — create {azure_config_path()} "
                    "with resource_group and location."
                )
                _acknowledge_static_view()
                return
            except ValidationError as exc:
                first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
                warning(f"Invalid azure.toml: {first_error}")
                _acknowledge_static_view()
                return

            console.print(
                Panel(
                    f"resource_group: {cfg.resource_group}\n"
                    f"location:       {cfg.location}\n"
                    f"vm_size:        {cfg.vm_size} (stack) / {cfg.loadgen_vm_size} (loadgen)\n"
                    f"vm_name:        {cfg.vm_name} / {cfg.loadgen_name}",
                    title="Azure defaults (profiles/azure.toml)",
                )
            )

            confirmed = _ask(
                lambda: questionary.confirm(
                    "Proceed with azure-vm-loadtest?", default=True, style=_STYLE
                ).ask()
            )
            if not confirmed:
                return

            request = _resolve_run_request(
                scenario="azure-vm-loadtest",
                runtime="java",
                lifecycle="azure",
                name=cfg.vm_name,
                host=None,
                user="azureuser",
                home=None,
                cpus=4,
                memory="12G",
                disk="30G",
                cleanup_vm=False,
                namespace=None,
                local_registry=None,
                function_preset=None,
                functions_csv=None,
                scenario_file=None,
                saved_profile=None,
                loadgen_name=cfg.loadgen_name,
                loadgen_cpus=2,
                loadgen_memory="2G",
                loadgen_disk="10G",
                azure_resource_group=cfg.resource_group,
                azure_location=cfg.location,
                azure_vm_size=cfg.vm_size,
                azure_image_urn=cfg.image_urn,
                azure_ssh_key_path=cfg.ssh_key_path,
            )
            plan = E2eRunner(repo_root=repo_root).plan(request)

            def _run_azure_loadtest_workflow(
                dashboard: WorkflowDashboard, sink: TuiWorkflowSink
            ) -> None:
                def _on_step_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                dashboard.append_log("Starting azure-vm-loadtest workflow")
                sink._update()
                flow = build_scenario_flow(
                    "azure-vm-loadtest",
                    repo_root=repo_root,
                    request=request,
                    event_listener=_on_step_event,
                )
                self._controller.run_shared_flow(flow)
                dashboard.append_log("azure-vm-loadtest E2E completed")
                sink._update()

            self._controller.run_live_workflow(
                title="E2E Scenarios",
                summary_lines=[
                    "Scenario: azure-vm-loadtest",
                    f"Resource group: {cfg.resource_group}",
                    f"Location: {cfg.location}",
                    f"Stack VM: {cfg.vm_name} ({cfg.vm_size})",
                    f"Loadgen VM: {cfg.loadgen_name} ({cfg.loadgen_vm_size})",
                ],
                planned_steps=[step.summary for step in plan.steps],
                action=_run_azure_loadtest_workflow,
            )
```

- [ ] **Step 2.8: Run full test suite**

```bash
cd tools/controlplane && uv run pytest tests/ --ignore=tests/integration -x -q 2>&1 | tail -5
```

Expected: 1000+ passed, only the pre-existing `test_remote_k6` failure.

- [ ] **Step 2.9: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/app.py \
  tools/controlplane/tests/test_tui_choices.py
git commit -m "feat: add azure-vm-loadtest to TUI scenario menu with azure.toml config support"
```

---

## Task 3: azure.toml template

**Files:**
- Create: `tools/controlplane/profiles/azure.toml`

No tests — this is a static config file. The tests in Task 1 already cover the reader.

- [ ] **Step 3.1: Create the template config file**

Create `tools/controlplane/profiles/azure.toml`:

```toml
# Azure defaults for azure-vm-loadtest TUI scenario.
# Required fields: resource_group, location.
# Optional fields: uncomment and set as needed.

resource_group = "my-rg"
location = "westeurope"
vm_size = "Standard_B2s"
loadgen_vm_size = "Standard_B1s"
# image_urn = ""         # empty = Ubuntu 24.04 LTS default
# ssh_key_path = ""      # empty = auto-discovery ~/.ssh/id_ed25519
# vm_name = ""           # empty = "nanofaas-azure"
# loadgen_name = ""      # empty = "nanofaas-azure-loadgen"
```

Note: the commented-out optional fields with empty strings won't be parsed by tomllib
(they are genuine TOML comments). The `AzureConfig` defaults will apply automatically.

- [ ] **Step 3.2: Verify the template is readable by the loader**

```bash
cd tools/controlplane && uv run python -c "
from controlplane_tool.workspace.azure_config import load_azure_config
cfg = load_azure_config()
print(f'resource_group: {cfg.resource_group}')
print(f'location: {cfg.location}')
print(f'vm_size: {cfg.vm_size}')
print(f'loadgen_vm_size: {cfg.loadgen_vm_size}')
"
```

Expected: prints the values from azure.toml without errors.

- [ ] **Step 3.3: Commit**

```bash
git add tools/controlplane/profiles/azure.toml
git commit -m "chore: add azure.toml config template for azure-vm-loadtest TUI scenario"
```

---

## Usage after implementation

1. Edit `tools/controlplane/profiles/azure.toml` with your Azure resource group and location.
2. Run `controlplane-tool` (no args) to open the TUI.
3. Navigate: **platform → platform end-to-end scenarios → azure-vm-loadtest**.
4. Review the summary panel and confirm.
5. The workflow provisions two Azure VMs and runs the k6 load test.
