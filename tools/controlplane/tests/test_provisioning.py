from dataclasses import dataclass
from pathlib import Path

import pytest

from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from controlplane_tool.cli.provisioning import provision_environment


@dataclass
class _Result:
    return_code: int = 0
    stdout: str = ""
    stderr: str = ""


class RecordingOrchestrator:
    def __init__(self) -> None:
        self.actions: list[tuple[str, str]] = []
        self.ansible = self

    def _record(self, action, request):
        self.actions.append((action, request.name or request.host or ""))
        return _Result()

    def ensure_running(self, request):
        return self._record("ensure", request)

    def install_dependencies(self, request, *, install_helm):
        assert install_helm
        return self._record("base", request)

    def install_k3s(self, request):
        return self._record("k3s", request)

    def setup_registry(self, request):
        return self._record("registry", request)

    def sync_project(self, request):
        return self._record("sync", request)

    def run_playbook(self, name, request):
        assert name == "install-k6.yml"
        return self._record("k6", request)


def test_multipass_k8s_provisioning_prepares_stack_in_order(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    provision_environment(
        ScenarioConfig(workflow="validate", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    )

    assert orchestrator.actions == [
        ("ensure", "stack"),
        ("base", "stack"),
        ("k3s", "stack"),
        ("registry", "stack"),
        ("sync", "stack"),
    ]


def test_external_provisioning_reuses_ssh_host_without_teardown(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    provision_environment(
        ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {"provider": "external", "roles": {"stack": {"host": "vm.example"}}}
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    )

    assert orchestrator.actions[0] == ("ensure", "vm.example")
    assert all(action != "teardown" for action, _ in orchestrator.actions)


def test_loadtest_provisions_dedicated_load_generator_with_k6(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    provision_environment(
        ScenarioConfig(workflow="loadtest", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {
                "provider": "multipass",
                "roles": {
                    "stack": {"name": "stack"},
                    "loadgen": {"name": "loadgen"},
                },
            }
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    )

    assert ("k6", "loadgen") in orchestrator.actions
    assert orchestrator.actions[-1] == ("sync", "loadgen")


def test_provisioning_stops_on_first_failed_operation(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    def fail_k3s(request):
        orchestrator.actions.append(("k3s", request.name or ""))
        return _Result(return_code=1, stderr="k3s failed")

    orchestrator.install_k3s = fail_k3s

    with pytest.raises(RuntimeError, match="k3s failed"):
        provision_environment(
            ScenarioConfig(workflow="validate", backend="k8s", functions=["word-stats-java"]),
            EnvironmentConfig.model_validate(
                {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
            ),
            repo_root=tmp_path,
            orchestrator_factory=lambda _: orchestrator,
        )

    assert orchestrator.actions == [("ensure", "stack"), ("base", "stack"), ("k3s", "stack")]
