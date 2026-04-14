"""
tui_app.py — Interactive Rich TUI for the controlplane tool.

Launched automatically when `controlplane-tool` is invoked with no arguments.
All CLI commands remain available for scripted / CI use.
"""
from __future__ import annotations

import sys
import traceback
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable

import questionary
from prompt_toolkit.application import Application
from prompt_toolkit.application.current import get_app
from prompt_toolkit.key_binding import KeyBindings
from prompt_toolkit.layout import Dimension, Layout
from prompt_toolkit.layout.containers import HSplit, VSplit, Window
from prompt_toolkit.layout.controls import FormattedTextControl
from prompt_toolkit.widgets import Frame, RadioList
from questionary import Style
from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.table import Table

from controlplane_tool.console import console, fail, header, phase, skip, step, success, warning
from controlplane_tool.infra_flows import build_vm_flow
from controlplane_tool.loadtest_commands import build_loadtest_request
from controlplane_tool.loadtest_flows import build_loadtest_flow
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.registry_runtime import default_registry_url, ensure_local_registry
from controlplane_tool.profiles import list_profiles, load_profile
from controlplane_tool.scenario_flows import build_scenario_flow
from controlplane_tool.cli_stack_runner import CliStackRunner
from controlplane_tool.tui_event_applier import TuiEventApplier
from controlplane_tool.tui_workflow import TuiWorkflowSink, WorkflowDashboard
from controlplane_tool.tui_workflow_controller import TuiWorkflowController

# ── questionary theme consistent with Rich cyan palette ──────────────────────
_STYLE = Style(
    [
        ("qmark", "fg:cyan bold"),
        ("question", "bold"),
        ("answer", "fg:cyan bold"),
        ("pointer", "fg:cyan bold"),
        ("highlighted", "fg:cyan bold"),
        ("selected", "fg:cyan"),
        ("separator", "fg:grey"),
        ("instruction", "fg:grey"),
    ]
)

_BACK_VALUE = "back"


def _ask(prompt_fn):
    """Execute a questionary prompt; exit cleanly on Ctrl-C / None."""
    result = prompt_fn()
    if result is None:
        raise KeyboardInterrupt
    return result


@dataclass(frozen=True)
class _DescribedChoice:
    title: str
    value: str
    description: str


def _back_choice() -> questionary.Choice:
    return questionary.Choice("back — return to previous menu", _BACK_VALUE)


def _with_back_choice(choices: list[Any]) -> list[Any]:
    if any(getattr(choice, "value", choice) == _BACK_VALUE for choice in choices):
        return choices
    return [*choices, questionary.Separator(), _back_choice()]


def _with_back_described_choice(choices: list[_DescribedChoice]) -> list[_DescribedChoice]:
    if any(choice.value == _BACK_VALUE for choice in choices):
        return choices
    return [
        *choices,
        _DescribedChoice(
            "back — return to previous menu",
            _BACK_VALUE,
            "Return to the previous menu without starting a workflow.",
        ),
    ]


def _select_value(
    message: str,
    *,
    choices: list[Any],
    default: str | None = None,
    include_back: bool = False,
) -> Any:
    prompt_choices = _with_back_choice(list(choices)) if include_back else choices
    return _ask(
        lambda: questionary.select(
            message,
            choices=prompt_choices,
            default=default,
            style=_STYLE,
        ).ask()
    )


class _AcceptingRadioList(RadioList):
    def __init__(
        self,
        values: list[tuple[str, str]],
        *,
        on_accept: Callable[[str], None],
    ) -> None:
        super().__init__(values)
        self._on_accept = on_accept

    def _handle_enter(self) -> None:
        super()._handle_enter()
        self._on_accept(_selected_radiolist_value(self))


def _selected_radiolist_value(radio_list: RadioList) -> str:
    selected_index = getattr(radio_list, "_selected_index", 0)
    return str(radio_list.values[selected_index][0])


def _selected_described_choice(
    radio_list: RadioList,
    choices: list[_DescribedChoice],
) -> _DescribedChoice:
    selected_value = _selected_radiolist_value(radio_list)
    return next(choice for choice in choices if choice.value == selected_value)


