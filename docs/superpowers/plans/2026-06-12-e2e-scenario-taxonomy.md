# E2E Scenario Taxonomy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement `docs/superpowers/specs/2026-06-12-e2e-scenario-taxonomy-design.md`: purpose-first canonical scenario names with deprecated aliases, two-tier descriptions (`description` + `details`), and the TUI menu reorganization (loadtests move to `Loadtest → vm`).

**Architecture:** Normalize at the boundaries, canonical everywhere inside. Task 1 builds the alias machinery as a no-op (empty alias tables) so every later task stays green. Tasks 2–4 flip names category-by-category (loadtest, validate, cli) — each task renames the catalog entry, updates every internal key for that group, and extends the alias contract test, ending with a green full suite. Task 5 reorganizes the TUI menus, Task 6 lands the `details` texts, Task 7 adds the no-stale-names guard and updates docs.

**Tech Stack:** Python (`controlplane_tool`, `workflow_tasks`), typer, pytest via `uv`.

---

## Conventions

- Repo root `/Users/micheleciavotta/Downloads/mcFaas`; feature branch `refactor/scenario-taxonomy` (worktree optional via `superpowers:using-git-worktrees`).
- Tests: `uv run --project tools/controlplane pytest tools/controlplane/tests -q` and `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests` (always the FULL workflow-tasks suite — coverage gate). These suites are NOT in CI; local green is the only gate.
- Spec is the source of truth for names/descriptions/details/menu map: `docs/superpowers/specs/2026-06-12-e2e-scenario-taxonomy-design.md`.
- Commit after every task.

## Rename pairs (authoritative)

```
k3s-junit-curl        -> validate-k3s
container-local       -> validate-container-local
docker                -> validate-docker-pool
buildpack             -> validate-buildpack-pool
deploy-host           -> validate-deploy-host
helm-stack            -> loadtest-helm-legacy
one-vm-helm-loadtest  -> loadtest-one-vm
two-vm-loadtest       -> loadtest-two-vm
azure-vm-loadtest     -> loadtest-azure
proxmox-vm-loadtest   -> loadtest-proxmox
cli                   -> cli-suite
cli-stack             -> cli-stack      (unchanged)
cli-host              -> cli-host      (unchanged)
```

## ⚠️ Lookalikes that must NOT be renamed

- **`cli-test` namespace** (`CliTestScenarioName` in `core/models.py:16`: `unit`, `cli-stack`, `host-platform`, `deploy-host`) — a SEPARATE command family. Its `deploy-host`/`host-platform` strings stay. Only the *e2e* scenario names change.
- **`TWO_VM_REMOTE_DIR_NAME`** (`workflow_tasks/loadtest/two_vm.py`) and any literal used as a REMOTE PATH (`/home/<user>/two-vm-loadtest/...`) — filesystem names, not scenario IDs.
- **Run-dir naming** (`tools/controlplane/runs/<ts>-two-vm-loadtest`) — if derived from a constant, leave it; if derived from the scenario name it will change naturally and that is acceptable.
- **Sample manifests** `tools/controlplane/scenarios/*.toml` keep their old `base_scenario` values on purpose (alias regression fixtures), as do the tests that load them (`test_scenario_loader.py`).
- **Preset names** (`demo-java`, `demo-loadtest`) — out of scope.
- **Historical docs** under `docs/plans/`, `docs/superpowers/plans|specs/` — never rewrite history.

Decision rule for every grep hit: rename only where the string is used as an *e2e scenario identifier*.

---

### Task 1: alias machinery (no-op foundation)

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/catalog.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py` (scenario arg of `e2e run`, `--only/--skip` of `e2e all`)
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_loader.py` (`base_scenario`)
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py` (dispatch entry)
- Test: create `tools/controlplane/tests/test_scenario_aliases.py`

- [ ] **Step 1: failing test** — create `tools/controlplane/tests/test_scenario_aliases.py`:

```python
from __future__ import annotations

