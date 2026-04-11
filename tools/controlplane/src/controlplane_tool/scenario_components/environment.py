from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.cli_test_models import CliTestRequest
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.models import RuntimeKind, VM_BACKED_SCENARIOS
from controlplane_tool.scenario_models import ResolvedScenario
from controlplane_tool.vm_models import VmRequest

CLI_TEST_VM_BACKED_SCENARIOS = frozenset({"vm", "cli-stack", "host-platform"})


@dataclass(frozen=True, slots=True)
class ScenarioExecutionContext:
    repo_root: Path
    request: E2eRequest | CliTestRequest
    scenario_name: str
    runtime: RuntimeKind
    namespace: str | None
    local_registry: str
    resolved_scenario: ResolvedScenario | None
    vm_request: VmRequest


def default_managed_vm_request() -> VmRequest:
    return VmRequest(lifecycle="multipass", name="nanofaas-e2e")


def _managed_vm_request(request: E2eRequest | CliTestRequest) -> VmRequest:
    if request.vm is not None:
        return request.vm
    if request.scenario in VM_BACKED_SCENARIOS or request.scenario in CLI_TEST_VM_BACKED_SCENARIOS:
        return default_managed_vm_request()
    raise ValueError(f"scenario '{request.scenario}' requires an explicit vm request")


def resolve_scenario_environment(
    repo_root: Path,
    request: E2eRequest | CliTestRequest,
) -> ScenarioExecutionContext:
    return ScenarioExecutionContext(
        repo_root=repo_root,
        request=request,
        scenario_name=request.scenario,
        runtime=request.runtime,
        namespace=request.namespace,
        local_registry=request.local_registry,
        resolved_scenario=request.resolved_scenario,
        vm_request=_managed_vm_request(request),
    )
