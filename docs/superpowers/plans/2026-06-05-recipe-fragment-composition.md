# Recipe-Fragment Composition (Phase A) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the six flat-tuple `ScenarioRecipe` definitions in `recipes.py` with composition from named fragments, producing byte-for-byte identical `component_ids` (behavior-preserving), and collapsing the three near-identical loadtest recipes into one shared definition.

**Architecture:** Define module-level fragment tuples (`BASE_PROVISION`, `STACK_PRELUDE`, `LOADTEST_TAIL`) in `recipes.py` and build each recipe by tuple concatenation `fragment + delta`. A golden characterization test pins the exact current `component_ids` of all six scenarios as the regression guard. No execution changes; this is Phase A of the loadtest-scenario-unification spec.

**Tech Stack:** Python 3.12, `pytest`, `uv`, `workflow_tasks.components.models.ScenarioRecipe`.

**Spec:** `docs/superpowers/specs/2026-06-05-loadtest-scenario-unification-design.md` (§2.1, §3 Phase A).

**Scope:** ONLY `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`. The scenarios' hand-built prelude tuples (`_TWO_VM_STACK_PRELUDE_COMPONENTS`, `_PROXMOX_LOADTEST_PRELUDE_COMPONENTS`) are NOT touched here — they feed execution and are handled in Phase B1. This plan changes no runtime behavior.

---

## File Structure

- `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py` — add fragment constants; rebuild `_SCENARIO_RECIPES` via composition. `build_scenario_recipe` unchanged.
- `tools/controlplane/tests/test_recipe_fragments.py` (new) — golden component_ids guard + loadtest-recipes-identical assertion.

---

## Task 1: Golden characterization test (regression guard)

This is a characterization test: it pins the CURRENT exact `component_ids` of all six recipes and PASSES immediately (before any refactor). It is the safety net that guarantees Task 2's refactor is byte-for-byte behavior-preserving.

**Files:**
- Create: `tools/controlplane/tests/test_recipe_fragments.py`

- [ ] **Step 1: Write the golden test**

```python
from __future__ import annotations

from controlplane_tool.scenario.components.recipes import build_scenario_recipe

# Golden snapshot of every scenario's exact component_ids as of 2026-06-05.
# This pins current behavior so the fragment refactor (Task 2) stays byte-for-byte identical.
GOLDEN: dict[str, tuple[str, ...]] = {
    "k3s-junit-curl": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "tests.run_k3s_curl_checks", "tests.run_k8s_junit",
        "cleanup.uninstall_function_runtime", "cleanup.uninstall_control_plane",
        "namespace.uninstall", "vm.down",
    ),
    "helm-stack": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "loadtest.install_k6", "loadtest.run", "experiments.autoscaling",
    ),
    "two-vm-loadtest": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "cli.build_install_dist", "cli.fn_apply_selected",
        "loadgen.ensure_running", "loadgen.provision_base", "loadgen.install_k6",
        "loadgen.run_k6", "metrics.prometheus_snapshot", "loadtest.write_report",
        "loadgen.down", "vm.down",
    ),
    "azure-vm-loadtest": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "cli.build_install_dist", "cli.fn_apply_selected",
        "loadgen.ensure_running", "loadgen.provision_base", "loadgen.install_k6",
        "loadgen.run_k6", "metrics.prometheus_snapshot", "loadtest.write_report",
        "loadgen.down", "vm.down",
    ),
    "proxmox-vm-loadtest": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "helm.deploy_control_plane", "helm.deploy_function_runtime",
        "cli.build_install_dist", "cli.fn_apply_selected",
        "loadgen.ensure_running", "loadgen.provision_base", "loadgen.install_k6",
        "loadgen.run_k6", "metrics.prometheus_snapshot", "loadtest.write_report",
        "loadgen.down", "vm.down",
    ),
    "cli-stack": (
        "vm.ensure_running", "vm.provision_base", "repo.sync_to_vm",
        "registry.ensure_container", "images.build_core", "images.build_selected_functions",
        "k3s.install", "k3s.configure_registry", "namespace.install",
        "cli.build_install_dist", "cli.platform_install", "cli.platform_status",
        "cli.fn_apply_selected", "cli.fn_list_selected", "cli.fn_invoke_selected",
        "cli.fn_enqueue_selected", "cli.fn_delete_selected",
        "cleanup.uninstall_control_plane", "namespace.uninstall",
        "cleanup.verify_cli_platform_status_fails", "vm.down",
    ),
}


def test_recipe_component_ids_match_golden() -> None:
    for name, expected in GOLDEN.items():
        assert build_scenario_recipe(name).component_ids == expected, name


def test_all_recipes_have_golden_entry() -> None:
    # Guard against a new scenario being added without updating the golden snapshot.
    from controlplane_tool.scenario.components.recipes import _SCENARIO_RECIPES

    assert set(_SCENARIO_RECIPES) == set(GOLDEN)
```

