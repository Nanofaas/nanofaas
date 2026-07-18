from typer.testing import CliRunner
import json
import subprocess
from pathlib import Path
from dataclasses import dataclass
from contextlib import contextmanager
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

# These tests pass repo-relative paths (scenarios-v2/..., environments/...) to the
# CLI, so they must run from the project root regardless of pytest's cwd.
_PROJECT_ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture(autouse=True)
def _run_from_project_root(monkeypatch):
    monkeypatch.chdir(_PROJECT_ROOT)

from controlplane_tool.app.main import app
from controlplane_tool.cli.preflight import PreflightError
from controlplane_tool.cli.product import _git_provenance, _slice, _workflow
import controlplane_tool.cli.product as product_module
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


def test_doctor_uses_shared_diagnostics(monkeypatch) -> None:
    shared_check = MagicMock(return_value=["docker"])
    monkeypatch.setattr(
        product_module,
        "diagnostics",
        SimpleNamespace(missing_executables=shared_check),
        raising=False,
    )
    monkeypatch.setattr(
        product_module,
        "shutil",
        SimpleNamespace(
            which=lambda _name: (_ for _ in ()).throw(
                AssertionError("CLI duplicated the executable check")
            )
        ),
        raising=False,
    )

    result = CliRunner().invoke(app, ["doctor"])

    assert result.exit_code != 0
    assert "missing executables: docker" in result.output
    shared_check.assert_called_once_with()


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


def test_run_aborts_local_cli_before_building_workflow_when_preflight_fails(
    monkeypatch,
) -> None:
    error = (
        "Control plane unavailable; "
        "start it with './gradlew :control-plane:bootRun'."
    )
    preflight = MagicMock(side_effect=PreflightError(error))
    build_cli_plan = MagicMock()
    monkeypatch.setattr(
        product_module, "preflight_control_plane", preflight, raising=False
    )
    monkeypatch.setattr(product_module, "build_cli_plan", build_cli_plan)

    result = CliRunner().invoke(app, ["run", "scenarios-v2/cli.yaml"])

    assert result.exit_code == 1
    assert result.stderr == f"Error: {error}\n"
    assert "Traceback" not in result.output
    scenario, environment = preflight.call_args.args
    assert scenario.workflow == "cli"
    assert environment.provider == "local"
    assert preflight.call_args.kwargs == {"base_url": "http://127.0.0.1:8080"}
    build_cli_plan.assert_not_called()


def test_plan_does_not_run_preflight(monkeypatch) -> None:
    preflight = MagicMock(side_effect=AssertionError("plan must remain offline"))
    monkeypatch.setattr(
        product_module, "preflight_control_plane", preflight, raising=False
    )

    result = CliRunner().invoke(app, ["plan", "scenarios-v2/cli.yaml"])

    assert result.exit_code == 0
    preflight.assert_not_called()


def test_run_uses_custom_control_plane_url_for_preflight_and_cli_plan(
    monkeypatch,
) -> None:
    preflight = MagicMock()
    build_cli_plan = MagicMock(return_value=Workflow(tasks=[]))
    monkeypatch.setattr(
        product_module, "preflight_control_plane", preflight, raising=False
    )
    monkeypatch.setattr(product_module, "build_cli_plan", build_cli_plan)

    result = CliRunner().invoke(
        app,
        [
            "run",
            "scenarios-v2/cli.yaml",
            "--control-plane-url",
            "http://control-plane.example:8181",
        ],
    )

    assert result.exit_code == 0
    assert preflight.call_args.kwargs == {
        "base_url": "http://control-plane.example:8181"
    }
    assert build_cli_plan.call_args.kwargs["endpoint"] == (
        "http://control-plane.example:8181"
    )


