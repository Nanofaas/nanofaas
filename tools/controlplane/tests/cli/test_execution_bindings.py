from pathlib import Path

from controlplane_tool.cli.execution import build_role_bindings, resolve_loadtest_urls
from controlplane_tool.config.environment import EnvironmentConfig
from workflow_tasks.tasks.models import CommandTaskSpec


class RecordingRunner:
    def __init__(self) -> None:
        self.calls: list[tuple[list[str], Path | None, dict[str, str], bool]] = []

    def run(self, argv, *, cwd, env, dry_run):
        self.calls.append((argv, cwd, env, dry_run))
        return type("Result", (), {"return_code": 0, "stdout": "", "stderr": ""})()


class RecordingVmProvider:
    def __init__(self) -> None:
        self.exec_calls = []
        self.fetch_calls = []

    def exec_argv(self, request, argv, *, env, cwd, dry_run):
        self.exec_calls.append((request, tuple(argv), env, cwd, dry_run))
        return type("Result", (), {"return_code": 0, "stdout": "", "stderr": ""})()

    def transfer_from(self, request, *, source, destination):
        self.fetch_calls.append((request, source, destination))
        return type("Result", (), {"return_code": 0, "stdout": "", "stderr": ""})()

    def connection_host(self, request):
        return "20.30.40.50"

    def guest_host(self, request):
        return "10.0.0.50"

    def publish_port(self, request, *, service, guest_port):
        assert service == "PROMETHEUS_HTTP"
        assert guest_port == 30090
        return "pve.example", 43090


def test_external_stack_uses_ssh_in_remote_repository() -> None:
    runner = RecordingRunner()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "external",
            "roles": {"stack": {"host": "vm.example", "user": "alice", "home": "/srv/alice"}},
        }
    )

    bindings, _ = build_role_bindings(environment, runner=runner)
    bindings.stack.run(
        CommandTaskSpec(
            task_id="check",
            summary="check",
            argv=("ansible-playbook", "site.yml"),
            role="stack",
        )
    )

    assert runner.calls[0][0][:4] == ["ssh", "-o", "BatchMode=yes", "alice@vm.example"]
    assert (
        "cd /srv/alice/nanofaas && env KUBECONFIG=/srv/alice/.kube/config ansible-playbook site.yml"
    ) in runner.calls[0][0][-1]


def test_multipass_stack_uses_named_instance() -> None:
    runner = RecordingRunner()
    environment = EnvironmentConfig.model_validate(
        {"provider": "multipass", "roles": {"stack": {"name": "nanofaas-stack"}}}
    )

    bindings, _ = build_role_bindings(environment, runner=runner)
    bindings.stack.run(
        CommandTaskSpec(task_id="check", summary="check", argv=("kubectl", "version"), role="stack")
    )

    assert runner.calls[0][0][:4] == ["multipass", "exec", "nanofaas-stack", "--"]
    assert "cd /home/ubuntu/nanofaas" in runner.calls[0][0][-1]


def test_remote_stack_exports_its_kubeconfig() -> None:
    runner = RecordingRunner()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "external",
            "roles": {
                "stack": {
                    "host": "vm.example",
                    "home": "/srv/nanofaas",
                    "kubeconfig": "/etc/nanofaas/kubeconfig",
                }
            },
        }
    )

    bindings, _ = build_role_bindings(environment, runner=runner)
    bindings.stack.run(
        CommandTaskSpec(task_id="check", summary="check", argv=("kubectl", "get", "nodes"))
    )

    assert "env KUBECONFIG=/etc/nanofaas/kubeconfig kubectl get nodes" in runner.calls[0][0][-1]


def test_distinct_external_loadgen_gets_distinct_executor_and_fetcher() -> None:
    runner = RecordingRunner()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "external",
            "roles": {
                "stack": {"host": "stack.example"},
                "loadgen": {"host": "load.example"},
            },
        }
    )

    bindings, fetcher = build_role_bindings(environment, runner=runner)

    assert bindings.loadgen is not bindings.stack
    assert fetcher is not None


def test_multipass_three_role_environment_binds_cloud_like_stack() -> None:
    runner = RecordingRunner()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "multipass",
            "roles": {
                "stack": {"name": "nanofaas-edge"},
                "cloud": {"name": "nanofaas-cloud"},
                "loadgen": {"name": "nanofaas-loadgen"},
            },
        }
    )

    bindings, _ = build_role_bindings(environment, runner=runner)

    assert bindings.cloud is not None
    bindings.cloud.run(
        CommandTaskSpec(task_id="check", summary="check", argv=("kubectl", "version"), role="cloud")
    )

    assert runner.calls[0][0][:4] == ["multipass", "exec", "nanofaas-cloud", "--"]
    assert "env KUBECONFIG=/home/ubuntu/.kube/config kubectl version" in runner.calls[0][0][-1]


