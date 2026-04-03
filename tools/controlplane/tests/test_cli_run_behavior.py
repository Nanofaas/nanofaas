from pathlib import Path

from pydantic import ValidationError
from typer.testing import CliRunner

import controlplane_tool.main as main_mod
from controlplane_tool.models import ControlPlaneConfig, Profile
from controlplane_tool.pipeline import RunResult, StepResult


class _FailedRunner:
    def run(self, profile: Profile) -> RunResult:
        return RunResult(
            profile_name=profile.name,
            run_dir=Path("tooling/runs/fake"),
            final_status="failed",
            steps=[StepResult(name="compile", status="failed", detail="boom", duration_ms=1)],
        )


def _valid_profile() -> Profile:
    return Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
    )


def test_run_returns_nonzero_when_pipeline_failed(monkeypatch) -> None:
    monkeypatch.setattr(main_mod, "load_profile", lambda _name: _valid_profile())
    monkeypatch.setattr(main_mod, "PipelineRunner", lambda: _FailedRunner())

    runner = CliRunner()
    result = runner.invoke(
        main_mod.app,
        ["pipeline-run", "--profile-name", "qa", "--use-saved-profile"],
    )

    assert result.exit_code == 1
    assert "Run status: failed" in result.stdout


def test_run_missing_profile_is_user_friendly(monkeypatch) -> None:
    monkeypatch.setattr(main_mod, "load_profile", lambda _name: (_ for _ in ()).throw(FileNotFoundError("missing")))

    runner = CliRunner()
    result = runner.invoke(
        main_mod.app,
        ["pipeline-run", "--profile-name", "qa", "--use-saved-profile"],
    )

    assert result.exit_code == 2
    assert "Profile not found" in result.stderr
    assert "Traceback" not in result.stdout


def test_run_invalid_profile_is_user_friendly(monkeypatch) -> None:
    def _raise_validation(_name: str) -> Profile:
        Profile.model_validate(
            {
                "name": "qa",
                "control_plane": {"implementation": "golang", "build_mode": "jvm"},
            }
        )
        raise AssertionError("unreachable")

    monkeypatch.setattr(main_mod, "load_profile", _raise_validation)

    runner = CliRunner()
    result = runner.invoke(
        main_mod.app,
        ["pipeline-run", "--profile-name", "qa", "--use-saved-profile"],
    )

    assert result.exit_code == 2
    assert "Invalid profile" in result.stderr
    assert "Traceback" not in result.stdout


def test_run_command_supports_passthrough_gradle_args_in_dry_run() -> None:
    runner = CliRunner()
    result = runner.invoke(
        main_mod.app,
        [
            "run",
            "--profile",
            "container-local",
            "--dry-run",
            "--",
            "--args=--nanofaas.deployment.default-backend=container-local",
        ],
    )

    assert result.exit_code == 0
    assert ":control-plane:bootRun" in result.stdout
    assert "container-deployment-provider" in result.stdout
    assert "--args=--nanofaas.deployment.default-backend=container-local" in result.stdout
