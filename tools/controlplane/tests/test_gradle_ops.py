"""
Tests for gradle_ops — GradleOps and run_logged utility.
"""
from __future__ import annotations

from pathlib import Path
from unittest.mock import MagicMock, patch

from controlplane_tool.building.gradle_ops import CommandResult, GradleOps, run_logged
from controlplane_tool.core.models import ControlPlaneConfig, Profile, TestsConfig


# ---------------------------------------------------------------------------
# CommandResult
# ---------------------------------------------------------------------------

def test_command_result_ok() -> None:
    r = CommandResult(ok=True, detail="ok (42 ms)")
    assert r.ok is True
    assert "42" in r.detail


def test_command_result_failure() -> None:
    r = CommandResult(ok=False, detail="exit=1 (10 ms)")
    assert r.ok is False


# ---------------------------------------------------------------------------
# run_logged
# ---------------------------------------------------------------------------

def test_run_logged_writes_command_to_log(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="hello\n", stderr="")
        run_logged(["echo", "hello"], run_dir, "out.log", repo_root=tmp_path)
    log = (run_dir / "out.log").read_text()
    assert "$ echo hello" in log
    assert "hello" in log


def test_run_logged_returns_ok_on_zero_exit(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=0, stdout="", stderr="")
        result = run_logged(["true"], run_dir, "out.log", repo_root=tmp_path)
    assert result.ok is True


def test_run_logged_returns_failure_on_nonzero_exit(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="error")
        result = run_logged(["false"], run_dir, "out.log", repo_root=tmp_path)
    assert result.ok is False
    assert "exit=1" in result.detail


def test_run_logged_appends_stderr_to_log(tmp_path: Path) -> None:
    run_dir = tmp_path / "run"
    run_dir.mkdir()
    with patch("subprocess.run") as mock_run:
        mock_run.return_value = MagicMock(returncode=1, stdout="", stderr="err msg")
        run_logged(["cmd"], run_dir, "out.log", repo_root=tmp_path)
    assert "err msg" in (run_dir / "out.log").read_text()


# ---------------------------------------------------------------------------
# GradleOps
# ---------------------------------------------------------------------------

def _java_profile(name: str = "qa") -> Profile:
    return Profile(
        name=name,
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=False),
    )


def _rust_profile(name: str = "rust-qa") -> Profile:
    return Profile(
        name=name,
        control_plane=ControlPlaneConfig(implementation="rust", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=False),
    )


def test_gradle_ops_preflight_missing_reports_no_docker(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("shutil.which", lambda tool: None if tool == "docker" else "/usr/bin/tool")
    ops = GradleOps(tmp_path)
    missing = ops.preflight_missing(_java_profile())
    assert "docker" in missing


def test_gradle_ops_preflight_missing_reports_missing_gradlew(tmp_path: Path) -> None:
    ops = GradleOps(tmp_path)
    profile = Profile(
        name="qa",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="jvm"),
        modules=[],
        tests=TestsConfig(enabled=True, api=True),
    )
    missing = ops.preflight_missing(profile)
    assert "gradlew" in missing


def test_gradle_ops_preflight_missing_is_empty_when_tools_present(tmp_path: Path, monkeypatch) -> None:
    (tmp_path / "gradlew").touch()
    monkeypatch.setattr("shutil.which", lambda tool: f"/usr/bin/{tool}")
    ops = GradleOps(tmp_path)
    missing = ops.preflight_missing(_java_profile())
    assert missing == []


def test_gradle_ops_preflight_missing_reports_cargo_for_rust(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr("shutil.which", lambda tool: None if tool == "cargo" else f"/usr/bin/{tool}")
    ops = GradleOps(tmp_path)
    missing = ops.preflight_missing(_rust_profile())
    assert "cargo" in missing


def test_gradle_ops_compile_java_invokes_gradlew(tmp_path: Path) -> None:
    ops = GradleOps(tmp_path)
    (tmp_path / "run").mkdir()
    with patch.object(ops, "_run") as mock_run:
        mock_run.return_value = CommandResult(ok=True, detail="ok")
        ok, _ = ops.compile(_java_profile(), tmp_path / "run")
    assert ok is True
    called_cmd = mock_run.call_args[0][0]
    assert any("gradlew" in c for c in called_cmd)


def test_gradle_ops_compile_rust_invokes_cargo(tmp_path: Path) -> None:
    rust_dir = tmp_path / "control-plane-rust"
    rust_dir.mkdir()
    (rust_dir / "Cargo.toml").write_text("[package]\nname = \"cp\"", encoding="utf-8")
    ops = GradleOps(tmp_path)
    (tmp_path / "run").mkdir()
    with patch.object(ops, "_run") as mock_run:
        mock_run.return_value = CommandResult(ok=True, detail="ok")
        ok, _ = ops.compile(_rust_profile(), tmp_path / "run")
    assert ok is True
    called_cmd = mock_run.call_args[0][0]
    assert called_cmd[0] == "cargo"


def test_gradle_ops_compile_rust_fails_if_manifest_missing(tmp_path: Path) -> None:
    ops = GradleOps(tmp_path)
    (tmp_path / "run").mkdir()
    ok, detail = ops.compile(_rust_profile(), tmp_path / "run")
    assert ok is False
    assert "Rust control plane manifest" in detail


def test_gradle_ops_build_image_java_invokes_docker_build(tmp_path: Path) -> None:
    (tmp_path / "control-plane" / "Dockerfile").parent.mkdir(parents=True)
    (tmp_path / "control-plane" / "Dockerfile").write_text("FROM scratch", encoding="utf-8")
    ops = GradleOps(tmp_path)
    (tmp_path / "run").mkdir()
    with patch.object(ops, "_run") as mock_run:
        mock_run.return_value = CommandResult(ok=True, detail="ok")
        ok, detail = ops.build_image(_java_profile(), tmp_path / "run")
    assert ok is True
    assert "image=" in detail


def test_gradle_ops_run_api_tests_uses_control_plane_api_test_selector(tmp_path: Path) -> None:
    ops = GradleOps(tmp_path)
    (tmp_path / "run").mkdir()
    with patch.object(ops, "_run") as mock_run:
        mock_run.return_value = CommandResult(ok=True, detail="ok")
        ops.run_api_tests(_java_profile(), tmp_path / "run")
    called_cmd = mock_run.call_args[0][0]
    assert any("ControlPlaneApiTest" in c for c in called_cmd)


def test_gradle_ops_run_mockk8s_tests_uses_mock_selectors(tmp_path: Path) -> None:
    ops = GradleOps(tmp_path)
    (tmp_path / "run").mkdir()
    with patch.object(ops, "_run") as mock_run:
        mock_run.return_value = CommandResult(ok=True, detail="ok")
        ops.run_mockk8s_tests(_java_profile(), tmp_path / "run")
    called_cmd = mock_run.call_args[0][0]
    assert "--tests" in called_cmd
