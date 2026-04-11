from __future__ import annotations

from pathlib import Path

from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.scenario_components.environment import (
    resolve_scenario_environment,
)


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
