from __future__ import annotations

from pydantic import BaseModel

from controlplane_tool.cli_validation.cli_test_models import CliTestGradleTask
from controlplane_tool.core.models import CliTestScenarioName, ScenarioName


class CliTestScenarioDefinition(BaseModel):
    name: CliTestScenarioName
    description: str
    requires_vm: bool
    accepts_function_selection: bool
    gradle_task: CliTestGradleTask
    legacy_e2e_scenario: ScenarioName | None = None


_SCENARIOS: tuple[CliTestScenarioDefinition, ...] = (
    CliTestScenarioDefinition(
        name="unit",
        description="Run nanofaas-cli Gradle tests locally.",
        requires_vm=False,
        accepts_function_selection=False,
        gradle_task=":nanofaas-cli:test",
    ),
    CliTestScenarioDefinition(
        name="cli-stack",
        description="Run the dedicated VM-backed CLI stack evaluation flow.",
        requires_vm=True,
        accepts_function_selection=True,
        gradle_task=":nanofaas-cli:installDist",
        legacy_e2e_scenario="cli-stack",
    ),
    CliTestScenarioDefinition(
        name="host-platform",
        description="Run the host CLI platform workflow against a VM-backed platform.",
        requires_vm=True,
        accepts_function_selection=False,
        gradle_task=":nanofaas-cli:installDist",
        legacy_e2e_scenario="cli-host",
    ),
    CliTestScenarioDefinition(
        name="deploy-host",
        description="Run the host-only deploy workflow against a fake control-plane.",
        requires_vm=False,
        accepts_function_selection=True,
        gradle_task=":nanofaas-cli:installDist",
        legacy_e2e_scenario="deploy-host",
    ),
)

_SCENARIO_INDEX = {scenario.name: scenario for scenario in _SCENARIOS}


def list_cli_test_scenarios() -> list[CliTestScenarioDefinition]:
    return [scenario.model_copy(deep=True) for scenario in _SCENARIOS]


def resolve_cli_test_scenario(name: CliTestScenarioName | str) -> CliTestScenarioDefinition:
    try:
        return _SCENARIO_INDEX[name].model_copy(deep=True)
    except KeyError as exc:
        raise ValueError(f"Unknown cli-test scenario: {name}") from exc
