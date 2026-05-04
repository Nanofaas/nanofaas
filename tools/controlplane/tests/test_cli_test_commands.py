from typer.testing import CliRunner

from controlplane_tool.app.main import app


def test_cli_test_group_lists_known_scenarios() -> None:
    result = CliRunner().invoke(app, ["cli-test", "list"])

    assert result.exit_code == 0
    assert "vm" in result.stdout
    assert "cli-stack" in result.stdout
    assert "deploy-host" in result.stdout


def test_cli_test_run_vm_dry_run_renders_backend_steps() -> None:
    result = CliRunner().invoke(app, ["cli-test", "run", "vm", "--dry-run"])

    assert result.exit_code == 0
    assert "cli-test run vm" in result.stdout
    assert "cli-e2e" not in result.stdout
    assert "e2e-cli-backend.sh" not in result.stdout
    assert ":nanofaas-cli:installDist" in result.stdout


def test_cli_test_run_cli_stack_dry_run_shows_cli_stack_tail() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "cli-stack", "--function-preset", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "Scenario: cli-stack" in result.stdout
    assert "Build nanofaas-cli installDist" in result.stdout
    assert "install nanofaas into k3s" in result.stdout.lower()
    assert "platform status" in result.stdout.lower()
    assert "verify cli-stack status fails" in result.stdout.lower()


def test_cli_test_inspect_shows_gradle_task_and_vm_requirement() -> None:
    result = CliRunner().invoke(app, ["cli-test", "inspect", "host-platform"])

    assert result.exit_code == 0
    assert ":nanofaas-cli:installDist" in result.stdout
    assert "Requires VM: True" in result.stdout
    assert "Accepts Function Selection: False" in result.stdout


def test_cli_test_run_uses_saved_profile_default_scenario() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "--saved-profile", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "Scenario: vm" in result.stdout


def test_cli_test_run_saved_profile_demo_javascript_defaults_to_cli_stack() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "--saved-profile", "demo-javascript", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "Scenario: cli-stack" in result.stdout


def test_cli_test_run_host_platform_rejects_explicit_function_preset() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "host-platform", "--function-preset", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 2
    assert "does not accept function selection" in (result.stdout + result.stderr)


def test_cli_test_run_host_platform_ignores_saved_profile_function_defaults() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "host-platform", "--saved-profile", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "Scenario: host-platform" in result.stdout
    assert "Resolved Functions:" not in result.stdout


def test_cli_test_run_deploy_host_dry_run_accepts_demo_java_preset() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "deploy-host", "--function-preset", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "Resolved Functions: word-stats-java, json-transform-java" in result.stdout


def test_cli_test_run_missing_saved_profile_exits_cleanly() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "--saved-profile", "does-not-exist"],
    )

    assert result.exit_code == 2
    assert "Profile not found" in (result.stdout + result.stderr)
    assert "Traceback" not in (result.stdout + result.stderr)


def test_cli_test_run_missing_scenario_file_exits_cleanly() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "vm", "--scenario-file", "tools/controlplane/scenarios/nope.toml"],
    )

    assert result.exit_code == 2
    assert "Scenario file not found" in (result.stdout + result.stderr)
    assert "Traceback" not in (result.stdout + result.stderr)
