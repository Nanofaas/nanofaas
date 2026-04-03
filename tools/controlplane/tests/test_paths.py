from pathlib import Path

from controlplane_tool.paths import ToolPaths


def test_default_paths_are_rooted_under_tools_controlplane() -> None:
    paths = ToolPaths.repo_root(Path("/repo"))
    assert paths.tool_root == Path("/repo/tools/controlplane")
    assert paths.profiles_dir == Path("/repo/tools/controlplane/profiles")
    assert paths.runs_dir == Path("/repo/tools/controlplane/runs")