def _select_described_value(
    message: str,
    choices: list[_DescribedChoice],
    *,
    include_back: bool = False,
) -> str | None:
    """Show an interactive selector with a live description panel on the right."""
    if include_back:
        choices = _with_back_described_choice(choices)
    if not choices:
        raise ValueError("choices must not be empty")

    if not sys.stdin.isatty() or not sys.stdout.isatty():
        return questionary.select(
            message,
            choices=[questionary.Choice(choice.title, choice.value) for choice in choices],
            style=_STYLE,
        ).ask()

    radio_list = _AcceptingRadioList(
        [(choice.value, choice.title) for choice in choices],
        on_accept=lambda value: get_app().exit(result=value),
    )

    def _description_fragments() -> list[tuple[str, str]]:
        selected = _selected_described_choice(radio_list, choices)
        return [
            ("class:question", selected.description),
            ("", "\n\n"),
            ("class:instruction", "Enter to confirm • Esc to cancel"),
        ]

    body = VSplit(
        [
            Frame(radio_list, title=message),
            Frame(
                Window(
                    FormattedTextControl(_description_fragments),
                    wrap_lines=True,
                ),
                title="Description",
            ),
        ],
        padding=1,
        width=Dimension(preferred=100),
    )
    root = HSplit(
        [
            Window(
                height=1,
                content=FormattedTextControl(
                    [("class:instruction", "Use arrow keys to move through the list.")]
                ),
            ),
            body,
        ]
    )

    bindings = KeyBindings()

    @bindings.add("escape")
    @bindings.add("c-c")
    def _cancel(event) -> None:  # noqa: ANN001
        event.app.exit(result=None)

    app = Application(
        layout=Layout(root, focused_element=radio_list),
        key_bindings=bindings,
        full_screen=True,
        style=_STYLE,
        mouse_support=False,
    )
    return app.run()


# ── Main application ─────────────────────────────────────────────────────────


