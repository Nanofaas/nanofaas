"""
tui_app.py — Interactive Rich TUI for the controlplane tool.

Launched automatically when `controlplane-tool` is invoked with no arguments.
All CLI commands remain available for scripted / CI use.
"""
from __future__ import annotations

import sys
import traceback
from pathlib import Path
from typing import Any, Callable

import questionary
from questionary import Style
from rich.live import Live
from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.table import Table

from controlplane_tool.console import bind_workflow_sink, console, fail, header, phase, skip, step, success, warning
from controlplane_tool.infra_flows import build_vm_flow
from controlplane_tool.loadtest_commands import build_loadtest_request
from controlplane_tool.loadtest_flows import build_loadtest_flow
from controlplane_tool.paths import default_tool_paths
from controlplane_tool.prefect_runtime import run_local_flow
from controlplane_tool.profiles import list_profiles, load_profile
from controlplane_tool.scenario_flows import build_scenario_flow
from controlplane_tool.tui_workflow import TuiWorkflowSink, WorkflowDashboard, WorkflowKeyListener

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


def _ask(prompt_fn):
    """Execute a questionary prompt; exit cleanly on Ctrl-C / None."""
    result = prompt_fn()
    if result is None:
        raise KeyboardInterrupt
    return result


# ── Main application ─────────────────────────────────────────────────────────


