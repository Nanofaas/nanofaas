from __future__ import annotations

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
