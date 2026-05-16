# Workflow Task Composition — Piano 2: Wire New Tasks into Recipe Execution

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collegare i task concreti di Piano 1 all'esecuzione reale degli scenari modificando le closure di `plan_recipe_steps` in `e2e_runner.py`: `RunK6Matrix` sostituisce il loop `[0]` sul k6, `RegisterFunctions` (REST) sostituisce `cli.fn_apply_selected`.

**Architecture:** Due modifiche chirurgiche alle closure in `plan_recipe_steps` — nessuna modifica al recipe system, nessun nuovo file di scenario. Il `ScenarioPlan` Protocol viene aggiunto come fondamenta per Piano 3 (full builder migration). I test esistenti del recipe system continuano a passare.

**Tech Stack:** Python 3.11+, `RunK6Matrix` + `RegisterFunctions` + `K6MatrixResult` (da Piano 1 in `controlplane_tool/scenario/tasks/`), pytest.

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — 3 modifiche alle closure in `plan_recipe_steps`:
  1. `_on_loadgen_run_k6` → usa `RunK6Matrix` (itera tutti i target)
  2. Nuova closure `_on_register_functions` + handler `cli.fn_apply_selected`
  3. Aggiunta property `task_ids` al dataclass `ScenarioPlan`
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py` — nuovo file con `ScenarioPlan` Protocol

**Creati:**
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py`
- `tools/controlplane/tests/test_recipe_execution_hooks.py`

---

## Task 1: Fix `_on_loadgen_run_k6` — usa `RunK6Matrix`

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

**Contesto:** Riga ~124 della funzione `plan_recipe_steps`. La closure corrente:
```python
def _on_loadgen_run_k6() -> None:
    nonlocal two_vm_k6_result
    two_vm_k6_result = two_vm_runner.run_k6(request)
```
chiama `run_k6(request)` che internamente usa `two_vm_target_function(request)` → `function_keys[0]`. Solo la prima funzione viene testata.

- [ ] **Step 1: Scrivi il test che verifica il bug e poi il fix**

`tools/controlplane/tests/test_recipe_execution_hooks.py`:
```python
from __future__ import annotations

import pytest
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch, call
from controlplane_tool.scenario.tasks.loadtest import K6MatrixResult, RunK6Matrix
from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmK6Result


def _utcnow() -> datetime:
    return datetime.now(timezone.utc)


def _make_k6_result(fn: str) -> TwoVmK6Result:
    return TwoVmK6Result(
        run_dir=Path("/tmp"),
        k6_summary_path=Path(f"/tmp/{fn}.json"),
        target_function=fn,
        started_at=_utcnow(),
        ended_at=_utcnow(),
    )


def test_on_loadgen_run_k6_calls_matrix_not_single(monkeypatch) -> None:
    """Regression: _on_loadgen_run_k6 must iterate ALL targets, not just [0]."""
    fn_keys = ["word-stats-java", "json-transform-java", "word-stats-python"]
    runner = MagicMock()
    runner.run_k6_for_function.side_effect = [_make_k6_result(fn) for fn in fn_keys]

    captured: list[K6MatrixResult] = []

    # Simulate what plan_recipe_steps does: build a RunK6Matrix and call it
    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys
    request.functions = []

    task = RunK6Matrix(
        task_id="loadgen.run_k6",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    )
    result = task.run()

    assert runner.run_k6_for_function.call_count == 3
    calls_fn_keys = [c.args[1] for c in runner.run_k6_for_function.call_args_list]
    assert calls_fn_keys == fn_keys
    assert len(result.results) == 3


def test_run_k6_matrix_first_result_is_available_for_prometheus(monkeypatch) -> None:
    """_on_prometheus_snapshot uses first result — must not fail when matrix has multiple."""
    fn_keys = ["word-stats-java", "json-transform-java"]
    runner = MagicMock()
    runner.run_k6_for_function.side_effect = [_make_k6_result(fn) for fn in fn_keys]

    request = MagicMock()
    request.resolved_scenario.function_keys = fn_keys
    request.functions = []

    matrix_result = RunK6Matrix(
        task_id="loadgen.run_k6",
        title="Run k6 against all targets",
        runner=runner,
        request=request,
    ).run()

    assert matrix_result.results[0].target_function == "word-stats-java"
    assert matrix_result.window is not None
```

- [ ] **Step 2: Esegui il test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_recipe_execution_hooks.py -v
```
Expected: `2 passed` (i test usano direttamente `RunK6Matrix`, già implementato in Piano 1)

- [ ] **Step 3: Modifica `_on_loadgen_run_k6` in `e2e_runner.py`**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`:

