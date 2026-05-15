from __future__ import annotations

from dataclasses import dataclass
from typing import Literal

from controlplane_tool.core.models import RuntimeKind, ScenarioName

SelectionMode = Literal["single", "multi"]


@dataclass(frozen=True)
class ScenarioDefinition:
    name: ScenarioName
    description: str
    requires_vm: bool
    supported_runtimes: tuple[RuntimeKind, ...]
    selection_mode: SelectionMode = "multi"
    uses_host_cli: bool = False
    grouped_phases: bool = False


SCENARIOS: tuple[ScenarioDefinition, ...] = (
    ScenarioDefinition(
        name="docker",
        description="Local Docker POOL regression path.",
        requires_vm=False,
        supported_runtimes=("java",),
    ),
    ScenarioDefinition(
        name="buildpack",
        description="Buildpack regression and managed local DEPLOYMENT coverage.",
        requires_vm=False,
        supported_runtimes=("java",),
    ),
    ScenarioDefinition(
        name="container-local",
        description="No-k8s managed DEPLOYMENT provider flow for a single selected function.",
        requires_vm=False,
        supported_runtimes=("java",),
        selection_mode="single",
    ),
    ScenarioDefinition(
        name="k3s-junit-curl",
        description="Shared k3s Helm deployment with curl and JUnit validation.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
    ),
    ScenarioDefinition(
        name="cli",
        description="Full CLI lifecycle suite inside the VM.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
    ),
    ScenarioDefinition(
        name="cli-stack",
        description="Dedicated VM-backed CLI evaluation flow over k3s.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        uses_host_cli=True,
    ),
    ScenarioDefinition(
        name="cli-host",
        description="Host CLI driving a VM-backed platform install.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        uses_host_cli=True,
    ),
    ScenarioDefinition(
        name="deploy-host",
        description="Host-only deploy workflow against a fake control-plane.",
        requires_vm=False,
        supported_runtimes=("java",),
        uses_host_cli=True,
    ),
    ScenarioDefinition(
        name="helm-stack",
        description="Helm install, loadtest, and autoscaling sequence.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
    ),
    ScenarioDefinition(
        name="two-vm-loadtest",
        description="Two-VM Helm stack load test with a dedicated k6 generator.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
    ),
    ScenarioDefinition(
        name="azure-vm-loadtest",
        description="Two-VM Azure load test: stack VM + k6 loadgen on Azure.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
    ),
)

SCENARIO_INDEX = {scenario.name: scenario for scenario in SCENARIOS}


def list_scenarios() -> list[ScenarioDefinition]:
    return list(SCENARIOS)


def resolve_scenario(name: str) -> ScenarioDefinition:
    try:
        return SCENARIO_INDEX[name]
    except KeyError as exc:
        raise ValueError(f"Unknown scenario: {name}") from exc
