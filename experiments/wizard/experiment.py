#!/usr/bin/env python3
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import shlex
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
DEFAULT_LOCAL_REGISTRY = "localhost:5000"
CONTROL_PLANE_CACHE_ROOT = REPO_ROOT / "experiments" / ".image-cache/control-plane"


@dataclass(frozen=True)
class DeployConfig:
    vm_name: str
    cpus: str
    memory: str
    disk: str
    namespace: str
    keep_vm: bool
    tag: str
    control_plane_native_build: bool
    selected_modules: list[str]
    explicitly_selected_modules: list[str]
    auto_added_modules: list[str]


@dataclass(frozen=True)
class WizardConfig:
    deploy: DeployConfig
    run_loadtest: bool
    loadtest: InteractiveLoadtestConfig | None
    skip_grafana: bool
    host_rebuild_images: bool


def docker_image_exists(image_ref: str) -> bool:
    cmd = f"docker image inspect {shlex.quote(image_ref)} >/dev/null 2>&1"
    result = subprocess.run(["bash", "-lc", cmd], cwd=str(REPO_ROOT), check=False)
    return result.returncode == 0


def docker_image_id(image_ref: str) -> str | None:
    cmd = f"docker image inspect --format='{{{{.Id}}}}' {shlex.quote(image_ref)}"
    result = subprocess.run(
        ["bash", "-lc", cmd],
        cwd=str(REPO_ROOT),
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    image_id = result.stdout.strip()
    return image_id or None


def build_host_control_plane_image_ref(
    *,
    control_plane_native_build: bool,
    selected_modules: list[str],
) -> str:
    build_mode = "native" if control_plane_native_build else "jvm"
    modules_selector = ",".join(selected_modules) if selected_modules else "none"
    fingerprint_input = f"{build_mode}|{modules_selector}"
    fingerprint = hashlib.sha256(fingerprint_input.encode("utf-8")).hexdigest()[:12]
    return f"nanofaas/control-plane:host-{build_mode}-{fingerprint}"


def control_plane_compat_manifest_path(
    *,
    control_plane_native_build: bool,
    selected_modules: list[str],
) -> Path:
    build_mode = "native" if control_plane_native_build else "jvm"
    modules_selector = ",".join(selected_modules) if selected_modules else "none"
    modules_hash = hashlib.sha256(modules_selector.encode("utf-8")).hexdigest()[:12]
    return CONTROL_PLANE_CACHE_ROOT / build_mode / modules_hash / "manifest.json"


def control_plane_cache_manifest_is_valid(
    *,
    manifest_path: Path,
    image_ref: str,
    expected_build_mode: str,
    expected_modules: list[str],
) -> bool:
    if not manifest_path.is_file():
        return False
    try:
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception:
        return False

    image_id = docker_image_id(image_ref)
    if image_id is None:
        return False

    expected_modules_sorted = sorted(expected_modules)
    manifest_modules = payload.get("selected_modules")
    if not isinstance(manifest_modules, list):
        return False
    manifest_modules_sorted = sorted(str(item).strip() for item in manifest_modules if str(item).strip())

    return (
        payload.get("build_mode") == expected_build_mode
        and payload.get("image_ref") == image_ref
        and payload.get("image_id") == image_id
        and manifest_modules_sorted == expected_modules_sorted
    )


def docker_latest_image_for_repository(repo: str) -> str | None:
    cmd = f"docker image ls {shlex.quote(repo)} --format '{{{{.Repository}}}}:{{{{.Tag}}}}'"
    result = subprocess.run(
        ["bash", "-lc", cmd],
        cwd=str(REPO_ROOT),
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        return None
    for line in result.stdout.splitlines():
        ref = line.strip()
        if not ref or ref.endswith(":<none>"):
            continue
        return ref
    return None


def resolve_reusable_control_plane_image_ref(
    *,
    control_plane_native_build: bool,
    selected_modules: list[str],
) -> str | None:
    manifest_path = control_plane_compat_manifest_path(
        control_plane_native_build=control_plane_native_build,
        selected_modules=selected_modules,
    )
    if not manifest_path.is_file():
        return None
    try:
        payload = json.loads(manifest_path.read_text(encoding="utf-8"))
    except Exception:
        return None
    image_ref = payload.get("image_ref")
    image_id = payload.get("image_id")
    if not isinstance(image_ref, str) or not image_ref.strip():
        return None
    if not isinstance(image_id, str) or not image_id.strip():
        return None
    actual_image_id = docker_image_id(image_ref)
    if actual_image_id is None or actual_image_id != image_id:
        return None
    build_mode = "native" if control_plane_native_build else "jvm"
    if payload.get("build_mode") != build_mode:
        return None
    manifest_modules = payload.get("selected_modules")
    if not isinstance(manifest_modules, list):
        return None
    expected_modules_sorted = sorted(selected_modules)
    manifest_modules_sorted = sorted(str(item).strip() for item in manifest_modules if str(item).strip())
    if manifest_modules_sorted != expected_modules_sorted:
        return None
    return image_ref


def required_host_images_for_selection(
    *,
    tag: str,
    control_plane_native_build: bool,
    selected_modules: list[str],
    run_loadtest: bool,
    loadtest: InteractiveLoadtestConfig | None,
) -> list[str]:
    images = [
        build_host_control_plane_image_ref(
            control_plane_native_build=control_plane_native_build,
            selected_modules=selected_modules,
        )
    ]
    if not run_loadtest or loadtest is None:
        return images

    images.append(f"{DEFAULT_LOCAL_REGISTRY}/nanofaas/function-runtime:{tag}")
    for test_name in loadtest.selected_tests():
        if test_name.endswith("-java-lite"):
            workload = test_name[: -len("-java-lite")]
            images.append(f"{DEFAULT_LOCAL_REGISTRY}/nanofaas/java-lite-{workload}:{tag}")
        elif test_name.endswith("-java"):
            workload = test_name[: -len("-java")]
            images.append(f"{DEFAULT_LOCAL_REGISTRY}/nanofaas/java-{workload}:{tag}")
        elif test_name.endswith("-python"):
            workload = test_name[: -len("-python")]
            images.append(f"{DEFAULT_LOCAL_REGISTRY}/nanofaas/python-{workload}:{tag}")
        elif test_name.endswith("-exec"):
            workload = test_name[: -len("-exec")]
            images.append(f"{DEFAULT_LOCAL_REGISTRY}/nanofaas/bash-{workload}:{tag}")
    return images


def ask_host_rebuild_images(
    *,
    tag: str,
    control_plane_native_build: bool,
    selected_modules: list[str],
    run_loadtest: bool,
    loadtest: InteractiveLoadtestConfig | None,
) -> bool:
    required_images = required_host_images_for_selection(
        tag=tag,
        control_plane_native_build=control_plane_native_build,
        selected_modules=selected_modules,
        run_loadtest=run_loadtest,
        loadtest=loadtest,
    )
    control_plane_image_ref = required_images[0]

    present_images: list[str] = []
    reusable_images: list[tuple[str, str]] = []
    missing_images: list[str] = []
    for index, image_ref in enumerate(required_images):
        if not docker_image_exists(image_ref):
            if index == 0:
                reusable_ref = resolve_reusable_control_plane_image_ref(
                    control_plane_native_build=control_plane_native_build,
                    selected_modules=selected_modules,
                )
                if reusable_ref:
                    reusable_images.append((image_ref, reusable_ref))
                    continue
            repo = image_ref.rsplit(":", 1)[0]
            reusable_ref = docker_latest_image_for_repository(repo)
            if reusable_ref:
                reusable_images.append((image_ref, reusable_ref))
                continue
            missing_images.append(image_ref)
            continue
        present_images.append(image_ref)

    if present_images:
        print("")
        print("Immagini host gia presenti localmente:")
        for image_ref in present_images:
            print(f"  - {image_ref}")
    if reusable_images:
        print("")
        print("Immagini riusabili da tag differente (retag automatico):")
        for target_ref, source_ref in reusable_images:
            print(f"  - {target_ref} <= {source_ref}")

    if not missing_images and (present_images or reusable_images):
        rebuild = questionary.confirm(
            "Le immagini host richieste sono gia presenti/riusabili localmente. Vuoi comunque ricompilarle?",
            default=False,
        ).ask()
        if rebuild is None:
            raise SystemExit(1)
        return bool(rebuild)

    if (present_images or reusable_images) and missing_images:
        print("")
        print("Alcune immagini non sono presenti localmente: verranno compilate.")
        for image_ref in missing_images:
            print(f"  - {image_ref}")
        rebuild = questionary.confirm(
            "Vuoi comunque ricompilare anche le immagini gia presenti?",
            default=False,
        ).ask()
        if rebuild is None:
            raise SystemExit(1)
        return bool(rebuild)

    print("")
    print("Immagini host mancanti: verranno compilate automaticamente.")
    for image_ref in missing_images:
        print(f"  - {image_ref}")
    return True


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

    build_mode = questionary.select(
        "Build control-plane:",
        choices=[
            questionary.Choice("JVM (piu veloce)", value=False),
            questionary.Choice("Native (piu lenta, latenza minore)", value=True),
        ],
        default=False,
    ).ask()
    if build_mode is None:
        raise SystemExit(1)

    return DeployConfig(
        vm_name=vm_name,
        cpus=cpus,
        memory=memory,
        disk=disk,
        namespace=namespace,
        keep_vm=bool(keep_vm),
        tag=tag,
        control_plane_native_build=bool(build_mode),
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
    host_rebuild_images = ask_host_rebuild_images(
        tag=deploy.tag,
        control_plane_native_build=deploy.control_plane_native_build,
        selected_modules=deploy.selected_modules,
        run_loadtest=run_loadtest,
        loadtest=loadtest,
    )
    return WizardConfig(
        deploy=deploy,
        run_loadtest=run_loadtest,
        loadtest=loadtest,
        skip_grafana=skip_grafana,
        host_rebuild_images=host_rebuild_images,
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
    build_mode_label = "nativa" if config.deploy.control_plane_native_build else "JVM"
    control_plane_only = not config.run_loadtest
    scope_label = (
        "solo control-plane (demo disabilitate)"
        if control_plane_only
        else "piattaforma completa (demo abilitate per load test)"
    )
    image_build_label = (
        "control-plane, runtime e demo su host"
        if not control_plane_only
        else "control-plane su host; nessun build runtime/demo"
    )
    print("")
    print("Configurazione esperimento (dettagliata):")
    print(f"  VM: {config.deploy.vm_name}")
    print(f"  Risorse VM: {config.deploy.cpus} CPU, {config.deploy.memory} RAM, {config.deploy.disk} disk")
    print(f"  Namespace: {config.deploy.namespace}")
    print(f"  Image tag control-plane: {config.deploy.tag}")
    print(f"  Keep VM a fine run: {config.deploy.keep_vm}")
    print("  Modalita build/deploy:")
    print(f"    - Compilazione control-plane: {build_mode_label} (selezionabile)")
    print("    - Build immagine control-plane: host (Docker Desktop)")
    print(f"    - Build immagini host: {'ricompila' if config.host_rebuild_images else 'riuso immagini locali'}")
    print(f"    - Strategia build immagini: {image_build_label}")
    print(f"    - Scope deploy: {scope_label}")
    print("  Moduli control-plane:")
    print(f"    - Finali (con dipendenze): {modules_label}")
    print(f"    - Selezionati manualmente: {manual_label}")
    print(f"    - Aggiunti per dipendenza: {auto_label}")
    print("  Piano esecuzione:")
    print(f"    1. Build immagine control-plane {build_mode_label} su host")
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
        if not config.deploy.keep_vm:
            print("    Nota: la VM verra mantenuta fino al termine dei load test e poi rimossa")
    else:
        print("  Load test: disabled")


def run_deploy(
    config: DeployConfig,
    keep_vm: bool,
    run_loadtest: bool,
    loadtest: InteractiveLoadtestConfig | None,
    host_rebuild_images: bool,
) -> None:
    control_plane_only = not run_loadtest
    loadtest_workloads = ",".join(loadtest.workloads) if loadtest is not None else ",".join([item[1] for item in WORKLOAD_CHOICES])
    loadtest_runtimes = ",".join(loadtest.runtimes) if loadtest is not None else ",".join([item[1] for item in RUNTIME_CHOICES])
    env = os.environ.copy()
    env.update(
        build_deploy_env(
            vm_name=config.vm_name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
            namespace=config.namespace,
            keep_vm=keep_vm,
            tag=config.tag,
            control_plane_native_build=config.control_plane_native_build,
            control_plane_only=control_plane_only,
            host_rebuild_images=host_rebuild_images,
            loadtest_workloads=loadtest_workloads,
            loadtest_runtimes=loadtest_runtimes,
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


def cleanup_vm(vm_name: str) -> None:
    env = os.environ.copy()
    env["VM_NAME"] = vm_name
    env["KEEP_VM"] = "false"
    common_script = SCRIPTS_DIR / "lib" / "e2e-k3s-common.sh"
    cmd = (
        f"source {shlex.quote(str(common_script))}; "
        "e2e_set_log_prefix wizard; "
        "e2e_cleanup_vm"
    )
    print("")
    print(f"$ bash -lc {shlex.quote(cmd)}")
    result = subprocess.run(["bash", "-lc", cmd], cwd=str(REPO_ROOT), env=env, check=False)
    if result.returncode != 0:
        raise SystemExit(result.returncode)


def main() -> int:
    config = ask_config()
    print_summary(config)
    proceed = questionary.confirm("Avviare deploy (e opzionalmente load test) con questa configurazione?", default=True).ask()
    if proceed is None or not proceed:
        print("Operazione annullata.")
        return 1

    keep_vm_during_deploy = config.deploy.keep_vm or config.run_loadtest
    try:
        run_deploy(
            config.deploy,
            keep_vm=keep_vm_during_deploy,
            run_loadtest=config.run_loadtest,
            loadtest=config.loadtest,
            host_rebuild_images=config.host_rebuild_images,
        )
        run_loadtests(config)
    finally:
        if not config.deploy.keep_vm:
            cleanup_vm(config.deploy.vm_name)
    print("")
    print("Esperimento completato.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
