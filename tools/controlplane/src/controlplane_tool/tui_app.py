"""
tui_app.py — Interactive Rich TUI for the controlplane tool.

Launched automatically when `controlplane-tool` is invoked with no arguments.
All CLI commands remain available for scripted / CI use.
"""
from __future__ import annotations

import sys
import traceback
from pathlib import Path
from typing import Any

import questionary
from questionary import Style
from rich.markup import escape
from rich.panel import Panel
from rich.rule import Rule
from rich.table import Table

from controlplane_tool.console import console, fail, header, phase, skip, step, success, warning

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
                        "Cosa vuoi fare?",
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
                    fail(str(exc), detail=traceback.format_exc(limit=4))
        except KeyboardInterrupt:
            pass
        console.print("\n[dim]Bye.[/]\n")

    # ── BUILD ─────────────────────────────────────────────────────────────────

    def _build_menu(self) -> None:
        from controlplane_tool.cli_commands import GradleCommandExecutor

        phase("Build & Test")

        action = _ask(
            lambda: questionary.select(
                "Azione:",
                choices=[
                    questionary.Choice("jar — assemble JARs", "jar"),
                    questionary.Choice("build — compile + unit tests", "build"),
                    questionary.Choice("test — unit tests only", "test"),
                    questionary.Choice("run — avvia control-plane", "run"),
                    questionary.Choice("image — build OCI image", "image"),
                    questionary.Choice("native — build GraalVM native", "native"),
                    questionary.Choice("inspect — mostra configurazione", "inspect"),
                ],
                style=_STYLE,
            ).ask()
        )

        profile = _ask(
            lambda: questionary.select(
                "Profilo:",
                choices=["core", "all", "container-local"],
                default="core",
                style=_STYLE,
            ).ask()
        )

        dry_run = _ask(
            lambda: questionary.confirm(
                "Dry-run? (mostra solo il comando)", default=False, style=_STYLE
            ).ask()
        )

        step(f"Eseguo {action}", f"profile={profile}")
        executor = GradleCommandExecutor()
        result = executor.execute(
            action=action,
            profile=profile,
            modules=None,
            extra_gradle_args=[],
            dry_run=dry_run,
        )

        if dry_run:
            console.print(Panel(" ".join(result.command), title="Comando", border_style="dim"))
        elif result.return_code == 0:
            success(f"{action} completato")
        else:
            fail(f"{action} fallito", detail=f"exit code {result.return_code}")

    # ── VM ────────────────────────────────────────────────────────────────────

    def _vm_menu(self) -> None:
        from controlplane_tool.vm_adapter import VmOrchestrator
        from controlplane_tool.vm_models import VmRequest
        from controlplane_tool.paths import default_tool_paths

        phase("VM Management")

        action = _ask(
            lambda: questionary.select(
                "Azione:",
                choices=[
                    questionary.Choice("up — avvia / provisiona", "up"),
                    questionary.Choice("down — spegni e cancella", "down"),
                    questionary.Choice("sync — sincronizza il progetto", "sync"),
                    questionary.Choice("provision-base — installa dipendenze base", "provision-base"),
                    questionary.Choice("provision-k3s — installa k3s", "provision-k3s"),
                    questionary.Choice("inspect — mostra stato", "inspect"),
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
                    "Nome VM:", default="nanofaas-e2e", style=_STYLE
                ).ask()
            )
            host = None
        else:
            name = None
            host = _ask(
                lambda: questionary.text(
                    "Host remoto (IP/hostname):", style=_STYLE
                ).ask()
            )

        user = _ask(
            lambda: questionary.text("Utente SSH:", default="ubuntu", style=_STYLE).ask()
        )

        dry_run = _ask(
            lambda: questionary.confirm(
                "Dry-run?", default=False, style=_STYLE
            ).ask()
        )

        request = VmRequest(lifecycle=lifecycle, name=name, host=host, user=user)
        orchestrator = VmOrchestrator(default_tool_paths().workspace_root)

        step(f"Eseguo vm {action}", f"lifecycle={lifecycle}")

        if action == "up":
            result = orchestrator.ensure_running(request, dry_run=dry_run)
        elif action == "down":
            result = orchestrator.teardown(request, dry_run=dry_run)
        elif action == "sync":
            result = orchestrator.sync_project(request, dry_run=dry_run)
        elif action == "provision-base":
            install_helm = _ask(
                lambda: questionary.confirm(
                    "Installa Helm?", default=False, style=_STYLE
                ).ask()
            )
            result = orchestrator.install_dependencies(
                request, install_helm=install_helm, dry_run=dry_run
            )
        elif action == "provision-k3s":
            result = orchestrator.install_k3s(request, dry_run=dry_run)
        else:  # inspect
            result = orchestrator.inspect(request, dry_run=dry_run)

        if dry_run:
            console.print(Panel(" ".join(result.command), title="Comando", border_style="dim"))
        elif result.return_code == 0:
            if result.stdout:
                console.print(Panel(escape(result.stdout.strip()), title="Output", border_style="dim"))
            success(f"vm {action} completato")
        else:
            fail(f"vm {action} fallito", detail=result.stderr or f"exit code {result.return_code}")

    # ── E2E ───────────────────────────────────────────────────────────────────

    def _e2e_menu(self) -> None:
        phase("E2E Scenarios")

        scenario_choice = _ask(
            lambda: questionary.select(
                "Scenario:",
                choices=[
                    questionary.Choice("k8s-vm  — deploy su k3s in Multipass VM", "k8s-vm"),
                    questionary.Choice("k3s-curl — test curl-based in VM", "k3s-curl"),
                    questionary.Choice("helm-stack — Helm stack compatibility", "helm-stack"),
                    questionary.Choice("container-local — managed DEPLOYMENT locale", "container-local"),
                    questionary.Choice("deploy-host — deploy-host con registry locale", "deploy-host"),
                    questionary.Choice("docker — POOL locale con Docker", "docker"),
                    questionary.Choice("buildpack — buildpack POOL locale", "buildpack"),
                ],
                style=_STYLE,
            ).ask()
        )

        dry_run = _ask(
            lambda: questionary.confirm("Dry-run?", default=False, style=_STYLE).ask()
        )

        if scenario_choice in ("k3s-curl", "helm-stack", "k8s-vm"):
            self._run_vm_e2e(scenario_choice, dry_run=dry_run)
        elif scenario_choice == "container-local":
            self._run_container_local(dry_run=dry_run)
        elif scenario_choice == "deploy-host":
            self._run_deploy_host(dry_run=dry_run)
        else:
            self._run_generic_e2e(scenario_choice, dry_run=dry_run)

    def _run_vm_e2e(self, scenario: str, dry_run: bool) -> None:
        from controlplane_tool.paths import default_tool_paths

        repo_root = default_tool_paths().workspace_root

        if scenario == "k3s-curl":
            from controlplane_tool.k3s_curl_runner import K3sCurlRunner
            runner = K3sCurlRunner(repo_root=repo_root)
            step("Avvio k3s-curl E2E")
            runner.run()
            success("k3s-curl E2E completato")

        elif scenario == "helm-stack":
            from controlplane_tool.helm_stack_runner import HelmStackRunner
            runner = HelmStackRunner(repo_root=repo_root)
            step("Avvio helm-stack E2E")
            runner.run()
            success("helm-stack E2E completato")

        else:  # k8s-vm
            from controlplane_tool.e2e_runner import E2eRunner
            from controlplane_tool.e2e_models import E2eRequest
            from controlplane_tool.vm_models import VmRequest

            vm_name = _ask(
                lambda: questionary.text(
                    "Nome VM:", default="nanofaas-e2e", style=_STYLE
                ).ask()
            )
            runtime = _ask(
                lambda: questionary.select(
                    "Runtime control-plane:",
                    choices=["java", "rust"],
                    default="java",
                    style=_STYLE,
                ).ask()
            )

            request = E2eRequest(
                scenario="k8s-vm",
                runtime=runtime,
                vm=VmRequest(lifecycle="multipass", name=vm_name),
            )
            runner = E2eRunner(repo_root=repo_root)
            step("Avvio k8s-vm E2E")
            plan = runner.run(request)
            _show_plan_table(plan)

    def _run_container_local(self, dry_run: bool) -> None:
        from controlplane_tool.container_local_runner import ContainerLocalE2eRunner
        from controlplane_tool.paths import default_tool_paths

        step("Avvio container-local E2E")
        runner = ContainerLocalE2eRunner(repo_root=default_tool_paths().workspace_root)
        runner.run()
        success("container-local E2E completato")

    def _run_deploy_host(self, dry_run: bool) -> None:
        from controlplane_tool.deploy_host_runner import DeployHostE2eRunner
        from controlplane_tool.paths import default_tool_paths

        step("Avvio deploy-host E2E")
        runner = DeployHostE2eRunner(repo_root=default_tool_paths().workspace_root)
        result = runner.run()
        if result is not None and result.failed:
            fail("deploy-host E2E fallito")
        else:
            success("deploy-host E2E completato")

    def _run_generic_e2e(self, scenario: str, dry_run: bool) -> None:
        from controlplane_tool.e2e_runner import E2eRunner
        from controlplane_tool.e2e_models import E2eRequest
        from controlplane_tool.paths import default_tool_paths

        runtime = _ask(
            lambda: questionary.select(
                "Runtime:", choices=["java", "rust"], default="java", style=_STYLE
            ).ask()
        )

        request = E2eRequest(scenario=scenario, runtime=runtime)
        runner = E2eRunner(repo_root=default_tool_paths().workspace_root)
        step(f"Avvio E2E scenario={scenario}")
        plan = runner.run(request)
        _show_plan_table(plan)

    # ── CLI E2E ───────────────────────────────────────────────────────────────

    def _cli_e2e_menu(self) -> None:
        from controlplane_tool.paths import default_tool_paths

        phase("CLI E2E")

        runner_choice = _ask(
            lambda: questionary.select(
                "Runner:",
                choices=[
                    questionary.Choice("vm — CLI E2E su VM k3s", "vm"),
                    questionary.Choice("host-platform — CLI su host vs cluster", "host"),
                ],
                style=_STYLE,
            ).ask()
        )

        repo_root = default_tool_paths().workspace_root

        if runner_choice == "vm":
            from controlplane_tool.cli_vm_runner import CliVmRunner
            step("Avvio CLI VM E2E")
            CliVmRunner(repo_root=repo_root).run()
            success("CLI VM E2E completato")
        else:
            from controlplane_tool.cli_host_runner import CliHostPlatformRunner
            step("Avvio CLI Host Platform E2E")
            CliHostPlatformRunner(repo_root=repo_root).run()
            success("CLI Host Platform E2E completato")

    # ── LOAD TEST ─────────────────────────────────────────────────────────────

    def _loadtest_menu(self) -> None:
        from controlplane_tool.loadtest_catalog import list_load_profiles
        from controlplane_tool.profiles import list_profiles

        phase("Load Testing")

        action = _ask(
            lambda: questionary.select(
                "Azione:",
                choices=[
                    questionary.Choice("run — esegui load test con profilo", "run"),
                    questionary.Choice("plan — mostra piano senza eseguire", "plan"),
                    questionary.Choice("nuovo profilo — wizard interattivo", "new_profile"),
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
                    "Usa un profilo salvato?", default=True, style=_STYLE
                ).ask()
            )
            if use_saved:
                profile_name = _ask(
                    lambda: questionary.select(
                        "Profilo:", choices=saved, style=_STYLE
                    ).ask()
                )
                from controlplane_tool.profiles import load_profile
                profile = load_profile(profile_name)
            else:
                profile = self._build_profile_interactive("default")
        else:
            warning("Nessun profilo salvato trovato, apertura wizard…")
            profile = self._build_profile_interactive("default")

        from controlplane_tool.loadtest_commands import build_loadtest_request, run_loadtest_request
        request = build_loadtest_request(profile=profile)

        if action == "plan":
            _show_loadtest_plan(request)
        else:
            step("Avvio load test")
            run_loadtest_request(request, dry_run=False)
            success("Load test completato")

    # ── FUNCTIONS ─────────────────────────────────────────────────────────────

    def _functions_menu(self) -> None:
        from controlplane_tool.function_catalog import list_functions, list_function_presets

        phase("Function Catalog")

        view = _ask(
            lambda: questionary.select(
                "Visualizza:",
                choices=[
                    questionary.Choice("Tutte le funzioni", "all"),
                    questionary.Choice("Preset", "presets"),
                    questionary.Choice("Dettaglio singola funzione", "show"),
                ],
                style=_STYLE,
            ).ask()
        )

        if view == "all":
            functions = list_functions()
            table = Table(title="Funzioni disponibili", border_style="cyan dim")
            table.add_column("Key", style="cyan bold")
            table.add_column("Family", style="dim")
            table.add_column("Runtime", style="green")
            for fn in functions:
                table.add_row(fn.key, fn.family, fn.runtime)
            console.print(table)

        elif view == "presets":
            presets = list_function_presets()
            table = Table(title="Preset disponibili", border_style="cyan dim")
            table.add_column("Nome", style="cyan bold")
            table.add_column("Descrizione", style="dim")
            table.add_column("Funzioni", style="green")
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
                    "Funzione:", choices=keys, style=_STYLE
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
            table = Table(title=f"Funzione: {fn.key}", border_style="cyan dim", show_header=False)
            table.add_column("Campo", style="dim")
            table.add_column("Valore", style="cyan")
            for label, value in rows:
                table.add_row(label, escape(str(value)))
            console.print(table)

    # ── PROFILE MANAGER ───────────────────────────────────────────────────────

    def _profile_menu(self) -> None:
        from controlplane_tool.profiles import list_profiles, load_profile, save_profile

        phase("Profile Manager")

        action = _ask(
            lambda: questionary.select(
                "Azione:",
                choices=[
                    questionary.Choice("Crea nuovo profilo", "new"),
                    questionary.Choice("Visualizza profilo esistente", "show"),
                    questionary.Choice("Elimina profilo", "delete"),
                ],
                style=_STYLE,
            ).ask()
        )

        if action == "new":
            name = _ask(
                lambda: questionary.text(
                    "Nome profilo:", default="default", style=_STYLE
                ).ask()
            )
            profile = self._build_profile_interactive(name)
            dest = save_profile(profile)
            success(f"Profilo '{name}' salvato", detail=str(dest))

        elif action == "show":
            saved = list_profiles()
            if not saved:
                warning("Nessun profilo salvato.")
                return
            name = _ask(
                lambda: questionary.select(
                    "Profilo:", choices=saved, style=_STYLE
                ).ask()
            )
            profile = load_profile(name)
            _show_profile_table(profile)

        else:  # delete
            saved = list_profiles()
            if not saved:
                warning("Nessun profilo salvato.")
                return
            name = _ask(
                lambda: questionary.select(
                    "Profilo da eliminare:", choices=saved, style=_STYLE
                ).ask()
            )
            confirm = _ask(
                lambda: questionary.confirm(
                    f"Elimina '{name}'?", default=False, style=_STYLE
                ).ask()
            )
            if confirm:
                from controlplane_tool.profiles import profile_path
                profile_path(name).unlink(missing_ok=True)
                success(f"Profilo '{name}' eliminato")

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
        table = Table(title="Piano di esecuzione", border_style="cyan dim")
        table.add_column("#", style="dim", justify="right")
        table.add_column("Step", style="cyan")
        table.add_column("Stato", justify="center")
        for i, s in enumerate(steps, 1):
            label = getattr(s, "name", None) or getattr(s, "label", None) or str(s)
            status_val = getattr(s, "status", "—")
            table.add_row(str(i), escape(str(label)), escape(str(status_val)))
        console.print(table)
    else:
        console.print(Panel(escape(str(plan)), title="Piano", border_style="dim"))


def _show_loadtest_plan(request: Any) -> None:
    table = Table(title="Piano Load Test", border_style="cyan dim", show_header=False)
    table.add_column("Campo", style="dim")
    table.add_column("Valore", style="cyan")
    for attr in ("load_profile", "scenario", "metrics_gate", "runs_root"):
        val = getattr(request, attr, None)
        if val is not None:
            table.add_row(attr, escape(str(val)))
    console.print(table)


def _show_profile_table(profile: Any) -> None:
    table = Table(
        title=f"Profilo: {escape(profile.name)}",
        border_style="cyan dim",
        show_header=False,
    )
    table.add_column("Campo", style="dim")
    table.add_column("Valore", style="cyan")
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
