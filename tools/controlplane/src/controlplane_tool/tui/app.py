"""
app.py — Interactive Rich TUI for the controlplane tool.

Launched automatically when `controlplane-tool` is invoked with no arguments.
All CLI commands remain available for scripted / CI use.
"""
from __future__ import annotations

from pathlib import Path
import traceback
from typing import Any

import questionary
from rich.markup import escape
from rich.panel import Panel
from rich.table import Table

from tui_toolkit import header
from workflow_tasks import fail, phase, step, success, warning
from tui_toolkit.console import console
from tui_toolkit.pickers import (
    Choice as _DescribedChoice,
    _BACK_VALUE,
    _ask,
    select as _select_described_value,
    select as _select_value,
)
from tui_toolkit.theme import DEFAULT_THEME, to_questionary_style

from controlplane_tool.orchestation.infra_flows import build_vm_flow
from controlplane_tool.cli.loadtest_commands import build_loadtest_request
from controlplane_tool.core.models import (
    BuildAction,
    ProfileName,
    is_build_action,
    is_profile_name,
)
from controlplane_tool.loadtest.loadtest_flows import build_loadtest_flow
from controlplane_tool.workspace.paths import default_tool_paths
from controlplane_tool.infra.runtimes.registry_runtime import default_registry_url, ensure_local_registry
from controlplane_tool.workspace.profiles import list_profiles, load_profile
from controlplane_tool.scenario.scenario_flows import build_scenario_flow
from controlplane_tool.tui.event_applier import TuiEventApplier
from controlplane_tool.tui.selection import (
    TuiSelectionResult,
    TuiSelectionTarget,
    function_choices,
    preset_choices,
    saved_profile_choices,
    scenario_file_choices,
    selection_source_choices,
)
from controlplane_tool.tui.workflow import TuiWorkflowSink, WorkflowDashboard
from controlplane_tool.tui.workflow_controller import TuiWorkflowController

_STYLE = to_questionary_style(DEFAULT_THEME)

# ── Main application ─────────────────────────────────────────────────────────


def _choice(title: str, value: str, description: str) -> questionary.Choice:
    return questionary.Choice(title, value, description=description)


def _value_choice(value: str, description: str, *, title: str | None = None) -> questionary.Choice:
    return _choice(title or value, value, description)


def _saved_profile_description(name: str) -> str:
    try:
        profile = load_profile(name)
    except Exception:  # noqa: BLE001
        return (
            f"Reuse the saved profile '{name}' for building, validation, and load-testing workflows "
            "without re-entering its defaults manually. Load tests use a local control-plane, "
            "a mock Kubernetes API, and LOCAL fixture functions."
        )

    control_plane = getattr(profile, "control_plane", None)
    scenario = getattr(profile, "scenario", None)
    cli_test = getattr(profile, "cli_test", None)
    loadtest = getattr(profile, "loadtest", None)
    tests = getattr(profile, "tests", None)
    implementation = getattr(control_plane, "implementation", "—") or "—"
    build_mode = getattr(control_plane, "build_mode", "—") or "—"
    base_scenario = getattr(scenario, "base_scenario", "—") or "—"
    cli_default = getattr(cli_test, "default_scenario", "—") or "—"
    load_profile_name = (
        getattr(loadtest, "default_load_profile", None)
        or getattr(tests, "load_profile", None)
        or "—"
    )
    return (
        f"Reuse the saved profile '{name}'. Current defaults: implementation={implementation}, "
        f"building={build_mode}, scenario={base_scenario}, cli={cli_default}, load={load_profile_name}. "
        "Load tests use a mock Kubernetes API and LOCAL fixture functions, not Kubernetes pods "
        "for the requested target images."
    )


def _saved_profile_choices(names: list[str]) -> list[questionary.Choice]:
    return [_value_choice(name, _saved_profile_description(name)) for name in names]


K3S_SELECTION_TARGET = TuiSelectionTarget(
    key="k3s-junit-curl",
    label="k3s-junit-curl",
    resolver_scenario="k3s-junit-curl",
    selection_mode="multi",
    allow_default=True,
    allow_presets=True,
    allow_single_functions=False,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=frozenset({"k3s-junit-curl"}),
)

CLI_STACK_SELECTION_TARGET = TuiSelectionTarget(
    key="cli-stack",
    label="cli-stack",
    resolver_scenario="cli-stack",
    selection_mode="multi",
    allow_default=True,
    allow_presets=True,
    allow_single_functions=False,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=None,
)

DEPLOY_HOST_SELECTION_TARGET = TuiSelectionTarget(
    key="deploy-host",
    label="deploy-host",
    resolver_scenario="deploy-host",
    selection_mode="multi",
    allow_default=True,
    allow_presets=True,
    allow_single_functions=False,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=None,
)

CONTAINER_LOCAL_SELECTION_TARGET = TuiSelectionTarget(
    key="container-local",
    label="container-local",
    resolver_scenario="container-local",
    selection_mode="single",
    allow_default=True,
    allow_presets=False,
    allow_single_functions=True,
    allow_scenario_files=True,
    allow_saved_profiles=True,
    strict_base_scenarios=None,
)


def _prompt_function_selection(target: TuiSelectionTarget) -> TuiSelectionResult:
    while True:
        source = _ask(
            lambda: _select_described_value(
                "Selection source:",
                choices=selection_source_choices(target),
            )
        )
        if source == "default":
            return TuiSelectionResult(source="default")
        if source == "preset":
            choices = preset_choices(target)
            if not choices:
                warning(f"No compatible function presets found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="preset",
                function_preset=_ask(
                    lambda: _select_described_value(
                        "Function preset:",
                        choices=choices,
                    )
                ),
            )
        if source == "function":
            choices = function_choices(target)
            if not choices:
                warning(f"No compatible functions found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="function",
                functions_csv=_ask(
                    lambda: _select_described_value(
                        "Function:",
                        choices=choices,
                    )
                ),
            )
        if source == "scenario-file":
            choices = scenario_file_choices(target)
            if not choices:
                warning(f"No compatible scenario files found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="scenario-file",
                scenario_file=Path(
                    _ask(
                        lambda: _select_described_value(
                            "Scenario file:",
                            choices=choices,
                        )
                    )
                ),
            )
        if source == "saved-profile":
            choices = saved_profile_choices(target)
            if not choices:
                warning(f"No compatible saved profiles found for {target.label}.")
                continue
            return TuiSelectionResult(
                source="saved-profile",
                saved_profile=_ask(
                    lambda: _select_described_value(
                        "Saved profile:",
                        choices=choices,
                    )
                ),
            )
        raise ValueError(f"Unsupported selection source: {source}")


