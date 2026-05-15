from __future__ import annotations

from typing import Literal, TypeGuard

from pydantic import BaseModel, Field

ControlPlaneImplementation = Literal["rust", "java"]
BuildMode = Literal["native", "jvm", "rust"]
LoadProfile = Literal["quick", "smoke", "stress"]
MetricsGateMode = Literal["enforce", "warn", "off"]
BuildAction = Literal["jar", "building", "run", "image", "native", "test", "inspect"]
ProfileName = Literal["core", "k8s", "container-local", "all"]
VmLifecycle = Literal["multipass", "external", "azure"]
RuntimeKind = Literal["java", "rust"]
FunctionRuntimeKind = Literal["java", "java-lite", "go", "python", "exec", "javascript", "fixture"]
CliTestScenarioName = Literal["unit", "cli-stack", "host-platform", "deploy-host"]
ScenarioName = Literal[
    "docker",
    "buildpack",
    "container-local",
    "k3s-junit-curl",
    "cli",
    "cli-stack",
    "cli-host",
    "deploy-host",
    "helm-stack",
    "two-vm-loadtest",
    "azure-vm-loadtest",
]

VM_BACKED_SCENARIOS = frozenset(
    {
        "k3s-junit-curl",
        "cli",
        "cli-stack",
        "cli-host",
        "helm-stack",
        "two-vm-loadtest",
        "azure-vm-loadtest",
    }
)

BUILD_ACTION_VALUES: tuple[BuildAction, ...] = (
    "jar",
    "building",
    "run",
    "image",
    "native",
    "test",
    "inspect",
)
PROFILE_NAME_VALUES: tuple[ProfileName, ...] = ("core", "k8s", "container-local", "all")


def is_build_action(value: str) -> TypeGuard[BuildAction]:
    return value in BUILD_ACTION_VALUES


def is_profile_name(value: str) -> TypeGuard[ProfileName]:
    return value in PROFILE_NAME_VALUES


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


class ScenarioSelectionConfig(BaseModel):
    base_scenario: ScenarioName | None = None
    function_preset: str | None = None
    functions: list[str] = Field(default_factory=list)
    scenario_file: str | None = None
    namespace: str | None = None
    local_registry: str | None = None


class LoadtestConfig(BaseModel):
    default_load_profile: LoadProfile = "quick"
    metrics_gate_mode: MetricsGateMode = "enforce"
    scenario_file: str | None = None
    function_preset: str | None = None


class CliTestConfig(BaseModel):
    default_scenario: CliTestScenarioName | None = None


class Profile(BaseModel):
    name: str
    control_plane: ControlPlaneConfig
    modules: list[str] = Field(default_factory=list)
    tests: TestsConfig = Field(default_factory=TestsConfig)
    metrics: MetricsConfig = Field(default_factory=MetricsConfig)
    report: ReportConfig = Field(default_factory=ReportConfig)
    scenario: ScenarioSelectionConfig = Field(default_factory=ScenarioSelectionConfig)
    loadtest: LoadtestConfig = Field(default_factory=LoadtestConfig)
    cli_test: CliTestConfig = Field(default_factory=CliTestConfig)


class AzureConfig(BaseModel):
    resource_group: str
    location: str
    vm_size: str = "Standard_B2s"
    loadgen_vm_size: str = "Standard_B1s"
    image_urn: str | None = None
    ssh_key_path: str | None = None
    vm_name: str = "nanofaas-azure"
    loadgen_name: str = "nanofaas-azure-loadgen"
