from __future__ import annotations

import importlib.util
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
MODULE_PATH = REPO_ROOT / "experiments" / "autoscaling.py"
SPEC = importlib.util.spec_from_file_location("autoscaling", MODULE_PATH)
assert SPEC is not None and SPEC.loader is not None
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
AutoscalingExperiment = MODULE.AutoscalingExperiment


def test_phase_a_register_uses_cluster_service_url(monkeypatch) -> None:
    experiment = AutoscalingExperiment(namespace="nanofaas-e2e")
    commands: list[str] = []

    monkeypatch.setattr(experiment, "_cluster_api_url", lambda: "http://10.43.191.148:8080")
    monkeypatch.setattr("time.sleep", lambda _: None)

    def fake_vm_exec(command: str) -> str:
        commands.append(command)
        if " -X POST " in command:
            return "201"
        if "kubectl get deployment" in command:
            return "ok"
        return ""

    monkeypatch.setattr(experiment, "_vm_exec", fake_vm_exec)

    experiment._phase_a_register()

    assert any("http://10.43.191.148:8080/v1/functions/word-stats-java" in command for command in commands)
    assert any("http://10.43.191.148:8080/v1/functions" in command for command in commands)


def test_host_api_url_uses_node_port_when_service_is_exposed(monkeypatch) -> None:
    experiment = AutoscalingExperiment(namespace="nanofaas-e2e")

    monkeypatch.setattr(experiment, "_control_plane_service_type", lambda: "NodePort")
    monkeypatch.setattr(experiment, "_control_plane_http_node_port", lambda: "30080")
    monkeypatch.setattr(experiment, "_resolve_public_host", lambda: "192.168.2.2")

    with experiment._host_api_url_context() as url:
        assert url == "http://192.168.2.2:30080"


def test_host_api_url_port_forwards_cluster_ip_service(monkeypatch, tmp_path: Path) -> None:
    experiment = AutoscalingExperiment(namespace="nanofaas-e2e")
    kubeconfig = tmp_path / "kubeconfig.yaml"
    kubeconfig.write_text("apiVersion: v1\n", encoding="utf-8")
    commands: list[list[str]] = []

    class FakeProc:
        def __init__(self) -> None:
            self.returncode = None
            self.terminated = False

        def poll(self):  # noqa: ANN201
            return self.returncode

        def terminate(self) -> None:
            self.terminated = True
            self.returncode = 0

        def wait(self, timeout=None) -> int:  # noqa: ANN001,ARG002
            self.returncode = 0
            return 0

        def kill(self) -> None:
            self.returncode = -9

        def communicate(self) -> tuple[str, str]:
            return ("", "")

    proc = FakeProc()

    monkeypatch.setattr(experiment, "_control_plane_service_type", lambda: "ClusterIP")
    monkeypatch.setattr(experiment, "_resolve_public_host", lambda: "192.168.2.2")
    monkeypatch.setattr(experiment, "_export_host_kubeconfig", lambda host: kubeconfig)
    monkeypatch.setattr(experiment, "_pick_port_forward_port", lambda: 18080)
    monkeypatch.setattr("shutil.which", lambda binary: f"/usr/bin/{binary}")
    monkeypatch.setattr(
        "subprocess.Popen",
        lambda command, **kwargs: commands.append(list(command)) or proc,  # noqa: ARG005
    )
    monkeypatch.setattr(
        experiment,
        "_is_url_reachable",
        lambda url: url == "http://127.0.0.1:18080/v1/functions",
    )

    with experiment._host_api_url_context() as url:
        assert url == "http://127.0.0.1:18080"
        assert kubeconfig.exists()

    assert proc.terminated is True
    assert not kubeconfig.exists()
    assert commands == [[
        "/usr/bin/kubectl",
        "--kubeconfig",
        str(tmp_path / "kubeconfig.yaml"),
        "-n",
        "nanofaas-e2e",
        "port-forward",
        "svc/control-plane",
        "18080:8080",
        "--address",
        "127.0.0.1",
    ]]


def test_phase_a_waits_for_deployment_with_kubeconfig_prefix(monkeypatch) -> None:
    experiment = AutoscalingExperiment(namespace="nanofaas-e2e")
    commands: list[str] = []

    monkeypatch.setenv("E2E_KUBECONFIG_PATH", "/home/ubuntu/.kube/config")
    monkeypatch.setattr("time.sleep", lambda _: None)

    def fake_vm_exec(command: str) -> str:
        commands.append(command)
        if " -X POST " in command:
            return "201"
        if "kubectl get deployment" in command:
            return "ok"
        return ""

    monkeypatch.setattr(experiment, "_vm_exec", fake_vm_exec)
    monkeypatch.setattr(experiment, "_cluster_api_url", lambda: "http://10.43.191.148:8080")

    experiment._phase_a_register()

    deployment_wait = next(command for command in commands if "kubectl get deployment" in command)
    assert deployment_wait.startswith("KUBECONFIG=/home/ubuntu/.kube/config ")


def test_get_desired_replicas_uses_kubeconfig_prefix(monkeypatch) -> None:
    experiment = AutoscalingExperiment(namespace="nanofaas-e2e")
    commands: list[str] = []

    monkeypatch.setenv("E2E_KUBECONFIG_PATH", "/home/ubuntu/.kube/config")

    def fake_vm_exec(command: str) -> str:
        commands.append(command)
        return "0"

    monkeypatch.setattr(experiment, "_vm_exec", fake_vm_exec)

    assert experiment._get_desired_replicas("fn-word-stats-java") == 0

    assert commands == [
        "KUBECONFIG=/home/ubuntu/.kube/config kubectl get deployment fn-word-stats-java -n nanofaas-e2e -o jsonpath='{.spec.replicas}' 2>/dev/null || echo 0"
    ]
