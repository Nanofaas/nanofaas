from dataclasses import dataclass
from pathlib import Path

import pytest

from controlplane_tool.cli.provisioning import provision_environment
from controlplane_tool.config import EnvironmentConfig, ScenarioConfig


@dataclass
class _Result:
    return_code: int = 0
    stdout: str = ""
    stderr: str = ""


class RecordingShell:
    def __init__(self, events: list[tuple[str, object]]) -> None:
        self.events = events
        self.fail_playbook: str | None = None

    def run(self, argv, *, cwd=None, env=None, dry_run=False):
        command = tuple(argv)
        self.events.append(("command", command))
        if self.fail_playbook and command[-1].endswith(self.fail_playbook):
            return _Result(return_code=1, stderr=f"{self.fail_playbook} failed")
        return _Result()


class RecordingOrchestrator:
    def __init__(self) -> None:
        self.events: list[tuple[str, object]] = []
        self.shell = RecordingShell(self.events)
        self.ensure_result = _Result()

    def ensure_running(self, request):
        target = request.name or request.host or ""
        self.events.append(("ensure", (request.lifecycle, target)))
        return self.ensure_result

    def connection_host(self, request):
        return request.host or f"{request.name}.internal"

    def teardown(self, request):
        self.events.append(("teardown", request.name or request.host or ""))
        return _Result()

    def ssh_endpoint(self, request):
        return "pve.example", 42022

    def ssh_private_key_path(self, request):
        return Path("/keys/provider")


def _playbooks(orchestrator: RecordingOrchestrator) -> list[str]:
    return [
        Path(command[-1]).name
        for kind, command in orchestrator.events
        if kind == "command" and command[0] == "ansible-playbook"
    ]


def _commands(orchestrator: RecordingOrchestrator) -> list[tuple[str, ...]]:
    return [command for kind, command in orchestrator.events if kind == "command"]


