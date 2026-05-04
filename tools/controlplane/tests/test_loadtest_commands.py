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


def test_loadtest_run_dry_run_describes_mockk8s_local_fixture_semantics() -> None:
    result = CliRunner().invoke(
        app,
        ["loadtest", "run", "--saved-profile", "demo-javascript", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "mock Kubernetes API" in result.stdout
    assert "LOCAL fixture functions" in result.stdout
    assert "not Kubernetes pods" in result.stdout
    assert "sequentially" in result.stdout


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


def test_loadtest_run_dry_run_shows_effective_metrics_gate_for_saved_profile() -> None:
    result = CliRunner().invoke(
        app,
        ["loadtest", "run", "--saved-profile", "demo-java", "--dry-run"],
    )

    assert result.exit_code == 0
    assert "Metrics gate:" in result.stdout
    assert "function_dispatch_total" in result.stdout


def test_loadtest_run_dry_run_shows_prefect_flow_tasks() -> None:
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
    assert "loadtest.bootstrap" in result.stdout
    assert "metrics.evaluate_gate" in result.stdout


def test_loadtest_run_dry_run_reads_flow_tasks_from_catalog(monkeypatch) -> None:
    import controlplane_tool.loadtest_commands as loadtest_commands

    monkeypatch.setattr(
        loadtest_commands,
        "resolve_flow_task_ids",
        lambda flow_name, **kwargs: ["catalog.loadtest.task"],
    )

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
    assert "catalog.loadtest.task" in result.stdout


def test_loadtest_run_missing_saved_profile_exits_with_clean_cli_error() -> None:
    result = CliRunner().invoke(
        app,
        ["loadtest", "run", "--saved-profile", "does-not-exist"],
    )

    combined = result.stdout + result.stderr
    assert result.exit_code == 2
    assert "Profile not found" in combined
    assert "Traceback" not in combined


def test_loadtest_run_missing_scenario_file_exits_with_clean_cli_error() -> None:
    result = CliRunner().invoke(
        app,
        [
            "loadtest",
            "run",
            "--scenario-file",
            "tools/controlplane/scenarios/nope.toml",
            "--dry-run",
        ],
    )

    combined = result.stdout + result.stderr
    assert result.exit_code == 2
    assert "Scenario file not found" in combined
    assert "Traceback" not in combined
