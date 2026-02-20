#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import os
from pathlib import Path
import subprocess
import sys

import questionary


REPO_ROOT = Path(__file__).resolve().parents[2]
SCRIPTS_DIR = REPO_ROOT / "scripts"
EXPERIMENTS_DIR = REPO_ROOT / "experiments"
sys.path.insert(0, str(EXPERIMENTS_DIR / "lib"))

from control_plane_experiment_config import (  # noqa: E402
    build_deploy_env,
    discover_module_dependencies,
    normalize_module_selection,
    resolve_module_selection_with_dependencies,
    split_module_selection_details,
)
from loadtest_registry_config import (  # noqa: E402
    InteractiveLoadtestConfig,
)


WORKLOAD_CHOICES = [
    ("Word Stats", "word-stats"),
    ("JSON Transform", "json-transform"),
]

RUNTIME_CHOICES = [
    ("Java (Spring)", "java"),
    ("Java (Lite)", "java-lite"),
    ("Python", "python"),
    ("Exec/Bash", "exec"),
]

DEFAULT_VM_NAME = "nanofaas-e2e"
DEFAULT_CPUS = "4"
DEFAULT_MEMORY = "12G"
DEFAULT_DISK = "30G"
DEFAULT_NAMESPACE = "nanofaas"


@dataclass(frozen=True)
class DeployConfig:
    vm_name: str
    cpus: str
    memory: str
    disk: str
    namespace: str
    keep_vm: bool
    tag: str
    selected_modules: list[str]
    explicitly_selected_modules: list[str]
    auto_added_modules: list[str]


@dataclass(frozen=True)
class WizardConfig:
    deploy: DeployConfig
    run_loadtest: bool
    loadtest: InteractiveLoadtestConfig | None
    skip_grafana: bool


def discover_control_plane_modules_with_dependencies() -> tuple[list[str], dict[str, list[str]]]:
    modules_root = REPO_ROOT / "control-plane-modules"
    dependencies = discover_module_dependencies(modules_root)
    return sorted(dependencies.keys()), dependencies


def ask_checkbox(message: str, choices: list[questionary.Choice], allow_empty: bool = False) -> list[str]:
    selected = questionary.checkbox(message, choices=choices).ask()
    if selected is None:
        raise SystemExit(1)
    if not selected and not allow_empty:
        raise SystemExit("Selezione vuota non consentita.")
    return list(selected)


def ask_text(message: str, default: str, validator) -> str:
    value = questionary.text(message, default=default, validate=validator).ask()
    if value is None:
        raise SystemExit(1)
    return value.strip()


def ask_deploy_config() -> DeployConfig:
    available_modules, module_dependencies = discover_control_plane_modules_with_dependencies()
    if not available_modules:
        raise SystemExit("Nessun modulo control-plane trovato in control-plane-modules/.")

    selected_raw = ask_checkbox(
        "Seleziona i moduli control-plane da includere (nessuno = core-only):",
        choices=[
            questionary.Choice(module_name, value=module_name, checked=True)
            for module_name in available_modules
        ],
        allow_empty=True,
    )
    explicitly_selected_modules = normalize_module_selection(available_modules, selected_raw)
    selected_modules = resolve_module_selection_with_dependencies(
        available_modules=available_modules,
        selected_modules=explicitly_selected_modules,
        module_dependencies=module_dependencies,
    )
    explicit_modules, auto_added = split_module_selection_details(
        resolved_modules=selected_modules,
        explicitly_selected_modules=explicitly_selected_modules,
    )
    if auto_added:
        print("")
        print(f"Dipendenze aggiunte automaticamente: {', '.join(auto_added)}")

    suggested_tag = f"exp-{datetime.now().strftime('%Y%m%d-%H%M%S')}"
    tag = ask_text(
        "Tag immagine locale:",
        default=suggested_tag,
        validator=lambda txt: bool(txt.strip()),
    )

    vm_name = ask_text(
        "VM_NAME:",
        default=DEFAULT_VM_NAME,
        validator=lambda txt: bool(txt.strip()),
    )
    cpus = ask_text("CPUS:", default=DEFAULT_CPUS, validator=lambda txt: txt.isdigit() and int(txt) >= 1)
    memory = ask_text(
        "MEMORY (es. 8G):",
        default=DEFAULT_MEMORY,
        validator=lambda txt: bool(txt.strip()),
    )
    disk = ask_text(
        "DISK (es. 30G):",
        default=DEFAULT_DISK,
        validator=lambda txt: bool(txt.strip()),
    )
    namespace = ask_text(
        "Namespace Kubernetes:",
        default=DEFAULT_NAMESPACE,
        validator=lambda txt: bool(txt.strip()),
    )
    keep_vm = questionary.confirm("Tenere la VM al termine?", default=True).ask()
    if keep_vm is None:
        raise SystemExit(1)

    return DeployConfig(
        vm_name=vm_name,
        cpus=cpus,
        memory=memory,
        disk=disk,
        namespace=namespace,
        keep_vm=bool(keep_vm),
        tag=tag,
        selected_modules=selected_modules,
        explicitly_selected_modules=explicit_modules,
        auto_added_modules=auto_added,
    )


