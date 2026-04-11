from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.cli_test_models import CliTestRequest
from controlplane_tool.scenario_components.environment import (
    resolve_scenario_environment,
)
from controlplane_tool.vm_models import VmRequest


def test_e2e_request_allows_missing_vm_for_managed_vm_scenarios() -> None:
    request = E2eRequest(scenario="k3s-junit-curl", runtime="java", vm=None)

    assert request.vm is None


def test_environment_resolver_creates_managed_vm_when_request_has_none(
    tmp_path: Path,
) -> None:
    request = E2eRequest(scenario="helm-stack", runtime="java", vm=None)

    context = resolve_scenario_environment(repo_root=tmp_path, request=request)

    assert context.repo_root == tmp_path
    assert context.request is request
    assert request.vm is None
    assert context.vm_request is not None
    assert context.vm_request.lifecycle == "multipass"
    assert context.vm_request.name == "nanofaas-e2e"


def test_cli_test_request_cli_stack_can_be_resolved_without_vm(
    tmp_path: Path,
) -> None:
    request = CliTestRequest(scenario="cli-stack", runtime="java", vm=None)

    context = resolve_scenario_environment(repo_root=tmp_path, request=request)

    assert context.request is request
    assert context.vm_request is not None
    assert context.vm_request == VmRequest(lifecycle="multipass", name="nanofaas-e2e")
