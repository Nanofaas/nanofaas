from __future__ import annotations

from pathlib import Path

from controlplane_tool.scenario_flows import build_scenario_flow


def test_k8s_vm_flow_uses_reusable_vm_and_deploy_tasks() -> None:
    flow = build_scenario_flow("k8s-vm", repo_root=Path("/repo"))

    assert flow.task_ids == [
        "vm.ensure_running",
        "vm.provision_base",
        "repo.sync_to_vm",
        "registry.ensure_container",
        "images.build_core",
        "k3s.install",
        "k3s.configure_registry",
        "tests.run_k8s_e2e",
    ]


def test_cli_vm_flow_reuses_build_and_helm_deploy_tasks() -> None:
    flow = build_scenario_flow("cli", repo_root=Path("/repo"))

    assert "helm.deploy_control_plane" in flow.task_ids


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