def test_run_provisions_before_executing_workflow(monkeypatch, tmp_path: Path) -> None:
    actions: list[str] = []
    workflow = Workflow(tasks=[_Task()])

    @contextmanager
    def provision(*args, **kwargs):
        actions.append(f"provision:keep={kwargs['keep']}")
        try:
            yield
        finally:
            actions.append("cleanup")

    monkeypatch.setattr("controlplane_tool.cli.product.provision_environment", provision)
    monkeypatch.setattr(
        "controlplane_tool.cli.product._workflow",
        lambda *args, **kwargs: (
            actions.append(f"build:{kwargs['control_plane_url']}:{kwargs['prometheus_url']}")
            or workflow
        ),
    )
    monkeypatch.setattr(
        "controlplane_tool.cli.product.resolve_loadtest_urls",
        lambda *args, **kwargs: (
            actions.append("resolve") or "http://stack:30080",
            "http://stack:30090",
        ),
    )
    monkeypatch.setattr(workflow, "run", lambda: actions.append("run"))
    monkeypatch.setattr(
        "controlplane_tool.cli.product._git_provenance",
        lambda *args: actions.append("provenance")
        or {
            "git_commit": "abc",
            "git_dirty": False,
            "git_diff_sha256": "digest",
            "git_status": [],
        },
    )

    result = CliRunner().invoke(
        app,
        [
            "run",
            "scenarios-v2/loadtest.yaml",
            "--environment",
            "environments/multipass.yaml",
            "--provision",
            "--run-dir",
            str(tmp_path),
        ],
    )

    assert result.exit_code == 0
    assert actions == [
        "provenance",
        "provision:keep=False",
        "resolve",
        "build:http://stack:30080:http://stack:30090",
        "run",
        "cleanup",
    ]
    metadata = json.loads((tmp_path / "run-metadata.json").read_text(encoding="utf-8"))
    assert metadata["schema_version"] == 1
    assert metadata["status"] == "passed"
    assert metadata["git_commit"]
    assert isinstance(metadata["git_dirty"], bool)
    assert metadata["git_diff_sha256"]
    assert isinstance(metadata["git_status"], list)
    assert metadata["scenario"]["config"]["workflow"] == "loadtest"
    assert metadata["environment"]["config"]["provider"] == "multipass"
    assert metadata["tasks"] == []


def test_git_provenance_fingerprints_tracked_and_untracked_content(tmp_path: Path) -> None:
    repo = tmp_path / "repo"
    repo.mkdir()
    subprocess.run(("git", "init", "-q"), cwd=repo, check=True)
    subprocess.run(("git", "config", "user.email", "test@example.com"), cwd=repo, check=True)
    subprocess.run(("git", "config", "user.name", "Test"), cwd=repo, check=True)
    tracked = repo / "tracked.txt"
    tracked.write_text("base", encoding="utf-8")
    subprocess.run(("git", "add", "tracked.txt"), cwd=repo, check=True)
    subprocess.run(("git", "commit", "-qm", "base"), cwd=repo, check=True)

    clean = _git_provenance(repo)
    tracked.write_text("changed", encoding="utf-8")
    tracked_change = _git_provenance(repo)
    untracked = repo / "untracked.txt"
    untracked.write_text("one", encoding="utf-8")
    untracked_one = _git_provenance(repo)
    untracked.write_text("two", encoding="utf-8")
    untracked_two = _git_provenance(repo)

    assert clean["git_dirty"] is False
    assert tracked_change["git_diff_sha256"] != clean["git_diff_sha256"]
    assert untracked_one["git_diff_sha256"] != tracked_change["git_diff_sha256"]
    assert untracked_two["git_diff_sha256"] != untracked_one["git_diff_sha256"]


def test_failed_loadtest_writes_failure_metadata(monkeypatch, tmp_path: Path) -> None:
    @dataclass
    class _FailTask:
        task_id: str = "loadtest.fail"
        title: str = "Fail load test"

        def run(self) -> None:
            raise RuntimeError("load exploded")

    monkeypatch.setattr(
        "controlplane_tool.cli.product._workflow",
        lambda *args, **kwargs: Workflow(tasks=[_FailTask()]),
    )
    monkeypatch.setattr(
        "controlplane_tool.cli.product.resolve_loadtest_urls",
        lambda *args, **kwargs: ("http://stack:30080", "http://stack:30090"),
    )

    result = CliRunner().invoke(
        app,
        [
            "run",
            "scenarios-v2/loadtest.yaml",
            "--run-dir",
            str(tmp_path),
        ],
    )

    assert result.exit_code != 0
    metadata = json.loads((tmp_path / "run-metadata.json").read_text(encoding="utf-8"))
    assert metadata["status"] == "failed"
    assert metadata["error"] == "load exploded"
    assert metadata["tasks"][-1]["status"] == "failed"


def test_run_rejects_provisioning_for_local_environment() -> None:
    result = CliRunner().invoke(
        app,
        ["run", "scenarios-v2/validate-container.yaml", "--provision"],
    )

    assert result.exit_code != 0
    assert "--provision requires a non-local environment" in result.output


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