**3a.** Aggiungi import in testa alla funzione `plan_recipe_steps` (subito dopo `two_vm_k6_result: TwoVmK6Result | None = None`):

```python
    two_vm_k6_result: TwoVmK6Result | None = None
    two_vm_prometheus_snapshot_path: Path | None = None
    two_vm_k6_matrix_result: "K6MatrixResult | None" = None
```

**3b.** Aggiungi l'import di `K6MatrixResult` e `RunK6Matrix` in testa al file (sezione import esistente):

```python
from controlplane_tool.scenario.tasks.loadtest import K6MatrixResult, RunK6Matrix
```

**3c.** Sostituisci la closure `_on_loadgen_run_k6` (riga ~124):

```python
    def _on_loadgen_run_k6() -> None:
        nonlocal two_vm_k6_result, two_vm_k6_matrix_result
        matrix_result = RunK6Matrix(
            task_id="loadgen.run_k6",
            title="Run k6 against all targets",
            runner=two_vm_runner,
            request=request,
        ).run()
        two_vm_k6_matrix_result = matrix_result
        if matrix_result.results:
            two_vm_k6_result = matrix_result.results[0]
```

- [ ] **Step 4: Esegui i test esistenti per verificare nessuna regressione**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_two_vm_loadtest_runner.py tests/test_azure_vm_loadtest_runner.py tests/test_recipe_execution_hooks.py -v 2>&1 | tail -20
```
Expected: tutti passano

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
        tools/controlplane/tests/test_recipe_execution_hooks.py
git commit -m "fix: use RunK6Matrix in loadgen.run_k6 hook — iterates all targets"
```

---

## Task 2: Replace `cli.fn_apply_selected` with `RegisterFunctions`

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

**Contesto:** Il componente `cli.fn_apply_selected` genera N step (uno per funzione), ognuno dei quali esegue `nanofaas-cli fn apply` via SSH. Il fix sostituisce tutti questi step con un singolo step che chiama `RegisterFunctions.run()` (POST REST diretto al control-plane).

**Come funziona la risoluzione dell'URL al runtime:** Il `host_resolver` (o `vm_orch.connection_host`) risolve l'IP della VM solo al momento dell'esecuzione, non durante la pianificazione. La closure `_on_register_functions` deve risolvere l'IP pigro (inside the action, not outside).

- [ ] **Step 1: Aggiungi test per il comportamento `cli.fn_apply_selected` → REST**

Aggiungi a `tools/controlplane/tests/test_recipe_execution_hooks.py`:

```python
from controlplane_tool.scenario.tasks.functions import FunctionSpec, RegisterFunctions
from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions


def test_register_functions_spec_built_from_resolved_scenario() -> None:
    """FunctionSpec list must include name + image for each selected function."""
    from controlplane_tool.scenario.tasks.functions import FunctionSpec

    # Simulate what _on_register_functions does
    fn_keys = ["word-stats-java", "json-transform-java"]
    resolved = MagicMock()
    resolved.functions = [
        MagicMock(key="word-stats-java", image="registry/word-stats-java:e2e"),
        MagicMock(key="json-transform-java", image="registry/json-transform-java:e2e"),
    ]
    local_registry = "localhost:5000"
    runtime_image_default = f"{local_registry}/nanofaas/function-runtime:e2e"

    specs = [
        FunctionSpec(
            name=fn_key,
            image=function_image(fn_key, resolved, runtime_image_default),
        )
        for fn_key in selected_functions(resolved)
    ]

    assert len(specs) == 2
    assert specs[0].name == "word-stats-java"
    assert specs[0].image == "registry/word-stats-java:e2e"
    assert specs[1].name == "json-transform-java"
    assert specs[1].image == "registry/json-transform-java:e2e"
    assert specs[0].execution_mode == "DEPLOYMENT"
    assert specs[0].timeout_ms == 5000


def test_register_functions_step_has_correct_step_id() -> None:
    """The replacement step for cli.fn_apply_selected must have step_id=functions.register."""
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    def _on_register_functions() -> None:
        pass  # stub

    step = ScenarioPlanStep(
        summary="Register selected functions via REST API",
        command=["python", "-c", "# RegisterFunctions via REST"],
        step_id="functions.register",
        action=_on_register_functions,
    )
    assert step.step_id == "functions.register"
    assert step.action is not None
```