import pytest

from controlplane_tool.scenario.catalog import (
    SCENARIOS,
    canonical_scenario_name,
    resolve_scenario,
)

# Grows in Tasks 2-4 as each category is renamed; final state = the spec table.
RENAME_PAIRS: list[tuple[str, str]] = []


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
```

- [ ] **Step 2: run → FAIL** (ImportError: `canonical_scenario_name`; `aliases` attribute missing):
`uv run --project tools/controlplane pytest tools/controlplane/tests/test_scenario_aliases.py -q`

- [ ] **Step 3: implement in `catalog.py`**

1. Add to `ScenarioDefinition`: `aliases: tuple[str, ...] = ()` and `details: str = ""` (after `grouped_phases`).
2. After `SCENARIO_INDEX`, add:

```python
_ALIAS_INDEX: dict[str, str] = {
    alias: scenario.name for scenario in SCENARIOS for alias in scenario.aliases
}


def canonical_scenario_name(name: str) -> str:
    """Map a deprecated scenario alias to its canonical name.

    Canonical and unknown names pass through unchanged (unknown names must keep
    failing in resolve_scenario with the existing error message). Callers at the
    user-facing boundaries (CLI args, scenario files, TUI dispatch) are expected
    to call this; internal code only ever sees canonical names.
    """
    canonical = _ALIAS_INDEX.get(name)
    if canonical is not None:
        import sys

        print(f"note: scenario '{name}' is deprecated, use '{canonical}'", file=sys.stderr)
        return canonical
    return name
```

- [ ] **Step 4: hook the three boundaries** (all no-ops while alias tables are empty)

1. `cli/e2e_commands.py` — at the top of the `e2e run` command body (function at ~line 558), first statement touching the scenario value: `scenario = canonical_scenario_name(scenario)`. In `e2e all`, map the parsed `--only`/`--skip` CSV lists through `canonical_scenario_name` right after parsing. Add the import from `controlplane_tool.scenario.catalog`.
2. `scenario/scenario_loader.py` — where `base_scenario=spec.base_scenario` is read from the parsed file (~line 91), wrap: `base_scenario=canonical_scenario_name(spec.base_scenario) if spec.base_scenario else spec.base_scenario`. READ the surrounding code first; if `base_scenario` is consumed in more than one place, normalize once at parse time instead.
3. `tui/app.py` — in the E2E scenarios loop where `scenario_choice` is read from the menu (the `if scenario_choice in (...)` dispatch, ~line 986), insert `scenario_choice = canonical_scenario_name(scenario_choice)` right after the back-check. (TUI values will already be canonical after Task 5; this hook is the safety net.)

- [ ] **Step 5: run the new test file → PASS; full controlplane suite → green; commit**

```bash
git add tools/controlplane
git commit -m "feat(scenario): alias machinery — canonical_scenario_name + aliases/details fields (no-op)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: rename the loadtest group (5 scenarios)

Pairs for this task:

```
helm-stack            -> loadtest-helm-legacy
one-vm-helm-loadtest  -> loadtest-one-vm
two-vm-loadtest       -> loadtest-two-vm
azure-vm-loadtest     -> loadtest-azure
proxmox-vm-loadtest   -> loadtest-proxmox
```

