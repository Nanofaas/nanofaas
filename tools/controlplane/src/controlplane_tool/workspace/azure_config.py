from __future__ import annotations

import tomllib
from pathlib import Path

from controlplane_tool.core.models import AzureConfig
from controlplane_tool.workspace.paths import default_tool_paths


def azure_config_path(root: Path | None = None) -> Path:
    tool_root = Path(root) if root is not None else default_tool_paths().tool_root
    return tool_root / "profiles" / "azure.toml"


def load_azure_config(root: Path | None = None) -> AzureConfig:
    path = azure_config_path(root)
    if not path.exists():
        raise FileNotFoundError(path)
    data = tomllib.loads(path.read_text(encoding="utf-8"))
    return AzureConfig.model_validate(data)


def azure_config_exists(root: Path | None = None) -> bool:
    return azure_config_path(root).exists()