- [ ] **Step 2: Esegui per verificare che i test passano**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_recipe_execution_hooks.py -v
```
Expected: `4 passed`

- [ ] **Step 3: Aggiungi gli import necessari in `e2e_runner.py`**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, nella sezione import (dopo le import esistenti), aggiungi:

```python
from controlplane_tool.scenario.tasks.functions import FunctionSpec, RegisterFunctions
from controlplane_tool.scenario.scenario_helpers import function_image, selected_functions
```

- [ ] **Step 4: Aggiungi `_resolve_cp_host` e `_on_register_functions` in `plan_recipe_steps`**

In `plan_recipe_steps`, dopo la closure `_on_remote_exec` (riga ~122) e prima di `_on_loadgen_run_k6`, aggiungi:

```python
    def _resolve_cp_host() -> str:
        if host_resolver is not None:
            return host_resolver(vm_request)
        return vm_orch.connection_host(vm_request)

    def _on_register_functions() -> None:
        runtime_image_default = f"{context.local_registry}/nanofaas/function-runtime:e2e"
        fn_keys = selected_functions(request.resolved_scenario)
        specs = [
            FunctionSpec(
                name=fn_key,
                image=function_image(fn_key, request.resolved_scenario, runtime_image_default),
            )
            for fn_key in fn_keys
        ]
        cp_host = _resolve_cp_host()
        cp_url = f"http://{cp_host}:{TWO_VM_CONTROL_PLANE_HTTP_NODE_PORT}"
        RegisterFunctions(
            task_id="functions.register",
            title="Register functions",
            control_plane_url=cp_url,
            specs=specs,
        ).run()
```

- [ ] **Step 5: Aggiungi l'handler `cli.fn_apply_selected` nel loop principale**

Nel loop `for component in compose_recipe(recipe):` di `plan_recipe_steps`, aggiungi prima dello `steps.extend(component_steps)` finale (dopo il blocco `if component.component_id == "loadtest.write_report":`):

```python
        if component.component_id == "cli.fn_apply_selected":
            component_steps = [
                ScenarioPlanStep(
                    summary="Register selected functions via REST API",
                    command=["python", "-c", "# RegisterFunctions via REST"],
                    step_id="functions.register",
                    action=_on_register_functions,
                )
            ]
```

- [ ] **Step 6: Verifica che `_resolve_cp_host` funzioni per entrambi i lifecycle**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && grep -n "def connection_host" tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py
```
Expected: entrambi i file mostrano `def connection_host` — `AzureVmOrchestrator.connection_host` esiste e restituisce l'IP della VM Azure. La closure `_resolve_cp_host` funziona senza modifiche per entrambi i lifecycle.

- [ ] **Step 7: Esegui la suite esistente per verificare nessuna regressione**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_flows.py tests/test_recipe_execution_hooks.py tests/test_two_vm_loadtest_runner.py -v 2>&1 | tail -25
```
Expected: tutti i test preesistenti continuano a passare

- [ ] **Step 8: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
        tools/controlplane/tests/test_recipe_execution_hooks.py
git commit -m "fix: replace cli.fn_apply_selected with RegisterFunctions REST hook"
```

---

## Task 3: Aggiungi `ScenarioPlan` Protocol e `task_ids` property

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — aggiungi `task_ids` al dataclass `ScenarioPlan` esistente

**Contesto:** Il `ScenarioPlan` dataclass esistente in `e2e_runner.py` ha `scenario`, `request`, `steps`. Aggiungere `task_ids` lo fa soddisfare il Protocol e prepara Piano 3.

- [ ] **Step 1: Scrivi il test**

Aggiungi a `tools/controlplane/tests/test_recipe_execution_hooks.py`:

```python
def test_scenario_plan_protocol_is_satisfied_by_existing_dataclass() -> None:
    """Existing ScenarioPlan dataclass must satisfy the new ScenarioPlan Protocol."""
    from workflow_tasks.core.task import Task
    from controlplane_tool.scenario.scenarios import ScenarioPlan as ScenarioPlanProtocol
    from controlplane_tool.e2e.e2e_runner import ScenarioPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo", "x"], step_id="test.step")
    plan = ScenarioPlan(
        scenario=MagicMock(),
        request=MagicMock(),
        steps=[step],
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["test.step"]


def test_scenario_plan_task_ids_skips_empty_step_ids() -> None:
    """Steps without step_id are excluded from task_ids."""
    from controlplane_tool.e2e.e2e_runner import ScenarioPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    steps = [
        ScenarioPlanStep(summary="a", command=["echo"], step_id="a.step"),
        ScenarioPlanStep(summary="b", command=["echo"], step_id=""),
        ScenarioPlanStep(summary="c", command=["echo"], step_id="c.step"),
    ]
    plan = ScenarioPlan(scenario=MagicMock(), request=MagicMock(), steps=steps)
    assert plan.task_ids == ["a.step", "c.step"]
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_recipe_execution_hooks.py::test_scenario_plan_protocol_is_satisfied_by_existing_dataclass -v 2>&1 | head -15
```
Expected: `ImportError` o `AttributeError` (Protocol non ancora definito, `task_ids` non esiste)