**Files (the inventoried blast radius for this group):**
- `tools/controlplane/src/controlplane_tool/scenario/catalog.py` (names + aliases + spec one-liner descriptions)
- `tools/controlplane/src/controlplane_tool/core/models.py` (`ScenarioName` Literal + any VALID/ordered scenario lists)
- `tools/controlplane/src/controlplane_tool/scenario/components/recipes.py` (`_SCENARIO_RECIPES` keys + `_loadtest_recipe` names)
- `tools/controlplane/src/controlplane_tool/scenario/scenario_defaults.py` (`_DEFAULTS` keys: `helm-stack`, `one-vm-helm-loadtest`)
- `tools/workflow-tasks/src/workflow_tasks/loadtest/two_vm.py` (`LOADTEST_SCENARIOS` set — NOT `TWO_VM_REMOTE_DIR_NAME`)
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` (`plan`/`plan_all` string branches)
- `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py` (branches + `e2e.<name>` flow ids)
- `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`, `one_vm_loadtest_adapter.py`, `scenario/scenarios/{two_vm_loadtest,azure_vm_loadtest,one_vm_helm_loadtest,helm_stack,...}.py` (scenario-name checks; module FILENAMES stay — renaming Python modules is churn without benefit)
- `tools/controlplane/src/controlplane_tool/cli/e2e_commands.py` (`_default_selection_for` set, stack/loadgen name defaults keyed on scenario, `_AZURE_STACK_NODE_PORTS` guard, `_resolve_run_request` special cases)
- `tools/controlplane/src/controlplane_tool/tui/app.py` (the two membership sets + azure/proxmox branches — VALUES become canonical here; labels are Task 5)
- Tests: recipe/catalog goldens (`test_recipe_fragments.py` GOLDEN keys, `test_e2e_catalog.py` expected names), `test_e2e_runner.py`, `test_loadtest_flow.py`, `test_e2e_commands.py`, `test_tui_choices.py`, `test_scenario_recipes.py`, plus `RENAME_PAIRS` in `test_scenario_aliases.py`

- [ ] **Step 1: extend the contract test first** — in `test_scenario_aliases.py` set:

```python
RENAME_PAIRS = [
    ("helm-stack", "loadtest-helm-legacy"),
    ("one-vm-helm-loadtest", "loadtest-one-vm"),
    ("two-vm-loadtest", "loadtest-two-vm"),
    ("azure-vm-loadtest", "loadtest-azure"),
    ("proxmox-vm-loadtest", "loadtest-proxmox"),
]
```

Run the file → the 5 parametrized cases FAIL (aliases not registered yet).

- [ ] **Step 2: catalog flip** — for each pair in `catalog.py`: `name=<new>`, add `aliases=("<old>",)`, replace `description` with the spec's one-liner. Update the `ScenarioName` Literal in `core/models.py` to the new names (the Literal holds CANONICAL names only — aliases never enter typed land) and any ordered scenario list in that module.

- [ ] **Step 3: flip every internal key for the 5 names.** Mechanical procedure, per old name:

```bash
grep -rn --include="*.py" "<old-name>" tools/controlplane/src tools/workflow-tasks/src
```

and apply the decision rule (scenario identifier → new name; lookalike → leave). Expected hits per the inventory above. Notable specifics:
- `recipes.py`: dict keys AND the `name=` inside each `ScenarioRecipe` (e.g. `name="one-vm-helm-loadtest-stack"` → `"loadtest-one-vm-stack"`).
- `scenario_flows.py`: flow ids are `f"e2e.{scenario.replace('-', '_')}"` — derived, no literal change; the `if scenario == "..."` branches DO change.
- `e2e_commands.py` stack-name defaults: the `if scenario == "azure-vm-loadtest" and stack_name is None` style branches → new names (VM names like `nanofaas-azure` themselves stay).
- `two_vm.py`: only `LOADTEST_SCENARIOS` entries; `TWO_VM_REMOTE_DIR_NAME` and the `TWO_VM_*_NODE_PORT` constants stay.

- [ ] **Step 4: flip the test expectations** — same grep over `tools/controlplane/tests` and `tools/workflow-tasks/tests`; goldens (`test_recipe_fragments.GOLDEN`, `test_e2e_catalog` expected list order), plan-shape tests, TUI dispatch tests → canonical names. EXCEPTIONS (keep old): `test_scenario_loader.py` manifest assertions, anything loading `tools/controlplane/scenarios/*.toml`, and `test_scenario_aliases.py` RENAME_PAIRS lefthand sides.

- [ ] **Step 5: full suites green** (`controlplane` + `workflow-tasks`), including an alias smoke:

```bash
uv run --project tools/controlplane controlplane-tool e2e run two-vm-loadtest --dry-run 2>err.log; grep deprecated err.log
uv run --project tools/controlplane controlplane-tool e2e run loadtest-two-vm --dry-run
```

Both must print the same plan; the first must print the deprecation note.

- [ ] **Step 6: commit**

```bash
git add tools/controlplane tools/workflow-tasks
git commit -m "refactor(scenario): loadtest group renamed (loadtest-{one-vm,two-vm,azure,proxmox,helm-legacy}) with aliases

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: rename the validate group (5 scenarios)

Pairs:

```
k3s-junit-curl   -> validate-k3s
container-local  -> validate-container-local
docker           -> validate-docker-pool
buildpack        -> validate-buildpack-pool
deploy-host      -> validate-deploy-host
```

Same 6-step procedure as Task 2 (extend `RENAME_PAIRS` → red; catalog flip + Literal; internal keys; test expectations; suites + alias smoke `e2e run k3s-junit-curl --dry-run` vs `e2e run validate-k3s --dry-run`; commit). Group-specific cautions:

- **`docker` and `buildpack` are dangerous greps** (the words appear everywhere). Grep for them ONLY as exact scenario identifiers: `grep -rn --include='*.py' -e '"docker"' -e "'docker'" tools/controlplane/src` and inspect each hit; same for `"buildpack"`. Scenario-identifier hits live in: catalog, `core/models.py` Literal, `e2e_runner.plan` branches (the docker/buildpack runner dispatch), recipes (if present), TUI `_PLATFORM_LOCAL_RUNTIME_CHOICES` values, gradle-side `-PrunE2e`-style wiring if any (grep `e2e run docker` in `*.gradle` too — `grep -rn "e2e run docker\|runE2e" build.gradle */build.gradle | head`).
- **`deploy-host`**: the e2e scenario renames; the `cli-test` suite name `deploy-host` in `CliTestScenarioName` and `cli_test` command modules DOES NOT. Inspect every hit's namespace before touching.
- **`container-local`**: also a Helm/runtime-adapter term (`nanofaas.container-local.*` config keys, `container-deployment-provider`) — those are CONTROL-PLANE config keys, not scenario IDs: leave them. Only the scenario identifier hits change.
- Docs commands like `e2e run docker` in CLAUDE.md/README are Task 7.

Commit message: `refactor(scenario): validate group renamed (validate-{k3s,container-local,docker-pool,buildpack-pool,deploy-host}) with aliases`.

---

### Task 4: rename `cli` → `cli-suite`

Same procedure, single pair, extend `RENAME_PAIRS` with `("cli", "cli-suite")`. Caution: `"cli"` as a bare string is even more grep-hostile than docker — the scenario-identifier surface is small: catalog, `ScenarioName` Literal, `scenario_task_ids` special set in `scenario_flows.py` (`{"container-local", "deploy-host", "cli", "cli-host"}` — note these were renamed in Task 3 already; this set must end as `{"validate-container-local", "validate-deploy-host", "cli-suite", "cli-host"}`), `e2e_runner` branch, TUI choice value. Alias smoke: `e2e run cli --dry-run` → deprecation note + same plan as `cli-suite`.

Commit: `refactor(scenario): cli -> cli-suite with alias`.

---

### Task 5: TUI menu reorganization

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Tests: `tools/controlplane/tests/test_tui_scenario_choices.py`, `test_tui_choices.py`

Target state (spec menu map):

```
Validation → platform: validate-k3s, validate-container-local, validate-docker-pool, validate-buildpack-pool
Validation → cli:      cli-stack, cli-suite, cli-host
Validation → host:     validate-deploy-host
Loadtest   → local:    existing run / plan / new_profile actions (unchanged)
Loadtest   → vm:       loadtest-one-vm, loadtest-two-vm, loadtest-azure, loadtest-proxmox, loadtest-helm-legacy (last)
```

- [ ] **Step 1: failing tests** — replace/extend `tools/controlplane/tests/test_tui_scenario_choices.py`:

```python
from __future__ import annotations

