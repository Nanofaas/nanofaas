from pathlib import Path

from typer.testing import CliRunner

from controlplane_tool.app.main import app


def test_cli_test_group_lists_known_scenarios() -> None:
    result = CliRunner().invoke(app, ["cli-test", "list"])

    assert result.exit_code == 0
    assert "cli-stack" in result.stdout
    assert "deploy-host" in result.stdout


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
    assert "Scenario: cli-stack" in result.stdout


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


def test_cli_test_request_applies_cli_override_to_saved_profile_scenario_file(
    monkeypatch,
    tmp_path: Path,
) -> None:
    import controlplane_tool.cli.test_commands as test_commands
    from controlplane_tool.cli.test_commands import _resolve_run_request
    from controlplane_tool.core.models import (
        CliTestConfig,
        ControlPlaneConfig,
        Profile,
        ScenarioSelectionConfig,
    )

    scenario_file = tmp_path / "scenario.toml"
    scenario_file.write_text(
        """
name = "custom"
base_scenario = "cli-stack"
runtime = "java"
function_preset = "demo-java"
namespace = "from-file"
local_registry = "registry:5000"
""",
        encoding="utf-8",
    )
    profile_file = tmp_path / "profile.toml"
    profile_file.write_text("", encoding="utf-8")

    monkeypatch.setattr(test_commands, "profile_path", lambda name: profile_file)
    monkeypatch.setattr(
        test_commands,
        "load_profile",
        lambda name: Profile(
            name=name,
            control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
            scenario=ScenarioSelectionConfig(scenario_file=str(scenario_file)),
            cli_test=CliTestConfig(default_scenario="cli-stack"),
        ),
    )

    request = _resolve_run_request(
        scenario=None,
        runtime="rust",
        lifecycle="external",
        name=None,
        host="127.0.0.1",
        user="ubuntu",
        home=None,
        cpus=2,
        memory="2G",
        disk="10G",
        keep_vm=True,
        namespace="override",
        local_registry="localhost:5001",
        function_preset="demo-javascript",
        functions_csv=None,
        scenario_file=None,
        saved_profile="saved",
    )

    assert request.resolved_scenario is not None
    assert request.resolved_scenario.runtime == "rust"
    assert request.resolved_scenario.namespace == "override"
    assert request.resolved_scenario.local_registry == "localhost:5001"
    assert request.resolved_scenario.function_preset == "demo-javascript"
    assert request.resolved_scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_cli_test_run_vm_is_no_longer_a_valid_scenario() -> None:
    """cli-test run vm must be rejected after legacy CLI consumer cleanup."""
    result = CliRunner().invoke(app, ["cli-test", "run", "vm", "--dry-run"])
    assert result.exit_code != 0, "cli-test run vm should fail — vm scenario was removed"


def test_cli_test_list_does_not_advertise_vm_scenario() -> None:
    """cli-test list must not include vm after legacy CLI consumer cleanup."""
    result = CliRunner().invoke(app, ["cli-test", "list"])
    assert result.exit_code == 0
    lines = result.stdout.splitlines()
    scenario_names = [line.split()[1] for line in lines if "│" in line and len(line.split()) > 1]
    assert "vm" not in scenario_names, f"vm still listed in cli-test catalog: {scenario_names}"


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
        ["cli-test", "run", "cli-stack", "--scenario-file", "tools/controlplane/scenarios/nope.toml"],
    )

    assert result.exit_code == 2
    assert "Scenario file not found" in (result.stdout + result.stderr)
    assert "Traceback" not in (result.stdout + result.stderr)
