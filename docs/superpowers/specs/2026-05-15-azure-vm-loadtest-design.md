# azure-vm-loadtest — Design Specification

## Overview

Add an `azure-vm-loadtest` scenario to `tools/controlplane` that mirrors the existing
`two-vm-loadtest` scenario but provisions and operates its VMs on Azure via the
`azure-vm-sdk` library (OpenTofu + paramiko SSH) instead of Multipass.

Two Azure VMs are created: a **stack VM** (runs k3s + nanofaas via Helm) and a
**loadgen VM** (runs k6 load tests). The scenario produces the same k6 summary,
Prometheus snapshots, and HTML report as `two-vm-loadtest`.

## Scope

- New scenario `azure-vm-loadtest` registered in the existing scenario catalog.
- New `AzureVmOrchestrator` adapter in `infra/vm/` — same method surface as `VmOrchestrator`.
- New `lifecycle="azure"` value and Azure-specific optional fields on `VmRequest`.
- No changes to `TwoVmLoadtestRunner` logic — only the type annotation widens.
- The recipe (phase ordering) is identical to `two-vm-loadtest`.

## Section 1 — Models & Catalog

### `core/models.py`

```python
VmLifecycle = Literal["multipass", "external", "azure"]
ScenarioName = Literal[..., "two-vm-loadtest", "azure-vm-loadtest"]
VM_BACKED_SCENARIOS = frozenset({..., "two-vm-loadtest", "azure-vm-loadtest"})
```

### `infra/vm/vm_models.py`

Add optional Azure-specific fields to `VmRequest`. All fields are ignored when
`lifecycle != "azure"`. `resource_group` and `location` fall back to the env vars
`AZURE_RESOURCE_GROUP` and `AZURE_LOCATION` when `None` (handled inside `AzureClient`).

```python
azure_vm_size: str = "Standard_B2s"
azure_resource_group: str | None = None
azure_location: str | None = None
azure_image_urn: str | None = None        # default: Ubuntu 24.04 LTS
azure_ssh_key_path: str | None = None     # default: first key found in ~/.ssh/
```

The existing `user` field defaults to `"ubuntu"`. When `lifecycle="azure"` and `user`
is not explicitly provided by the caller, the CLI sets it to `"azureuser"` (the default
cloud-init SSH user on Azure Ubuntu images). `AzureVmOrchestrator` always passes
`request.user` as `ssh_username` to `AzureClient` without further defaulting.

### `scenario/catalog.py`

```python
ScenarioDefinition(
    name="azure-vm-loadtest",
    description="Two-VM Azure load test: stack VM + k6 loadgen on Azure.",
    requires_vm=True,
    supported_runtimes=("java", "rust"),
    grouped_phases=True,
)
```

### `pyproject.toml`

```toml
"azure-vm-sdk @ git+https://github.com/miciav/azure-vm-sdk.git@2311b1c",
```

Added alongside the existing `multipass-sdk` git dependency.

## Section 2 — `AzureVmOrchestrator`

New file: `tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py`

The class implements the subset of `VmOrchestrator`'s interface consumed by
`TwoVmLoadtestRunner` and `e2e_runner.py`:

| Method | Implementation |
|---|---|
| `remote_home(request)` | Returns `request.home` or `/home/{user}` or `/root` |
| `connection_host(request)` | `AzureVM.wait_for_ip()` |
| `ensure_running(request)` | `AzureClient.ensure_running()` with cloud-init SSH key injection |
| `exec_argv(request, argv, env, cwd)` | `AzureVM.exec_structured()` → `ShellExecutionResult` |
| `transfer_to(request, source, destination)` | `AzureVM.transfer(str(source), destination)` — paramiko `sftp.put` |
| `transfer_from(request, source, destination)` | `scp -i key user@ip:source dest` via subprocess — avoids the `:` ambiguity in `AzureVM.transfer` |
| `teardown(request)` | `AzureVM.delete()`, silences `VmNotFoundError` |

`_client(request)` builds an `AzureClient` from the request's Azure fields on every
call (stateless per-call); the client itself caches the workspace on disk so this is
cheap.

