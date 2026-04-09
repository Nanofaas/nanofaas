from pathlib import Path

from controlplane_tool.paths import ToolPaths, resolve_workspace_path


def test_default_paths_are_rooted_under_tools_controlplane() -> None:
    paths = ToolPaths.repo_root(Path("/repo"))
    assert paths.tool_root == Path("/repo/tools/controlplane")
    assert paths.profiles_dir == Path("/repo/tools/controlplane/profiles")
    assert paths.runs_dir == Path("/repo/tools/controlplane/runs")
    assert paths.scenarios_dir == Path("/repo/tools/controlplane/scenarios")
    assert paths.scenario_payloads_dir == Path("/repo/tools/controlplane/scenarios/payloads")
    assert paths.ops_root == Path("/repo/ops")
    assert paths.ansible_root == Path("/repo/ops/ansible")


def test_resolve_workspace_path_prefers_active_worktree_root(
    tmp_path: Path,
    monkeypatch,
) -> None:
    workspace_root = tmp_path / "workspace"
    scenario_path = workspace_root / "tools" / "controlplane" / "scenarios" / "demo.toml"
    scenario_path.parent.mkdir(parents=True, exist_ok=True)
    scenario_path.write_text('name = "demo"\nbase_scenario = "k3s-junit-curl"\nruntime = "java"\n', encoding="utf-8")

    monkeypatch.setattr(
        "controlplane_tool.paths.default_tool_paths",
        lambda: ToolPaths.repo_root(workspace_root),
    )
    monkeypatch.chdir("/")

    assert resolve_workspace_path(Path("tools/controlplane/scenarios/demo.toml")) == scenario_path.resolve()


def test_resolve_workspace_path_keeps_absolute_paths(tmp_path: Path) -> None:
    absolute_path = tmp_path / "already-absolute.toml"
    absolute_path.write_text("", encoding="utf-8")

    assert resolve_workspace_path(absolute_path) == absolute_path.resolve()
