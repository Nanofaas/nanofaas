from __future__ import annotations

from pathlib import Path

import pytest

import controlplane_tool.scenario_flows as scenario_flows_mod
from controlplane_tool.e2e_models import E2eRequest
from controlplane_tool.scenario_flows import build_scenario_flow
from controlplane_tool.vm_models import VmRequest


def test_k8s_vm_flow_uses_reusable_vm_and_deploy_tasks() -> None:
    flow = build_scenario_flow(
        "k8s-vm",
        repo_root=Path("/repo"),
        request=E2eRequest(
            scenario="k8s-vm",
            runtime="java",
            vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        ),
    )

    assert flow.task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "k3s.install",
        "registry.ensure_container",
        "k3s.configure_registry",
        "images.build_core",
        "tests.run_k8s_e2e",
    ]


def test_cli_vm_flow_reuses_build_and_helm_deploy_tasks() -> None:
    flow = build_scenario_flow("cli", repo_root=Path("/repo"))

    assert "helm.deploy_control_plane" in flow.task_ids


def test_k8s_vm_flow_requires_request_for_executable_definition() -> None:
    with pytest.raises(ValueError):
        build_scenario_flow("k8s-vm", repo_root=Path("/repo"))


def test_request_backed_scenario_flow_forwards_event_listener(monkeypatch) -> None:
    called: dict[str, object] = {}

    monkeypatch.setattr(
        scenario_flows_mod.E2eRunner,
        "run",
        lambda self, request, event_listener=None: called.update(  # noqa: ANN001
            {"request": request, "event_listener": event_listener}
        ) or "ok",
    )
    request = E2eRequest(
        scenario="k8s-vm",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )
    listener = lambda event: None  # noqa: ARG005,E731

    flow = build_scenario_flow(
        "k8s-vm",
        repo_root=Path("/repo"),
        request=request,
        event_listener=listener,
    )

    assert flow.run() == "ok"
    assert called["request"] is request
    assert called["event_listener"] is listener


def test_runner_modules_no_longer_inline_docker_and_helm_orchestration() -> None:
    repo_root = Path(__file__).resolve().parents[3]
    for relative_path in [
        "tools/controlplane/src/controlplane_tool/e2e_runner.py",
        "tools/controlplane/src/controlplane_tool/cli_vm_runner.py",
        "tools/controlplane/src/controlplane_tool/k3s_curl_runner.py",
    ]:
        source = (repo_root / relative_path).read_text(encoding="utf-8")
        assert "docker build" not in source
        assert "docker push" not in source
        assert "helm upgrade --install" not in source
