from __future__ import annotations

import pytest

from controlplane_tool.scenario.catalog import (
    SCENARIOS,
    canonical_scenario_name,
    resolve_scenario,
)

# Grows in Tasks 2-4 as each category is renamed; final state = the spec table.
RENAME_PAIRS: list[tuple[str, str]] = [
    ("helm-stack", "loadtest-helm-legacy"),
    ("one-vm-helm-loadtest", "loadtest-one-vm"),
    ("two-vm-loadtest", "loadtest-two-vm"),
    ("azure-vm-loadtest", "loadtest-azure"),
    ("proxmox-vm-loadtest", "loadtest-proxmox"),
    ("k3s-junit-curl", "validate-k3s"),
    ("container-local", "validate-container-local"),
    ("docker", "validate-docker-pool"),
    ("buildpack", "validate-buildpack-pool"),
    ("deploy-host", "validate-deploy-host"),
    ("cli", "cli-suite"),
]


def test_canonical_name_passthrough_for_canonical_and_unknown() -> None:
    assert canonical_scenario_name("cli-stack") == "cli-stack"
    # Unknown names pass through unchanged so existing validation errors stay intact.
    assert canonical_scenario_name("does-not-exist") == "does-not-exist"


def test_every_alias_resolves_to_its_canonical_definition() -> None:
    for scenario in SCENARIOS:
        for alias in scenario.aliases:
            assert canonical_scenario_name(alias) == scenario.name
            assert resolve_scenario(canonical_scenario_name(alias)).name == scenario.name


def test_no_alias_collides_with_a_canonical_name() -> None:
    canonical = {s.name for s in SCENARIOS}
    for scenario in SCENARIOS:
        for alias in scenario.aliases:
            assert alias not in canonical


@pytest.mark.parametrize(("old", "new"), RENAME_PAIRS)
def test_rename_pairs_resolve(old: str, new: str) -> None:
    assert canonical_scenario_name(old) == new


def test_no_stale_scenario_names_in_sources() -> None:
    """Old scenario IDs may only survive as alias data; src/ must be canonical-only.

    Scope: this guard checks only the UNAMBIGUOUS multi-word old scenario names
    (the ones with no legitimate non-scenario meaning). It deliberately excludes
    the bare-word old names ("docker", "buildpack", "cli", "deploy-host",
    "container-local") because those strings have legitimate non-scenario uses
    in src (container-runtime words, typer app names, cli-test family ids,
    control-plane config keys, runner step-ids, ProfileName values) — a naive
    grep would flag all of those false positives. Those aliases are already
    exercised by test_every_alias_resolves_to_its_canonical_definition and
    test_rename_pairs_resolve above, so coverage of a half-rename for those
    names is not lost.
    """
    import re
    from pathlib import Path

    # Multi-word old names: no legitimate non-scenario use, so any survival
    # of these literal strings outside the alias table is a real half-rename.
    unambiguous_old_names = [
        old
        for old, _ in RENAME_PAIRS
        if old
        in {
            "k3s-junit-curl",
            "one-vm-helm-loadtest",
            "two-vm-loadtest",
            "azure-vm-loadtest",
            "proxmox-vm-loadtest",
            "helm-stack",
        }
    ]

    roots = [
        Path(__file__).resolve().parents[1] / "src",
        Path(__file__).resolve().parents[2] / "workflow-tasks" / "src",
    ]
    offenders: list[str] = []
    for root in roots:
        for path in root.rglob("*.py"):
            text = path.read_text(encoding="utf-8")
            for old in unambiguous_old_names:
                for m in re.finditer(rf'["\']({re.escape(old)})["\']', text):
                    line = text.count("\n", 0, m.start()) + 1
                    offenders.append(f"{path.name}:{line}:{old}")

    allowed = {
        # alias tuples live here (ScenarioDefinition(..., aliases=(...)))
        "catalog.py",
        # TWO_VM_REMOTE_DIR_NAME = "two-vm-loadtest" is a remote directory name
        # on the VM filesystem, not a scenario id -- legitimate non-scenario use.
        "two_vm.py",
    }
    real = [o for o in offenders if o.split(":")[0] not in allowed]
    assert real == [], real