- [ ] **Step 2: Run the test to verify it PASSES now (characterization)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_recipe_fragments.py -v`
Expected: PASS (it pins current behavior; this is a regression guard, not red-green TDD).

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/tests/test_recipe_fragments.py
git commit -m "test(recipes): golden component_ids guard before fragment refactor"
```

---

## Task 2: Compose recipes from fragments

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py`
- Test: `tools/controlplane/tests/test_recipe_fragments.py`

- [ ] **Step 1: Add a test asserting the three loadtest recipes are identical**

Append to `tools/controlplane/tests/test_recipe_fragments.py`:

```python
def test_loadtest_recipes_are_identical() -> None:
    # The fragment refactor collapses the three loadtest recipes to one shared definition.
    two_vm = build_scenario_recipe("two-vm-loadtest").component_ids
    azure = build_scenario_recipe("azure-vm-loadtest").component_ids
    proxmox = build_scenario_recipe("proxmox-vm-loadtest").component_ids
    assert two_vm == azure == proxmox
```

- [ ] **Step 2: Run it to verify it passes (they are already identical today)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_recipe_fragments.py::test_loadtest_recipes_are_identical -v`
Expected: PASS (the three tuples are already equal; this locks the intent for the refactor).

- [ ] **Step 3: Rewrite `recipes.py` using fragment composition**

Replace the entire body of `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py` with:

```python
from __future__ import annotations

from workflow_tasks.components.models import ScenarioRecipe

# ── Reusable recipe fragments ────────────────────────────────────────────────
# Provisioning shared by every managed-VM scenario, up to and including the
# namespace Helm release (the last step before scenarios diverge).
BASE_PROVISION: tuple[str, ...] = (
    "vm.ensure_running",
    "vm.provision_base",
    "repo.sync_to_vm",
    "registry.ensure_container",
    "images.build_core",
    "images.build_selected_functions",
    "k3s.install",
    "k3s.configure_registry",
    "namespace.install",
)

# Deploy nanofaas via Helm (control-plane + function-runtime). Used by every
# scenario except cli-stack, which installs through the CLI instead.
HELM_DEPLOY: tuple[str, ...] = (
    "helm.deploy_control_plane",
    "helm.deploy_function_runtime",
)

# Full Helm-based stack prelude (provision + deploy).
STACK_PRELUDE: tuple[str, ...] = BASE_PROVISION + HELM_DEPLOY

# Shared tail for the loadgen-based loadtest scenarios: build the CLI, register
# functions, then run the k6 load test from the loadgen VM and tear down.
LOADTEST_TAIL: tuple[str, ...] = (
    "cli.build_install_dist",
    "cli.fn_apply_selected",
    "loadgen.ensure_running",
    "loadgen.provision_base",
    "loadgen.install_k6",
    "loadgen.run_k6",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
    "loadgen.down",
    "vm.down",
)

# The three loadtest scenarios share one recipe shape (they differ only by
# lifecycle/connectivity at execution time, not by component list).
_LOADTEST_COMPONENT_IDS: tuple[str, ...] = STACK_PRELUDE + LOADTEST_TAIL


def _loadtest_recipe(name: str) -> ScenarioRecipe:
    return ScenarioRecipe(
        name=name,
        component_ids=_LOADTEST_COMPONENT_IDS,
        requires_managed_vm=True,
    )


_SCENARIO_RECIPES: dict[str, ScenarioRecipe] = {
    "k3s-junit-curl": ScenarioRecipe(
        name="k3s-junit-curl",
        component_ids=STACK_PRELUDE
        + (
            "tests.run_k3s_curl_checks",
            "tests.run_k8s_junit",
            "cleanup.uninstall_function_runtime",
            "cleanup.uninstall_control_plane",
            "namespace.uninstall",
            "vm.down",
        ),
        requires_managed_vm=True,
    ),
    "helm-stack": ScenarioRecipe(
        name="helm-stack",
        component_ids=STACK_PRELUDE
        + (
            "loadtest.install_k6",
            "loadtest.run",
            "experiments.autoscaling",
        ),
        requires_managed_vm=True,
    ),
    "two-vm-loadtest": _loadtest_recipe("two-vm-loadtest"),
    "azure-vm-loadtest": _loadtest_recipe("azure-vm-loadtest"),
    "proxmox-vm-loadtest": _loadtest_recipe("proxmox-vm-loadtest"),
    "cli-stack": ScenarioRecipe(
        name="cli-stack",
        component_ids=BASE_PROVISION
        + (
            "cli.build_install_dist",
            "cli.platform_install",
            "cli.platform_status",
            "cli.fn_apply_selected",
            "cli.fn_list_selected",
            "cli.fn_invoke_selected",
            "cli.fn_enqueue_selected",
            "cli.fn_delete_selected",
            "cleanup.uninstall_control_plane",
            "namespace.uninstall",
            "cleanup.verify_cli_platform_status_fails",
            "vm.down",
        ),
        requires_managed_vm=True,
    ),
}


def build_scenario_recipe(name: str) -> ScenarioRecipe:
    try:
        recipe = _SCENARIO_RECIPES[name]
    except KeyError as exc:
        raise ValueError(f"Unsupported scenario recipe: {name}") from exc

    return ScenarioRecipe(
        name=recipe.name,
        component_ids=tuple(recipe.component_ids),
        requires_managed_vm=recipe.requires_managed_vm,
    )
```