from controlplane_tool.tui import app as tui_app


def _values(choices) -> list[str]:
    return [c.value for c in choices]


def test_platform_menu_holds_only_validations() -> None:
    assert _values(tui_app._PLATFORM_VALIDATION_CHOICES) == [
        "validate-k3s",
        "validate-container-local",
        "validate-docker-pool",
        "validate-buildpack-pool",
    ]


def test_loadtest_vm_menu_lists_loadtests_legacy_last() -> None:
    assert _values(tui_app._LOADTEST_VM_CHOICES) == [
        "loadtest-one-vm",
        "loadtest-two-vm",
        "loadtest-azure",
        "loadtest-proxmox",
        "loadtest-helm-legacy",
    ]


def test_loadtest_menu_forks_local_and_vm() -> None:
    values = _values(tui_app._LOADTEST_ACTION_CHOICES)
    assert "local" in values and "vm" in values
```

Run → FAIL (`_LOADTEST_VM_CHOICES` missing; platform list still contains loadtests).

- [ ] **Step 2: restructure `app.py`**

1. `_PLATFORM_VALIDATION_CHOICES`: keep/move only the 4 validations (docker/buildpack move IN from `_PLATFORM_LOCAL_RUNTIME_CHOICES`, which is then deleted or kept as the source of those two entries — read how it's surfaced today and fold accordingly), values = canonical names, labels = `"<canonical> — <one-liner from the catalog>"`. Build labels FROM the catalog (`resolve_scenario(name).description`) instead of hardcoding, so descriptions stay single-sourced:

```python
def _scenario_choice(name: str) -> _DescribedChoice:
    scenario = resolve_scenario(name)
    return _DescribedChoice(f"{name} — {scenario.description}", name, scenario.details or scenario.description)
