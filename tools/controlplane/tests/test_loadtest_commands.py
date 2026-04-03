from typer.testing import CliRunner

from controlplane_tool.main import app


def test_loadtest_group_lists_profiles_and_run_command() -> None:
    result = CliRunner().invoke(app, ["loadtest", "--help"])

    assert result.exit_code == 0
    assert "list-profiles" in result.stdout
    assert "run" in result.stdout


def test_loadtest_run_dry_run_renders_resolved_scenario_and_k6_plan() -> None:
    result = CliRunner().invoke(
        app,
        [
            "loadtest",
            "run",
            "--scenario-file",
            "tools/controlplane/scenarios/k8s-demo-java.toml",
            "--dry-run",
        ],
    )

    assert result.exit_code == 0
    assert "k8s-demo-java" in result.stdout
    assert "quick" in result.stdout
    assert "k6" in result.stdout.lower()


def test_loadtest_run_dry_run_resolves_scenario_file_from_workspace_root(
    monkeypatch,
) -> None:
    monkeypatch.chdir("/")

    result = CliRunner().invoke(
        app,
        [
            "loadtest",
            "run",
            "--scenario-file",
            "tools/controlplane/scenarios/k8s-demo-java.toml",
            "--dry-run",
        ],
    )

    assert result.exit_code == 0
    assert "word-stats-java, json-transform-java" in result.stdout