def ask_loadtest_config() -> tuple[bool, InteractiveLoadtestConfig | None, bool]:
    run_loadtest = questionary.confirm("Eseguire anche i load test dopo il deploy?", default=True).ask()
    if run_loadtest is None:
        raise SystemExit(1)
    if not run_loadtest:
        return False, None, True

    workloads = ask_checkbox(
        "Quali workload vuoi testare?",
        choices=[questionary.Choice(label, value=value, checked=True) for label, value in WORKLOAD_CHOICES],
    )
    runtimes = ask_checkbox(
        "Quali runtime vuoi testare?",
        choices=[questionary.Choice(label, value=value, checked=True) for label, value in RUNTIME_CHOICES],
    )

    invocation_mode = questionary.select(
        "Modalita invocazione:",
        choices=[
            questionary.Choice("sync", value="sync"),
            questionary.Choice("async", value="async"),
            questionary.Choice("both (run separati)", value="both"),
        ],
        default="sync",
    ).ask()
    if invocation_mode is None:
        raise SystemExit(1)

    stage_profile = questionary.select(
        "Profilo stages k6:",
        choices=[
            questionary.Choice("quick", value="quick"),
            questionary.Choice("standard", value="standard"),
            questionary.Choice("stress", value="stress"),
            questionary.Choice("custom", value="custom"),
        ],
        default="quick",
    ).ask()
    if stage_profile is None:
        raise SystemExit(1)

    custom_total_seconds = None
    if stage_profile == "custom":
        custom_raw = ask_text(
            "Durata totale custom (secondi, >=30):",
            default="120",
            validator=lambda txt: txt.isdigit() and int(txt) >= 30,
        )
        custom_total_seconds = int(custom_raw)

    max_vus_raw = ask_text(
        "Picco massimo VU:",
        default="20",
        validator=lambda txt: txt.isdigit() and int(txt) >= 1,
    )
    max_vus = int(max_vus_raw)

    payload_mode = questionary.select(
        "Modalita payload:",
        choices=[
            questionary.Choice("pool-sequential", value="pool-sequential"),
            questionary.Choice("pool-random", value="pool-random"),
            questionary.Choice("legacy-random", value="legacy-random"),
        ],
        default="pool-sequential",
    ).ask()
    if payload_mode is None:
        raise SystemExit(1)

    payload_pool_size = 5000
    if payload_mode != "legacy-random":
        payload_pool_size_raw = ask_text(
            "Dimensione pool payload:",
            default="5000",
            validator=lambda txt: txt.isdigit() and int(txt) >= 1,
        )
        payload_pool_size = int(payload_pool_size_raw)

    skip_grafana = questionary.confirm("Saltare Grafana locale durante load test?", default=True).ask()
    if skip_grafana is None:
        raise SystemExit(1)

    return (
        True,
        InteractiveLoadtestConfig(
            workloads=workloads,
            runtimes=runtimes,
            invocation_mode=invocation_mode,
            stage_profile=stage_profile,
            custom_total_seconds=custom_total_seconds,
            max_vus=max_vus,
            payload_mode=payload_mode,
            payload_pool_size=payload_pool_size,
        ),
        bool(skip_grafana),
    )


def ask_config() -> WizardConfig:
    deploy = ask_deploy_config()
    run_loadtest, loadtest, skip_grafana = ask_loadtest_config()
    return WizardConfig(
        deploy=deploy,
        run_loadtest=run_loadtest,
        loadtest=loadtest,
        skip_grafana=skip_grafana,
    )