- [ ] **Step 4: Run the golden + identity tests (must stay green)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_recipe_fragments.py -v`
Expected: PASS (golden component_ids unchanged; loadtest recipes identical).

- [ ] **Step 5: Run the existing recipe + scenario suites (no regressions)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_recipes.py tools/controlplane/tests/test_scenario_component_library.py tools/controlplane/tests/test_two_vm_loadtest_plan.py tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py tools/controlplane/tests/test_e2e_catalog.py -v`
Expected: PASS (membership/ordering tests still hold because component_ids are byte-for-byte identical).

- [ ] **Step 6: Run the full controlplane suite (final safety net)**

Run: `cd /Users/micheleciavotta/Downloads/mcFaas && uv run --project tools/controlplane pytest tools/controlplane/tests -q`
Expected: PASS (1128+ passed; no behavior changed).

- [ ] **Step 7: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/components/recipes.py tools/controlplane/tests/test_recipe_fragments.py
git commit -m "refactor(recipes): compose scenario recipes from shared fragments"
```

---

## Self-Review

- **Spec coverage (§2.1, §3 Phase A):** fragments `BASE_PROVISION`/`STACK_PRELUDE`/`LOADTEST_TAIL` defined (Task 2 Step 3); `recipes.py` rebuilt as fragment+delta; three loadtest recipes collapsed to one shared `_LOADTEST_COMPONENT_IDS` (Task 2) with an explicit identity test; behavior-preserving guaranteed by the golden test (Task 1). The hand-built scenario prelude tuples are explicitly deferred to Phase B1 (stated in Scope) — not a gap.
- **Placeholder scan:** no TBD/TODO; the full replacement body of `recipes.py` is provided verbatim; golden tuples transcribed from the current file.
- **Type consistency:** fragments are `tuple[str, ...]`; `STACK_PRELUDE = BASE_PROVISION + HELM_DEPLOY` and `_LOADTEST_COMPONENT_IDS = STACK_PRELUDE + LOADTEST_TAIL` are tuple concatenations; `build_scenario_recipe` signature/behavior unchanged; `ScenarioRecipe(name, component_ids, requires_managed_vm)` matches `workflow_tasks.components.models.ScenarioRecipe`.
- **Manual cross-check of composition vs golden:**
  - k3s-junit-curl = STACK_PRELUDE(11) + 6 = 17 ✓
  - helm-stack = STACK_PRELUDE(11) + 3 = 14 ✓
  - two-vm/azure/proxmox = STACK_PRELUDE(11) + LOADTEST_TAIL(10) = 21 ✓
  - cli-stack = BASE_PROVISION(9) + 12 = 21 ✓