def _resolve_tui_e2e_request(
    *,
    scenario: str,
    selection: TuiSelectionResult,
    runtime: str,
    lifecycle: str,
    name: str | None,
    host: str | None,
    user: str,
    home: str | None,
    cpus: int,
    memory: str,
    disk: str,
    cleanup_vm: bool,
    namespace: str | None,
    local_registry: str | None,
):
    from controlplane_tool.cli.e2e_commands import _resolve_run_request

    return _resolve_run_request(
        scenario=scenario,
        runtime=runtime,
        lifecycle=lifecycle,
        name=name,
        host=host,
        user=user,
        home=home,
        cpus=cpus,
        memory=memory,
        disk=disk,
        cleanup_vm=cleanup_vm,
        namespace=namespace,
        local_registry=local_registry,
        **selection.as_resolver_kwargs(),
    )


def _function_detail_choices() -> list[questionary.Choice]:
    from controlplane_tool.functions.catalog import list_functions

    choices: list[questionary.Choice] = []
    for fn in list_functions():
        image = fn.default_image or "no default image configured"
        payload = fn.default_payload_file or "no default payload file configured"
        description = (
            f"{fn.description} Runtime={fn.runtime}; family={fn.family}; "
            f"default image={image}; default payload={payload}."
        )
        choices.append(_value_choice(fn.key, description))
    return choices


def _workspace_relative_path(path: Path | None) -> str:
    if path is None:
        return "—"
    workspace_root = default_tool_paths().workspace_root.resolve()
    try:
        return path.resolve().relative_to(workspace_root).as_posix()
    except ValueError:
        return str(path)


def _acknowledge_static_view(message: str = "Press any key to return to the previous menu.") -> None:
    _ask(
        lambda: questionary.press_any_key_to_continue(
            message,
            style=_STYLE,
        ).ask()
    )


_MAIN_MENU_CHOICES = [
    _choice(
        "Build",
        "building",
        "Compile, package, inspect, and run the selected control-plane profile before moving into deployment-oriented workflows.",
    ),
    _choice(
        "Environment",
        "environment",
        "Prepare shared infrastructure such as managed VMs and the local registry that the rest of the validation flows depend on.",
    ),
    _choice(
        "Validation",
        "validation",
        "Run platform, CLI, and host-compatibility verification flows that prove the stack behaves correctly end to end.",
    ),
    _choice(
        "Load Testing",
        "loadtest",
        "Run k6 against a local control-plane using a mock Kubernetes API and LOCAL fixture functions; this validates dispatch and metrics, not real target pods.",
    ),
    _choice(
        "Catalog",
        "catalog",
        "Browse function definitions, presets, and per-function metadata used by scenarios, demos, and load tests.",
    ),
    _choice(
        "Profiles",
        "profiles",
        "Create, inspect, and remove saved profiles that capture defaults across building, validation, and load-testing workflows.",
    ),
    _choice(
        "Exit",
        "exit",
        "Leave the interactive tool without starting another workflow.",
    ),
]

_BUILD_ACTION_CHOICES = [
    _choice(
        "jar — assemble JARs",
        "jar",
        "Assemble bootable JAR artifacts for the selected module set without running the broader verification suite.",
    ),
    _choice(
        "building — compile + unit tests",
        "building",
        "Compile the selected profile and run the standard Gradle building lifecycle, including unit tests.",
    ),
    _choice(
        "test — unit tests only",
        "test",
        "Execute the test lifecycle only, without packaging artifacts or starting long-running services.",
    ),
    _choice(
        "run — start control-plane",
        "run",
        "Start the control-plane locally for manual verification and fast development feedback.",
    ),
    _choice(
        "image — building OCI image",
        "image",
        "Build OCI images for the selected profile, ready for registry push or local runtime testing.",
    ),
    _choice(
        "native — building GraalVM native",
        "native",
        "Produce GraalVM native binaries when the native-image toolchain is available for the selected profile.",
    ),
    _choice(
        "inspect — show configuration",
        "inspect",
        "Show the resolved building configuration and module selection before running an expensive building step.",
    ),
]

_BUILD_PROFILE_CHOICES = [
    _value_choice(
        "core",
        "Use the core control-plane module set for the default day-to-day development loop.",
    ),
    _value_choice(
        "k8s",
        "Include Kubernetes-facing modules and integrations needed for cluster-oriented validation paths.",
    ),
    _value_choice(
        "all",
        "Build every available control-plane module and optional extension exposed by this repository.",
    ),
    _value_choice(
        "container-local",
        "Focus on the local container execution path and the modules required for host-managed workflows.",
    ),
]

def _select_build_action() -> BuildAction | None:
    value = _select_value(
        "Action:",
        choices=_BUILD_ACTION_CHOICES,
        include_back=True,
    )
    if value == _BACK_VALUE:
        return None
    if is_build_action(value):
        return value
    raise ValueError(f"Unsupported build action selected: {value}")


def _select_build_profile() -> ProfileName | None:
    value = _select_value(
        "Profile:",
        choices=_BUILD_PROFILE_CHOICES,
        default="core",
        include_back=True,
    )
    if value == _BACK_VALUE:
        return None
    if is_profile_name(value):
        return value
    raise ValueError(f"Unsupported build profile selected: {value}")

_ENVIRONMENT_ACTION_CHOICES = [
    _choice(
        "vm — lifecycle, provisioning, and inspection",
        "vm",
        "Manage VM lifecycle, provisioning, synchronization, and inspection for the canonical managed environments.",
    ),
    _choice(
        "registry — local registry bootstrap",
        "registry",
        "Start or verify the local image registry used by VM-backed and local validation workflows.",
    ),
]

_VALIDATION_ACTION_CHOICES = [
    _choice(
        "platform — platform end-to-end scenarios",
        "platform",
        "Run platform-level end-to-end scenarios that building, deploy, and verify the stack from the control-plane outward.",
    ),
    _choice(
        "cli — CLI validation workflows",
        "cli",
        "Validate nanofaas-cli workflows against a managed environment and catch CLI-specific regressions early.",
    ),
    _choice(
        "host — deploy-host compatibility path",
        "host",
        "Exercise the deploy-host compatibility route with host-side building and registration behavior.",
    ),
]

