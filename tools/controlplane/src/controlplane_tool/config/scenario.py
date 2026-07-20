from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

WorkflowName = Literal["validate", "cli", "loadtest", "offload", "offload-loadtest"]
BackendName = Literal["pool", "container", "k8s"]
BuildStrategy = Literal["docker", "buildpack"]


class ResourceQuantity(BaseModel):
    model_config = ConfigDict(extra="forbid", populate_by_name=True)

    cpu: float | None = Field(default=None, gt=0, multiple_of=0.001)
    memory_mib: int | None = Field(default=None, alias="memoryMiB", gt=0)


class ResourceSpec(BaseModel):
    model_config = ConfigDict(extra="forbid")

    requests: ResourceQuantity | None = None
    limits: ResourceQuantity | None = None

    @model_validator(mode="after")
    def requests_within_limits(self) -> "ResourceSpec":
        if self.requests is None or self.limits is None:
            return self
        for field in ("cpu", "memory_mib"):
            request = getattr(self.requests, field)
            limit = getattr(self.limits, field)
            if request is not None and limit is not None and request > limit:
                raise ValueError("resource request must not exceed limit")
        return self


class ScenarioConfig(BaseModel):
    model_config = ConfigDict(extra="forbid")

    workflow: WorkflowName
    backend: BackendName | None = None
    build: BuildStrategy = "docker"
    functions: list[str] = Field(min_length=1)
    resources: dict[str, ResourceSpec] = Field(default_factory=dict)
    autoscaling: bool = False

    @model_validator(mode="after")
    def validate_workflow(self) -> "ScenarioConfig":
        if self.workflow == "validate" and self.backend is None:
            raise ValueError("backend is required for validate workflow")
        if self.workflow == "offload" and self.backend is not None:
            raise ValueError("offload workflow does not take a backend")
        if set(self.resources) - set(self.functions):
            raise ValueError("resources must refer to selected functions")
        if self.autoscaling and self.workflow != "loadtest":
            raise ValueError("autoscaling is only supported by the loadtest workflow")
        return self
