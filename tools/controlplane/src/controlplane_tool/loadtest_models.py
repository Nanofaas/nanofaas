from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, model_validator

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


class LoadtestRequest(BaseModel):
    name: str
    profile: Profile
    scenario: ResolvedScenario
    load_profile: LoadProfileDefinition
    targets: LoadTargetSelection | None = None
    metrics_gate: MetricsGate = Field(default_factory=MetricsGate)
    report_title: str | None = None

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
                update={"required_metrics": list(self.profile.metrics.required)}
            )
        if self.report_title is None:
            self.report_title = self.profile.report.title
        return self