_VM_ACTION_CHOICES = [
    _choice(
        "up — start / provision",
        "up",
        "Create or resume the target VM environment and make it ready for the next workflow.",
    ),
    _choice(
        "down — stop and delete",
        "down",
        "Stop the managed VM and remove it cleanly when the environment is no longer needed.",
    ),
    _choice(
        "sync — sync project",
        "sync",
        "Copy the current workspace content into the target VM before a building or validation run.",
    ),
    _choice(
        "provision-base — install base dependencies",
        "provision-base",
        "Install the shared VM prerequisites required before cluster setup or image building work begins.",
    ),
    _choice(
        "provision-k3s — install k3s",
        "provision-k3s",
        "Install and configure the k3s runtime layer inside the managed VM environment.",
    ),
    _choice(
        "inspect — show status",
        "inspect",
        "Inspect the current VM state, connectivity, and resolved runtime settings without changing anything.",
    ),
]

_VM_LIFECYCLE_CHOICES = [
    _value_choice(
        "multipass",
        "Use the managed Multipass lifecycle handled directly by the tool for provisioning and teardown.",
    ),
    _value_choice(
        "external",
        "Target an existing remote host or VM that you manage outside the tool, using SSH only.",
    ),
    _value_choice(
        "azure",
        "Provision and manage VMs on Azure via OpenTofu. Requires profiles/azure.toml.",
    ),
]

_REGISTRY_ACTION_CHOICES = [
    _choice(
        "start — start Docker Desktop if present and run registry",
        "start",
        "Ensure Docker Desktop is available when needed and launch the local registry used by building and validation flows.",
    ),
]

_PLATFORM_VALIDATION_CHOICES = [
    _DescribedChoice(
        "k3s-junit-curl — self-bootstrapping VM stack with curl + JUnit verification",
        "k3s-junit-curl",
        "Provision a managed VM, install k3s, deploy the stack, and verify the result with curl probes plus JUnit checks.",
    ),
    _DescribedChoice(
        "helm-stack — self-bootstrapping VM stack for Helm compatibility",
        "helm-stack",
        "Bootstrap the full VM-backed Helm stack and validate the deployment path that load testing and demos rely on.",
    ),
    _DescribedChoice(
        "two-vm-loadtest — Helm stack with dedicated k6 load generator VM",
        "two-vm-loadtest",
        "Bootstrap the Helm stack on one VM and run k6 from a second managed load generator VM.",
    ),
    _DescribedChoice(
        "azure-vm-loadtest — Two-VM Azure load test with k6",
        "azure-vm-loadtest",
        "Provision two Azure VMs (stack + loadgen) via OpenTofu, run k6 load test, capture "
        "Prometheus snapshots. Reads defaults from profiles/azure.toml.",
    ),
    _DescribedChoice(
        "container-local — local managed DEPLOYMENT",
        "container-local",
        "Run the managed DEPLOYMENT workflow entirely on the local machine without provisioning a VM first.",
    ),
]

_PLATFORM_HOST_COMPAT_CHOICE = _DescribedChoice(
    "deploy-host — deploy-host with local registry",
    "deploy-host",
    "Build on the host, push through a local registry, and validate the host compatibility deployment path.",
)

_PLATFORM_LOCAL_RUNTIME_CHOICES = [
    _DescribedChoice(
        "docker — local POOL with Docker",
        "docker",
        "Exercise the local POOL runtime with Docker-backed execution on the host machine.",
    ),
    _DescribedChoice(
        "buildpack — local POOL with buildpack",
        "buildpack",
        "Exercise the local POOL runtime using buildpack-produced images on the host machine.",
    ),
]

_CLI_E2E_RUNNER_CHOICES = [
    _DescribedChoice(
        "cli-stack — canonical self-bootstrapping CLI stack in VM",
        "cli-stack",
        "Run the canonical VM-backed CLI stack that bootstraps the platform and validates the CLI end to end.",
    ),
    _DescribedChoice(
        "host-platform — compatibility path, CLI on host vs cluster",
        "host-platform",
        "Keep the CLI on the host and validate the compatibility route against a VM-backed platform.",
    ),
]

_LOADTEST_ACTION_CHOICES = [
    _choice(
        "run — run load test with profile",
        "run",
        "Execute the load-test workflow with a local control-plane, mock Kubernetes API, and LOCAL fixture functions; requested target images are not Kubernetes pods.",
    ),
    _choice(
        "plan — show plan without executing",
        "plan",
        "Show that the run would use a local control-plane, mock Kubernetes API, LOCAL fixture functions, sequential k6 traffic, and not Kubernetes pods.",
    ),
    _choice(
        "new profile — interactive wizard",
        "new_profile",
        "Create or revise a saved profile interactively before selecting it for load testing.",
    ),
]

_CATALOG_VIEW_CHOICES = [
    _choice(
        "All functions",
        "all",
        "Browse every function definition shipped with the repository, including runtime and family metadata.",
    ),
    _choice(
        "Presets",
        "presets",
        "Inspect named function bundles that are reused by scenarios, demos, and load-testing flows.",
    ),
    _choice(
        "Function detail",
        "show",
        "Open the detailed record for a single function, including image and payload defaults.",
    ),
]

_PROFILE_ACTION_CHOICES = [
    _choice(
        "Create new profile",
        "new",
        "Launch the interactive wizard and save a new reusable profile for building, validation, and load testing.",
    ),
    _choice(
        "View existing profile",
        "show",
        "Inspect the resolved defaults stored in a saved profile before reusing it in other workflows.",
    ),
    _choice(
        "Delete profile",
        "delete",
        "Remove a saved profile when it is obsolete, misleading, or no longer part of the supported workflow set.",
    ),
]


