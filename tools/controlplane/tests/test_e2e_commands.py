from pathlib import Path

from typer.testing import CliRunner

from controlplane_tool.e2e_commands import _resolve_run_request
from controlplane_tool.main import app


def test_e2e_list_prints_known_scenarios() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "list"])
    assert result.exit_code == 0
    assert "k8s-vm" in result.stdout


def test_e2e_run_dry_run_prints_planned_steps() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "k3s-curl", "--dry-run"])
    assert result.exit_code == 0
    assert "scenario" in result.stdout.lower()
    assert "step" in result.stdout.lower()


def test_e2e_run_dry_run_renders_resolved_functions() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "k8s-vm", "--function-preset", "demo-java", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "word-stats-java" in result.stdout
    assert "json-transform-java" in result.stdout


def test_helm_stack_default_selection_uses_supported_loadtest_functions() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "helm-stack", "--dry-run"])

    assert result.exit_code == 0
    assert "word-stats-go" not in result.stdout
    assert "json-transform-go" not in result.stdout


def test_helm_stack_rejects_unsupported_go_selection_before_backend() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["e2e", "run", "helm-stack", "--functions", "word-stats-go", "--dry-run"],
    )

    assert result.exit_code == 2
    rendered = result.stdout + result.stderr
    assert "helm-stack" in rendered
    assert "go" in rendered


def test_e2e_run_accepts_scenario_file_without_positional_scenario() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        [
            "e2e",
            "run",
            "--scenario-file",
            "tools/controlplane/scenarios/k8s-demo-java.toml",
            "--dry-run",
        ],
    )
    assert result.exit_code == 0
    assert "k8s-vm" in result.stdout
    assert "scenario source" in result.stdout.lower()


def test_cli_function_override_preserves_scenario_file_load_targets() -> None:
    request = _resolve_run_request(
        scenario=None,
        runtime=None,
        lifecycle="multipass",
        name=None,
        host=None,
        user="ubuntu",
        home=None,
        cpus=4,
        memory="8G",
        disk="30G",
        keep_vm=False,
        namespace=None,
        local_registry=None,
        function_preset=None,
        functions_csv="word-stats-java",
        scenario_file=Path("tools/controlplane/scenarios/k8s-demo-java.toml"),
        saved_profile=None,
    )

    assert request.resolved_scenario is not None
    assert request.resolved_scenario.load.targets == ["word-stats-java"]
    assert "word-stats-java" in request.resolved_scenario.payloads


def test_e2e_all_dry_run_honors_only_filter() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "all", "--only", "k3s-curl,k8s-vm", "--dry-run"])
    assert result.exit_code == 0
    assert "k3s-curl" in result.stdout
    assert "k8s-vm" in result.stdout
    assert "docker" not in result.stdout


def test_e2e_group_lists_expected_commands() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "--help"])
    assert result.exit_code == 0
    assert "list" in result.stdout
    assert "run" in result.stdout
    assert "all" in result.stdout


def test_container_local_dry_run_no_longer_uses_placeholder_echo() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["e2e", "run", "container-local", "--dry-run"])
    assert result.exit_code == 0
    assert "echo container-local verification workflow" not in result.stdout


def test_e2e_explicit_functions_override_saved_profile_defaults(monkeypatch) -> None:
    import controlplane_tool.e2e_commands as e2e_commands
    from controlplane_tool.models import (
        ControlPlaneConfig,
        Profile,
        ScenarioSelectionConfig,
    )

    monkeypatch.setattr(
        e2e_commands,
        "load_profile",
        lambda name: Profile(
            name=name,
            control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
            scenario=ScenarioSelectionConfig(
                base_scenario="k8s-vm",
                function_preset="demo-java",
            ),
        ),
    )

    runner = CliRunner()
    result = runner.invoke(
        app,
        [
            "e2e",
            "run",
            "k8s-vm",
            "--saved-profile",
            "demo-java",
            "--functions",
            "word-stats-go",
            "--dry-run",
        ],
    )
    assert result.exit_code == 0
    assert "word-stats-go" in result.stdout
    assert "json-transform-java" not in result.stdout
