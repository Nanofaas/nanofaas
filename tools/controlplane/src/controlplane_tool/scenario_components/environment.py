from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

from controlplane_tool.cli_test_models import CLI_TEST_VM_BACKED_SCENARIOS, CliTestRequest
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.models import RuntimeKind, VM_BACKED_SCENARIOS
from controlplane_tool.scenario_models import ResolvedScenario
from controlplane_tool.scenario_manifest import write_scenario_manifest
from controlplane_tool.scenario_defaults import (
    resolve_scenario_namespace,
    resolve_scenario_release,
)
from controlplane_tool.scenario_components.recipes import build_scenario_recipe
from controlplane_tool.vm_models import VmRequest


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
    cleanup_vm: bool
    manifest_path: Path | None = None
    release: str | None = None


def default_managed_vm_request() -> VmRequest:
    return VmRequest(lifecycle="multipass", name="nanofaas-e2e")


def _managed_vm_request(request: E2eRequest | CliTestRequest) -> VmRequest:
    if request.vm is not None:
        return request.vm
    if isinstance(request, E2eRequest):
        try:
            recipe = build_scenario_recipe(request.scenario)
        except ValueError:
            recipe = None
        if recipe is not None and recipe.requires_managed_vm:
            return default_managed_vm_request()
    if request.scenario in VM_BACKED_SCENARIOS or request.scenario in CLI_TEST_VM_BACKED_SCENARIOS:
        return default_managed_vm_request()
    raise ValueError(f"scenario '{request.scenario}' requires an explicit vm request")


def resolve_scenario_environment(
    repo_root: Path,
    request: E2eRequest | CliTestRequest,
    *,
    manifest_root: Path | None = None,
    release: str | None = None,
) -> ScenarioExecutionContext:
    manifest_path = (
        write_scenario_manifest(request.resolved_scenario, root=manifest_root)
        if manifest_root is not None and request.resolved_scenario is not None
        else None
    )
    effective_namespace = resolve_scenario_namespace(
        request.scenario,
        explicit_namespace=request.namespace,
        resolved_scenario_namespace=(
            request.resolved_scenario.namespace if request.resolved_scenario is not None else None
        ),
    )
    effective_release = resolve_scenario_release(
        request.scenario,
        explicit_release=release,
    )

    return ScenarioExecutionContext(
        repo_root=repo_root,
        request=request,
        scenario_name=request.scenario,
        runtime=request.runtime,
        namespace=effective_namespace,
        local_registry=request.local_registry,
        resolved_scenario=request.resolved_scenario,
        vm_request=_managed_vm_request(request),
        cleanup_vm=getattr(request, "cleanup_vm", True),
        manifest_path=manifest_path,
        release=effective_release,
    )
