from __future__ import annotations

from typer.testing import CliRunner

from controlplane_tool.app.main import app
from controlplane_tool.building.image_workflow import ImageCellResult

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


def test_images_keep_going_exits_nonzero_when_runner_returns_failure(monkeypatch, tmp_path) -> None:
    monkeypatch.chdir(tmp_path)
    (tmp_path / "build.gradle").write_text("version = 'vtest'\n", encoding="utf-8")

    def fake_run_image_matrix_plan(runner, plan, *, dry_run, fail_fast):  # noqa: ANN001
        _ = runner, plan
        assert dry_run is False
        assert fail_fast is False
        return [
            ImageCellResult(
                target="watchdog",
                arch="amd64",
                flavor="default",
                image="ghcr.io/miciav/nanofaas/watchdog:vtest-amd64",
                phase="build",
                ok=False,
                return_code=17,
                detail="docker build failed",
            )
        ]

    monkeypatch.setattr(
        "controlplane_tool.cli.commands.run_image_matrix_plan",
        fake_run_image_matrix_plan,
    )

    result = runner.invoke(
        app,
        [
            "images",
            "--keep-going",
            "--no-push",
            "--only",
            "watchdog",
            "--arch",
            "amd64",
        ],
    )

    assert result.exit_code == 1
    assert "Image matrix failed: 1 failed result" in result.stderr
    assert "watchdog amd64 build failed (exit 17): docker build failed" in result.stderr
