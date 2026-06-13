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
    aliases: tuple[str, ...] = ()
    details: str = ""


SCENARIOS: tuple[ScenarioDefinition, ...] = (
    ScenarioDefinition(
        name="validate-docker-pool",
        description="Local POOL runtime regression with Docker-built images on the host.",
        requires_vm=False,
        supported_runtimes=("java",),
        aliases=("docker",),
    ),
    ScenarioDefinition(
        name="validate-buildpack-pool",
        description="Local POOL runtime with buildpack-built images, plus managed local DEPLOYMENT coverage.",
        requires_vm=False,
        supported_runtimes=("java",),
        aliases=("buildpack",),
    ),
    ScenarioDefinition(
        name="validate-container-local",
        description="No-Kubernetes managed DEPLOYMENT backend, fully local, one selected function.",
        requires_vm=False,
        supported_runtimes=("java",),
        selection_mode="single",
        aliases=("container-local",),
    ),
    ScenarioDefinition(
        name="validate-k3s",
        description="Multipass VM + k3s + Helm stack, verified with curl probes and the JUnit K8sE2eTest suite.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        aliases=("k3s-junit-curl",),
    ),
    ScenarioDefinition(
        name="cli-suite",
        description="Full nanofaas-cli lifecycle test suite executed inside a managed VM against k3s.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        aliases=("cli",),
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
        name="validate-deploy-host",
        description="Host-only deploy workflow against a stub control-plane (host compatibility path).",
        requires_vm=False,
        supported_runtimes=("java",),
        uses_host_cli=True,
        aliases=("deploy-host",),
    ),
    ScenarioDefinition(
        name="loadtest-helm-legacy",
        description="Legacy Helm install + k6 loadtest + autoscaling sequence via the experiments/ scripts.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("helm-stack",),
    ),
    ScenarioDefinition(
        name="loadtest-one-vm",
        description="Helm stack, k6, and autoscaling verification on a single Multipass VM.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("one-vm-helm-loadtest",),
    ),
    ScenarioDefinition(
        name="loadtest-two-vm",
        description="Helm stack on one Multipass VM, dedicated k6 generator on a second; Prometheus snapshots + HTML report.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("two-vm-loadtest",),
    ),
    ScenarioDefinition(
        name="loadtest-azure",
        description="Two-VM loadtest on Azure (OpenTofu, profiles/azure.toml): stack VM with open NodePorts + k6 loadgen VM.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("azure-vm-loadtest",),
    ),
    ScenarioDefinition(
        name="loadtest-proxmox",
        description="Two-VM loadtest on Proxmox VE (cloned templates, NAT-published ports, profiles/proxmox.toml).",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("proxmox-vm-loadtest",),
    ),
)

SCENARIO_INDEX = {scenario.name: scenario for scenario in SCENARIOS}

_ALIAS_INDEX: dict[str, str] = {
    alias: scenario.name for scenario in SCENARIOS for alias in scenario.aliases
}


def canonical_scenario_name(name: str) -> str:
    """Map a deprecated scenario alias to its canonical name.

    Canonical and unknown names pass through unchanged (unknown names must keep
    failing in resolve_scenario with the existing error message). Callers at the
    user-facing boundaries (CLI args, scenario files, TUI dispatch) are expected
    to call this; internal code only ever sees canonical names.
    """
    canonical = _ALIAS_INDEX.get(name)
    if canonical is not None:
        import sys

        print(f"note: scenario '{name}' is deprecated, use '{canonical}'", file=sys.stderr)
        return canonical
    return name


def list_scenarios() -> list[ScenarioDefinition]:
    return list(SCENARIOS)


def resolve_scenario(name: str) -> ScenarioDefinition:
    try:
        return SCENARIO_INDEX[name]
    except KeyError as exc:
        raise ValueError(f"Unknown scenario: {name}") from exc
