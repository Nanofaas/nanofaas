from __future__ import annotations

from pathlib import Path
import tomllib

import tomli_w

from controlplane_tool.models import Profile
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.scenario_models import ScenarioPrefectConfig


def profiles_dir(root: Path | None = None) -> Path:
    if root is not None:
        return root
    return default_tool_paths().profiles_dir


def profile_path(name: str, root: Path | None = None) -> Path:
    return profiles_dir(root) / f"{name}.toml"


def save_profile(
    profile: Profile,
    root: Path | None = None,
    *,
    prefect: ScenarioPrefectConfig | None = None,
) -> Path:
    destination_dir = profiles_dir(root)
    destination_dir.mkdir(parents=True, exist_ok=True)
    destination = profile_path(profile.name, root)
    payload = profile.model_dump(mode="python", exclude_none=True)
    if prefect is not None:
        payload["prefect"] = prefect.model_dump(mode="python", exclude_none=True)
    destination.write_text(tomli_w.dumps(payload), encoding="utf-8")
    return destination


def load_profile(name: str, root: Path | None = None) -> Profile:
    source = profile_path(name, root)
    data = tomllib.loads(source.read_text(encoding="utf-8"))
    return Profile.model_validate(data)


def list_profiles(root: Path | None = None) -> list[str]:
    directory = profiles_dir(root)
    if not directory.exists():
        return []
    return sorted(path.stem for path in directory.glob("*.toml"))


def load_profile_prefect_config(name: str, root: Path | None = None) -> ScenarioPrefectConfig | None:
    source = profile_path(name, root)
    data = tomllib.loads(source.read_text(encoding="utf-8"))
    prefect = data.get("prefect")
    if not prefect:
        return None
    return ScenarioPrefectConfig.model_validate(prefect)
