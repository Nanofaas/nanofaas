from datetime import UTC, datetime

from typer.testing import CliRunner

from controlplane_tool.orchestation.prefect_models import FlowRunResult
from controlplane_tool.app.main import app
from controlplane_tool.core.shell_backend import ShellExecutionResult
from controlplane_tool.cli.vm_commands import _emit_result


def test_vm_up_dry_run_prints_planned_multipass_command() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["vm", "up", "--lifecycle", "multipass", "--name", "nanofaas-e2e", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "multipass" in result.stdout
    assert "launch" in result.stdout


def test_vm_provision_base_dry_run_prints_planned_ansible_command() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        [
            "vm",
            "provision-base",
            "--lifecycle",
            "external",
            "--host",
            "vm.example.test",
            "--user",
            "dev",
            "--dry-run",
        ],
    )
    assert result.exit_code == 0
    assert "ansible-playbook" in result.stdout
    assert "provision-base.yml" in result.stdout
    assert "vm.example.test" in result.stdout


def test_vm_group_lists_expected_commands() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["vm", "--help"])
    assert result.exit_code == 0
    assert "up" in result.stdout
    assert "sync" in result.stdout
    assert "provision-base" in result.stdout


def test_vm_registry_dry_run_prints_both_registry_commands() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        [
            "vm",
            "registry",
            "--lifecycle",
            "external",
            "--host",
            "vm.example.test",
            "--user",
            "dev",
            "--dry-run",
        ],
    )

    assert result.exit_code == 0
    unwrapped_stdout = result.stdout.replace("\n", "")
    assert "ensure-registry.yml" in unwrapped_stdout
    assert "configure-k3s-registry.yml" in unwrapped_stdout


def test_vm_provision_base_command_runs_prefect_flow(monkeypatch) -> None:
    runner = CliRunner()
    called: dict[str, str] = {}

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        called["flow_id"] = flow_id
        now = datetime.now(UTC)
        return FlowRunResult.completed(
            flow_id=flow_id,
            flow_run_id="flow-run-1",
            orchestrator_backend="none",
            started_at=now,
            finished_at=now,
            result=ShellExecutionResult(command=["ansible-playbook", "provision-base.yml"], return_code=0),
        )

    monkeypatch.setattr("controlplane_tool.cli.vm_commands.run_local_flow", fake_run_local_flow)

    result = runner.invoke(
        app,
        ["vm", "provision-base", "--lifecycle", "external", "--host", "vm.example.test", "--user", "dev", "--dry-run"],
    )

    assert result.exit_code == 0
    assert called["flow_id"] == "vm.provision_base"


def test_emit_result_prints_stderr_and_exits_with_result_code() -> None:
    result = ShellExecutionResult(
        command=["multipass", "launch"],
        return_code=17,
        stdout="",
        stderr="vm failed",
    )

    try:
        _emit_result(result, dry_run=False)
    except BaseException as exc:
        assert exc.__class__.__name__ == "Exit"
        assert getattr(exc, "exit_code") == 17
    else:
        raise AssertionError("expected VM command failure to exit")
