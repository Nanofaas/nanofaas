# Workflow Task Composition — Piano 11: Builder per cli e cli-host

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere builder tipizzati (`CliVmPlan`, `CliHostPlan`) per i due scenari VM rimanenti senza builder, completando la copertura di tutti gli scenari VM con tipi espliciti.

**Architecture:** `CliVmPlan` e `CliHostPlan` seguono il pattern dei builder di Piano 8 (cli-stack in `plan_all()`): wrappano l'output di `_planner.vm_backed_steps()` invece di `plan_recipe_steps()` (questi non sono scenari recipe). I builder factory per `plan()` chiamano `_planner.vm_backed_steps(request)` direttamente. In `plan_all()`, i builder wrappano i passi con `include_bootstrap` già applicato. Dopo questo piano, `plan()` non chiama più `_planner.vm_backed_steps()` per nessuno scenario.

**Tech Stack:** Python 3.11+, dataclasses, builder pattern (Piano 3–9). 4 file toccati: 2 nuovi builder + `e2e_runner.py` + `test_e2e_runner.py`.

---

## Background — stato corrente

**`plan()` fallback (righe 347–352):**
```python
steps = (
    self._planner.vm_backed_steps(request)   # raggiunto per cli e cli-host
    if scenario.requires_vm
    else self._planner.local_steps(request)  # raggiunto per docker, buildpack, ecc.
)
return ScenarioPlan(scenario=scenario, request=request, steps=steps)
```

**`plan_all()` (riga 447):**
```python
else:
    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))  # cli e cli-host
```

**Dopo Piano 11:**
- `plan()` non raggiunge più `_planner.vm_backed_steps()` per nessuno scenario (solo `_planner.local_steps()` per scenari locali)
- `plan_all()` ritorna `CliVmPlan`/`CliHostPlan` invece di `ScenarioPlan` generico per cli/cli-host
- `run()` e `run_all()` dispatch aggiornati

**Step generati da `vm_backed_steps("cli")`:**
1. Bootstrap steps: `vm.ensure_running`, `vm.provision_base`, `repo.sync_to_vm`, `k3s.install`, `registry.ensure_container`, `k3s.configure_registry`
2. Scenario step: `cli.vm_e2e_flow` — "Run CLI E2E workflow inside VM (Python)"

**Step generati da `vm_backed_steps("cli-host")`:**
1. Bootstrap steps (stessi)
2. Scenario step: `cli.host_platform_flow` — "Run host CLI platform lifecycle (Python)"

---

## File Structure

**Creati:**
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_vm.py` — `CliVmPlan` + `build_cli_vm_plan()`
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_host.py` — `CliHostPlan` + `build_cli_host_plan()`

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — `plan()`, `plan_all()`, `run()`, `run_all()`
- `tools/controlplane/tests/test_e2e_runner.py` — nuovi test

---

## Task 1: `CliVmPlan` e `CliHostPlan` builder

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_vm.py`
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_host.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Aggiungi test che falliscono**

Leggi le ultime righe di `test_e2e_runner.py`, poi aggiungi:

```python
def test_plan_returns_typed_cli_vm_plan(tmp_path: Path) -> None:
    """plan() must return CliVmPlan for cli scenario."""
    from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(
        scenario="cli",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    ))

    assert isinstance(plan, CliVmPlan), f"Expected CliVmPlan, got {type(plan)}"
    assert "cli.vm_e2e_flow" in plan.task_ids


def test_plan_returns_typed_cli_host_plan(tmp_path: Path) -> None:
    """plan() must return CliHostPlan for cli-host scenario."""
    from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(
        scenario="cli-host",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    ))

    assert isinstance(plan, CliHostPlan), f"Expected CliHostPlan, got {type(plan)}"
    assert "cli.host_platform_flow" in plan.task_ids
```

- [ ] **Step 2: Verifica che falliscono (ImportError)**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_returns_typed_cli_vm_plan -v 2>&1 | tail -6
```
Expected: `ImportError: cannot import name 'CliVmPlan'`.

- [ ] **Step 3: Crea `scenario/scenarios/cli_vm.py`**

```python
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class CliVmPlan:
    """ScenarioPlan Protocol implementation for the cli (VM-backed) scenario."""

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy, event_listener=event_listener)


def build_cli_vm_plan(runner: "E2eRunner", request: E2eRequest) -> CliVmPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("cli")
    steps = runner._planner.vm_backed_steps(request)
    return CliVmPlan(scenario=scenario, request=request, steps=steps, runner=runner)
```

- [ ] **Step 4: Crea `scenario/scenarios/cli_host.py`**

```python
from __future__ import annotations

from dataclasses import dataclass, field
from typing import TYPE_CHECKING

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


@dataclass
class CliHostPlan:
    """ScenarioPlan Protocol implementation for the cli-host scenario."""

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy, event_listener=event_listener)


def build_cli_host_plan(runner: "E2eRunner", request: E2eRequest) -> CliHostPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("cli-host")
    steps = runner._planner.vm_backed_steps(request)
    return CliHostPlan(scenario=scenario, request=request, steps=steps, runner=runner)
```

