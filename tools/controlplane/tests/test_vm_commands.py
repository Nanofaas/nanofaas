from datetime import UTC, datetime

from typer.testing import CliRunner

from controlplane_tool.prefect_models import FlowRunResult
from controlplane_tool.main import app
from controlplane_tool.shell_backend import ShellExecutionResult


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
    assert "ensure-registry.yml" in result.stdout
    assert "configure-k3s-registry.yml" in result.stdout


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

    monkeypatch.setattr("controlplane_tool.vm_commands.run_local_flow", fake_run_local_flow)

    result = runner.invoke(
        app,
        ["vm", "provision-base", "--lifecycle", "external", "--host", "vm.example.test", "--user", "dev", "--dry-run"],
    )

    assert result.exit_code == 0
    assert called["flow_id"] == "vm.provision_base"
