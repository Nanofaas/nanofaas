from typer.testing import CliRunner

from controlplane_tool.main import app


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
