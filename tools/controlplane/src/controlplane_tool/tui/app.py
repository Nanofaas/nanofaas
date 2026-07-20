"""Menu navigation for the interactive control-plane tool."""

from __future__ import annotations

from collections.abc import Callable
from contextlib import nullcontext
from pathlib import Path
import sys
from typing import Any

from rich.table import Table
from rich.text import Text

from controlplane_tool.cli import diagnostics
from controlplane_tool.cli.execution import resolve_loadtest_urls
from controlplane_tool.cli.preflight import PreflightError, preflight_control_plane
from controlplane_tool.cli.product import _environment, _scenario, _workflow
from controlplane_tool.cli.provisioning import provision_environment
from controlplane_tool.tui.workflow_controller import TuiWorkflowController
from controlplane_tool.workspace.paths import default_tool_paths
from tui_toolkit import Choice, render_screen_frame, select
from tui_toolkit.console import console as default_console

MAIN_MENU = [
    Choice(
        "Validation",
        "validation",
        "Validate container and Kubernetes execution paths.",
    ),
    Choice(
        "CLI",
        "cli",
        "Validate the nanofaas CLI against a selected environment.",
    ),
    Choice(
        "Load Testing",
        "loadtest",
        "Run the current k6 and autoscaling workflow.",
    ),
    Choice(
        "Tools",
        "tools",
        "Inspect scenarios and check local prerequisites.",
    ),
    Choice("Exit", "exit", "Leave the interactive control-plane tool."),
]

VALIDATION_MENU = [
    Choice("Container", "container", "Validate the local container execution path."),
    Choice("Kubernetes", "kubernetes", "Validate the Kubernetes execution path."),
    Choice("Offload", "offload", "Validate edge-to-cloud offload with two local instances."),
]
CLI_MENU = [
    Choice("Validate CLI", "validate", "Validate the nanofaas CLI workflow."),
]
LOADTEST_MENU = [
    Choice("Run load test", "run", "Run the current k6 and autoscaling workflow."),
    Choice(
        "Offload load test",
        "offload",
        "Run mixed-policy k6 traffic against edge and cloud control planes.",
    ),
]
TOOLS_MENU = [
    Choice("Inspect scenario", "inspect", "Inspect a supported scenario."),
    Choice("Doctor", "doctor", "Check required local executables."),
]

_SECTION_MENUS = {
    "validation": VALIDATION_MENU,
    "cli": CLI_MENU,
    "loadtest": LOADTEST_MENU,
    "tools": TOOLS_MENU,
}
_SECTION_TITLES = {
    "validation": "Validation",
    "cli": "CLI",
    "loadtest": "Load Testing",
    "tools": "Tools",
}
_SCENARIO_FILES = {
    ("validation", "container"): "validate-container.yaml",
    ("validation", "kubernetes"): "validate-k8s.yaml",
    ("validation", "offload"): "validate-offload.yaml",
    ("cli", "validate"): "cli.yaml",
    ("loadtest", "run"): "loadtest.yaml",
    ("loadtest", "offload"): "offload-loadtest.yaml",
}
_SCENARIO_TITLES = {
    scenario_name: _SECTION_TITLES[section]
    for (section, _action), scenario_name in _SCENARIO_FILES.items()
}

_ACTION_CHOICES = [
    Choice("Plan", "plan", "Show the workflow tasks without running them."),
    Choice("Run", "run", "Run the workflow and follow its live progress."),
]
_PROVISION_CHOICES = [
    Choice("Use existing", "existing", "Use the configured environment as-is."),
    Choice("Provision", "provision", "Provision the configured environment first."),
]
_CLEANUP_CHOICES = [
    Choice("Cleanup", "cleanup", "Remove infrastructure created by the workflow."),
    Choice("Keep", "keep", "Keep infrastructure after the workflow finishes."),
]
_PROVIDER_SETUP = {
    "azure": ("Azure", "azure.yaml.example", "azure.yaml"),
    "proxmox": ("Proxmox", "proxmox.yaml.example", "proxmox.yaml"),
}
_PROVIDER_GUIDANCE = {
    "azure": (
        "cp tools/controlplane/environments/azure.yaml.example "
        "tools/controlplane/environments/azure.yaml\n\n"
        "Set the Azure provider values and ssh_key_path, then run az login."
    ),
    "proxmox": (
        "cp tools/controlplane/environments/proxmox.yaml.example "
        "tools/controlplane/environments/proxmox.yaml\n\n"
        "Set host, node, template_id, and ssh_key_path. The template's password_env "
        "names PROXMOX_PASSWORD; export that environment variable."
    ),
}


