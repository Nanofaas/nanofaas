from pathlib import Path

import typer
from typer.testing import CliRunner

import controlplane_tool.main as main_mod
from controlplane_tool.models import ControlPlaneConfig, Profile


def _valid_profile() -> Profile:
    return Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
    )


def test_run_returns_nonzero_when_pipeline_failed(monkeypatch) -> None:
    monkeypatch.setattr(main_mod, "load_profile", lambda _name: _valid_profile())
    monkeypatch.setattr(main_mod, "build_loadtest_request", lambda profile: {"name": profile.name})

    def _fail_run(request, *, dry_run, runner=None):  # noqa: ANN001, ARG001
        typer.echo("Run status: failed")
        raise typer.Exit(code=1)

    monkeypatch.setattr(main_mod, "run_loadtest_request", _fail_run)

    runner = CliRunner()
    result = runner.invoke(
        main_mod.app,
        ["tui", "--profile-name", "qa", "--use-saved-profile"],
    )

    assert result.exit_code == 1
    assert "Run status: failed" in result.stdout


def test_run_missing_profile_is_user_friendly(monkeypatch) -> None:
    monkeypatch.setattr(main_mod, "load_profile", lambda _name: (_ for _ in ()).throw(FileNotFoundError("missing")))

    runner = CliRunner()
    result = runner.invoke(
        main_mod.app,
        ["tui", "--profile-name", "qa", "--use-saved-profile"],
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
        ["tui", "--profile-name", "qa", "--use-saved-profile"],
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


def test_tui_uses_loadtest_executor_for_saved_and_interactive_profiles(
    monkeypatch,
    tmp_path: Path,
) -> None:
    calls: list[str] = []

    monkeypatch.setattr(main_mod, "load_profile", lambda _name: _valid_profile())
    monkeypatch.setattr(
        main_mod,
        "build_and_save_profile",
        lambda profile_name: (
            _valid_profile().model_copy(update={"name": profile_name}),
            tmp_path / f"{profile_name}.toml",
        ),
    )
    monkeypatch.setattr(main_mod, "build_loadtest_request", lambda profile: {"name": profile.name})

    def _record_run_loadtest_request(request, *, dry_run, runner=None):  # noqa: ANN001, ARG001
        calls.append(request["name"])
        typer.echo("Run status: passed")

    monkeypatch.setattr(main_mod, "run_loadtest_request", _record_run_loadtest_request)

    runner = CliRunner()
    saved_result = runner.invoke(
        main_mod.app,
        ["tui", "--profile-name", "qa", "--use-saved-profile"],
    )
    interactive_result = runner.invoke(
        main_mod.app,
        ["tui", "--profile-name", "qa"],
    )

    assert saved_result.exit_code == 0
    assert interactive_result.exit_code == 0
    assert len(calls) == 2
    assert calls == ["qa", "qa"]
