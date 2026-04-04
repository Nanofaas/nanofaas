from __future__ import annotations

from pydantic import BaseModel, model_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

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


class _VmEnvSettings(BaseSettings):
    model_config = SettingsConfigDict(env_ignore_empty=True)

    e2e_vm_lifecycle: VmLifecycle = "multipass"
    vm_name: str | None = None
    e2e_vm_host: str | None = None
    e2e_vm_user: str = "ubuntu"
    e2e_vm_home: str | None = None
    cpus: int = 4
    memory: str = "8G"
    disk: str = "30G"


def vm_request_from_env() -> "VmRequest":
    """Reconstruct VmRequest from environment variables set by E2eRunner._vm_env()."""
    s = _VmEnvSettings()
    return VmRequest(
        lifecycle=s.e2e_vm_lifecycle,
        name=s.vm_name,
        host=s.e2e_vm_host,
        user=s.e2e_vm_user,
        home=s.e2e_vm_home,
        cpus=s.cpus,
        memory=s.memory,
        disk=s.disk,
    )