`transfer_from` uses `scp` subprocess rather than `AzureVM.transfer` because
paramiko's `sftp.get(remotepath, localpath)` expects a bare remote path, but the
`AzureVM.transfer` convention passes a `vm-name:path` string that SFTP cannot resolve.
The `scp` invocation resolves the private key via `request.azure_ssh_key_path` if set,
otherwise falls back to `_find_ssh_private_key_path()` (the same helper already used in
`vm_adapter.py` that scans `~/.ssh/id_ed25519`, `id_rsa`, etc.).

## Section 3 — Runner Integration

### `e2e/two_vm_loadtest_runner.py`

Widen the type annotation for `vm`:

```python
from controlplane_tool.infra.vm.azure_vm_adapter import AzureVmOrchestrator

class TwoVmLoadtestRunner:
    def __init__(
        self,
        *,
        repo_root: Path,
        vm: VmOrchestrator | AzureVmOrchestrator | None = None,
        ...
    ) -> None:
```

No logic changes — both orchestrators share the same method signatures.

### `e2e/e2e_runner.py`

Add a factory helper:

```python
def _make_vm_orchestrator(
    repo_root: Path,
    request: E2eRequest,
    shell: ShellBackend,
    multipass_client: MultipassClient | None = None,
) -> VmOrchestrator | AzureVmOrchestrator:
    if request.vm and request.vm.lifecycle == "azure":
        return AzureVmOrchestrator(repo_root)
    return VmOrchestrator(repo_root, shell=shell, multipass_client=multipass_client)
```

In `plan_recipe_steps` and `E2eRunner.plan`, add `"azure-vm-loadtest"` to the set of
scenarios that use `plan_recipe_steps` (same branch as `"two-vm-loadtest"`).

In `E2eRunner.plan_all`, add `loadgen_vm` resolution for `azure-vm-loadtest` with
`lifecycle="azure"` — same pattern as `two-vm-loadtest`, naming the loadgen VM
`"nanofaas-azure-loadgen"`.

### `scenario/components/recipes.py`

The `azure-vm-loadtest` recipe is identical to `two-vm-loadtest` (same phase list):

```
loadgen.ensure_running
loadgen.provision_base
loadgen.install_k6
vm.ensure_running        (stack VM)
vm.provision_base
vm.install_k3s
vm.setup_registry
vm.sync_project
vm.helm_install
loadgen.run_k6
metrics.prometheus_snapshot
loadtest.write_report
loadgen.down
vm.down
```

## Section 4 — CLI & Testing

### CLI (`cli/e2e_commands.py`)

Expose new options on `e2e run` for the Azure lifecycle:

```bash
./scripts/controlplane.sh e2e run azure-vm-loadtest \
  --lifecycle azure \
  --azure-resource-group my-rg \
  --azure-location westeurope \
  --azure-vm-size Standard_B2s \
  --name nanofaas-azure
```

`--lifecycle azure` sets `VmRequest.lifecycle = "azure"`. The `--azure-*` flags map
to the corresponding `VmRequest` fields.

### Testing

- `tests/test_azure_vm_loadtest_components.py` — unit tests for `AzureVmOrchestrator`
  using `FakeBackend` from `azure-vm-sdk`. Covers `ensure_running`, `exec_argv`,
  `transfer_to`, `transfer_from`, `teardown`.
- `tests/test_azure_vm_loadtest_runner.py` — unit tests for `TwoVmLoadtestRunner` with
  a mock `AzureVmOrchestrator`. Mirrors `test_two_vm_loadtest_runner.py`.
- `tests/test_e2e_catalog.py` — add `"azure-vm-loadtest"` to catalog assertions.
- `tests/test_scenario_loader.py` — add `"azure-vm-loadtest"` to scenario load cases.

No automated integration tests (require live Azure credentials).

## Dependencies

| Package | Source |
|---|---|
| `azure-vm-sdk` | `git+https://github.com/miciav/azure-vm-sdk.git@2311b1c` |
| `multipass-sdk` | already present — no change |

## Out of scope

- Provisioning the Azure resource group or VNet before the scenario runs (assumed to
  exist or created by `AzureClient` shared-infra on first launch).
- Cost management or VM auto-shutdown after the experiment.
- Azure-specific Prometheus or Grafana configuration (same NodePort setup as Multipass).
