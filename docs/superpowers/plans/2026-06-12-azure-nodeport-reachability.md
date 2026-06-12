# Azure NodePort Reachability Implementation Plan (nanofaas)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The azure-vm-loadtest stack VM must expose the k8s NodePorts (30080 control-plane, 30081 actuator, 30090 Prometheus) through its NSG, so function registration (operator machine), Prometheus snapshots (operator machine) and k6 (loadgen VM via public IP) can reach it. Today the run dies at step 40/48 with a connect timeout.

**Architecture:** `VmRequest` gains `azure_open_ports`; the azure provider forwards it to `AzureClient.ensure_running(open_ports=...)` (SDK support from the companion plan); the CLI request builder sets the three NodePorts on the azure STACK VM only (loadgen needs nothing inbound beyond SSH). Proxmox needs nothing — it already solves reachability via NAT port publishing (`vm.stack.publish_ports`).

**Tech Stack:** Python (`workflow_tasks`, `controlplane_tool`), pytest via `uv`.

**GATE:** the companion SDK plan (`~/Downloads/azure-vm-sdk/docs/superpowers/plans/2026-06-12-nsg-open-ports.md`) must be implemented; final task additionally requires the user to have PUSHED it (pin bump).

---

## File map

- `tools/workflow-tasks/src/workflow_tasks/vm/models.py` — `VmRequest.azure_open_ports: tuple[int, ...] | None = None`
- `tools/workflow-tasks/src/workflow_tasks/vm/azure.py` — forward `open_ports=request.azure_open_ports` in `ensure_running`
- `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py` — `_build_vm_request` gains `azure_open_ports`; `_build_request` sets the NodePorts tuple on the azure stack VM
- Tests: `tools/workflow-tasks/tests/vm/test_azure_provider.py` (or the credentials regression file), `tools/controlplane/tests/test_azure_vm_request.py` + the e2e request-resolution test file

### Task 1: plumb `azure_open_ports` end to end (TDD)

- [ ] **Step 1: failing tests.**

(a) workflow-tasks — add to `tools/workflow-tasks/tests/vm/test_adapters_credentials.py` (it already has the recording-orchestrator pattern) or `test_azure_provider.py`:

```python
def test_azure_provider_forwards_open_ports_to_sdk(monkeypatch):
    from workflow_tasks.vm.azure import AzureVmProvider  # adapt to the real class name in vm/azure.py
    from workflow_tasks.vm.models import VmRequest

    captured = {}

    class _FakeClient:
        def ensure_running(self, name, **kwargs):
            captured.update(kwargs, name=name)
            class _Vm:
                def wait_for_ip(self):
                    return "1.2.3.4"
            return _Vm()

    provider = AzureVmProvider(repo_root=".")  # adapt ctor to reality
    monkeypatch.setattr(provider, "_client", lambda request: _FakeClient())

    provider.ensure_running(
        VmRequest(
            lifecycle="azure",
            name="stack",
            azure_resource_group="rg",
            azure_location="westeurope",
            azure_open_ports=(30080, 30081, 30090),
        )
    )

    assert captured["open_ports"] == (30080, 30081, 30090)
```

READ `tools/workflow-tasks/src/workflow_tasks/vm/azure.py` first and adapt the plumbing (class name, ctor, what ensure_running returns/does after the client call) — the ASSERTION (open_ports forwarded) is the requirement.

(b) controlplane — add to `tools/controlplane/tests/test_azure_vm_request.py`:

```python
def test_azure_stack_request_opens_nodeports():
    from controlplane_tool.cli.e2e_commands import _resolve_run_request

    request = _resolve_run_request(
        scenario="azure-vm-loadtest", runtime="java", lifecycle="azure",
        name=None, host=None, user="azureuser", home=None,
        cpus=4, memory="12G", disk="30G", cleanup_vm=True,
        namespace=None, local_registry=None, function_preset=None,
        functions_csv=None, scenario_file=None, saved_profile=None,
        azure_resource_group="rg", azure_location="westeurope",
    )

    assert request.vm.azure_open_ports == (30080, 30081, 30090)
    assert request.loadgen_vm.azure_open_ports is None
```

- [ ] **Step 2: run both → expect failures** (unknown field / kwarg).

- [ ] **Step 3: implement.**

1. `VmRequest` (`tools/workflow-tasks/src/workflow_tasks/vm/models.py`): add `azure_open_ports: tuple[int, ...] | None = None` next to the other azure_* fields. Check whether `controlplane_tool` re-exports/duplicates VmRequest (`tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py`) — if it's a re-export, nothing to do; if duplicated, mirror the field.
2. `vm/azure.py` `ensure_running`: add `open_ports=request.azure_open_ports` to the `self._client(request).ensure_running(...)` kwargs.
3. `e2e_commands.py`: `_build_vm_request` gains `azure_open_ports: tuple[int, ...] | None = None` parameter forwarded into `VmRequest(...)`; in `_build_request`'s STACK construction, pass

```python
            azure_open_ports=(
                (
                    TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT,
                    TWO_VM_CONTROL_PLANE_ACTUATOR_NODE_PORT,
                    TWO_VM_PROMETHEUS_NODE_PORT,
                )
                if scenario == "azure-vm-loadtest"
                else None
            ),
```

with the constants imported from `workflow_tasks.loadtest.two_vm`. The LOADGEN construction does NOT pass it (stays None — nothing inbound but SSH).

- [ ] **Step 4: full suites green** — `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests` (coverage gate: always full suite) and `uv run --project tools/controlplane pytest tools/controlplane/tests -q`.

- [ ] **Step 5: commit** on a feature branch (`fix/azure-nodeport-nsg`):

```bash
git add tools/workflow-tasks tools/controlplane
git commit -m "fix(azure): open k8s NodePorts in the stack VM NSG (register/prometheus/k6 reachability)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

### Task 2 (GATED on the user pushing the SDK): bump azure-vm-sdk pin

- [ ] Same procedure as the previous bumps: replace the current pin in BOTH `tools/workflow-tasks/pyproject.toml` and `tools/controlplane/pyproject.toml` with the new SHA, `uv lock` both, sanity-check `inspect.getsource(azure_vm.client.AzureClient.launch)` contains `open_ports`, full suites, commit on the same branch, push, PR.

### Final verification

- [ ] Both suites green.
- [ ] Real-Azure smoke: re-run `azure-vm-loadtest` from the TUI — step 40 (register) must pass; watch steps 43 (k6) and 45 (prometheus snapshot), which depend on the same NSG rules. Cost note: provisions 2 VMs (D4s_v5 + B1s).
- [ ] Security note for the PR: ports are world-open for the VM lifetime (consistent with the existing SSH rule); source restriction listed as future work.