```

2. New `_LOADTEST_VM_CHOICES = [_scenario_choice(n) for n in (...)]` with the 5 loadtest names.
3. `_LOADTEST_ACTION_CHOICES`: prepend a `local`/`vm` fork — `local` leads to the existing run/plan/new_profile actions (unchanged), `vm` opens the `_LOADTEST_VM_CHOICES` list and dispatches into the same `_run_vm_e2e_scenario`/azure/proxmox handlers used today (move that dispatch from the Validation flow or share the helper — read the current `E2E Scenarios` loop and reuse, don't duplicate).
4. Update the two membership sets and azure/proxmox branch names to canonical (done in Tasks 2-4 — verify) and make the Loadtest→vm path go through the SAME code.
5. `Validation → cli` gains `cli-suite`; `Validation → host` value becomes `validate-deploy-host`.

This is the one genuinely judgment-heavy task: preserve the existing dashboard/flow wiring (`E2eRunner.plan(request)` + `build_scenario_flow`) for every moved scenario; only the menu paths change.

- [ ] **Step 3: align existing TUI tests** — `test_tui_choices.py` dispatch/choice assertions move to canonical names and new menu locations; any test asserting loadtests under platform updates to the new home. Full suite green.

- [ ] **Step 4: manual sanity** — `scripts/controlplane.sh tui`: walk `Validation → platform`, `Validation → cli`, `Loadtest → local`, `Loadtest → vm`; back out without running.

- [ ] **Step 5: commit** — `refactor(tui): loadtest scenarios move to Loadtest → vm; menus list canonical names from the catalog`.

---

### Task 6: `details` texts + TUI help pane

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/catalog.py`
- Test: `tools/controlplane/tests/test_e2e_catalog.py`

- [ ] **Step 1: failing test** — add to `test_e2e_catalog.py`:

```python
def test_every_scenario_has_substantial_details() -> None:
    from controlplane_tool.scenario.catalog import list_scenarios

    for scenario in list_scenarios():
        assert len(scenario.details) > 120, scenario.name
        # details must add information beyond the one-liner
        assert scenario.details != scenario.description, scenario.name
```

