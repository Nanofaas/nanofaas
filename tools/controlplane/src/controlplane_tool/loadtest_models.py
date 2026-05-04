from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from controlplane_tool.metrics_contract import (
    CORE_REQUIRED_METRICS,
    LEGACY_STRICT_REQUIRED_METRICS,
)
from controlplane_tool.models import LoadProfile, MetricsGateMode, Profile
from controlplane_tool.scenario_models import ResolvedScenario


class LoadStage(BaseModel):
    duration: str
    target: int = Field(ge=0)


class LoadProfileDefinition(BaseModel):
    name: LoadProfile
    description: str
    stages: list[LoadStage] = Field(default_factory=list)
    summary_window_seconds: int = Field(default=30, gt=0)


class LoadTargetSelection(BaseModel):
    scenario_name: str | None = None
    targets: list[str] = Field(default_factory=list)


class MetricsGate(BaseModel):
    mode: MetricsGateMode = "enforce"
    required_metrics: list[str] = Field(default_factory=list)


class TargetRunResult(BaseModel):
    function_key: str
    k6_summary_path: Path
    status: Literal["passed", "failed"]
    detail: str


def effective_required_metrics(profile: Profile) -> list[str]:
    configured = list(profile.metrics.required)
    if profile.metrics.strict_required:
        return configured
    if not configured:
        return list(CORE_REQUIRED_METRICS)
    if set(configured) == set(LEGACY_STRICT_REQUIRED_METRICS):
        return list(CORE_REQUIRED_METRICS)
    return configured


class LoadtestRequest(BaseModel):
    name: str
    profile: Profile
    scenario: ResolvedScenario
    load_profile: LoadProfileDefinition
    targets: LoadTargetSelection | None = None
    metrics_gate: MetricsGate = Field(default_factory=MetricsGate)
    report_title: str | None = None

    @property
    def execution_description(self) -> str:
        return (
            "This load test starts a local control-plane against a mock Kubernetes API. "
            "During bootstrap the requested targets are ensured as LOCAL fixture functions "
            "and warmed up through the control-plane API; k6 then invokes the requested "
            "targets sequentially over HTTP. It validates control-plane dispatch, k6 traffic, "
            "and Prometheus metrics, not Kubernetes pods for the requested target images. "
            "The bootstrap also provisions a separate demo-word-stats-deployment DEPLOYMENT "
            "sanity check; that deployment is not the same as the requested load-test targets."
        )

    @model_validator(mode="after")
    def hydrate_defaults(self) -> "LoadtestRequest":
        resolved_targets = (
            list(self.scenario.load.targets) if self.scenario.load.targets else list(self.scenario.function_keys)
        )
        current_targets = list(self.targets.targets) if self.targets is not None else []
        self.targets = LoadTargetSelection(
            scenario_name=self.scenario.name,
            targets=current_targets or resolved_targets,
        )
        if not self.metrics_gate.required_metrics:
            self.metrics_gate = self.metrics_gate.model_copy(
                update={"required_metrics": effective_required_metrics(self.profile)}
            )
        if self.report_title is None:
            self.report_title = self.profile.report.title
        return self
