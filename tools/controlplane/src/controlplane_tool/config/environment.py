from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

ProviderName = Literal["local", "multipass", "external", "azure", "proxmox"]
ExecutionRole = Literal["host", "stack", "loadgen", "cloud", "arm-builder"]


class RoleTarget(BaseModel):
    model_config = ConfigDict(extra="forbid")

    name: str | None = None
    host: str | None = None
    user: str = "ubuntu"
    home: str | None = None
    kubeconfig: str | None = None
    cpus: int = Field(default=4, gt=0)
    memory: str = "12G"
    disk: str = "30G"


class AzureEnvironment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    resource_group: str
    location: str
    image_urn: str | None = None
    ssh_key_path: str | None = None
    operator_source_cidr: str | None = None
    vm_size: str = "Standard_D4s_v5"
    loadgen_vm_size: str = "Standard_B1s"
    # Native ARM64 builder (Ampere): used by the release arm64 phases.
    arm_vm_size: str = "Standard_D8ps_v5"
    arm_image_urn: str | None = None


class ProxmoxEnvironment(BaseModel):
    model_config = ConfigDict(extra="forbid")

    host: str
    node: str
    user: str = "root@pam"
    password_env: str = "PROXMOX_PASSWORD"
    template_id: int | None = None
    ssh_key_path: str | None = None


class EnvironmentConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    provider: ProviderName
    roles: dict[ExecutionRole, RoleTarget] = Field(default_factory=dict)
    azure: AzureEnvironment | None = None
    proxmox: ProxmoxEnvironment | None = None

    @model_validator(mode="after")
    def validate_provider(self) -> "EnvironmentConfig":
        if self.provider == "multipass":
            stack = self.roles.get("stack")
            if stack is None or not stack.name:
                raise ValueError("stack name is required for multipass provider")
        if self.provider == "external":
            stack = self.roles.get("stack")
            if stack is None or not stack.host:
                raise ValueError("stack host is required for external provider")
            loadgen = self.roles.get("loadgen")
            if loadgen is not None and not loadgen.host:
                raise ValueError("loadgen host is required for external provider")
        if self.provider == "azure" and self.azure is None:
            raise ValueError("azure configuration is required for azure provider")
        if self.provider == "proxmox" and self.proxmox is None:
            raise ValueError("proxmox configuration is required for proxmox provider")
        return self

    def target(self, role: ExecutionRole) -> RoleTarget:
        if role == "host":
            return self.roles.get("host", RoleTarget())
        if role == "stack":
            return self.roles.get("stack", self.target("host"))
        if role == "cloud":
            return self.roles.get("cloud", self.target("stack"))
        if role == "arm-builder":
            return self.roles.get("arm-builder", self.target("stack"))
        return self.roles.get("loadgen", self.target("stack"))
