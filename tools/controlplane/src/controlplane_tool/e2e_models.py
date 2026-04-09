from __future__ import annotations

from pathlib import Path

from pydantic import BaseModel, Field, model_validator

from controlplane_tool.models import RuntimeKind, ScenarioName, VM_BACKED_SCENARIOS
from controlplane_tool.scenario_models import ResolvedScenario
from controlplane_tool.vm_models import VmRequest


class E2eRequest(BaseModel):
    scenario: ScenarioName
    runtime: RuntimeKind = "java"
    function_preset: str | None = None
    functions: list[str] = Field(default_factory=list)
    scenario_file: Path | None = None
    saved_profile: str | None = None
    scenario_source: str | None = None
    resolved_scenario: ResolvedScenario | None = None
    vm: VmRequest | None = None
    cleanup_vm: bool = True
    namespace: str | None = None
    local_registry: str = "localhost:5000"

    @model_validator(mode="after")
    def validate_scenario_requirements(self) -> "E2eRequest":
        if self.function_preset and self.functions:
            raise ValueError("function selection must use only one of function_preset or functions")
        if self.scenario in VM_BACKED_SCENARIOS and self.vm is None:
            raise ValueError(f"vm configuration is required for scenario '{self.scenario}'")
        if self.resolved_scenario is not None and self.scenario != self.resolved_scenario.base_scenario:
            raise ValueError(
                "resolved scenario base_scenario must match the request scenario"
            )
        return self