class NanofaasTUI:
    """Menu-driven Rich TUI for all controlplane operations."""

    _MAIN_MENU = [
        questionary.Choice("🏗  Build & Test", "build"),
        questionary.Choice("🖥  VM Management", "vm"),
        questionary.Choice("🧪  E2E Scenarios", "e2e"),
        questionary.Choice("🖥  CLI E2E", "cli_e2e"),
        questionary.Choice("📊  Load Testing", "loadtest"),
        questionary.Choice("📦  Function Catalog", "functions"),
        questionary.Choice("⚙️   Profile Manager", "profile"),
        questionary.Separator(),
        questionary.Choice("🚪  Exit", "exit"),
    ]

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

    def _run_live_workflow(
        self,
        *,
        title: str,
        summary_lines: list[str],
        planned_steps: list[str] | None,
        action: Callable[[WorkflowDashboard, TuiWorkflowSink], Any],
    ) -> Any:
        dashboard = WorkflowDashboard(
            title=title,
            summary_lines=[*summary_lines, "Hotkeys: l toggle logs"],
            planned_steps=planned_steps,
        )
        live: Live | None = None

        def _refresh() -> None:
            if live is not None:
                live.update(dashboard.render(), refresh=True)

        sink = TuiWorkflowSink(dashboard, refresh=_refresh)
        key_listener = WorkflowKeyListener(
            lambda key: (
                dashboard.toggle_logs(),
                _refresh(),
            )
            if key.lower() == "l"
            else None
        )
        with Live(dashboard.render(), console=console, refresh_per_second=8, transient=False) as active_live:
            live = active_live
            active_live.update(dashboard.render(), refresh=True)
            key_listener.start()
            try:
                with bind_workflow_sink(sink):
                    return action(dashboard, sink)
            finally:
                key_listener.stop()

    def _run_shared_flow(
        self,
        flow: Any,
        *,
        allow_none_result: bool = True,
        on_result: Callable[[Any], None] | None = None,
    ) -> Any:
        flow_result = run_local_flow(flow.flow_id, flow.run)
        if flow_result.status != "completed":
            raise RuntimeError(flow_result.error or f"{flow.flow_id} failed")
        if flow_result.result is None and not allow_none_result:
            raise RuntimeError(f"{flow.flow_id} returned no result")
        if on_result is not None:
            on_result(flow_result.result)
        self._raise_on_nonzero_command_result(flow_result.result)
        return flow_result.result

    def _append_command_result_logs(self, dashboard: WorkflowDashboard, result: Any) -> None:
        if isinstance(result, list):
            for item in result:
                self._append_command_result_logs(dashboard, item)
            return
        stdout = getattr(result, "stdout", "")
        stderr = getattr(result, "stderr", "")
        if stdout:
            dashboard.append_log(str(stdout).strip())
        if stderr:
            dashboard.append_log(str(stderr).strip())

    def _raise_on_nonzero_command_result(self, result: Any) -> None:
        if isinstance(result, list):
            for item in result:
                self._raise_on_nonzero_command_result(item)
            return
        return_code = getattr(result, "return_code", None)
        if return_code in (None, 0):
            return
        stderr = getattr(result, "stderr", "") or ""
        stdout = getattr(result, "stdout", "") or ""
        detail = str(stderr).strip() or str(stdout).strip() or f"exit code {return_code}"
        raise RuntimeError(detail)

    def _apply_e2e_step_event(self, dashboard: WorkflowDashboard, event: Any) -> None:
        if event.status == "running":
            dashboard.mark_step_running(event.step_index)
            dashboard.append_log(f"[start] {event.step.summary}")
            return
        if event.status == "success":
            dashboard.mark_step_success(event.step_index)
            dashboard.append_log(f"[done] {event.step.summary}")
            return
        dashboard.mark_step_failed(event.step_index, event.error or "")
        dashboard.append_log(
            f"[fail] {event.step.summary}" + (f" ({event.error})" if event.error else "")
        )

    def _apply_loadtest_step_event(self, dashboard: WorkflowDashboard, event: Any) -> None:
        step_index = dashboard.upsert_step(event.step_name)
        if event.status == "running":
            dashboard.mark_step_running(step_index)
            dashboard.append_log(f"[start] {event.step_name}")
            return
        if event.status == "passed":
            dashboard.mark_step_success(step_index)
            dashboard.append_log(
                f"[done] {event.step_name}" + (f" ({event.detail})" if event.detail else "")
            )
            return
        dashboard.mark_step_failed(step_index, event.detail)
        dashboard.append_log(
            f"[fail] {event.step_name}" + (f" ({event.detail})" if event.detail else "")
        )

    # ── BUILD ─────────────────────────────────────────────────────────────────

    def _build_menu(self) -> None:
        from controlplane_tool.cli_commands import GradleCommandExecutor

        phase("Build & Test")

        action = _ask(
            lambda: questionary.select(
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
                style=_STYLE,
            ).ask()
        )

        profile = _ask(
            lambda: questionary.select(
                "Profile:",
                choices=["core", "k8s", "all", "container-local"],
                default="core",
                style=_STYLE,
            ).ask()
        )

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

        self._run_live_workflow(
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

        action = _ask(
            lambda: questionary.select(
                "Action:",
                choices=[
                    questionary.Choice("up — start / provision", "up"),
                    questionary.Choice("down — stop and delete", "down"),
                    questionary.Choice("sync — sync project", "sync"),
                    questionary.Choice("provision-base — install base dependencies", "provision-base"),
                    questionary.Choice("provision-k3s — install k3s", "provision-k3s"),
                    questionary.Choice("inspect — show status", "inspect"),
                ],
                style=_STYLE,
            ).ask()
        )

        lifecycle = _ask(
            lambda: questionary.select(
                "Lifecycle:",
                choices=["multipass", "external"],
                default="multipass",
                style=_STYLE,
            ).ask()
        )

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
            result = self._run_shared_flow(flow, allow_none_result=False)
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
            live_result = self._run_shared_flow(
                flow,
                allow_none_result=False,
                on_result=lambda result: self._append_command_result_logs(dashboard, result),
            )
            success(f"vm {action} completed")
            return live_result

        self._run_live_workflow(
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

    # ── E2E ───────────────────────────────────────────────────────────────────

    def _e2e_menu(self) -> None:
        phase("E2E Scenarios")

        scenario_choice = _ask(
            lambda: questionary.select(
                "Scenario:",
                choices=[
                    questionary.Choice("k8s-vm — deploy to k3s in Multipass VM", "k8s-vm"),
                    questionary.Choice("k3s-curl — curl-based tests in VM", "k3s-curl"),
                    questionary.Choice("helm-stack — Helm stack compatibility", "helm-stack"),
                    questionary.Choice("container-local — local managed DEPLOYMENT", "container-local"),
                    questionary.Choice("deploy-host — deploy-host with local registry", "deploy-host"),
                    questionary.Choice("docker — local POOL with Docker", "docker"),
                    questionary.Choice("buildpack — local POOL with buildpack", "buildpack"),
                ],
                style=_STYLE,
            ).ask()
        )

        if scenario_choice in ("k3s-curl", "helm-stack", "k8s-vm"):
            self._run_vm_e2e(scenario_choice)
        elif scenario_choice == "container-local":
            self._run_container_local()
        elif scenario_choice == "deploy-host":
            self._run_deploy_host()
        else:
            self._run_generic_e2e(scenario_choice)

    def _run_vm_e2e(self, scenario: str) -> None:
        repo_root = default_tool_paths().workspace_root

        if scenario == "k3s-curl":
            def _run_k3s_curl_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step("Running k3s-curl E2E")
                flow = build_scenario_flow(
                    "k3s-curl",
                    repo_root=repo_root,
                )
                self._run_shared_flow(flow)
                success("k3s-curl E2E completed")

            self._run_live_workflow(
                title="E2E Scenarios",
                summary_lines=[f"Scenario: {scenario}"],
                planned_steps=["Build", "Deploy", "Verify"],
                action=_run_k3s_curl_workflow,
            )

        elif scenario == "helm-stack":
            def _run_helm_stack_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step("Running helm-stack E2E")
                flow = build_scenario_flow(
                    "helm-stack",
                    repo_root=repo_root,
                )
                self._run_shared_flow(flow)
                success("helm-stack E2E completed")

            self._run_live_workflow(
                title="E2E Scenarios",
                summary_lines=[f"Scenario: {scenario}"],
                planned_steps=["Run"],
                action=_run_helm_stack_workflow,
            )

        else:  # k8s-vm
            from controlplane_tool.e2e_models import E2eRequest
            from controlplane_tool.vm_models import VmRequest
            from controlplane_tool.e2e_runner import E2eRunner

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
            dry_run = _ask(
                lambda: questionary.confirm(
                    "Dry-run? (show plan without executing)", default=False, style=_STYLE
                ).ask()
            )

            request = E2eRequest(
                scenario="k8s-vm",
                runtime=runtime,
                vm=VmRequest(lifecycle="multipass", name=vm_name),
            )
            runner = E2eRunner(repo_root=repo_root)
            if dry_run:
                plan = runner.plan(request)
                step("k8s-vm E2E plan (dry-run)")
                _show_plan_table(plan)
                return

            plan = runner.plan(request)
            flow = build_scenario_flow(
                "k8s-vm",
                repo_root=repo_root,
                request=request,
            )

            def _run_k8s_vm_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                dashboard.append_log("Starting k8s-vm workflow")
                sink._update()
                self._run_shared_flow(flow)
                success("k8s-vm E2E completed")
                return plan

            self._run_live_workflow(
                title="E2E Scenarios",
                summary_lines=[
                    "Scenario: k8s-vm",
                    f"VM Name: {vm_name}",
                    f"Control-plane runtime: {runtime}",
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
            self._run_shared_flow(flow)
            success("container-local E2E completed")

        self._run_live_workflow(
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
            self._run_shared_flow(flow)
            success("deploy-host E2E completed")

        self._run_live_workflow(
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
        flow = build_scenario_flow(
            scenario,
            repo_root=default_tool_paths().workspace_root,
            request=request,
        )
        from controlplane_tool.e2e_runner import E2eRunner
        plan = E2eRunner(repo_root=default_tool_paths().workspace_root).plan(request)

        def _run_generic_e2e_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
            dashboard.append_log(f"Starting E2E scenario={scenario}")
            sink._update()
            self._run_shared_flow(flow)
            success(f"{scenario} E2E completed")
            return plan

        self._run_live_workflow(
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
            lambda: questionary.select(
                "Runner:",
                choices=[
                    questionary.Choice("vm — CLI E2E on k3s VM", "vm"),
                    questionary.Choice("host-platform — CLI on host vs cluster", "host"),
                ],
                style=_STYLE,
            ).ask()
        )

        repo_root = default_tool_paths().workspace_root

        if runner_choice == "vm":
            def _run_cli_vm_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step("Running CLI VM E2E")
                flow = build_scenario_flow(
                    "cli",
                    repo_root=repo_root,
                )
                self._run_shared_flow(flow)
                success("CLI VM E2E completed")

            self._run_live_workflow(
                title="CLI E2E",
                summary_lines=["Runner: vm"],
                planned_steps=["Build", "Deploy", "Verify"],
                action=_run_cli_vm_workflow,
            )
        else:
            def _run_cli_host_workflow(dashboard: WorkflowDashboard, sink: TuiWorkflowSink):
                step("Running CLI Host Platform E2E")
                flow = build_scenario_flow(
                    "cli-host",
                    repo_root=repo_root,
                )
                self._run_shared_flow(flow)
                success("CLI Host Platform E2E completed")

            self._run_live_workflow(
                title="CLI E2E",
                summary_lines=["Runner: host-platform"],
                planned_steps=["Build", "Deploy", "Verify"],
                action=_run_cli_host_workflow,
            )

    # ── LOAD TEST ─────────────────────────────────────────────────────────────

    def _loadtest_menu(self) -> None:
        from controlplane_tool.loadtest_catalog import list_load_profiles

        phase("Load Testing")

        action = _ask(
            lambda: questionary.select(
                "Action:",
                choices=[
                    questionary.Choice("run — run load test with profile", "run"),
                    questionary.Choice("plan — show plan without executing", "plan"),
                    questionary.Choice("new profile — interactive wizard", "new_profile"),
                ],
                style=_STYLE,
            ).ask()
        )

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
                profile_name = _ask(
                    lambda: questionary.select(
                        "Profile:", choices=saved, style=_STYLE
                    ).ask()
                )
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
                    self._apply_loadtest_step_event(dashboard, event)
                    sink._update()

                flow = build_loadtest_flow(
                    request.load_profile.name,
                    request=request,
                    event_listener=_on_event,
                )
                result = self._run_shared_flow(flow, allow_none_result=False)
                dashboard.append_log(f"Summary: {result.run_dir / 'summary.json'}")
                dashboard.append_log(f"Report: {result.run_dir / 'report.html'}")
                sink._update()
                if result.final_status == "passed":
                    success("Load test completed")
                    return result
                fail("Load test failed", detail=str(result.run_dir))
                raise RuntimeError(f"load test failed: {result.run_dir}")

            self._run_live_workflow(
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

        view = _ask(
            lambda: questionary.select(
                "View:",
                choices=[
                    questionary.Choice("All functions", "all"),
                    questionary.Choice("Presets", "presets"),
                    questionary.Choice("Function detail", "show"),
                ],
                style=_STYLE,
            ).ask()
        )

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
            key = _ask(
                lambda: questionary.select(
                    "Function:", choices=keys, style=_STYLE
                ).ask()
            )
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

        action = _ask(
            lambda: questionary.select(
                "Action:",
                choices=[
                    questionary.Choice("Create new profile", "new"),
                    questionary.Choice("View existing profile", "show"),
                    questionary.Choice("Delete profile", "delete"),
                ],
                style=_STYLE,
            ).ask()
        )

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
            name = _ask(
                lambda: questionary.select(
                    "Profile:", choices=saved, style=_STYLE
                ).ask()
            )
            profile = load_profile(name)
            _show_profile_table(profile)

        else:  # delete
            saved = list_profiles()
            if not saved:
                warning("No saved profiles.")
                return
            name = _ask(
                lambda: questionary.select(
                    "Profile to delete:", choices=saved, style=_STYLE
                ).ask()
            )
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
