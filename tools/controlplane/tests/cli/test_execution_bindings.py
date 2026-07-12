from pathlib import Path

from controlplane_tool.cli.execution import build_role_bindings
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
    assert "cd /srv/alice/nanofaas && ansible-playbook site.yml" in runner.calls[0][0][-1]


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
