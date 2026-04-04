from __future__ import annotations

import os

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


def vm_request_from_env() -> "VmRequest":
    """Reconstruct VmRequest from environment variables set by E2eRunner._vm_env()."""
    return VmRequest(
        lifecycle=os.getenv("E2E_VM_LIFECYCLE", "multipass"),
        name=os.getenv("VM_NAME"),
        host=os.getenv("E2E_VM_HOST"),
        user=os.getenv("E2E_VM_USER", "ubuntu"),
        home=os.getenv("E2E_VM_HOME"),
        cpus=int(os.getenv("CPUS", "4")),
        memory=os.getenv("MEMORY", "8G"),
        disk=os.getenv("DISK", "30G"),
    )
