from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class ToolPaths:
    workspace_root: Path
    tool_root: Path
    profiles_dir: Path
    runs_dir: Path
    scenarios_dir: Path
    scenario_payloads_dir: Path
    ops_root: Path
    ansible_root: Path

    @classmethod
    def repo_root(cls, root: Path) -> "ToolPaths":
        workspace_root = Path(root)
        tool_root = workspace_root / "tools" / "controlplane"
        ops_root = workspace_root / "ops"
        return cls(
            workspace_root=workspace_root,
            tool_root=tool_root,
            profiles_dir=tool_root / "profiles",
            runs_dir=tool_root / "runs",
            scenarios_dir=tool_root / "scenarios",
            scenario_payloads_dir=tool_root / "scenarios" / "payloads",
            ops_root=ops_root,
            ansible_root=ops_root / "ansible",
        )


def discover_repo_root() -> Path:
    return Path(__file__).resolve().parents[5]


def default_tool_paths() -> ToolPaths:
    return ToolPaths.repo_root(discover_repo_root())


def scenario_path_from_env(cli_path: Path | None = None) -> Path | None:
    """Resolve scenario path: CLI argument takes precedence over NANOFAAS_SCENARIO_PATH env var."""
    if cli_path is not None:
        return cli_path
    import os

    s = os.getenv("NANOFAAS_SCENARIO_PATH", "").strip()
    return Path(s) if s else None


def resolve_workspace_path(path: Path) -> Path:
    if path.is_absolute():
        return path.resolve()

    workspace_candidate = default_tool_paths().workspace_root / path
    if workspace_candidate.exists():
        return workspace_candidate.resolve()

    return (Path.cwd() / path).resolve()
