from typer.testing import CliRunner
from pathlib import Path
from dataclasses import dataclass

from controlplane_tool.app.main import app
from controlplane_tool.cli.product import _slice, _workflow
from controlplane_tool.config import EnvironmentConfig, ScenarioConfig
from workflow_tasks.core.workflow import Workflow


@dataclass
class _Task:
    task_id: str = "test.task"
    title: str = "Test task"

    def run(self) -> None:
        pass


def test_top_level_exposes_only_six_product_commands() -> None:
    result = CliRunner().invoke(app, ["--help"])

    assert result.exit_code == 0
    commands = {command.name for command in app.registered_commands}
    assert commands == {"run", "plan", "list", "inspect", "doctor", "tui"}


def test_plan_builds_shared_validate_workflow() -> None:
    result = CliRunner().invoke(
        app,
        ["plan", "scenarios-v2/validate-k8s.yaml", "--environment", "environments/local.yaml"],
    )

    assert result.exit_code == 0
    assert "images.build.word-stats-java" in result.stdout
    assert "resources.inspect.k8s.word-stats-java" in result.stdout


def test_run_renders_normalized_task_progress(monkeypatch) -> None:
    monkeypatch.setattr(
        "controlplane_tool.cli.product._workflow",
        lambda *args, **kwargs: Workflow(tasks=[_Task()]),
    )

    result = CliRunner().invoke(app, ["run", "scenarios-v2/validate-container.yaml"])

    assert result.exit_code == 0
    assert "[test.task] running" in result.stdout
    assert "[test.task] passed" in result.stdout


def test_inspect_renders_validated_configuration() -> None:
    result = CliRunner().invoke(app, ["inspect", "scenarios-v2/cli.yaml"])

    assert result.exit_code == 0
    assert '"workflow": "cli"' in result.stdout


def test_plan_can_select_one_task() -> None:
    result = CliRunner().invoke(
        app,
        ["plan", "scenarios-v2/validate-k8s.yaml", "--only", "functions.invoke.word-stats-java"],
    )

    assert result.exit_code == 0
    assert "functions.invoke.word-stats-java" in result.stdout
    assert "images.build.word-stats-java" not in result.stdout


def test_task_slice_keeps_only_cleanup_for_selected_acquisitions() -> None:
    workflow = _workflow(
        ScenarioConfig(workflow="validate", backend="k8s", functions=["word-stats-java"]),
        EnvironmentConfig(provider="local"),
    )

    _slice(workflow, only="stack.preflight", start=None, until=None)

    assert workflow.cleanup_tasks == []


def test_plan_accepts_external_ssh_environment(tmp_path: Path) -> None:
    environment = tmp_path / "external.yaml"
    environment.write_text(
        "provider: external\nroles:\n  stack:\n    host: vm.example\n    user: ubuntu\n",
        encoding="utf-8",
    )

    result = CliRunner().invoke(
        app,
        ["plan", "scenarios-v2/validate-k8s.yaml", "--environment", str(environment)],
    )

    assert result.exit_code == 0
    assert "stack.preflight" in result.stdout


def test_plan_builds_loadtest_with_operational_defaults(tmp_path: Path) -> None:
    scenario = tmp_path / "loadtest.yaml"
    scenario.write_text("workflow: loadtest\nfunctions:\n  - word-stats-java\n", encoding="utf-8")

    result = CliRunner().invoke(app, ["plan", str(scenario)])

    assert result.exit_code == 0
    assert "loadgen.run_k6" in result.stdout
    assert "metrics.prometheus_snapshot" in result.stdout
