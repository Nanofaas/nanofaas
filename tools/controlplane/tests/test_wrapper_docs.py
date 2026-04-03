from pathlib import Path


def test_control_plane_build_wrapper_uses_tools_controlplane_project() -> None:
    script = (Path("scripts") / ("control" + "-plane-build.sh")).read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "controlplane.sh" in script


def test_pipeline_wrapper_uses_locked_uv_run() -> None:
    script = Path("scripts/controlplane.sh").read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked" in script


def test_pipeline_wrapper_forwards_to_tui_path() -> None:
    script = (Path("scripts") / ("controlplane" + "-tool.sh")).read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "controlplane.sh" in script
    assert "tui" in script


def test_profile_fixture_exists_for_saved_profile_flow() -> None:
    assert Path("tools/controlplane/profiles/demo-java.toml").exists()
    profile = Path("tools/controlplane/profiles/demo-java.toml").read_text(encoding="utf-8")
    assert "[cli_test]" in profile
    assert 'default_scenario = "vm"' in profile


def test_loadtest_wrapper_routes_to_legacy_backend() -> None:
    script = Path("scripts/e2e-loadtest.sh").read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "experiments/e2e-loadtest.sh" in script
    assert "scripts/controlplane.sh loadtest run" in script


def test_gitignore_includes_controlplane_runs_dir() -> None:
    gitignore = Path(".gitignore").read_text(encoding="utf-8")
    assert "tools/controlplane/runs/" in gitignore