def test_external_loadtest_urls_use_stack_node_ports() -> None:
    environment = EnvironmentConfig.model_validate(
        {"provider": "external", "roles": {"stack": {"host": "stack.example"}}}
    )

    urls = resolve_loadtest_urls(environment)

    assert urls == ("http://stack.example:30080", "http://stack.example:30090")


def test_multipass_loadtest_urls_resolve_instance_address() -> None:
    environment = EnvironmentConfig.model_validate(
        {"provider": "multipass", "roles": {"stack": {"name": "nanofaas-stack"}}}
    )

    urls = resolve_loadtest_urls(environment, host_resolver=lambda _: "10.20.30.40")

    assert urls == ("http://10.20.30.40:30080", "http://10.20.30.40:30090")


def test_explicit_loadtest_urls_do_not_resolve_stack_address() -> None:
    environment = EnvironmentConfig.model_validate(
        {"provider": "multipass", "roles": {"stack": {"name": "nanofaas-stack"}}}
    )

    urls = resolve_loadtest_urls(
        environment,
        control_plane_url="https://control.example",
        prometheus_url="https://metrics.example",
        host_resolver=lambda _: (_ for _ in ()).throw(AssertionError("must not resolve")),
    )

    assert urls == ("https://control.example", "https://metrics.example")


def test_dry_run_uses_stable_multipass_placeholder() -> None:
    environment = EnvironmentConfig.model_validate(
        {"provider": "multipass", "roles": {"stack": {"name": "nanofaas-stack"}}}
    )

    urls = resolve_loadtest_urls(environment, dry_run=True)

    assert urls == (
        "http://<multipass-ip:nanofaas-stack>:30080",
        "http://<multipass-ip:nanofaas-stack>:30090",
    )


def test_azure_role_uses_provider_native_execution_and_fetch(tmp_path: Path) -> None:
    provider = RecordingVmProvider()
    runner = RecordingRunner()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "azure",
            "roles": {"stack": {}},
            "azure": {"resource_group": "rg", "location": "westeurope"},
        }
    )

    bindings, fetcher = build_role_bindings(
        environment, runner=runner, vm_provider=provider, repo_root=tmp_path
    )
    bindings.stack.run(
        CommandTaskSpec(task_id="check", summary="check", argv=("kubectl", "get", "nodes"))
    )
    assert fetcher is not None
    fetcher.fetch_from("/tmp/result.json", tmp_path / "result.json")

    request, argv, env, cwd, dry_run = provider.exec_calls[0]
    assert request.lifecycle == "azure"
    assert argv == ("kubectl", "get", "nodes")
    assert env == {"KUBECONFIG": "/home/azureuser/.kube/config"}
    assert cwd == "/home/azureuser/nanofaas"
    assert provider.fetch_calls[0][0].lifecycle == "azure"
    assert runner.calls == []


def test_proxmox_role_uses_provider_native_execution(monkeypatch, tmp_path: Path) -> None:
    monkeypatch.setenv("PROXMOX_PASSWORD", "secret")
    provider = RecordingVmProvider()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "proxmox",
            "roles": {"stack": {}},
            "proxmox": {"host": "pve.example", "node": "pve1"},
        }
    )

    bindings, _ = build_role_bindings(environment, vm_provider=provider, repo_root=tmp_path)
    bindings.stack.run(
        CommandTaskSpec(task_id="check", summary="check", argv=("kubectl", "version"))
    )

    assert provider.exec_calls[0][0].lifecycle == "proxmox"
    assert provider.exec_calls[0][0].proxmox_password == "secret"


def test_azure_loadtest_urls_use_public_ip() -> None:
    provider = RecordingVmProvider()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "azure",
            "roles": {"stack": {}},
            "azure": {"resource_group": "rg", "location": "westeurope"},
        }
    )

    assert resolve_loadtest_urls(environment, vm_provider=provider) == (
        "http://20.30.40.50:30080",
        "http://20.30.40.50:30090",
    )


def test_proxmox_loadtest_urls_use_guest_network_and_published_prometheus(
    monkeypatch,
) -> None:
    monkeypatch.setenv("PROXMOX_PASSWORD", "secret")
    provider = RecordingVmProvider()
    environment = EnvironmentConfig.model_validate(
        {
            "provider": "proxmox",
            "roles": {"stack": {}},
            "proxmox": {"host": "pve.example", "node": "pve1"},
        }
    )

    assert resolve_loadtest_urls(environment, vm_provider=provider) == (
        "http://10.0.0.50:30080",
        "http://pve.example:43090",
    )
