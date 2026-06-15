from __future__ import annotations

from typer.testing import CliRunner

from controlplane_tool.app.main import app

runner = CliRunner()


def test_images_help_lists_matrix_options() -> None:
    result = runner.invoke(app, ["images", "--help"])

    assert result.exit_code == 0
    assert "--arch" in result.stdout
    assert "--flavor" in result.stdout
    assert "--fail-fast" in result.stdout
    assert "--keep-going" in result.stdout
    assert "--arch-suffix" not in result.stdout


def test_images_dry_run_no_push_plans_control_plane_arch_and_flavor_matrix(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = 'vtest'\n", encoding="utf-8")

    result = runner.invoke(
        app,
        [
            "images",
            "--dry-run",
            "--no-push",
            "--only",
            "control-plane",
            "--arch",
            "amd64",
            "--flavor",
            "all",
        ],
    )

    assert result.exit_code == 0
    assert "control-plane:vtest-amd64-jvm" in result.stdout
    assert "control-plane:vtest-amd64-native" in result.stdout


def test_images_rejects_multi_arch(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = 'vtest'\n", encoding="utf-8")

    result = runner.invoke(app, ["images", "--dry-run", "--arch", "multi"])

    assert result.exit_code != 0


def test_images_rejects_removed_arch_suffix_flag(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = 'vtest'\n", encoding="utf-8")

    result = runner.invoke(
        app,
        [
            "images",
            "--dry-run",
            "--arch-suffix",
            "--no-push",
            "--only",
            "control-plane",
            "--arch",
            "amd64",
            "--flavor",
            "native",
        ],
    )

    assert result.exit_code != 0


def test_images_unknown_target_errors(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = 'vtest'\n", encoding="utf-8")

    result = runner.invoke(app, ["images", "--dry-run", "--only", "does-not-exist", "--no-push"])

    assert result.exit_code != 0
