#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.11"
# dependencies = ["questionary>=2.1.1"]
# ///

from __future__ import annotations

import os
import subprocess
import sys
from pathlib import Path

import questionary

sys.path.insert(0, str(Path(__file__).resolve().parent / "lib"))
from loadtest_registry_config import (  # noqa: E402
    InteractiveLoadtestConfig,
    normalize_tag_suffix,
    pick_latest_base_tag,
)


RUNTIME_CHOICES = [
    ("Java (Spring)", "java"),
    ("Java (Lite)", "java-lite"),
    ("Python", "python"),
    ("Exec/Bash", "exec"),
]

WORKLOAD_CHOICES = [
    ("Word Stats", "word-stats"),
    ("JSON Transform", "json-transform"),
]


def suggest_latest_project_tag(default_tag: str) -> str:
    repo_root = Path(__file__).resolve().parents[1]
    proc = subprocess.run(
        ["git", "-C", str(repo_root), "tag", "--list", "--sort=-v:refname"],
        check=False,
        capture_output=True,
        text=True,
    )
    if proc.returncode != 0:
        return default_tag
    tags = [line.strip() for line in proc.stdout.splitlines() if line.strip()]
    return pick_latest_base_tag(tags, fallback=default_tag)


def ask_non_empty_checkbox(message: str, choices: list[questionary.Choice]) -> list[str]:
    if not choices:
        raise SystemExit("Nessuna opzione disponibile per la selezione richiesta.")
    selected = questionary.checkbox(message, choices=choices).ask()
    if not selected:
        raise SystemExit("Selezione vuota: devi scegliere almeno un'opzione.")
    return selected


def choose_runtimes() -> list[str]:
    mode = questionary.select(
        "Come confrontare i runtime?",
        choices=[
            questionary.Choice("Matrice completa tra runtime selezionati", value="matrix"),
            questionary.Choice("Baseline + competitor", value="baseline"),
        ],
    ).ask()
    if mode is None:
        raise SystemExit(1)

    selected = ask_non_empty_checkbox(
        "Seleziona i runtime candidati:",
        choices=[questionary.Choice(label, value=value, checked=True) for label, value in RUNTIME_CHOICES],
    )

    if mode == "matrix":
        return selected
    if len(selected) < 2:
        raise SystemExit("La modalita baseline richiede almeno 2 runtime selezionati.")

    baseline = questionary.select(
        "Scegli la baseline:",
        choices=[questionary.Choice(label, value=value) for label, value in RUNTIME_CHOICES if value in selected],
    ).ask()
    if baseline is None:
        raise SystemExit(1)

    competitors = ask_non_empty_checkbox(
        "Seleziona i competitor da confrontare con la baseline:",
        choices=[
            questionary.Choice(label, value=value, checked=(value != baseline))
            for label, value in RUNTIME_CHOICES
            if value in selected and value != baseline
        ],
    )
    ordered = [baseline]
    ordered.extend([value for _, value in RUNTIME_CHOICES if value in competitors])
    return ordered


def choose_config() -> tuple[InteractiveLoadtestConfig, bool, str, str, str]:
    workloads = ask_non_empty_checkbox(
        "Quali workload vuoi includere?",
        choices=[questionary.Choice(label, value=value, checked=True) for label, value in WORKLOAD_CHOICES],
    )
    runtimes = choose_runtimes()

    invocation_mode = questionary.select(
        "Modalita invocazione:",
        choices=[
            questionary.Choice("Sincrono", value="sync"),
            questionary.Choice("Asincrono", value="async"),
            questionary.Choice("Entrambi (2 run separati)", value="both"),
        ],
    ).ask()
    if invocation_mode is None:
        raise SystemExit(1)

    stage_profile = questionary.select(
        "Durata workload (profilo k6):",
        choices=[
            questionary.Choice("Quick (~40s per test)", value="quick"),
            questionary.Choice("Standard (~110s per test)", value="standard"),
            questionary.Choice("Stress (~220s per test)", value="stress"),
            questionary.Choice("Custom (durata totale in secondi)", value="custom"),
        ],
    ).ask()
    if stage_profile is None:
        raise SystemExit(1)

    custom_total_seconds = None
    if stage_profile == "custom":
        value = questionary.text(
            "Durata totale workload (secondi, min 30):",
            validate=lambda txt: txt.isdigit() and int(txt) >= 30,
        ).ask()
        if value is None:
            raise SystemExit(1)
        custom_total_seconds = int(value)

    payload_mode = questionary.select(
        "Variabilita payload k6:",
        choices=[
            questionary.Choice("Pool sequenziale (raccomandato per benchmark)", value="pool-sequential"),
            questionary.Choice("Pool random", value="pool-random"),
            questionary.Choice("Legacy random (comportamento storico)", value="legacy-random"),
        ],
        default="pool-sequential",
    ).ask()
    if payload_mode is None:
        raise SystemExit(1)

    payload_pool_size = 5000
    if payload_mode != "legacy-random":
        pool_size_value = questionary.text(
            "Dimensione pool payload (min 1):",
            default="5000",
            validate=lambda txt: txt.isdigit() and int(txt) >= 1,
        ).ask()
        if pool_size_value is None:
            raise SystemExit(1)
        payload_pool_size = int(pool_size_value)

    skip_grafana = questionary.confirm("Saltare avvio Grafana locale?", default=True).ask()
    if skip_grafana is None:
        raise SystemExit(1)

    fallback_default_tag = os.environ.get("BASE_IMAGE_TAG", "v0.12.0")
    suggested_tag = suggest_latest_project_tag(fallback_default_tag)
    base_image_tag = questionary.text(
        "BASE_IMAGE_TAG (suggerito: ultimo tag del progetto):",
        default=suggested_tag,
        validate=lambda txt: bool(txt.strip()),
    ).ask()
    if base_image_tag is None:
        raise SystemExit(1)

    tag_suffix_choice = questionary.select(
        "TAG_SUFFIX (default arm64, supporta amd64):",
        choices=[
            questionary.Choice("arm64", value="arm64"),
            questionary.Choice("amd64", value="amd64"),
            questionary.Choice("none (nessun suffisso)", value="none"),
            questionary.Choice("custom", value="custom"),
        ],
    ).ask()
    if tag_suffix_choice is None:
        raise SystemExit(1)

    tag_suffix_raw = tag_suffix_choice
    if tag_suffix_choice == "custom":
        tag_suffix_raw = questionary.text(
            "Inserisci TAG_SUFFIX custom (es. arm64, amd64, release1):",
            validate=lambda txt: bool(txt.strip()),
        ).ask()
        if tag_suffix_raw is None:
            raise SystemExit(1)
    tag_suffix = normalize_tag_suffix(tag_suffix_raw)

    results_root_default = str(Path("k6/results").resolve())
    results_root = questionary.text(
        "Directory base risultati:",
        default=results_root_default,
        validate=lambda txt: bool(txt.strip()),
    ).ask()
    if results_root is None:
        raise SystemExit(1)

    config = InteractiveLoadtestConfig(
        workloads=workloads,
        runtimes=runtimes,
        invocation_mode=invocation_mode,
        stage_profile=stage_profile,
        custom_total_seconds=custom_total_seconds,
        payload_mode=payload_mode,
        payload_pool_size=payload_pool_size,
    )
    return config, bool(skip_grafana), results_root.strip(), base_image_tag.strip(), tag_suffix