class NanofaasTUI:
    """Menu-driven Rich TUI for all controlplane operations."""

    _MAIN_MENU = [
        questionary.Choice("🏗  Build & Test", "build"),
        questionary.Choice("🖥  VM Management", "vm"),
        questionary.Choice("🗄  Registry", "registry"),
        questionary.Choice("🧪  E2E Scenarios", "e2e"),
        questionary.Choice("🖥  CLI E2E", "cli_e2e"),
        questionary.Choice("📊  Load Testing", "loadtest"),
        questionary.Choice("📦  Function Catalog", "functions"),
        questionary.Choice("⚙️   Profile Manager", "profile"),
        questionary.Separator(),
        questionary.Choice("🚪  Exit", "exit"),
    ]

    def __init__(self) -> None:
        self._applier = TuiEventApplier()
        self._controller = TuiWorkflowController(event_applier=self._applier)

    def run(self) -> None:
        header()
        try:
            while True:
                choice = _ask(
                    lambda: questionary.select(
                        "What would you like to do?",
                        choices=self._MAIN_MENU,
                        style=_STYLE,
                    ).ask()
                )
                if choice == "exit":
                    break
                try:
                    {
                        "build": self._build_menu,
                        "vm": self._vm_menu,
                        "registry": self._registry_menu,
                        "e2e": self._e2e_menu,
                        "cli_e2e": self._cli_e2e_menu,
                        "loadtest": self._loadtest_menu,
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
        from controlplane_tool.cli_commands import GradleCommandExecutor

        phase("Build & Test")

        action = _select_value(
            "Action:",
            choices=[
                questionary.Choice("jar — assemble JARs", "jar"),
                questionary.Choice("build — compile + unit tests", "build"),
                questionary.Choice("test — unit tests only", "test"),
                questionary.Choice("run — start control-plane", "run"),
                questionary.Choice("image — build OCI image", "image"),
                questionary.Choice("native — build GraalVM native", "native"),
                questionary.Choice("inspect — show configuration", "inspect"),
            ],
            include_back=True,
        )
        if action == _BACK_VALUE:
            return

        profile = _select_value(
            "Profile:",
            choices=["core", "k8s", "all", "container-local"],
            default="core",
            include_back=True,
        )
        if profile == _BACK_VALUE:
            return

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
            return

        def _run_build_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            step(f"Running {action}", f"profile={profile}")
            result = executor.execute(
                action=action,
                profile=profile,
                modules=None,
                extra_gradle_args=[],
                dry_run=False,
            )
            if result.return_code == 0:
                success(f"{action} completed")
                return result
            fail(f"{action} failed", detail=f"exit code {result.return_code}")
            raise RuntimeError(f"{action} failed (exit code {result.return_code})")

        self._controller.run_live_workflow(
            title="Build & Test",
            summary_lines=[
                f"Action: {action}",
                f"Profile: {profile}",
            ],
            planned_steps=[f"{action}"],
            action=_run_build_workflow,
        )

    # ── VM ────────────────────────────────────────────────────────────────────

    def _vm_menu(self) -> None:
        from controlplane_tool.vm_models import VmRequest

        phase("VM Management")

        action = _select_value(
            "Action:",
            choices=[
                questionary.Choice("up — start / provision", "up"),
                questionary.Choice("down — stop and delete", "down"),
                questionary.Choice("sync — sync project", "sync"),
                questionary.Choice("provision-base — install base dependencies", "provision-base"),
                questionary.Choice("provision-k3s — install k3s", "provision-k3s"),
                questionary.Choice("inspect — show status", "inspect"),
            ],
            include_back=True,
        )
        if action == _BACK_VALUE:
            return

        lifecycle = _select_value(
            "Lifecycle:",
            choices=["multipass", "external"],
            default="multipass",
            include_back=True,
        )
        if lifecycle == _BACK_VALUE:
            return

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
            return

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

    # ── REGISTRY ─────────────────────────────────────────────────────────────

    def _registry_menu(self) -> None:
        phase("Registry")

        action = _select_value(
            "Action:",
            choices=[
                questionary.Choice(
                    "start — start Docker Desktop if present and run registry",
                    "start",
                ),
            ],
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
        phase("E2E Scenarios")

        scenario_choice = _ask(
            lambda: _select_described_value(
                "Scenario:",
                choices=[
                    _DescribedChoice(
                        "k3s-junit-curl — self-bootstrapping VM stack with curl + JUnit verification",
                        "k3s-junit-curl",
                        "Provision the VM, install k3s, deploy the stack, then verify it with curl and JUnit checks.",
                    ),
                    _DescribedChoice(
                        "helm-stack — self-bootstrapping VM stack for Helm compatibility",
                        "helm-stack",
                        "Bootstrap the full VM-backed Helm stack, then verify deployment compatibility end to end.",
                    ),
                    _DescribedChoice(
                        "container-local — local managed DEPLOYMENT",
                        "container-local",
                        "Run the managed DEPLOYMENT workflow entirely on the local machine without a VM.",
                    ),
                    _DescribedChoice(
                        "deploy-host — deploy-host with local registry",
                        "deploy-host",
                        "Build on the host, push to a local registry, and register against a fake control-plane.",
                    ),
                    _DescribedChoice(
                        "docker — local POOL with Docker",
                        "docker",
                        "Exercise the local POOL runtime with Docker-based execution on the host.",
                    ),
                    _DescribedChoice(
                        "buildpack — local POOL with buildpack",
                        "buildpack",
                        "Exercise the local POOL runtime using buildpack-produced images on the host.",
                    ),
                ],
                include_back=True,
            )
        )
        if scenario_choice == _BACK_VALUE:
            return

        if scenario_choice in ("k3s-junit-curl", "helm-stack"):
            self._run_vm_e2e(scenario_choice)
        elif scenario_choice == "container-local":
            self._run_container_local()
        elif scenario_choice == "deploy-host":
            self._run_deploy_host()
        else:
            self._run_generic_e2e(scenario_choice)

    def _run_vm_e2e(self, scenario: str) -> None:
        repo_root = default_tool_paths().workspace_root

        if scenario == "helm-stack":
            from controlplane_tool.e2e_commands import _resolve_run_request
            from controlplane_tool.e2e_runner import E2eRunner

            request = _resolve_run_request(
                scenario="helm-stack",
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
                function_preset=None,
                functions_csv=None,
                scenario_file=None,
                saved_profile=None,
            )
            plan = E2eRunner(repo_root=repo_root).plan(request)

            def _run_helm_stack_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                dashboard.append_log("Starting helm-stack workflow")
                sink._update()
                flow = build_scenario_flow(
                    "helm-stack",
                    repo_root=repo_root,
                    request=request,
                    event_listener=_on_event,
                )
                self._controller.run_shared_flow(flow)
                dashboard.append_log("helm-stack E2E completed")
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

        else:  # k3s-junit-curl
            from controlplane_tool.e2e_runner import E2eRunner
            from controlplane_tool.e2e_commands import _resolve_run_request

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
                function_preset=None,
                functions_csv=None,
                scenario_file=None,
                saved_profile=None,
            )
            runner = E2eRunner(repo_root=repo_root)
            if dry_run:
                plan = runner.plan(request)
                step("k3s-junit-curl E2E plan (dry-run)")
                _show_plan_table(plan)
                return

            plan = runner.plan(request)

            def _run_k8s_vm_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                flow = build_scenario_flow(
                    "k3s-junit-curl",
                    repo_root=repo_root,
                    request=request,
                    event_listener=_on_event,
                )
                dashboard.append_log("Starting k3s-junit-curl workflow")
                sink._update()
                self._controller.run_shared_flow(flow)
                success("k3s-junit-curl E2E completed")
                return plan

            self._controller.run_live_workflow(
                title="E2E Scenarios",
                summary_lines=[
                    "Scenario: k3s-junit-curl",
                    "Mode: self-bootstrapping VM-backed scenario",
                    f"VM Name: {vm_name}",
                    f"Control-plane runtime: {runtime}",
                    f"Cleanup VM at end: {'yes' if cleanup_vm else 'no'}",
                ],
                planned_steps=[step.summary for step in plan.steps],
                action=_run_k8s_vm_workflow,
            )

    def _run_container_local(self) -> None:
        def _run_container_local_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            step("Running container-local E2E")
            flow = build_scenario_flow(
                "container-local",
                repo_root=default_tool_paths().workspace_root,
            )
            self._controller.run_shared_flow(flow)
            success("container-local E2E completed")

        self._controller.run_live_workflow(
            title="E2E Scenarios",
            summary_lines=["Scenario: container-local"],
            planned_steps=["Build", "Deploy", "Verify"],
            action=_run_container_local_workflow,
        )

    def _run_deploy_host(self) -> None:
        def _run_deploy_host_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            step("Running deploy-host E2E")
            flow = build_scenario_flow(
                "deploy-host",
                repo_root=default_tool_paths().workspace_root,
            )
            self._controller.run_shared_flow(flow)
            success("deploy-host E2E completed")

        self._controller.run_live_workflow(
            title="E2E Scenarios",
            summary_lines=["Scenario: deploy-host"],
            planned_steps=["Build", "Deploy", "Verify"],
            action=_run_deploy_host_workflow,
        )

    def _run_generic_e2e(self, scenario: str) -> None:
        from controlplane_tool.e2e_models import E2eRequest

        runtime = _ask(
            lambda: questionary.select(
                "Runtime:", choices=["java", "rust"], default="java", style=_STYLE
            ).ask()
        )

        request = E2eRequest(scenario=scenario, runtime=runtime)
        from controlplane_tool.e2e_runner import E2eRunner
        plan = E2eRunner(repo_root=default_tool_paths().workspace_root).plan(request)

        def _run_generic_e2e_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            def _on_event(event: Any) -> None:
                self._applier.apply_e2e_step_event(dashboard, event)
                sink._update()

            flow = build_scenario_flow(
                scenario,
                repo_root=default_tool_paths().workspace_root,
                request=request,
                event_listener=_on_event,
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
                choices=[
                    _DescribedChoice(
                        "vm — CLI E2E on k3s VM",
                        "vm",
                        "Run the CLI workflow inside a VM-backed environment with the classic CLI E2E path.",
                    ),
                    _DescribedChoice(
                        "cli-stack — canonical self-bootstrapping CLI stack in VM",
                        "cli-stack",
                        "Run the canonical VM-backed CLI stack that bootstraps the platform and validates the CLI end to end.",
                    ),
                    _DescribedChoice(
                        "host-platform — compatibility path, CLI on host vs cluster",
                        "host-platform",
                        "Keep the CLI on the host and validate the compatibility path against a VM-backed platform.",
                    ),
                ],
                include_back=True,
            )
        )
        if runner_choice == _BACK_VALUE:
            return

        repo_root = default_tool_paths().workspace_root

        if runner_choice == "vm":
            def _run_cli_vm_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step("Running CLI VM E2E")
                flow = build_scenario_flow(
                    "cli",
                    repo_root=repo_root,
                )
                self._controller.run_shared_flow(flow)
                success("CLI VM E2E completed")

            self._controller.run_live_workflow(
                title="CLI E2E",
                summary_lines=["Runner: vm", "Mode: legacy in-VM CLI validation path"],
                planned_steps=["Build", "Deploy", "Verify"],
                action=_run_cli_vm_workflow,
            )
            return

        if runner_choice == "cli-stack":
            from controlplane_tool.e2e_runner import E2eRunner
            from controlplane_tool.e2e_models import E2eRequest
            from controlplane_tool.scenario_components.environment import default_managed_vm_request

            cli_stack_request = E2eRequest(
                scenario="cli-stack",
                vm=default_managed_vm_request(),
                local_registry=default_registry_url(),
            )
            cli_stack_plan = E2eRunner(repo_root).plan(cli_stack_request)

            def _run_cli_stack_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_event(event: Any) -> None:
                    self._applier.apply_e2e_step_event(dashboard, event)
                    sink._update()

                step("Running CLI stack E2E")
                flow = build_scenario_flow(
                    "cli-stack",
                    repo_root=repo_root,
                    event_listener=_on_event,
                )
                self._controller.run_shared_flow(flow)
                success("CLI stack E2E completed")

            self._controller.run_live_workflow(
                title="CLI E2E",
                summary_lines=[
                    "Runner: cli-stack",
                    "Mode: canonical self-bootstrapping VM-backed CLI stack",
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
        from controlplane_tool.loadtest_catalog import list_load_profiles

        phase("Load Testing")

        action = _select_value(
            "Action:",
            choices=[
                questionary.Choice("run — run load test with profile", "run"),
                questionary.Choice("plan — show plan without executing", "plan"),
                questionary.Choice("new profile — interactive wizard", "new_profile"),
            ],
            include_back=True,
        )
        if action == _BACK_VALUE:
            return

        if action == "new_profile":
            self._profile_menu()
            return

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
                    choices=saved,
                    include_back=True,
                )
                if profile_name == _BACK_VALUE:
                    return
                profile = load_profile(profile_name)
            else:
                profile = self._build_profile_interactive("default")
        else:
            warning("No saved profiles found, launching wizard...")
            profile = self._build_profile_interactive("default")

        request = build_loadtest_request(profile=profile)

        if action == "plan":
            _show_loadtest_plan(request)
        else:
            def _run_loadtest_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                def _on_event(event: Any) -> None:
                    self._applier.apply_loadtest_step_event(dashboard, event)
                    sink._update()

                flow = build_loadtest_flow(
                    request.load_profile.name,
                    request=request,
                    event_listener=_on_event,
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

    # ── FUNCTIONS ─────────────────────────────────────────────────────────────

    def _functions_menu(self) -> None:
        from controlplane_tool.function_catalog import list_functions, list_function_presets

        phase("Function Catalog")

        view = _select_value(
            "View:",
            choices=[
                questionary.Choice("All functions", "all"),
                questionary.Choice("Presets", "presets"),
                questionary.Choice("Function detail", "show"),
            ],
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

        elif view == "presets":
            presets = list_function_presets()
            table = Table(title="Available presets", border_style="cyan dim")
            table.add_column("Name", style="cyan bold")
            table.add_column("Description", style="dim")
            table.add_column("Functions", style="green")
            for preset in presets:
                keys = ", ".join(f.key for f in preset.functions)
                table.add_row(preset.name, preset.description or "", keys)
            console.print(table)

        else:
            from controlplane_tool.function_catalog import (
                list_functions,
                resolve_function_definition,
            )
            keys = [fn.key for fn in list_functions()]
            key = _select_value(
                "Function:",
                choices=keys,
                include_back=True,
            )
            if key == _BACK_VALUE:
                return
            fn = resolve_function_definition(key)
            rows = [
                ("Key", fn.key),
                ("Family", fn.family),
                ("Runtime", fn.runtime),
                ("Description", getattr(fn, "description", "—")),
                ("Default image", str(getattr(fn, "default_image", "—") or "—")),
                ("Payload file", str(getattr(fn, "default_payload_file", "—") or "—")),
            ]
            table = Table(title=f"Function: {fn.key}", border_style="cyan dim", show_header=False)
            table.add_column("Field", style="dim")
            table.add_column("Value", style="cyan")
            for label, value in rows:
                table.add_row(label, escape(str(value)))
            console.print(table)

    # ── PROFILE MANAGER ───────────────────────────────────────────────────────

    def _profile_menu(self) -> None:
        from controlplane_tool.profiles import list_profiles, load_profile, save_profile

        phase("Profile Manager")

        action = _select_value(
            "Action:",
            choices=[
                questionary.Choice("Create new profile", "new"),
                questionary.Choice("View existing profile", "show"),
                questionary.Choice("Delete profile", "delete"),
            ],
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

        elif action == "show":
            saved = list_profiles()
            if not saved:
                warning("No saved profiles.")
                return
            name = _select_value(
                "Profile:",
                choices=saved,
                include_back=True,
            )
            if name == _BACK_VALUE:
                return
            profile = load_profile(name)
            _show_profile_table(profile)

        else:  # delete
            saved = list_profiles()
            if not saved:
                warning("No saved profiles.")
                return
            name = _select_value(
                "Profile to delete:",
                choices=saved,
                include_back=True,
            )
            if name == _BACK_VALUE:
                return
            confirm = _ask(
                lambda: questionary.confirm(
                    f"Delete '{name}'?", default=False, style=_STYLE
                ).ask()
            )
            if confirm:
                from controlplane_tool.profiles import profile_path
                profile_path(name).unlink(missing_ok=True)
                success(f"Profile '{name}' deleted")

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
        table.add_row("tests.load_profile", escape(str(getattr(tests, "load_profile", "—"))))
    console.print(table)
