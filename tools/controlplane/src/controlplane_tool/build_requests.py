from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, Field

from controlplane_tool.models import BuildAction, ProfileName


PROFILE_TO_MODULES_SELECTOR: dict[ProfileName, str] = {
    "core": "none",
    "k8s": "k8s-deployment-provider",
    "container-local": "container-deployment-provider",
    "all": "all",
}


class BuildRequest(BaseModel):
    action: BuildAction
    profile: ProfileName
    modules: str | None = None
    extra_gradle_args: list[str] = Field(default_factory=list)


def resolve_modules_selector(request: BuildRequest) -> str:
    if request.modules and request.modules.strip():
        return request.modules.strip()
    return PROFILE_TO_MODULES_SELECTOR[request.profile]


def parse_modules_csv(modules_csv: str | None) -> list[str]:
    if not modules_csv:
        return []
    modules: list[str] = []
    for module in modules_csv.split(","):
        cleaned = module.strip()
        if cleaned:
            modules.append(cleaned)
    return modules


def detect_optional_modules(repo_root: Path) -> list[str]:
    modules_root = repo_root / "control-plane-modules"
    if not modules_root.exists():
        return []
    modules: list[str] = []
    for candidate in sorted(modules_root.iterdir()):
        if not candidate.is_dir():
            continue
        if (candidate / "build.gradle").exists() or (candidate / "build.gradle.kts").exists():
            modules.append(candidate.name)
    return modules


def resolve_matrix_modules(
    repo_root: Path,
    modules_csv: str | None = None,
    modules: list[str] | None = None,
) -> list[str]:
    resolved = modules or parse_modules_csv(modules_csv)
    if resolved:
        return resolved
    return detect_optional_modules(repo_root)


def build_module_selectors(modules: list[str]) -> list[str]:
    selectors: list[str] = []
    module_count = len(modules)
    for mask in range(1 << module_count):
        selected = [
            modules[bit]
            for bit in range(module_count)
            if (mask >> bit) & 1
        ]
        selectors.append(",".join(selected) if selected else "none")
    return selectors
