from __future__ import annotations

from typer.testing import CliRunner

from controlplane_tool.app.main import app

runner = CliRunner()


def test_images_help_lists_options() -> None:
    result = runner.invoke(app, ["images", "--help"])
    assert result.exit_code == 0
    assert "--only" in result.stdout
    assert "--no-push" in result.stdout
    assert "--dry-run" in result.stdout


def test_images_dry_run_only_watchdog_no_push(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = '7.7.7'\n", encoding="utf-8")
    result = runner.invoke(app, ["images", "--dry-run", "--only", "watchdog", "--no-push"])
    assert result.exit_code == 0


def test_images_unknown_target_errors(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = '1.0.0'\n", encoding="utf-8")
    result = runner.invoke(app, ["images", "--dry-run", "--only", "does-not-exist", "--no-push"])
    assert result.exit_code != 0