- [ ] **Step 2: copy the 13 `details` texts VERBATIM from the spec** (`docs/superpowers/specs/2026-06-12-e2e-scenario-taxonomy-design.md`, section "Details") into each `ScenarioDefinition(details="...")`. The spec is the single source — do not paraphrase.
- [ ] **Step 3:** the `_scenario_choice` helper from Task 5 already surfaces `details` in the TUI help pane — verify with one manual TUI peek that a long text renders acceptably; if the help pane truncates badly, report it (don't silently shorten the texts).
- [ ] **Step 4: full suite green; commit** — `feat(scenario): two-tier descriptions — details texts for all 13 scenarios`.

---

### Task 7: no-stale-names guard + docs

**Files:**
- Test: `tools/controlplane/tests/test_scenario_aliases.py`
- Modify: `README.md`, `tools/controlplane/README.md`, `docs/testing.md`, `CLAUDE.md`

- [ ] **Step 1: guard test** — append to `test_scenario_aliases.py`:

```python
def test_no_stale_scenario_names_in_sources() -> None:
    """Old IDs may only survive as alias data; src/ must be canonical-only."""
    import re
    from pathlib import Path

    old_names = [old for old, _ in RENAME_PAIRS]
    roots = [
        Path(__file__).resolve().parents[1] / "src",
        Path(__file__).resolve().parents[2] / "workflow-tasks" / "src",
    ]
    offenders: list[str] = []
    for root in roots:
        for path in root.rglob("*.py"):
            text = path.read_text(encoding="utf-8")
            for old in old_names:
                for m in re.finditer(rf'["\']({re.escape(old)})["\']', text):
                    line = text.count("\n", 0, m.start()) + 1
                    offenders.append(f"{path.name}:{line}:{old}")
    allowed = {"catalog.py"}  # alias tuples live here
    real = [o for o in offenders if o.split(":")[0] not in allowed]
    assert real == [], real
```

CAUTION: `("cli", "cli-suite")` makes `"cli"` an old name — the guard will flag legitimate non-scenario `"cli"` strings (e.g. typer app names, `cli.fn_apply_selected` component ids). Inspect the first run's offender list: extend the regex to word-boundary scenario contexts or, simpler, exclude `"cli"` from the guard with a comment (its alias is still covered by the contract test). Same judgment for `"docker"`/`"buildpack"` hits that are container-runtime words rather than scenario IDs — tune `allowed`/exclusions until the list is empty *and honest*, and document each exclusion inline.

- [ ] **Step 2: docs** — `grep -rn` each old name in `README.md`, `tools/controlplane/README.md`, `docs/testing.md`, `CLAUDE.md`: update command examples (`e2e run k3s-junit-curl` → `e2e run validate-k3s`, `e2e run two-vm-loadtest --dry-run` → canonical, etc.) and prose references; add one line to `tools/controlplane/README.md` documenting the alias policy ("old scenario names keep working and print a deprecation note"). Historical plan/spec docs stay untouched.
- [ ] **Step 3:** Full suites green + `scripts/controlplane.sh e2e run validate-k3s --dry-run` exits 0.
- [ ] **Step 4: commit** — `docs+test(scenario): no-stale-names guard; docs use canonical scenario names`.

---

## Final Verification

- [ ] `uv run --project tools/controlplane pytest tools/controlplane/tests -q` — green
- [ ] `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests` — green (coverage gate)
- [ ] Alias smokes: `e2e run two-vm-loadtest --dry-run`, `e2e run k3s-junit-curl --dry-run`, `e2e run cli --dry-run` — each prints the deprecation note and the same plan as its canonical twin
- [ ] TUI walkthrough of all four menus
- [ ] Optional confidence run (cheap): `scripts/controlplane.sh e2e run validate-docker-pool` (local, needs Docker) — proves a renamed scenario still executes end-to-end, not just dry-runs
