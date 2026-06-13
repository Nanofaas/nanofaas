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
        details=(
            "Exercises the local POOL runtime path (OpenWhisk-style warm pods) using"
            " Docker-built images on the host via the Gradle e2e suite (Testcontainers"
            " + RestAssured). Regression net for warm-pool dispatch, retries, and"
            " idempotency against a real local container runtime. Requires a running"
            " Docker daemon. ~5–10 min."
        ),
    ),
    ScenarioDefinition(
        name="validate-buildpack-pool",
        description="Local POOL runtime with buildpack-built images, plus managed local DEPLOYMENT coverage.",
        requires_vm=False,
        supported_runtimes=("java",),
        aliases=("buildpack",),
        details=(
            "Same local POOL runtime exercise as `validate-docker-pool`, but the"
            " function images are produced with Cloud Native Buildpacks"
            " (`bootBuildImage`), additionally covering the managed local DEPLOYMENT"
            " path with buildpack output. Slower than the Docker variant because"
            " buildpack builds are heavyweight. Requires a running Docker daemon."
            " ~10–15 min."
        ),
    ),
    ScenarioDefinition(
        name="validate-container-local",
        description="No-Kubernetes managed DEPLOYMENT backend, fully local, one selected function.",
        requires_vm=False,
        supported_runtimes=("java",),
        selection_mode="single",
        aliases=("container-local",),
        details=(
            "Runs the managed DEPLOYMENT execution mode without any Kubernetes: the"
            " container-local provider drives a Docker-compatible runtime on this"
            " machine for a single selected function, exercising registration,"
            " provisioning, invocation, and teardown of the no-k8s backend. No VM is"
            " created. Requires a running Docker daemon. Minutes, not tens of minutes."
        ),
    ),
    ScenarioDefinition(
        name="validate-k3s",
        description="Multipass VM + k3s + Helm stack, verified with curl probes and the JUnit K8sE2eTest suite.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        aliases=("k3s-junit-curl",),
        details=(
            "Provisions a Multipass VM, installs k3s and a local image registry,"
            " builds and pushes the core + selected function images in-VM, deploys"
            " control-plane and function-runtime via Helm, then verifies the"
            " deployment two ways: curl probes against the NodePort API and the"
            " JUnit `K8sE2eTest` suite executed against the cluster (the resolved"
            " scenario manifest is forwarded via `-Dnanofaas.e2e.scenarioManifest`)."
            " Includes uninstall/namespace cleanup and VM teardown phases. The"
            ' canonical "is the platform healthy end-to-end" check. Requires'
            " Multipass. ~15–20 min."
        ),
    ),
    ScenarioDefinition(
        name="cli-suite",
        description="Full nanofaas-cli lifecycle test suite executed inside a managed VM against k3s.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        aliases=("cli",),
        details=(
            "Executes the full nanofaas-cli lifecycle test suite inside a managed VM"
            " against a k3s-backed platform: function init, build, deploy, invoke,"
            " logs, update, and removal flows. The broadest CLI regression net;"
            " significantly longer than `cli-stack`. Requires Multipass. Use"
            " `--no-cleanup-vm` to keep the VM for debugging."
        ),
    ),
    ScenarioDefinition(
        name="cli-stack",
        description="Dedicated VM-backed CLI evaluation flow over k3s.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        uses_host_cli=True,
        details=(
            "The canonical CLI evaluation flow: a self-bootstrapping VM stack where"
            " the CLI itself drives platform install and validation over k3s. Faster"
            ' and more focused than `cli-suite`; the default choice for "does the CLI'
            ' still work end-to-end". Requires Multipass.'
        ),
    ),
    ScenarioDefinition(
        name="cli-host",
        description="Host CLI driving a VM-backed platform install.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        uses_host_cli=True,
        details=(
            "Compatibility route where the CLI binary stays on the host machine and"
            " targets a VM-backed platform install — catches host/cluster drift"
            " (paths, kubeconfig, registry addressing) that in-VM runs can't see."
            " Requires Multipass."
        ),
    ),
    ScenarioDefinition(
        name="validate-deploy-host",
        description="Host-only deploy workflow against a stub control-plane (host compatibility path).",
        requires_vm=False,
        supported_runtimes=("java",),
        uses_host_cli=True,
        aliases=("deploy-host",),
        details=(
            "Host-only workflow: builds on the host, pushes through a local"
            " registry, and validates the deploy-host compatibility path against a"
            " stub control-plane — no VM, no k3s. Catches regressions in"
            " host-side build/registration behavior cheaply. Requires Docker for the"
            " local registry. Minutes."
        ),
    ),
    ScenarioDefinition(
        name="loadtest-helm-legacy",
        description="Legacy Helm install + k6 loadtest + autoscaling sequence via the experiments/ scripts.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("helm-stack",),
        details=(
            "The historical Helm path kept for parity: installs the stack, then"
            " drives the legacy `experiments/` scripts for k6 load and autoscaling"
            " checks (`loadtest.run`, `experiments.autoscaling`) on a Multipass VM."
            " Superseded by `loadtest-one-vm` for new work; run this when comparing"
            " against historical results or validating the legacy script path"
            " itself. Requires Multipass. ~25–35 min from scratch."
        ),
    ),
    ScenarioDefinition(
        name="loadtest-one-vm",
        description="Helm stack, k6, and autoscaling verification on a single Multipass VM.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("one-vm-helm-loadtest",),
        details=(
            "Single Multipass VM hosts everything: Helm stack, k6, and the"
            " autoscaling probe. After the standard k6 run + Prometheus snapshot +"
            " HTML report, a tail re-registers the target function with an INTERNAL"
            " scaling config (min 0 / max 5), runs a dedicated k6 ramp while a"
            " background watcher samples deployment replicas, and verifies scale-up"
            " >1 *during* load and scale-down to 0 after. The cheapest way to"
            " exercise the full loadtest + autoscaling path — no second VM, no"
            " cloud credentials. Artifacts under `tools/controlplane/runs/` (k6 +"
            " autoscaling summaries, snapshot, report). Requires Multipass. ~20 min."
        ),
    ),
    ScenarioDefinition(
        name="loadtest-two-vm",
        description="Helm stack on one Multipass VM, dedicated k6 generator on a second; Prometheus snapshots + HTML report.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("two-vm-loadtest",),
        details=(
            "Helm stack on one Multipass VM; a second, dedicated VM generates k6"
            " load so the stack VM's resources are not polluted by the generator."
            " Invokes the selected function through the control-plane NodePort,"
            " captures Prometheus snapshots, and writes `k6-summary.json`,"
            " `metrics/prometheus-snapshot.json` and `report.html` under"
            " `tools/controlplane/runs/`. Default function selection is the lean"
            " `demo-java` pair; pass `--function-preset demo-loadtest` for the full"
            " 8-image matrix. Requires Multipass. ~25–35 min from scratch."
        ),
    ),
    ScenarioDefinition(
        name="loadtest-azure",
        description="Two-VM loadtest on Azure (OpenTofu, profiles/azure.toml): stack VM with open NodePorts + k6 loadgen VM.",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("azure-vm-loadtest",),
        details=(
            "Provisions two Azure VMs via OpenTofu (stack: k3s + Helm stack,"
            " NodePorts 30080/30081/30090 opened in the NSG; loadgen: k6). Reads"
            " defaults from `profiles/azure.toml` (local file — copy from"
            " `azure.toml.example`). Registers the selected functions (default:"
            " demo-java), runs k6 against the control-plane public endpoint,"
            " captures Prometheus snapshots, writes the usual artifacts under"
            " `tools/controlplane/runs/`. Requires `az login` with access to the"
            " configured resource group, and tofu. VM sizes/cost: see the profile"
            " (default D4s_v5 + B1s). ~25–35 min; VMs destroyed at the end unless"
            " cleanup is declined."
        ),
    ),
    ScenarioDefinition(
        name="loadtest-proxmox",
        description="Two-VM loadtest on Proxmox VE (cloned templates, NAT-published ports, profiles/proxmox.toml).",
        requires_vm=True,
        supported_runtimes=("java", "rust"),
        grouped_phases=True,
        aliases=("proxmox-vm-loadtest",),
        details=(
            "Two-VM loadtest on a Proxmox VE cluster: clones a VM template for the"
            " stack node and the k6 loadgen node, publishes the control-plane and"
            " Prometheus NodePorts through NAT rules, runs the same Helm/k6 workflow"
            " as `loadtest-two-vm`, and tears the clones down on completion. Reads"
            " connection and template settings from `profiles/proxmox.toml` (local"
            " file — copy from `proxmox.toml.example`). Requires reachable"
            " Proxmox VE credentials and a prepared template. ~25–35 min."
        ),
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