class NanofaasTUI:
    """Menu-driven Rich TUI for all controlplane operations."""

    _MAIN_MENU = _MAIN_MENU_CHOICES

    def __init__(self) -> None:
        self._applier = TuiEventApplier()
        self._controller = TuiWorkflowController(event_applier=self._applier)

    def run(self) -> None:
        header()
        try:
            while True:
                choice = _select_value(
                    "What would you like to do?",
                    choices=self._MAIN_MENU,
                )
                if choice == "exit":
                    break
                try:
                    {
                        "building": self._build_menu,
                        "environment": self._environment_menu,
                        "validation": self._validation_menu,
                        "loadtest": self._loadtest_menu,
                        "catalog": self._catalog_menu,
                        "profiles": self._profiles_menu,
                        "vm": self._vm_menu,
                        "registry": self._registry_menu,
                        "e2e": self._e2e_menu,
                        "cli_e2e": self._cli_e2e_menu,
                        "functions": self._functions_menu,
                        "profile": self._profile_menu,
                    }[choice]()
                except KeyboardInterrupt:
                    console.print("\n  [dim]← back[/]")
                except Exception as exc:  # noqa: BLE001
                    fail(str(exc), detail=traceback.format_exc(limit=8))
        except KeyboardInterrupt:
            pass
        console.print("\n[dim]Bye.[/]\n")

    # ── BUILD ─────────────────────────────────────────────────────────────────

    def _build_menu(self) -> None:
        from controlplane_tool.cli.commands import GradleCommandExecutor

        while True:
            phase("Build & Test")

            action = _select_build_action()
            if action is None:
                return

            profile = _select_build_profile()
            if profile is None:
                continue

            dry_run = _ask(
                lambda: questionary.confirm(
                    "Dry-run? (show command only)", default=False, style=_STYLE
                ).ask()
            )
            executor = GradleCommandExecutor()
            if dry_run:
                result = executor.execute(
                    action=action,
                    profile=profile,
                    modules=None,
                    extra_gradle_args=[],
                    dry_run=True,
                )
                console.print(Panel(" ".join(result.command), title="Command", border_style="dim"))
                _acknowledge_static_view()
                continue

            def _run_build_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step(f"Running {action}", f"profile={profile}")
                result = executor.execute(
                    action=action,
                    profile=profile,
                    modules=None,
                    extra_gradle_args=[],
                    dry_run=False,
                )
                self._controller.append_command_result_logs(dashboard, result)
                if result.return_code == 0:
                    success(f"{action} completed")
                    return result
                detail = (
                    getattr(result, "stderr", "")
                    or getattr(result, "stdout", "")
                    or f"exit code {result.return_code}"
                )
                detail = str(detail).strip()
                fail(f"{action} failed", detail=detail)
                raise RuntimeError(f"{action} failed: {detail}")

            self._controller.run_live_workflow(
                title="Build & Test",
                summary_lines=[
                    f"Action: {action}",
                    f"Profile: {profile}",
                ],
                planned_steps=[f"{action}"],
                action=_run_build_workflow,
            )
            return

    # ── ENVIRONMENT ──────────────────────────────────────────────────────────

    def _environment_menu(self) -> None:
        while True:
            phase("Environment")

            action = _select_value(
                "Action:",
                choices=_ENVIRONMENT_ACTION_CHOICES,
                include_back=True,
            )
            if action == _BACK_VALUE:
                return

            {
                "vm": self._vm_menu,
                "registry": self._registry_menu,
            }[action]()

    # ── VALIDATION ───────────────────────────────────────────────────────────

    def _validation_menu(self) -> None:
        while True:
            phase("Validation")

            action = _select_value(
                "Action:",
                choices=_VALIDATION_ACTION_CHOICES,
                include_back=True,
            )
            if action == _BACK_VALUE:
                return

            if action == "platform":
                self._platform_validation_menu()
                continue
            if action == "cli":
                self._cli_e2e_menu()
                continue
            self._run_deploy_host()

    # ── VM ────────────────────────────────────────────────────────────────────

    def _vm_menu(self) -> None:
        from controlplane_tool.infra.vm.vm_models import VmRequest

        while True:
            phase("VM Management")

            action = _select_value(
                "Action:",
                choices=_VM_ACTION_CHOICES,
                include_back=True,
            )
            if action == _BACK_VALUE:
                return

            lifecycle = _select_value(
                "Lifecycle:",
                choices=_VM_LIFECYCLE_CHOICES,
                default="multipass",
                include_back=True,
            )
            if lifecycle == _BACK_VALUE:
                continue

            if lifecycle == "multipass":
                name = _ask(
                    lambda: questionary.text(
                        "VM Name:", default="nanofaas-e2e", style=_STYLE
                    ).ask()
                )
                host = None
            else:
                name = None
                host = _ask(
                    lambda: questionary.text(
                        "Remote host (IP/hostname):", style=_STYLE
                    ).ask()
                )

            user = _ask(
                lambda: questionary.text("SSH User:", default="ubuntu", style=_STYLE).ask()
            )

            dry_run = _ask(
                lambda: questionary.confirm(
                    "Dry-run?", default=False, style=_STYLE
                ).ask()
            )

            request = VmRequest(lifecycle=lifecycle, name=name, host=host, user=user)
            build_kwargs: dict[str, Any] = {
                "request": request,
                "repo_root": default_tool_paths().workspace_root,
                "dry_run": dry_run,
            }

            if action == "provision-base":
                install_helm = _ask(
                    lambda: questionary.confirm(
                        "Install Helm?", default=False, style=_STYLE
                    ).ask()
                )
                build_kwargs["install_helm"] = install_helm

            flow_id = {
                "up": "vm.up",
                "down": "vm.down",
                "sync": "vm.sync",
                "provision-base": "vm.provision_base",
                "provision-k3s": "vm.provision_k3s",
                "inspect": "vm.inspect",
            }[action]
            flow = build_vm_flow(flow_id, **build_kwargs)

            if dry_run:
                result = self._controller.run_shared_flow(flow, allow_none_result=False)
                console.print(Panel(" ".join(result.command), title="Command", border_style="dim"))
                _acknowledge_static_view()
                continue

            action_label = {
                "up": "Ensure VM is running",
                "down": "Teardown VM",
                "sync": "Sync project to VM",
                "provision-base": "Provision base dependencies",
                "provision-k3s": "Install k3s",
                "inspect": "Inspect VM",
            }[action]

            def _run_vm_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step(f"Running vm {action}", f"lifecycle={lifecycle}")
                live_result = self._controller.run_shared_flow(
                    flow,
                    allow_none_result=False,
                    on_result=lambda result: self._controller.append_command_result_logs(dashboard, result),
                )
                success(f"vm {action} completed")
                return live_result

            self._controller.run_live_workflow(
                title="VM Management",
                summary_lines=[
                    f"Action: {action}",
                    f"Lifecycle: {lifecycle}",
                    f"VM: {name or host or 'default'}",
                    f"User: {user}",
                ],
                planned_steps=[action_label],
                action=_run_vm_workflow,
            )
            return

    # ── REGISTRY ─────────────────────────────────────────────────────────────

    def _registry_menu(self) -> None:
        phase("Registry")

        action = _select_value(
            "Action:",
            choices=_REGISTRY_ACTION_CHOICES,
            default="start",
            include_back=True,
        )
        if action == _BACK_VALUE:
            return

        registry = default_registry_url()

        def _run_registry_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            step("Starting local registry", registry)
            result = ensure_local_registry(registry=registry)
            self._controller.append_command_result_logs(dashboard, result)
            return_code = getattr(result, "return_code", 0)
            if return_code != 0:
                detail = (
                    getattr(result, "stderr", "")
                    or getattr(result, "stdout", "")
                    or f"exit code {return_code}"
                )
                fail("Registry start failed", detail=detail)
                raise RuntimeError(detail)
            success("Registry ready", detail=registry)
            return result

        self._controller.run_live_workflow(
            title="Registry",
            summary_lines=[f"Registry URL: {registry}"],
            planned_steps=[
                "Start Docker Desktop (if present)",
                "Start local registry",
            ],
            action=_run_registry_workflow,
        )

    # ── E2E ───────────────────────────────────────────────────────────────────

    def _e2e_menu(self) -> None:
        self._platform_validation_menu(
            phase_label="E2E Scenarios",
            include_host_compat=True,
        )

    def _platform_validation_menu(
        self,
        *,
        phase_label: str = "Platform Validation",
        include_host_compat: bool = False,
    ) -> None:
        while True:
            phase(phase_label)

            choices = list(_PLATFORM_VALIDATION_CHOICES)
            if include_host_compat:
                choices.append(_PLATFORM_HOST_COMPAT_CHOICE)
            choices.extend(_PLATFORM_LOCAL_RUNTIME_CHOICES)

            scenario_choice = _ask(
                lambda: _select_described_value(
                    "Scenario:",
                    choices=choices,
                    include_back=True,
                )
            )
            if scenario_choice == _BACK_VALUE:
                return

            if scenario_choice in ("k3s-junit-curl", "helm-stack", "two-vm-loadtest", "azure-vm-loadtest"):
                self._run_vm_e2e_scenario(scenario_choice)
            elif scenario_choice == "container-local":
                self._run_container_local()
            elif scenario_choice == "deploy-host":
                self._run_deploy_host()
            else:
                self._run_e2e_scenario(scenario_choice)
            continue

    def _run_vm_e2e_scenario(self, scenario: str) -> None:
        repo_root = default_tool_paths().workspace_root

        if scenario in {"helm-stack", "two-vm-loadtest"}:
            from controlplane_tool.cli.e2e_commands import _resolve_run_request
            from controlplane_tool.e2e.e2e_runner import E2eRunner

            request = _resolve_run_request(
                scenario=scenario,
                runtime="java",
                lifecycle="multipass",
                name="nanofaas-e2e",
                host=None,
                user="ubuntu",
                home=None,
                cpus=4,
                memory="8G" if scenario == "two-vm-loadtest" else "12G",
                disk="30G",
                cleanup_vm=False,
                namespace=None,
                local_registry=None,
                function_preset=None,
                functions_csv=None,
                scenario_file=None,
                saved_profile=None,
            )
            plan = E2eRunner(repo_root=repo_root).plan(request)

            def _run_helm_stack_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_step_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                dashboard.append_log(f"Starting {scenario} workflow")
                sink._update()
                flow = build_scenario_flow(
                    scenario,
                    repo_root=repo_root,
                    request=request,
                    event_listener=_on_step_event,
                )
                self._controller.run_shared_flow(flow)
                dashboard.append_log(f"{scenario} E2E completed")
                sink._update()

            self._controller.run_live_workflow(
                title="E2E Scenarios",
                summary_lines=[
                    f"Scenario: {scenario}",
                    "Mode: self-bootstrapping VM-backed scenario",
                ],
                planned_steps=[step.summary for step in plan.steps],
                action=_run_helm_stack_workflow,
            )

        elif scenario == "azure-vm-loadtest":
            from pydantic import ValidationError
            from controlplane_tool.workspace.azure_config import (
                azure_config_path,
                load_azure_config,
            )
            from controlplane_tool.cli.e2e_commands import _resolve_run_request
            from controlplane_tool.e2e.e2e_runner import E2eRunner

            try:
                cfg = load_azure_config()
            except FileNotFoundError:
                warning(
                    f"Missing azure.toml — create {azure_config_path()} "
                    "with resource_group and location."
                )
                _acknowledge_static_view()
                return
            except ValidationError as exc:
                first_error = exc.errors()[0]["msg"] if exc.errors() else "validation failed"
                warning(f"Invalid azure.toml: {first_error}")
                _acknowledge_static_view()
                return

            console.print(
                Panel(
                    f"resource_group: {cfg.resource_group}\n"
                    f"location:       {cfg.location}\n"
                    f"vm_size:        {cfg.vm_size} (stack) / {cfg.loadgen_vm_size} (loadgen)\n"
                    f"vm_name:        {cfg.vm_name} / {cfg.loadgen_name}",
                    title="Azure defaults (profiles/azure.toml)",
                )
            )

            confirmed = _ask(
                lambda: questionary.confirm(
                    "Proceed with azure-vm-loadtest?", default=True, style=_STYLE
                ).ask()
            )
            if not confirmed:
                return

            request = _resolve_run_request(
                scenario="azure-vm-loadtest",
                runtime="java",
                lifecycle="azure",
                name=cfg.vm_name,
                host=None,
                user="azureuser",
                home=None,
                cpus=4,
                memory="12G",
                disk="30G",
                cleanup_vm=False,
                namespace=None,
                local_registry=None,
                function_preset=None,
                functions_csv=None,
                scenario_file=None,
                saved_profile=None,
                loadgen_name=cfg.loadgen_name,
                loadgen_cpus=2,
                loadgen_memory="2G",
                loadgen_disk="10G",
                azure_resource_group=cfg.resource_group,
                azure_location=cfg.location,
                azure_vm_size=cfg.vm_size,
                azure_image_urn=cfg.image_urn,
                azure_ssh_key_path=cfg.ssh_key_path,
            )
            plan = E2eRunner(repo_root=repo_root).plan(request)

            def _run_azure_loadtest_workflow(
                dashboard: WorkflowDashboard, sink: TuiWorkflowSink
            ) -> None:
                def _on_step_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                dashboard.append_log("Starting azure-vm-loadtest workflow")
                sink._update()
                flow = build_scenario_flow(
                    "azure-vm-loadtest",
                    repo_root=repo_root,
                    request=request,
                    event_listener=_on_step_event,
                )
                self._controller.run_shared_flow(flow)
                dashboard.append_log("azure-vm-loadtest E2E completed")
                sink._update()

            self._controller.run_live_workflow(
                title="E2E Scenarios",
                summary_lines=[
                    "Scenario: azure-vm-loadtest",
                    f"Resource group: {cfg.resource_group}",
                    f"Location: {cfg.location}",
                    f"Stack VM: {cfg.vm_name} ({cfg.vm_size})",
                    f"Loadgen VM: {cfg.loadgen_name} ({cfg.loadgen_vm_size})",
                ],
                planned_steps=[step.summary for step in plan.steps],
                action=_run_azure_loadtest_workflow,
            )

        else:  # k3s-junit-curl
            from controlplane_tool.e2e.e2e_runner import E2eRunner
            from controlplane_tool.cli.e2e_commands import _resolve_run_request

            vm_name = _ask(
                lambda: questionary.text(
                    "VM Name:", default="nanofaas-e2e", style=_STYLE
                ).ask()
            )
            runtime = _ask(
                lambda: questionary.select(
                    "Control-plane runtime:",
                    choices=["java", "rust"],
                    default="java",
                    style=_STYLE,
                ).ask()
            )
            cleanup_vm = _ask(
                lambda: questionary.confirm(
                    "Cleanup VM at end?",
                    default=True,
                    style=_STYLE,
                ).ask()
            )
            selection = _prompt_function_selection(K3S_SELECTION_TARGET)

            dry_run = _ask(
                lambda: questionary.confirm(
                    "Dry-run? (show plan without executing)", default=False, style=_STYLE
                ).ask()
            )

            request = _resolve_run_request(
                scenario="k3s-junit-curl",
                runtime=runtime,
                lifecycle="multipass",
                name=vm_name,
                host=None,
                user="ubuntu",
                home=None,
                cpus=4,
                memory="12G",
                disk="30G",
                cleanup_vm=cleanup_vm,
                namespace=None,
                local_registry=None,
                **selection.as_resolver_kwargs(),
            )
            runner = E2eRunner(repo_root=repo_root)
            if dry_run:
                plan = runner.plan(request)
                step("k3s-junit-curl E2E plan (dry-run)")
                _show_plan_table(plan)
                _acknowledge_static_view()
                return

            plan = runner.plan(request)
            summary_lines = [
                "Scenario: k3s-junit-curl",
                "Mode: self-bootstrapping VM-backed scenario",
                f"VM Name: {vm_name}",
                f"Control-plane runtime: {runtime}",
                f"Cleanup VM at end: {'yes' if cleanup_vm else 'no'}",
                f"Selection source: {selection.source}",
                *selection.summary_lines,
            ]

            def _run_k8s_vm_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_step_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                flow = build_scenario_flow(
                    "k3s-junit-curl",
                    repo_root=repo_root,
                    request=request,
                    event_listener=_on_step_event,
                )
                dashboard.append_log("Starting k3s-junit-curl workflow")
                sink._update()
                self._controller.run_shared_flow(flow)
                success("k3s-junit-curl E2E completed")
                return plan

            self._controller.run_live_workflow(
                title="E2E Scenarios",
                summary_lines=summary_lines,
                planned_steps=[step.summary for step in plan.steps],
                action=_run_k8s_vm_workflow,
            )

    def _run_container_local(self) -> None:
        selection = _prompt_function_selection(CONTAINER_LOCAL_SELECTION_TARGET)
        request = _resolve_tui_e2e_request(
            scenario="container-local",
            selection=selection,
            runtime="java",
            lifecycle="multipass",
            name=None,
            host=None,
            user="ubuntu",
            home=None,
            cpus=4,
            memory="12G",
            disk="30G",
            cleanup_vm=True,
            namespace=None,
            local_registry=None,
        )

        def _run_container_local_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            step("Running container-local E2E")
            flow = build_scenario_flow(
                "container-local",
                repo_root=default_tool_paths().workspace_root,
                request=request,
            )
            self._controller.run_shared_flow(flow)
            success("container-local E2E completed")

        self._controller.run_live_workflow(
            title="E2E Scenarios",
            summary_lines=[
                "Scenario: container-local",
                "Mode: local managed DEPLOYMENT path",
                *selection.summary_lines,
            ],
            planned_steps=["Build", "Deploy", "Verify"],
            action=_run_container_local_workflow,
        )

    def _run_deploy_host(self) -> None:
        selection = _prompt_function_selection(DEPLOY_HOST_SELECTION_TARGET)
        request = _resolve_tui_e2e_request(
            scenario="deploy-host",
            selection=selection,
            runtime="java",
            lifecycle="multipass",
            name=None,
            host=None,
            user="ubuntu",
            home=None,
            cpus=4,
            memory="12G",
            disk="30G",
            cleanup_vm=True,
            namespace=None,
            local_registry=None,
        )

        def _run_deploy_host_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            step("Running deploy-host E2E")
            flow = build_scenario_flow(
                "deploy-host",
                repo_root=default_tool_paths().workspace_root,
                request=request,
            )
            self._controller.run_shared_flow(flow)
            success("deploy-host E2E completed")

        self._controller.run_live_workflow(
            title="E2E Scenarios",
            summary_lines=[
                "Scenario: deploy-host",
                "Mode: host-side building/push/register compatibility path",
                *selection.summary_lines,
            ],
            planned_steps=["Build", "Deploy", "Verify"],
            action=_run_deploy_host_workflow,
        )

    def _run_e2e_scenario(self, scenario: str) -> None:
        from controlplane_tool.e2e.e2e_models import E2eRequest

        runtime = _ask(
            lambda: questionary.select(
                "Runtime:", choices=["java", "rust"], default="java", style=_STYLE
            ).ask()
        )

        request = E2eRequest(scenario=scenario, runtime=runtime)
        from controlplane_tool.e2e.e2e_runner import E2eRunner
        plan = E2eRunner(repo_root=default_tool_paths().workspace_root).plan(request)

        def _run_generic_e2e_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            def _on_step_event(event: Any) -> None:
                self._applier.apply_e2e_step_event(dashboard, event)
                sink._update()

            flow = build_scenario_flow(
                scenario,
                repo_root=default_tool_paths().workspace_root,
                request=request,
                event_listener=_on_step_event,
            )
            dashboard.append_log(f"Starting E2E scenario={scenario}")
            sink._update()
            self._controller.run_shared_flow(flow)
            success(f"{scenario} E2E completed")
            return plan

        self._controller.run_live_workflow(
            title="E2E Scenarios",
            summary_lines=[
                f"Scenario: {scenario}",
                f"Runtime: {runtime}",
            ],
            planned_steps=[step.summary for step in plan.steps],
            action=_run_generic_e2e_workflow,
        )

    # ── CLI E2E ───────────────────────────────────────────────────────────────

    def _cli_e2e_menu(self) -> None:
        phase("CLI E2E")

        runner_choice = _ask(
            lambda: _select_described_value(
                "Runner:",
                choices=_CLI_E2E_RUNNER_CHOICES,
                include_back=True,
            )
        )
        if runner_choice == _BACK_VALUE:
            return

        repo_root = default_tool_paths().workspace_root

        if runner_choice == "cli-stack":
            from controlplane_tool.e2e.e2e_runner import E2eRunner

            selection = _prompt_function_selection(CLI_STACK_SELECTION_TARGET)
            cli_stack_request = _resolve_tui_e2e_request(
                scenario="cli-stack",
                selection=selection,
                runtime="java",
                lifecycle="multipass",
                name="nanofaas-e2e",
                host=None,
                user="ubuntu",
                home=None,
                cpus=4,
                memory="12G",
                disk="30G",
                cleanup_vm=False,
                namespace=None,
                local_registry=None,
            )
            cli_stack_plan = E2eRunner(repo_root).plan(cli_stack_request)

            def _run_cli_stack_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_step_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                step("Running CLI stack E2E")
                flow = build_scenario_flow(
                    "cli-stack",
                    repo_root=repo_root,
                    request=cli_stack_request,
                    event_listener=_on_step_event,
                )
                self._controller.run_shared_flow(flow)
                success("CLI stack E2E completed")

            self._controller.run_live_workflow(
                title="CLI E2E",
                summary_lines=[
                    "Runner: cli-stack",
                    "Mode: canonical self-bootstrapping VM-backed CLI stack",
                    *selection.summary_lines,
                ],
                planned_steps=[s.summary for s in cli_stack_plan.steps],
                action=_run_cli_stack_workflow,
            )
            return

        if runner_choice == "host-platform":
            def _run_cli_host_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step("Running CLI Host Platform E2E")
                flow = build_scenario_flow(
                    "cli-host",
                    repo_root=repo_root,
                )
                self._controller.run_shared_flow(flow)
                success("CLI Host Platform E2E completed")

            self._controller.run_live_workflow(
                title="CLI E2E",
                summary_lines=[
                    "Runner: host-platform",
                    "Mode: compatibility path; platform-only on host vs cluster",
                ],
                planned_steps=["Build", "Deploy", "Verify"],
                action=_run_cli_host_workflow,
            )
            return

        raise ValueError(f"Unsupported CLI E2E runner: {runner_choice}")

    # ── LOAD TEST ─────────────────────────────────────────────────────────────

    def _loadtest_menu(self) -> None:

        while True:
            phase("Load Testing")

            action = _select_value(
                "Action:",
                choices=_LOADTEST_ACTION_CHOICES,
                include_back=True,
            )
            if action == _BACK_VALUE:
                return

            if action == "new_profile":
                self._profile_menu()
                continue

            saved = list_profiles()
            if saved:
                use_saved = _ask(
                    lambda: questionary.confirm(
                        "Use a saved profile?", default=True, style=_STYLE
                    ).ask()
                )
                if use_saved:
                    profile_name = _select_value(
                        "Profile:",
                        choices=_saved_profile_choices(saved),
                        include_back=True,
                    )
                    if profile_name == _BACK_VALUE:
                        continue
                    profile = load_profile(profile_name)
                else:
                    profile = self._build_profile_interactive("default")
            else:
                warning("No saved profiles found, launching wizard...")
                profile = self._build_profile_interactive("default")

            request = build_loadtest_request(profile=profile)

            if action == "plan":
                _show_loadtest_plan(request)
                _acknowledge_static_view()
                continue

            def _run_loadtest_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_step_event(event: Any) -> None:
                    self._applier.apply_loadtest_step_event(dashboard, event)
                    sink._update()

                flow = build_loadtest_flow(
                    request.load_profile.name,
                    request=request,
                    event_listener=_on_step_event,
                )
                result = self._controller.run_shared_flow(flow, allow_none_result=False)
                dashboard.append_log(f"Summary: {result.run_dir / 'summary.json'}")
                dashboard.append_log(f"Report: {result.run_dir / 'report.html'}")
                sink._update()
                if result.final_status == "passed":
                    success("Load test completed")
                    return result
                fail("Load test failed", detail=str(result.run_dir))
                raise RuntimeError(f"load test failed: {result.run_dir}")

            self._controller.run_live_workflow(
                title="Load Testing",
                summary_lines=[
                    f"Profile: {request.profile.name}",
                    f"Scenario: {request.scenario.name}",
                    f"Load profile: {request.load_profile.name}",
                ],
                planned_steps=["preflight", "bootstrap", "load_k6", "metrics_gate", "report"],
                action=_run_loadtest_workflow,
            )
            return

    # ── FUNCTIONS ─────────────────────────────────────────────────────────────

    def _catalog_menu(self) -> None:
        self._functions_menu()

    def _functions_menu(self) -> None:
        from controlplane_tool.functions.catalog import list_functions, list_function_presets

        while True:
            phase("Function Catalog")

            view = _select_value(
                "View:",
                choices=_CATALOG_VIEW_CHOICES,
                include_back=True,
            )
            if view == _BACK_VALUE:
                return

            if view == "all":
                functions = list_functions()
                table = Table(title="Available functions", border_style="cyan dim")
                table.add_column("Key", style="cyan bold")
                table.add_column("Family", style="dim")
                table.add_column("Runtime", style="green")
                for fn in functions:
                    table.add_row(fn.key, fn.family or "", fn.runtime or "")
                console.print(table)
                _acknowledge_static_view("Press any key to return to the catalog.")
                continue

            if view == "presets":
                presets = list_function_presets()
                table = Table(title="Available presets", border_style="cyan dim")
                table.add_column("Name", style="cyan bold")
                table.add_column("Description", style="dim")
                table.add_column("Functions", style="green")
                for preset in presets:
                    keys = ", ".join(f.key for f in preset.functions)
                    table.add_row(preset.name, preset.description or "", keys)
                console.print(table)
                _acknowledge_static_view("Press any key to return to the catalog.")
                continue

            from controlplane_tool.functions.catalog import (
                resolve_function_definition,
            )
            key = _select_value(
                "Function:",
                choices=_function_detail_choices(),
                include_back=True,
            )
            if key == _BACK_VALUE:
                continue
            fn = resolve_function_definition(key)
            rows = [
                ("Key", fn.key),
                ("Family", fn.family),
                ("Runtime", fn.runtime),
                ("Description", getattr(fn, "description", "—")),
                ("Example dir", _workspace_relative_path(getattr(fn, "example_dir", None))),
                ("Default image", str(getattr(fn, "default_image", "—") or "—")),
                ("Payload file", str(getattr(fn, "default_payload_file", "—") or "—")),
            ]
            table = Table(title=f"Function: {fn.key}", border_style="cyan dim", show_header=False)
            table.add_column("Field", style="dim")
            table.add_column("Value", style="cyan")
            for label, value in rows:
                table.add_row(label, escape(str(value)))
            console.print(table)
            _acknowledge_static_view("Press any key to return to the catalog.")

    # ── PROFILE MANAGER ───────────────────────────────────────────────────────

    def _profiles_menu(self) -> None:
        self._profile_menu()

    def _profile_menu(self) -> None:
        from controlplane_tool.workspace.profiles import list_profiles, load_profile, save_profile

        while True:
            phase("Profile Manager")

            action = _select_value(
                "Action:",
                choices=_PROFILE_ACTION_CHOICES,
                include_back=True,
            )
            if action == _BACK_VALUE:
                return

            if action == "new":
                name = _ask(
                    lambda: questionary.text(
                        "Profile name:", default="default", style=_STYLE
                    ).ask()
                )
                profile = self._build_profile_interactive(name)
                dest = save_profile(profile)
                success(f"Profile '{name}' saved", detail=str(dest))
                _acknowledge_static_view()
                continue

            if action == "show":
                saved = list_profiles()
                if not saved:
                    warning("No saved profiles.")
                    _acknowledge_static_view()
                    continue
                name = _select_value(
                    "Profile:",
                    choices=_saved_profile_choices(saved),
                    include_back=True,
                )
                if name == _BACK_VALUE:
                    continue
                profile = load_profile(name)
                _show_profile_table(profile)
                _acknowledge_static_view()
                continue

            saved = list_profiles()
            if not saved:
                warning("No saved profiles.")
                _acknowledge_static_view()
                continue
            name = _select_value(
                "Profile to delete:",
                choices=_saved_profile_choices(saved),
                include_back=True,
            )
            if name == _BACK_VALUE:
                continue
            confirm = _ask(
                lambda: questionary.confirm(
                    f"Delete '{name}'?", default=False, style=_STYLE
                ).ask()
            )
            if confirm:
                from controlplane_tool.workspace.profiles import profile_path
                profile_path(name).unlink(missing_ok=True)
                success(f"Profile '{name}' deleted")
                _acknowledge_static_view()

    def _build_profile_interactive(self, name: str) -> Any:
        from controlplane_tool.tui import build_profile_interactive
        return build_profile_interactive(profile_name=name)


