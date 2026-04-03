from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ToolPaths:
    workspace_root: Path
    tool_root: Path
    profiles_dir: Path
    runs_dir: Path

    @classmethod
    def repo_root(cls, root: Path) -> "ToolPaths":
        workspace_root = Path(root)
        tool_root = workspace_root / "tools" / "controlplane"
        return cls(
            workspace_root=workspace_root,
            tool_root=tool_root,
            profiles_dir=tool_root / "profiles",
            runs_dir=tool_root / "runs",
        )


def discover_repo_root() -> Path:
    return Path(__file__).resolve().parents[4]


def default_tool_paths() -> ToolPaths:
    return ToolPaths.repo_root(discover_repo_root())