- [ ] **Step 5: Verifica che i test passino ancora (ImportError risolto, ma plan() non ancora aggiornato)**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_returns_typed_cli_vm_plan -v 2>&1 | tail -6
```
Expected: ora `AssertionError` — plan() ritorna ancora `ScenarioPlan` generico, non `CliVmPlan`. Gli step test sono stati spostati al Task 2.

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_vm.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_host.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
feat: add CliVmPlan and CliHostPlan builder files

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Wiring in `e2e_runner.py`

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

- [ ] **Step 1: Leggi il codice attuale**

```bash
sed -n '344,355p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
sed -n '440,452p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
sed -n '658,690p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
```

- [ ] **Step 2: Aggiorna `plan()` — aggiungi dispatch per cli e cli-host**

Trova il blocco fallback (righe ~347–352):

```python
        steps = (
            self._planner.vm_backed_steps(request)
            if scenario.requires_vm
            else self._planner.local_steps(request)
        )
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)
```

Sostituisci con:

```python
        if scenario.requires_vm:
            if request.scenario == "cli":
                from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
                return build_cli_vm_plan(self, request)
            if request.scenario == "cli-host":
                from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
                return build_cli_host_plan(self, request)
            steps = self._planner.vm_backed_steps(request)
        else:
            steps = self._planner.local_steps(request)
        return ScenarioPlan(scenario=scenario, request=request, steps=steps)
```

**Nota:** Il `self._planner.vm_backed_steps(request)` nel fallback rimane per eventuali scenari VM futuri non ancora coperti da builder. Diventa dead code per il catalogo attuale.

- [ ] **Step 3: Aggiorna `plan_all()` — aggiungi dispatch per cli e cli-host**

Trova il blocco (righe ~441–448):

```python
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                if scenario.name == "cli-stack":
                    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
                    plans.append(CliStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                else:
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

Sostituisci con:

```python
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                if scenario.name == "cli-stack":
                    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
                    plans.append(CliStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "cli":
                    from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
                    plans.append(CliVmPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "cli-host":
                    from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan
                    plans.append(CliHostPlan(scenario=scenario, request=request, steps=steps, runner=self))
                else:
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

- [ ] **Step 4: Aggiorna `run()` isinstance check**

Nel metodo `run()`, trova:

```python
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan)):
            plan.run(event_listener=event_listener)
```

Sostituisci con:

```python
        from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
        from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan, CliVmPlan, CliHostPlan)):
            plan.run(event_listener=event_listener)
```

- [ ] **Step 5: Aggiorna `run_all()` isinstance check**

Nel metodo `run_all()`, trova:

```python
            _BUILDER_TYPES = (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan)
```

Sostituisci con:

```python
            from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
            from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan
            _BUILDER_TYPES = (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan, CliVmPlan, CliHostPlan)
```

- [ ] **Step 6: Verifica i nuovi test passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_returns_typed_cli_vm_plan tests/test_e2e_runner.py::test_plan_returns_typed_cli_host_plan -v 2>&1 | tail -6
```
Expected: `2 passed`.

- [ ] **Step 7: Aggiungi test per `plan_all()`**

READ le ultime righe di `test_e2e_runner.py`, poi aggiungi:

```python
def test_plan_all_returns_typed_cli_vm_plan(tmp_path: Path) -> None:
    """plan_all() must return CliVmPlan for cli scenario."""
    from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["cli"])

    assert len(plans) == 1
    assert isinstance(plans[0], CliVmPlan), f"Expected CliVmPlan, got {type(plans[0])}"


def test_plan_all_returns_typed_cli_host_plan(tmp_path: Path) -> None:
    """plan_all() must return CliHostPlan for cli-host scenario."""
    from controlplane_tool.scenario.scenarios.cli_host import CliHostPlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plans = runner.plan_all(only=["cli-host"])

    assert len(plans) == 1
    assert isinstance(plans[0], CliHostPlan), f"Expected CliHostPlan, got {type(plans[0])}"
```

Esegui:
```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "typed_cli" -v 2>&1 | tail -8
```
Expected: 4 passed.

- [ ] **Step 8: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1063+ passano, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce.

- [ ] **Step 9: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
feat: plan() and plan_all() return typed builders for cli and cli-host scenarios

Completes typed builder coverage for all VM scenarios.
plan() no longer calls _planner.vm_backed_steps() for any specific scenario;
run() and run_all() dispatch includes CliVmPlan and CliHostPlan.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -q 2>&1 | tail -5
```

Dopo questo piano:
- Tutti gli scenari VM hanno builder tipizzati
- `plan()` chiama `_planner.vm_backed_steps()` solo come fallback per scenari VM sconosciuti (dead code per il catalogo attuale)
- `plan_all()` ritorna builder tipizzati per TUTTI gli scenari VM
- `run()` e `run_all()` dispatchano via `plan.run()` per tutti i 7 builder tipi

---

## Note su Piano 12

Piano 12 (futuro) potrà rimuovere il ramo dead in `plan()`:
```python
# Questo diventa dead code dopo Piano 11:
if scenario.requires_vm:
    steps = self._planner.vm_backed_steps(request)  # ← mai raggiunto per scenari noti
```
E se `_planner.vm_backed_steps()` non è più chiamato da nessun path di produzione:
- `vm_backed_steps()`, `vm_scenario_steps()`, `vm_bootstrap_steps()` in `ScenarioPlanner` diventano dead code
- `ScenarioPlanner` si riduce a solo `local_steps()` + helper privati
- Possibile rimozione del `_planner` da `E2eRunner.__init__`