class NanofaasTUI:
    """Navigate the stable product menu and dispatch selected scenarios."""

    MAIN_MENU = MAIN_MENU
    SECTION_MENUS = _SECTION_MENUS
    SECTION_TITLES = _SECTION_TITLES
    SCENARIO_FILES = _SCENARIO_FILES

    def __init__(
        self,
        choose: Callable[..., str] = select,
        dispatch_scenario: Callable[[str], None] | None = None,
        *,
        controller: TuiWorkflowController | Any | None = None,
        console: Any = default_console,
        input_stream: Any = None,
    ) -> None:
        self._choose = choose
        self._dispatch_scenario = dispatch_scenario or self._workflow_menu
        self._controller = controller or TuiWorkflowController(console=console)
        self._console = console
        self._input_stream = sys.stdin if input_stream is None else input_stream

    def run(self) -> None:
        try:
            while True:
                section = self._choose(
                    "What would you like to do?",
                    choices=self.MAIN_MENU,
                    title="Main",
                    breadcrumb="Main",
                )
                if section == "exit":
                    return
                self._dispatch_section(section)
        except KeyboardInterrupt:
            return

    def _dispatch_section(self, section: str) -> None:
        menu = self.SECTION_MENUS.get(section)
        if menu is None:
            raise ValueError(f"Unsupported TUI section: {section}")

        title = self.SECTION_TITLES[section]
        while True:
            action = self._choose(
                title,
                choices=menu,
                include_back=True,
                escape_value="back",
                title=title,
                breadcrumb=f"Main / {title}",
            )
            if action == "back":
                return
            scenario_file = self.SCENARIO_FILES.get((section, action))
            if scenario_file is not None:
                self._dispatch_scenario(scenario_file)
            elif section == "tools":
                self._dispatch_tool(action)

    def _dispatch_tool(self, action: str) -> None:
        if action == "inspect":
            scenario_names = list(dict.fromkeys(self.SCENARIO_FILES.values()))
            selected = self._choose(
                "Scenario",
                choices=[
                    Choice(Path(name).stem, name, f"Inspect {name}.")
                    for name in scenario_names
                ],
                include_back=True,
                escape_value="back",
                title="Inspect scenario",
                breadcrumb="Main / Tools / Inspect scenario",
            )
            if selected == "back":
                return
            try:
                scenario = _scenario(
                    default_tool_paths().tool_root / "scenarios-v2" / selected
                )
                body = scenario.model_dump_json(by_alias=True, indent=2)
            except Exception as exc:
                body = str(exc)
            self._show_static(
                title="Inspect scenario",
                breadcrumb="Main / Tools / Inspect scenario",
                body=body,
            )
        elif action == "doctor":
            missing = diagnostics.missing_executables()
            body = f"missing executables: {', '.join(missing)}" if missing else "ok"
            self._show_static(
                title="Doctor",
                breadcrumb="Main / Tools / Doctor",
                body=body,
            )

    def _select_environment(self) -> Path | None:
        environment_dir = default_tool_paths().tool_root / "environments"
        while True:
            environment_paths = [
                path
                for path in sorted(environment_dir.glob("*.yaml"))
                if path.is_file() and ".example" not in path.name
            ]
            choices = [
                Choice(path.stem, str(path), f"Use {path.name}.")
                for path in environment_paths
            ]
            for provider, (label, template_name, target_name) in _PROVIDER_SETUP.items():
                if (environment_dir / template_name).is_file() and not (
                    environment_dir / target_name
                ).is_file():
                    choices.append(
                        Choice(
                            f"{label} (setup required)",
                            f"setup:{provider}",
                            f"Configure {target_name} before use.",
                        )
                    )
            if not choices:
                raise RuntimeError("at least one YAML environment or template is required")

            selected = self._choose(
                "Environment",
                choices=choices,
                include_back=True,
                escape_value="back",
                title="Environment",
                breadcrumb="Main / Environment",
            )
            if selected == "back":
                return None
            if selected.startswith("setup:"):
                provider = selected.removeprefix("setup:")
                self._show_static(
                    title=f"{_PROVIDER_SETUP[provider][0]} setup required",
                    breadcrumb="Main / Environment",
                    body=_PROVIDER_GUIDANCE[provider],
                )
                continue
            return Path(selected)

    def _workflow_menu(self, scenario_name: str) -> None:
        paths = default_tool_paths()
        scenario_path = paths.tool_root / "scenarios-v2" / scenario_name
        title = _SCENARIO_TITLES[scenario_name]
        state = "environment"
        environment_path: Path | None = None
        scenario: Any = None
        environment: Any = None
        provision = False
        keep = False

        while True:
            if state == "environment":
                environment_path = self._select_environment()
                if environment_path is None:
                    return
                scenario = None
                environment = None
                provision = False
                keep = False
                state = "action"
                continue

            if state == "action":
                action = self._choose(
                    "Action",
                    choices=_ACTION_CHOICES,
                    include_back=True,
                    escape_value="back",
                    title=title,
                    breadcrumb=f"Main / {title}",
                )
                if action == "back":
                    state = "environment"
                    continue
                try:
                    scenario = _scenario(scenario_path)
                    environment = _environment(environment_path)
                except Exception as exc:
                    self._show_static(
                        title="Configuration error",
                        breadcrumb=f"Main / {title}",
                        body=str(exc),
                    )
                    return
                if action == "plan":
                    break
                state = "cleanup" if environment.provider == "local" else "provision"
                continue

            if state == "provision":
                provision_choice = self._choose(
                    "Provision environment?",
                    choices=_PROVISION_CHOICES,
                    include_back=True,
                    escape_value="back",
                    title=title,
                    breadcrumb=f"Main / {title}",
                )
                if provision_choice == "back":
                    provision = False
                    state = "action"
                    continue
                provision = provision_choice == "provision"
                state = "cleanup"
                continue

            cleanup_choice = self._choose(
                "Cleanup policy",
                choices=_CLEANUP_CHOICES,
                include_back=True,
                escape_value="back",
                title=title,
                breadcrumb=f"Main / {title}",
            )
            if cleanup_choice == "back":
                keep = False
                state = "action" if environment.provider == "local" else "provision"
                continue
            keep = cleanup_choice == "keep"
            break

        assert environment_path is not None
        if action == "plan":
            try:
                workflow = self._build_workflow(
                    scenario,
                    environment,
                    dry_run=True,
                )
            except Exception as exc:
                self._show_static(
                    title="Preview error",
                    breadcrumb=f"Main / {title}",
                    body=str(exc),
                )
                return
            self._render_plan(title=title, workflow=workflow)
            return

        try:
            preview = self._build_workflow(
                scenario,
                environment,
                dry_run=True,
            )
        except Exception as exc:
            self._show_static(
                title="Preview error",
                breadcrumb=f"Main / {title}",
                body=str(exc),
            )
            return

        try:
            preflight_control_plane(
                scenario,
                environment,
                base_url="http://127.0.0.1:8080",
            )
        except PreflightError as exc:
            self._show_static(
                title="Preflight error",
                breadcrumb=f"Main / {title}",
                body=str(exc),
            )
            return

        try:
            def run_current_workflow(_dashboard: Any, _sink: Any) -> Any:
                provisioning = (
                    provision_environment(
                        scenario,
                        environment,
                        repo_root=paths.workspace_root,
                        keep=keep,
                    )
                    if provision
                    else nullcontext()
                )
                with provisioning:
                    workflow = self._build_workflow(
                        scenario,
                        environment,
                        dry_run=False,
                    )
                    workflow.keep_infrastructure = keep
                    return workflow.run()

            self._controller.run_live_workflow(
                title=title,
                summary_lines=[
                    f"Scenario: {scenario_path.name}",
                    f"Environment: {environment_path.name}",
                    f"Provision: {'yes' if provision else 'no'}",
                    f"Cleanup: {'keep' if keep else 'cleanup'}",
                ],
                planned_steps=preview.phase_titles,
                action=run_current_workflow,
            )
        except Exception:
            # The controller preserves and acknowledges the failed final dashboard.
            # Returning keeps the user in the scenario's submenu.
            return

    @staticmethod
    def _build_workflow(
        scenario: Any,
        environment: Any,
        *,
        dry_run: bool,
    ) -> Any:
        if scenario.workflow == "loadtest":
            control_plane_url, prometheus_url = resolve_loadtest_urls(
                environment,
                dry_run=dry_run,
            )
            return _workflow(
                scenario,
                environment,
                control_plane_url=control_plane_url,
                prometheus_url=prometheus_url,
            )
        if scenario.workflow == "offload-loadtest":
            return _workflow(scenario, environment, dry_run=dry_run)
        return _workflow(scenario, environment)

    def _render_plan(self, *, title: str, workflow: Any) -> None:
        table = Table(expand=True)
        table.add_column("#", justify="right", style="cyan", no_wrap=True)
        table.add_column("Task", style="bold")
        table.add_column("Description")
        for index, task in enumerate(workflow.tasks, start=1):
            table.add_row(f"{index:02d}", task.task_id, task.title)

        self._show_static(
            title=title,
            body=table,
            breadcrumb=f"Main / {title}",
        )

    def _show_static(self, title: str, breadcrumb: str, body: Any) -> None:
        input_is_tty = bool(
            hasattr(self._input_stream, "isatty") and self._input_stream.isatty()
        )
        self._console.clear()
        try:
            rendered_body = Text(body) if isinstance(body, str) else body
            self._console.print(
                render_screen_frame(
                    title=title,
                    body=rendered_body,
                    breadcrumb=breadcrumb,
                    footer_hint=(
                        "Press Enter to continue" if input_is_tty else "View complete"
                    ),
                )
            )
            if input_is_tty:
                self._input_stream.read(1)
        finally:
            self._console.clear()
