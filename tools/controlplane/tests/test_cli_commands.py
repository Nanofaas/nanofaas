from datetime import UTC, datetime
from types import SimpleNamespace

from typer.testing import CliRunner

from controlplane_tool.main import app
from controlplane_tool.prefect_models import FlowRunResult
from controlplane_tool.cli_commands import CommandExecutionResult
from controlplane_tool.cli_commands import GradleCommandExecutor


def test_build_command_accepts_profile_and_non_interactive_args() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["build", "--profile", "core", "--dry-run"])
    assert result.exit_code == 0
    assert "bootJar" in result.stdout


def test_jar_command_maps_to_bootjar() -> None:
    runner = CliRunner()
    result = runner.invoke(app, ["jar", "--profile", "core", "--dry-run"])
    assert result.exit_code == 0
    assert ":control-plane:bootJar" in result.stdout


def test_matrix_command_accepts_task_override() -> None:
    runner = CliRunner()
    result = runner.invoke(
        app,
        ["matrix", "--task", ":control-plane:test", "--max-combinations", "1", "--dry-run"],
    )
    assert result.exit_code == 0
    assert ":control-plane:test" in result.stdout
    assert ":control-plane:printSelectedControlPlaneModules" in result.stdout


def test_build_command_runs_prefect_flow(monkeypatch) -> None:
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
            result=CommandExecutionResult(command=["./gradlew", "build"], return_code=0, dry_run=True),
        )

    monkeypatch.setattr("controlplane_tool.cli_commands.run_local_flow", fake_run_local_flow)

    result = runner.invoke(app, ["build", "--profile", "core", "--dry-run"])

    assert result.exit_code == 0
    assert called["flow_id"] == "build.build"


def test_gradle_executor_preserves_captured_output_on_failure() -> None:
    executor = GradleCommandExecutor()
    executor._shell.run = lambda *args, **kwargs: SimpleNamespace(  # type: ignore[method-assign]
        return_code=17,
        stdout="gradle stdout",
        stderr="gradle stderr",
    )

    result = executor.execute(
        action="jar",
        profile="core",
        modules=None,
        extra_gradle_args=[],
        dry_run=False,
    )

    assert result.return_code == 17
    assert result.stdout == "gradle stdout"
    assert result.stderr == "gradle stderr"


def test_gradle_command_prints_captured_stderr_on_failure(monkeypatch) -> None:
    runner = CliRunner()

    def fake_run_local_flow(flow_id, flow, *args, **kwargs):  # noqa: ANN001
        now = datetime.now(UTC)
        return FlowRunResult.completed(
            flow_id=flow_id,
            flow_run_id="flow-run-1",
            orchestrator_backend="none",
            started_at=now,
            finished_at=now,
            result=SimpleNamespace(
                command=["./gradlew", ":control-plane:bootJar"],
                return_code=17,
                dry_run=False,
                stdout="gradle stdout",
                stderr="gradle stderr",
            ),
        )

    monkeypatch.setattr("controlplane_tool.cli_commands.run_local_flow", fake_run_local_flow)

    result = runner.invoke(app, ["jar", "--profile", "core"])

    assert result.exit_code == 17
    assert "gradle stderr" in result.stdout + result.stderr