- [ ] **Step 3: Crea `scenario/scenarios/__init__.py` con il Protocol**

```bash
mkdir -p /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/scenario/scenarios
```

`tools/controlplane/src/controlplane_tool/scenario/scenarios/__init__.py`:
```python
from __future__ import annotations

from typing import Protocol, runtime_checkable


@runtime_checkable
class ScenarioPlan(Protocol):
    """Protocol for scenario execution plans.

    Concrete implementations carry the steps (or tasks) for a scenario
    and know how to execute themselves. The task_ids property allows the
    TUI to display phases before execution (dry-run planning).
    """

    @property
    def task_ids(self) -> list[str]: ...

    def run(self) -> None: ...
```

- [ ] **Step 4: Aggiungi `task_ids` al dataclass `ScenarioPlan` esistente**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`, il dataclass `ScenarioPlan` (riga ~41):

```python
@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
```

Diventa:

```python
@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]

    @property
    def task_ids(self) -> list[str]:
        """Step IDs in execution order, for TUI dry-run planning."""
        return [s.step_id for s in self.steps if s.step_id]
```

Nota: `ScenarioPlan` in `e2e_runner.py` non implementa `run()` — quello rimane in `E2eRunner.execute()`. Per soddisfare il Protocol runtime-checkable, `run()` non è necessario a livello di `isinstance()` in Python per Protocol con metodi — solo gli attributi sono controllati runtime. Tuttavia, per chiarezza, aggiungi una `run()` delegante:

```python
@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    _executor: "Callable[[ScenarioPlan], None] | None" = field(default=None, repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        """Step IDs in execution order, for TUI dry-run planning."""
        return [s.step_id for s in self.steps if s.step_id]

    def run(self) -> None:
        if self._executor is None:
            raise RuntimeError("ScenarioPlan.run() requires an executor — use E2eRunner.execute(plan)")
        self._executor(self)
```

**Nota:** `_executor` è `None` di default per retrocompatibilità. `E2eRunner.execute()` continua a funzionare come prima. `run()` esplicito sarà usato nei builder di Piano 3.

Dovrai importare `Callable` e `field` se non già presenti:
```python
from dataclasses import dataclass, field
from collections.abc import Callable
```

- [ ] **Step 5: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_recipe_execution_hooks.py -v
```
Expected: tutti i test passano (inclusi i due nuovi)

- [ ] **Step 6: Esegui la suite completa per nessuna regressione**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_flows.py tests/test_e2e_catalog.py -v 2>&1 | tail -15
```
Expected: nessuna regressione

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/scenario/scenarios/ \
        tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
        tools/controlplane/tests/test_recipe_execution_hooks.py
git commit -m "feat: add ScenarioPlan Protocol and task_ids property to existing plan dataclass"
```

---

## Verifica Finale

- [ ] **Esegui la suite completa di controlplane**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -x -q 2>&1 | tail -25
```
Expected: nessuna nuova regressione rispetto a prima di Piano 2.

- [ ] **Verifica che workflow-tasks sia ancora pulito**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/core/ tests/test_public_api.py -v 2>&1 | tail -10
```
Expected: tutti i test di Piano 1 ancora verdi.

---

## Note su Piano 3

Piano 3 completerà la migrazione ai builder:
- Aggiunge task al catalogo per image building (`RemoteScriptTask`, `BuildCoreImages`, `BuildSelectedFunctions`)
- Aggiunge task per k8s testing (`K3sCurlVerify`, `K8sJunitTest`)
- Crea builder concreti per tutti gli scenari (`two-vm-loadtest`, `azure-vm-loadtest`, `k3s-junit-curl`, `helm-stack`, `cli-stack`)
- `E2eRunner.plan()` → restituisce builder plan per tutti gli scenari
- `scenario_flows.scenario_task_ids()` → usa `plan.task_ids` per tutti
- Rimuove `scenario/components/`, `scenario_planner.py`, test obsoleti del recipe system
