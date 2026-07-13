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
        "cd /srv/alice/nanofaas && env KUBECONFIG=/srv/alice/.kube/config "
        "ansible-playbook site.yml"
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