def run_registry(
    config: InteractiveLoadtestConfig,
    skip_grafana: bool,
    results_root: str,
    base_image_tag: str,
    tag_suffix: str,
) -> int:
    script_path = Path(__file__).resolve().parent / "e2e-loadtest-registry.sh"
    modes = config.selected_modes()
    stage_sequence = config.stage_sequence()
    selected_tests = config.selected_tests()

    print("")
    print("Configurazione selezionata:")
    print(f"  Workloads: {','.join(config.workloads)}")
    print(f"  Runtimes: {','.join(config.runtimes)}")
    print(f"  Invocation mode: {config.invocation_mode}")
    print(f"  Stage profile: {config.stage_profile} ({stage_sequence})")
    print(f"  Payload mode: {config.payload_mode}")
    if config.payload_mode != "legacy-random":
        print(f"  Payload pool size: {config.payload_pool_size}")
    print(f"  BASE_IMAGE_TAG: {base_image_tag}")
    print(f"  TAG_SUFFIX: {tag_suffix or '<none>'}")
    print(f"  Selected tests: {len(selected_tests)} -> {', '.join(selected_tests)}")
    print(f"  Results root: {results_root}")
    print("")

    proceed = questionary.confirm("Avvia il test con questa configurazione?", default=True).ask()
    if not proceed:
        print("Esecuzione annullata.")
        return 0

    env = os.environ.copy()
    env["LOADTEST_WORKLOADS"] = ",".join(config.workloads)
    env["LOADTEST_RUNTIMES"] = ",".join(config.runtimes)
    env["K6_STAGE_SEQUENCE"] = stage_sequence
    env["BASE_IMAGE_TAG"] = base_image_tag
    env["TAG_SUFFIX"] = tag_suffix
    env.update(config.payload_env())
    if skip_grafana:
        env["SKIP_GRAFANA"] = "true"

    results_root_path = Path(results_root).expanduser().resolve()
    results_root_path.mkdir(parents=True, exist_ok=True)

    for mode in modes:
        mode_results = results_root_path / mode if len(modes) > 1 else results_root_path
        mode_results.mkdir(parents=True, exist_ok=True)
        env["INVOCATION_MODE"] = mode
        env["RESULTS_DIR_OVERRIDE"] = str(mode_results)

        print("")
        print(f"==> Run mode: {mode} | results: {mode_results}")
        completed = subprocess.run(["bash", str(script_path)], env=env, check=False)
        if completed.returncode != 0:
            print(f"Run fallita in modalita {mode} con exit code {completed.returncode}.", file=sys.stderr)
            return completed.returncode

    print("")
    print("Tutti i run completati.")
    for mode in modes:
        output_dir = results_root_path / mode if len(modes) > 1 else results_root_path
        print(f"  - {mode}: {output_dir}")
    return 0


def main() -> int:
    if not sys.stdout.isatty():
        print("Modalita interattiva richiede un terminale TTY.", file=sys.stderr)
        return 2

    config, skip_grafana, results_root, base_image_tag, tag_suffix = choose_config()
    return run_registry(config, skip_grafana, results_root, base_image_tag, tag_suffix)


if __name__ == "__main__":
    raise SystemExit(main())
