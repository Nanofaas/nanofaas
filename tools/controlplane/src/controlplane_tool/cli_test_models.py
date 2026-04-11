from __future__ import annotations

from pathlib import Path
from typing import Literal

from pydantic import BaseModel, Field, model_validator

from controlplane_tool.models import CliTestScenarioName, RuntimeKind
from controlplane_tool.registry_runtime import default_registry_url
from controlplane_tool.scenario_models import ResolvedScenario
from controlplane_tool.vm_models import VmRequest

CliTestGradleTask = Literal[":nanofaas-cli:test", ":nanofaas-cli:installDist"]

CLI_TEST_VM_BACKED_SCENARIOS = frozenset({"vm", "cli-stack", "host-platform"})
CLI_TEST_FUNCTION_SELECTION_SCENARIOS = frozenset(
    {"vm", "cli-stack", "deploy-host"}
)


class CliTestRequest(BaseModel):
    scenario: CliTestScenarioName
    runtime: RuntimeKind = "java"
    function_preset: str | None = None
    functions: list[str] = Field(default_factory=list)
    scenario_file: Path | None = None
    saved_profile: str | None = None
    scenario_source: str | None = None
    resolved_scenario: ResolvedScenario | None = None
    vm: VmRequest | None = None
    keep_vm: bool = False
    namespace: str | None = None
    local_registry: str = Field(default_factory=default_registry_url)

    @model_validator(mode="after")
    def validate_request(self) -> "CliTestRequest":
        if self.function_preset and self.functions:
            raise ValueError(
                "function selection must use only one of function_preset or functions"
            )
        if self.scenario in CLI_TEST_VM_BACKED_SCENARIOS and self.vm is None:
            raise ValueError(f"vm configuration is required for scenario '{self.scenario}'")

        has_function_selection = any(
            (
                self.function_preset,
                self.functions,
                self.scenario_file,
                self.resolved_scenario,
            )
        )
        if (
            self.scenario not in CLI_TEST_FUNCTION_SELECTION_SCENARIOS
            and has_function_selection
        ):
            raise ValueError(
                f"scenario '{self.scenario}' does not accept function selection"
            )
        return self
