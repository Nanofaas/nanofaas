from __future__ import annotations

from pydantic import BaseModel, model_validator

from controlplane_tool.models import VmLifecycle


class VmRequest(BaseModel):
    lifecycle: VmLifecycle
    name: str | None = None
    host: str | None = None
    user: str = "ubuntu"
    home: str | None = None
    cpus: int = 4
    memory: str = "8G"
    disk: str = "30G"

    @model_validator(mode="after")
    def validate_lifecycle_requirements(self) -> "VmRequest":
        if self.lifecycle == "external" and not self.host:
            raise ValueError("host is required for external lifecycle")
        return self