# ── Shared display helpers ────────────────────────────────────────────────────


def _show_plan_table(plan: Any) -> None:
    """Render a ScenarioPlan or similar plan object in a Rich table."""
    if plan is None:
        return
    # Try to show steps/phases as a table
    steps = getattr(plan, "steps", None) or getattr(plan, "phases", None)
    if steps:
        table = Table(title="Execution plan", border_style="cyan dim")
        table.add_column("#", style="dim", justify="right")
        table.add_column("Step", style="cyan")
        table.add_column("Status", justify="center")
        for i, s in enumerate(steps, 1):
            label = getattr(s, "name", None) or getattr(s, "label", None) or str(s)
            status_val = getattr(s, "status", "—")
            table.add_row(str(i), escape(str(label)), escape(str(status_val)))
        console.print(table)
    else:
        console.print(Panel(escape(str(plan)), title="Plan", border_style="dim"))


def _show_loadtest_plan(request: Any) -> None:
    table = Table(title="Load Test Plan", border_style="cyan dim", show_header=False)
    table.add_column("Field", style="dim")
    table.add_column("Value", style="cyan")
    description = getattr(request, "execution_description", None)
    if description:
        table.add_row("execution", escape(str(description)))
    for attr in ("load_profile", "scenario", "metrics_gate", "runs_root"):
        val = getattr(request, attr, None)
        if val is not None:
            table.add_row(attr, escape(str(val)))
    console.print(table)


