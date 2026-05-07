from pathlib import Path

from controlplane_tool.workspace.paths import resolve_workspace_path


def test_control_plane_build_wrapper_uses_tools_controlplane_project() -> None:
    script = resolve_workspace_path(
        Path("scripts") / ("control" + "-plane-building.sh")
    ).read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "controlplane.sh" in script


def test_pipeline_wrapper_uses_locked_uv_run() -> None:
    script = resolve_workspace_path(Path("scripts/controlplane.sh")).read_text(encoding="utf-8")
    assert "uv run --project tools/controlplane --locked" in script


def test_pipeline_wrapper_forwards_to_tui_path() -> None:
    script = resolve_workspace_path(
        Path("scripts") / ("controlplane" + "-tool.sh")
    ).read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "controlplane.sh" in script
    assert "tui" in script


def test_tool_readme_uses_tui_as_interactive_entrypoint_without_profile_flags() -> None:
    readme = resolve_workspace_path(Path("tools/controlplane/README.md")).read_text(
        encoding="utf-8"
    )

    assert "scripts/controlplane.sh tui" in readme
    assert "scripts/controlplane.sh tui --profile-name" not in readme
    assert "scripts/controlplane.sh tui --profile-name dev --use-saved-profile" not in readme


def test_profile_fixture_exists_for_saved_profile_flow() -> None:
    profile_path = resolve_workspace_path(Path("tools/controlplane/profiles/demo-java.toml"))
    assert profile_path.exists()
    profile = profile_path.read_text(encoding="utf-8")
    assert "[cli_test]" in profile
    assert 'default_scenario = "cli-stack"' in profile


def test_javascript_profile_fixture_exists_for_saved_profile_flow() -> None:
    profile_path = resolve_workspace_path(Path("tools/controlplane/profiles/demo-javascript.toml"))
    assert profile_path.exists()
    profile = profile_path.read_text(encoding="utf-8")
    assert "[cli_test]" in profile
    assert 'default_scenario = "cli-stack"' in profile


def test_loadtest_wrapper_routes_to_python_runner() -> None:
    # M12: wrapper routes to controlplane.sh loadtest run (not experiments script)
    script = resolve_workspace_path(Path("scripts/e2e-loadtest.sh")).read_text(encoding="utf-8")
    assert "Compatibility wrapper" in script
    assert "experiments/e2e-loadtest.sh" not in script
    assert "controlplane.sh" in script
    assert "loadtest run" in script


def test_gitignore_includes_controlplane_runs_dir() -> None:
    gitignore = resolve_workspace_path(Path(".gitignore")).read_text(encoding="utf-8")
    assert "tools/controlplane/runs/" in gitignore
