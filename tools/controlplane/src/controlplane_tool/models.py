from __future__ import annotations

from typing import Literal

from pydantic import BaseModel, Field

ControlPlaneImplementation = Literal["rust", "java"]
BuildMode = Literal["native", "jvm", "rust"]
LoadProfile = Literal["quick", "stress"]


class ControlPlaneConfig(BaseModel):
    implementation: ControlPlaneImplementation
    build_mode: BuildMode


class TestsConfig(BaseModel):
    __test__ = False

    enabled: bool = True
    api: bool = True
    e2e_mockk8s: bool = True
    metrics: bool = True
    load_profile: LoadProfile = "quick"


class MetricsConfig(BaseModel):
    required: list[str] = Field(default_factory=list)
    prometheus_url: str | None = None
    strict_required: bool = False


class ReportConfig(BaseModel):
    title: str = "Control Plane Tooling Run"
    include_baseline: bool = False


class Profile(BaseModel):
    name: str
    control_plane: ControlPlaneConfig
    modules: list[str] = Field(default_factory=list)
    tests: TestsConfig = Field(default_factory=TestsConfig)
    metrics: MetricsConfig = Field(default_factory=MetricsConfig)
    report: ReportConfig = Field(default_factory=ReportConfig)