def run_cmd(cmd: list[str], env: dict[str, str]) -> None:
    print("")
    print("$ " + " ".join(cmd))
    result = subprocess.run(cmd, cwd=str(REPO_ROOT), env=env, check=False)
    if result.returncode != 0:
        raise SystemExit(result.returncode)


def print_summary(config: WizardConfig) -> None:
    modules_label = ",".join(config.deploy.selected_modules) if config.deploy.selected_modules else "none (core-only)"
    manual_label = ",".join(config.deploy.explicitly_selected_modules) if config.deploy.explicitly_selected_modules else "none"
    auto_label = ",".join(config.deploy.auto_added_modules) if config.deploy.auto_added_modules else "none"
    print("")
    print("Configurazione esperimento (dettagliata):")
    print(f"  VM: {config.deploy.vm_name}")
    print(f"  Risorse VM: {config.deploy.cpus} CPU, {config.deploy.memory} RAM, {config.deploy.disk} disk")
    print(f"  Namespace: {config.deploy.namespace}")
    print(f"  Image tag control-plane: {config.deploy.tag}")
    print(f"  Keep VM a fine run: {config.deploy.keep_vm}")
    print("  Modalita build/deploy:")
    print("    - Compilazione control-plane: nativa (obbligatoria)")
    print("    - Build immagine control-plane: host (Docker Desktop)")
    print("    - Scope deploy: solo control-plane (demo disabilitate)")
    print("  Moduli control-plane:")
    print(f"    - Finali (con dipendenze): {modules_label}")
    print(f"    - Selezionati manualmente: {manual_label}")
    print(f"    - Aggiunti per dipendenza: {auto_label}")
    print("  Piano esecuzione:")
    print("    1. Build immagine control-plane nativa su host")
    print("    2. Creazione VM k3s e registry locale")
    print("    3. Copia/push immagine nel registry della VM")
    print("    4. Deploy Helm piattaforma")
    print("    5. Verifica health control-plane + Prometheus")
    if config.run_loadtest and config.loadtest is not None:
        print("  Load test: enabled")
        print(f"    Workloads: {','.join(config.loadtest.workloads)}")
        print(f"    Runtimes: {','.join(config.loadtest.runtimes)}")
        print(f"    Invocation: {config.loadtest.invocation_mode}")
        print(f"    Stages: {config.loadtest.stage_sequence()}")
        print(f"    Payload mode: {config.loadtest.payload_mode}")
    else:
        print("  Load test: disabled")


def run_deploy(config: DeployConfig) -> None:
    env = os.environ.copy()
    env.update(
        build_deploy_env(
            vm_name=config.vm_name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
            namespace=config.namespace,
            keep_vm=config.keep_vm,
            tag=config.tag,
            selected_modules=config.selected_modules,
        )
    )
    run_cmd(["bash", str(SCRIPTS_DIR / "e2e-k3s-helm.sh")], env)


def run_loadtests(config: WizardConfig) -> None:
    if not config.run_loadtest or config.loadtest is None:
        return

    base_env = os.environ.copy()
    base_env["VM_NAME"] = config.deploy.vm_name
    base_env["SKIP_GRAFANA"] = "true" if config.skip_grafana else "false"
    base_env["LOADTEST_WORKLOADS"] = ",".join(config.loadtest.workloads)
    base_env["LOADTEST_RUNTIMES"] = ",".join(config.loadtest.runtimes)
    base_env["K6_STAGE_SEQUENCE"] = config.loadtest.stage_sequence()
    base_env.update(config.loadtest.payload_env())

    for mode in config.loadtest.selected_modes():
        env = dict(base_env)
        env["INVOCATION_MODE"] = mode
        run_cmd(["bash", str(EXPERIMENTS_DIR / "e2e-loadtest.sh")], env)


def main() -> int:
    config = ask_config()
    print_summary(config)
    proceed = questionary.confirm("Avviare deploy (e opzionalmente load test) con questa configurazione?", default=True).ask()
    if proceed is None or not proceed:
        print("Operazione annullata.")
        return 1

    run_deploy(config.deploy)
    run_loadtests(config)
    print("")
    print("Esperimento completato.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