def test_multipass_k8s_provisioning_composes_lifecycle_and_bootstrap_tasks(
    tmp_path: Path,
) -> None:
    orchestrator = RecordingOrchestrator()

    with provision_environment(
        ScenarioConfig(workflow="validate", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    ):
        pass

    assert orchestrator.events[0] == ("ensure", ("multipass", "stack"))
    assert _playbooks(orchestrator) == [
        "provision-base.yml",
        "provision-k3s.yml",
        "ensure-registry.yml",
        "configure-k3s-registry.yml",
    ]
    assert _commands(orchestrator)[-1][0] == "rsync"
    assert orchestrator.events[-1] == ("teardown", "stack")


def test_external_provisioning_reuses_ssh_host_without_teardown(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    with provision_environment(
        ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {"provider": "external", "roles": {"stack": {"host": "vm.example"}}}
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    ):
        pass

    assert orchestrator.events[0] == ("ensure", ("external", "vm.example"))
    assert all(kind != "teardown" for kind, _ in orchestrator.events)
    assert all("vm.example" in " ".join(command) for command in _commands(orchestrator))


def test_loadtest_provisions_dedicated_load_generator_with_k6(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    with provision_environment(
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
    ):
        pass

    ensures = [value for kind, value in orchestrator.events if kind == "ensure"]
    assert ensures == [("multipass", "stack"), ("multipass", "loadgen")]
    assert _playbooks(orchestrator)[-1] == "install-k6.yml"
    assert _commands(orchestrator)[-1][0] == "rsync"
    assert "loadgen.internal" in " ".join(_commands(orchestrator)[-1])
    teardowns = [value for kind, value in orchestrator.events if kind == "teardown"]
    assert teardowns == ["loadgen", "stack"]


def test_arm_builder_role_is_ensured_torn_down_and_base_provisioned(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    with provision_environment(
        ScenarioConfig(workflow="loadtest", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {
                "provider": "azure",
                "roles": {
                    "stack": {"name": "stack", "disk": "128G"},
                    "loadgen": {"name": "loadgen", "disk": "30G"},
                    "arm-builder": {"name": "arm", "disk": "64G"},
                },
                "azure": {"resource_group": "rg", "location": "westeurope"},
            }
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    ):
        pass

    ensures = [value for kind, value in orchestrator.events if kind == "ensure"]
    assert ("azure", "arm") in ensures
    teardowns = [value for kind, value in orchestrator.events if kind == "teardown"]
    assert "arm" in teardowns
    # the arm builder gets base packages (docker + socat) but no k3s/registry
    arm_playbooks = [
        Path(command[-1]).name
        for kind, command in orchestrator.events
        if kind == "command"
        and command[0] == "ansible-playbook"
        and "arm" in " ".join(command)
    ]
    assert "provision-base.yml" in arm_playbooks
    assert "provision-k3s.yml" not in arm_playbooks


def test_post_ensure_verifier_runs_before_each_vm_bootstrap(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    def verify(role, request) -> None:
        orchestrator.events.append(("verify", (role, request.lifecycle, request.name)))

    with provision_environment(
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
        post_ensure_verifier=verify,
    ):
        pass

    stack_ensure = orchestrator.events.index(("ensure", ("multipass", "stack")))
    stack_verify = orchestrator.events.index(
        ("verify", ("stack", "multipass", "stack"))
    )
    first_command = next(
        index for index, event in enumerate(orchestrator.events) if event[0] == "command"
    )
    loadgen_ensure = orchestrator.events.index(("ensure", ("multipass", "loadgen")))
    loadgen_verify = orchestrator.events.index(
        ("verify", ("loadgen", "multipass", "loadgen"))
    )
    assert stack_ensure < loadgen_ensure < stack_verify < loadgen_verify < first_command


def test_second_pre_bootstrap_verifier_failure_cleans_up_without_commands(
    tmp_path: Path,
) -> None:
    orchestrator = RecordingOrchestrator()

    def verify(role, request) -> None:
        orchestrator.events.append(("verify", (role, request.name)))
        if role == "loadgen":
            raise RuntimeError("final ingress verification failed")

    with pytest.raises(RuntimeError, match="final ingress verification failed"):
        with provision_environment(
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
            post_ensure_verifier=verify,
        ):
            pass

    assert _commands(orchestrator) == []
    assert [value for kind, value in orchestrator.events if kind == "ensure"] == [
        ("multipass", "stack"),
        ("multipass", "loadgen"),
    ]
    assert [value for kind, value in orchestrator.events if kind == "teardown"] == [
        "loadgen",
        "stack",
    ]


def test_provisioning_stops_on_first_failed_bootstrap_task(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()
    orchestrator.shell.fail_playbook = "provision-k3s.yml"

    with pytest.raises(RuntimeError, match="provision-k3s.yml failed"):
        with provision_environment(
            ScenarioConfig(workflow="validate", backend="k8s", functions=["word-stats-java"]),
            EnvironmentConfig.model_validate(
                {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
            ),
            repo_root=tmp_path,
            orchestrator_factory=lambda _: orchestrator,
        ):
            pass

    assert _playbooks(orchestrator) == ["provision-base.yml", "provision-k3s.yml"]
    assert not any(command[0] == "rsync" for command in _commands(orchestrator))
    assert orchestrator.events[-1] == ("teardown", "stack")


def test_provisioning_stops_when_vm_lifecycle_preflight_fails(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()
    orchestrator.ensure_result = _Result(return_code=255, stderr="SSH unavailable")

    with pytest.raises(RuntimeError, match="SSH unavailable"):
        with provision_environment(
            ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
            EnvironmentConfig.model_validate(
                {"provider": "external", "roles": {"stack": {"host": "vm.example"}}}
            ),
            repo_root=tmp_path,
            orchestrator_factory=lambda _: orchestrator,
        ):
            pass

    assert _commands(orchestrator) == []


def test_managed_vm_preflight_failure_still_triggers_teardown(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()
    orchestrator.ensure_result = _Result(return_code=255, stderr="SSH unavailable")

    with pytest.raises(RuntimeError, match="SSH unavailable"):
        with provision_environment(
            ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
            EnvironmentConfig.model_validate(
                {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
            ),
            repo_root=tmp_path,
            orchestrator_factory=lambda _: orchestrator,
        ):
            pass

    assert orchestrator.events[-1] == ("teardown", "stack")


def test_azure_provisioning_uses_public_endpoint_and_provider_key(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    with provision_environment(
        ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {
                "provider": "azure",
                "roles": {"stack": {}},
                "azure": {"resource_group": "rg", "location": "westeurope"},
            }
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    ):
        pass

    assert orchestrator.events[0] == ("ensure", ("azure", "nanofaas-azure"))
    commands = _commands(orchestrator)
    ansible = commands[0]
    assert ansible[ansible.index("-i") + 1] == "nanofaas-azure.internal,"
    assert ansible[ansible.index("--private-key") + 1] == "/keys/provider"
    assert commands[-1][-1] == "azureuser@nanofaas-azure.internal:/home/azureuser/nanofaas/"
    assert orchestrator.events[-1] == ("teardown", "nanofaas-azure")


def test_proxmox_provisioning_retargets_bootstrap_to_ssh_nat(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("PROXMOX_PASSWORD", "secret")
    orchestrator = RecordingOrchestrator()

    with provision_environment(
        ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {
                "provider": "proxmox",
                "roles": {"stack": {}},
                "proxmox": {"host": "pve.example", "node": "pve1"},
            }
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
    ):
        pass

    assert orchestrator.events[0] == ("ensure", ("proxmox", "nanofaas-proxmox"))
    commands = _commands(orchestrator)
    ansible = commands[0]
    assert ansible[ansible.index("-i") + 1] == "pve.example,"
    assert ansible[ansible.index("ansible_port=42022") - 1] == "-e"
    assert "-p 42022" in commands[-1][commands[-1].index("-e") + 1]
    assert commands[-1][-1] == "ubuntu@pve.example:/home/ubuntu/nanofaas/"
    assert orchestrator.events[-1] == ("teardown", "nanofaas-proxmox")


def test_keep_preserves_managed_vm(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    with provision_environment(
        ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig.model_validate(
            {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
        ),
        repo_root=tmp_path,
        orchestrator_factory=lambda _: orchestrator,
        keep=True,
    ):
        pass

    assert all(kind != "teardown" for kind, _ in orchestrator.events)


def test_workflow_failure_still_destroys_managed_vm(tmp_path: Path) -> None:
    orchestrator = RecordingOrchestrator()

    with pytest.raises(RuntimeError, match="workflow failed"):
        with provision_environment(
            ScenarioConfig(workflow="cli", backend="k8s", functions=["word-stats-java"]),
            EnvironmentConfig.model_validate(
                {"provider": "multipass", "roles": {"stack": {"name": "stack"}}}
            ),
            repo_root=tmp_path,
            orchestrator_factory=lambda _: orchestrator,
        ):
            raise RuntimeError("workflow failed")

    assert orchestrator.events[-1] == ("teardown", "stack")