def _show_profile_table(profile: Any) -> None:
    table = Table(
        title=f"Profile: {escape(profile.name)}",
        border_style="cyan dim",
        show_header=False,
    )
    table.add_column("Field", style="dim")
    table.add_column("Value", style="cyan")
    cp = getattr(profile, "control_plane", None)
    if cp:
        table.add_row("implementation", escape(str(getattr(cp, "implementation", "—"))))
        table.add_row("build_mode", escape(str(getattr(cp, "build_mode", "—"))))
    modules = getattr(profile, "modules", [])
    table.add_row("modules", escape(", ".join(modules) or "(core only)"))
    tests = getattr(profile, "tests", None)
    if tests:
        table.add_row("tests.enabled", str(getattr(tests, "enabled", False)))
        table.add_row("tests.api", str(getattr(tests, "api", False)))
        table.add_row("tests.e2e_mockk8s", str(getattr(tests, "e2e_mockk8s", False)))
        table.add_row("tests.metrics", str(getattr(tests, "metrics", False)))
        table.add_row("tests.load_profile", escape(str(getattr(tests, "load_profile", "—"))))
    scenario = getattr(profile, "scenario", None)
    if scenario:
        table.add_row(
            "scenario.base_scenario",
            escape(str(getattr(scenario, "base_scenario", "—") or "—")),
        )
        table.add_row(
            "scenario.function_preset",
            escape(str(getattr(scenario, "function_preset", "—") or "—")),
        )
        functions = getattr(scenario, "functions", []) or []
        table.add_row("scenario.functions", escape(", ".join(functions) or "—"))
        table.add_row(
            "scenario.scenario_file",
            escape(str(getattr(scenario, "scenario_file", "—") or "—")),
        )
        table.add_row(
            "scenario.namespace",
            escape(str(getattr(scenario, "namespace", "—") or "—")),
        )
        table.add_row(
            "scenario.local_registry",
            escape(str(getattr(scenario, "local_registry", "—") or "—")),
        )
    cli_test = getattr(profile, "cli_test", None)
    if cli_test:
        table.add_row(
            "cli_test.default_scenario",
            escape(str(getattr(cli_test, "default_scenario", "—") or "—")),
        )
    loadtest = getattr(profile, "loadtest", None)
    if loadtest:
        table.add_row(
            "loadtest.default_load_profile",
            escape(str(getattr(loadtest, "default_load_profile", "—") or "—")),
        )
        table.add_row(
            "loadtest.metrics_gate_mode",
            escape(str(getattr(loadtest, "metrics_gate_mode", "—") or "—")),
        )
        table.add_row(
            "loadtest.scenario_file",
            escape(str(getattr(loadtest, "scenario_file", "—") or "—")),
        )
        table.add_row(
            "loadtest.function_preset",
            escape(str(getattr(loadtest, "function_preset", "—") or "—")),
        )
    metrics = getattr(profile, "metrics", None)
    if metrics:
        required_metrics = getattr(metrics, "required", []) or []
        table.add_row(
            "metrics.required",
            escape(", ".join(required_metrics) or "—"),
        )
        table.add_row(
            "metrics.prometheus_url",
            escape(str(getattr(metrics, "prometheus_url", "—") or "—")),
        )
        table.add_row(
            "metrics.strict_required",
            str(getattr(metrics, "strict_required", False)),
        )
    console.print(table)
