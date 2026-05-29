# Workflow Task Composition — Piano 3: Scenario Builders

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Introdurre builder concreti per `two-vm-loadtest` e `azure-vm-loadtest` che soddisfino il `ScenarioPlan` Protocol con `task_ids` accurati (mostrano `functions.register`, non `cli.fn_apply_selected`), e aggiornare `e2e_runner.plan()` e `scenario_flows` per usarli.

**Architecture:** I builder (`TwoVmLoadtestPlan`, `AzureVmLoadtestPlan`) wrappano l'output di `plan_recipe_steps` esistente — non replicano la logica ma la incapsulano in un oggetto Protocol-compliant. `e2e_runner.plan()` ritorna il builder per questi due scenari. `scenario_task_ids()` viene corretta per riflettere il nuovo step ID `functions.register`. Il recipe system resta invariato per gli altri scenari.

**Tech Stack:** Python 3.11+, dataclasses, `ScenarioPlan` Protocol (da Piano 2), `plan_recipe_steps` + `E2eRunner` (esistenti), pytest.

---

## File Structure

**Creati:**
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` — `TwoVmLoadtestPlan` dataclass + `build_two_vm_loadtest_plan()`
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py` — `AzureVmLoadtestPlan` dataclass + `build_azure_vm_loadtest_plan()`
- `tools/controlplane/tests/test_scenario_builders.py` — test dei builder

**Modificati:**
- `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py:23–27` — `scenario_task_ids()` ritorna `functions.register` per i due loadtest
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:309–347` — `plan()` ritorna builder per two-vm e azure-vm
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:609–620` — `run()` chiama `plan.run()` per builder plans

---

## Task 1: Fix `scenario_task_ids()` per due scenari loadtest

**File:** `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py`

**Contesto:** `scenario_task_ids("two-vm-loadtest")` ritorna `cli.fn_apply_selected` (component ID dalla recipe), ma Piano 2 ha cambiato l'esecuzione reale in `functions.register`. Questo crea una discrepanza nella TUI (planning phase mostra ID sbagliato).

- [ ] **Step 1: Scrivi test che documenta il comportamento atteso**

Crea `tools/controlplane/tests/test_scenario_builders.py`:

```python
from __future__ import annotations

from controlplane_tool.scenario.scenario_flows import scenario_task_ids


def test_two_vm_loadtest_task_ids_include_functions_register() -> None:
    """scenario_task_ids must return functions.register, not cli.fn_apply_selected."""
    ids = scenario_task_ids("two-vm-loadtest")
    assert "functions.register" in ids
    assert "cli.fn_apply_selected" not in ids


def test_azure_vm_loadtest_task_ids_include_functions_register() -> None:
    """scenario_task_ids must return functions.register for azure-vm-loadtest."""
    ids = scenario_task_ids("azure-vm-loadtest")
    assert "functions.register" in ids
    assert "cli.fn_apply_selected" not in ids


def test_two_vm_loadtest_task_ids_order() -> None:
    """functions.register must appear between cli.build_install_dist and loadgen.ensure_running."""
    ids = scenario_task_ids("two-vm-loadtest")
    build_dist_idx = ids.index("cli.build_install_dist")
    register_idx = ids.index("functions.register")
    loadgen_idx = ids.index("loadgen.ensure_running")
    assert build_dist_idx < register_idx < loadgen_idx
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -v 2>&1 | head -15
```
Expected: `AssertionError` — `cli.fn_apply_selected` è ancora presente.

- [ ] **Step 3: Modifica `scenario_task_ids()` in `scenario_flows.py`**

Leggi `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py` riga 23, poi sostituisci la funzione `scenario_task_ids`:

```python
def scenario_task_ids(scenario: str) -> list[str]:
    if scenario in {"container-local", "deploy-host", "cli", "cli-host"}:
        return [f"tests.run_{scenario.replace('-', '_')}"]
    recipe = build_scenario_recipe(scenario)
    ids = [component.component_id for component in compose_recipe(recipe)]
    if scenario in {"two-vm-loadtest", "azure-vm-loadtest"}:
        ids = [
            "functions.register" if i == "cli.fn_apply_selected" else i
            for i in ids
        ]
    return ids
```

- [ ] **Step 4: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py tests/test_scenario_flows.py -v 2>&1 | tail -10
```
Expected: i 3 nuovi test passano + nessuna regressione in `test_scenario_flows.py`.

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py \
        tools/controlplane/tests/test_scenario_builders.py
git commit -m "fix: scenario_task_ids returns functions.register for loadtest scenarios"
```

---

## Task 2: `TwoVmLoadtestPlan` builder

**File da creare:** `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py`

**Contesto:** `TwoVmLoadtestPlan` è un dataclass che soddisfa il `ScenarioPlan` Protocol. Wrappa i `ScenarioPlanStep` prodotti da `plan_recipe_steps` (inclusi i hook Piano 2). `run()` crea una `ScenarioPlan` legacy e chiama `runner._execute_steps()`.

- [ ] **Step 1: Aggiungi test al file esistente**

Aggiungi a `tools/controlplane/tests/test_scenario_builders.py`:

```python
from pathlib import Path
from unittest.mock import MagicMock, patch

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_models import VmRequest
from controlplane_tool.scenario.scenarios import ScenarioPlan as ScenarioPlanProtocol


def _make_request() -> E2eRequest:
    return E2eRequest(
        scenario="two-vm-loadtest",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        loadgen_vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen"),
    )


def test_two_vm_loadtest_plan_satisfies_protocol() -> None:
    """TwoVmLoadtestPlan must satisfy the ScenarioPlan Protocol."""
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="test.step")
    plan = TwoVmLoadtestPlan(
        scenario=MagicMock(),
        request=_make_request(),
        steps=[step],
        runner=MagicMock(),
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["test.step"]


def test_two_vm_loadtest_plan_task_ids_skips_empty_step_ids() -> None:
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    steps = [
        ScenarioPlanStep(summary="a", command=["echo"], step_id="a.step"),
        ScenarioPlanStep(summary="b", command=["echo"], step_id=""),
        ScenarioPlanStep(summary="c", command=["echo"], step_id="c.step"),
    ]
    plan = TwoVmLoadtestPlan(
        scenario=MagicMock(), request=_make_request(), steps=steps, runner=MagicMock()
    )
    assert plan.task_ids == ["a.step", "c.step"]


def test_build_two_vm_loadtest_plan_returns_correct_type(tmp_path: Path) -> None:
    """build_two_vm_loadtest_plan returns TwoVmLoadtestPlan with non-empty task_ids."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import (
        TwoVmLoadtestPlan,
        build_two_vm_loadtest_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = _make_request()

    plan = build_two_vm_loadtest_plan(runner, request)

    assert isinstance(plan, TwoVmLoadtestPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "functions.register" in plan.task_ids
    assert "cli.fn_apply_selected" not in plan.task_ids
    assert "loadgen.run_k6" in plan.task_ids
    assert "vm.down" in plan.task_ids
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_two_vm_loadtest_plan_satisfies_protocol -v 2>&1 | head -10
```
Expected: `ImportError` — modulo non ancora creato.

- [ ] **Step 3: Crea `scenario/scenarios/two_vm_loadtest.py`**

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
class TwoVmLoadtestPlan:
    """ScenarioPlan Protocol implementation for two-vm-loadtest.

    Wraps plan_recipe_steps output. task_ids reflects actual execution steps,
    including functions.register (Piano 2) instead of cli.fn_apply_selected.
    """

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    def run(self) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy)


def build_two_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> TwoVmLoadtestPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("two-vm-loadtest")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "two-vm-loadtest",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
    )
    return TwoVmLoadtestPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
```

- [ ] **Step 4: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -k "two_vm" -v
```
Expected: tutti i test `two_vm` passano.

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py \
        tools/controlplane/tests/test_scenario_builders.py
git commit -m "feat: add TwoVmLoadtestPlan builder"
```

---

## Task 3: `AzureVmLoadtestPlan` builder

**File da creare:** `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py`

- [ ] **Step 1: Aggiungi test**

Aggiungi a `tools/controlplane/tests/test_scenario_builders.py`:

```python
def _make_azure_request() -> E2eRequest:
    return E2eRequest(
        scenario="azure-vm-loadtest",
        runtime="java",
        vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure",
            azure_resource_group="my-rg",
            azure_location="westeurope",
        ),
        loadgen_vm=VmRequest(
            lifecycle="azure",
            name="nanofaas-azure-loadgen",
            azure_resource_group="my-rg",
            azure_location="westeurope",
            azure_vm_size="Standard_B1s",
        ),
    )


def test_azure_vm_loadtest_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="vm.ensure_running")
    plan = AzureVmLoadtestPlan(
        scenario=MagicMock(),
        request=_make_azure_request(),
        steps=[step],
        runner=MagicMock(),
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["vm.ensure_running"]


def test_build_azure_vm_loadtest_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import (
        AzureVmLoadtestPlan,
        build_azure_vm_loadtest_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = _make_azure_request()

    plan = build_azure_vm_loadtest_plan(runner, request)

    assert isinstance(plan, AzureVmLoadtestPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "functions.register" in plan.task_ids
    assert "cli.fn_apply_selected" not in plan.task_ids
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_azure_vm_loadtest_plan_satisfies_protocol -v 2>&1 | head -10
```
Expected: `ImportError`.

- [ ] **Step 3: Crea `scenario/scenarios/azure_vm_loadtest.py`**

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
class AzureVmLoadtestPlan:
    """ScenarioPlan Protocol implementation for azure-vm-loadtest."""

    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    def run(self) -> None:
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
            scenario=self.scenario,
            request=self.request,
            steps=self.steps,
        )
        self.runner._execute_steps(legacy)


def build_azure_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> AzureVmLoadtestPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("azure-vm-loadtest")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "azure-vm-loadtest",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
    )
    return AzureVmLoadtestPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
```

- [ ] **Step 4: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -v 2>&1 | tail -15
```
Expected: tutti i test in `test_scenario_builders.py` passano.

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py \
        tools/controlplane/tests/test_scenario_builders.py
git commit -m "feat: add AzureVmLoadtestPlan builder"
```

---

## Task 4: Aggiorna `e2e_runner.plan()` e `run()` per usare i builder

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

**Contesto:** Attualmente `plan()` ritorna sempre `ScenarioPlan` (dataclass) per tutti gli scenari. Dopo questo task, ritorna `TwoVmLoadtestPlan` / `AzureVmLoadtestPlan` per i due loadtest. `run()` chiama `plan.run()` per i builder invece di `self.execute(plan)`.

- [ ] **Step 1: Scrivi il test**

Aggiungi a `tools/controlplane/tests/test_scenario_builders.py`:

```python
def test_e2e_runner_plan_returns_two_vm_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return TwoVmLoadtestPlan for two-vm-loadtest."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_request())

    assert isinstance(plan, TwoVmLoadtestPlan)
    assert "functions.register" in plan.task_ids
    assert "cli.fn_apply_selected" not in plan.task_ids


def test_e2e_runner_plan_returns_azure_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return AzureVmLoadtestPlan for azure-vm-loadtest."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.azure_vm_loadtest import AzureVmLoadtestPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_azure_request())

    assert isinstance(plan, AzureVmLoadtestPlan)
    assert "functions.register" in plan.task_ids
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_e2e_runner_plan_returns_two_vm_builder -v 2>&1 | head -10
```
Expected: `AssertionError` — `plan()` ritorna ancora il vecchio `ScenarioPlan`.

- [ ] **Step 3: Aggiorna `e2e_runner.plan()`**

In `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`:

**3a.** Aggiungi import dopo gli import esistenti:

```python
from controlplane_tool.scenario.scenarios.two_vm_loadtest import (
    TwoVmLoadtestPlan,
    build_two_vm_loadtest_plan,
)
from controlplane_tool.scenario.scenarios.azure_vm_loadtest import (
    AzureVmLoadtestPlan,
    build_azure_vm_loadtest_plan,
)
```

**3b.** Nel metodo `plan()` (riga ~309), nel blocco `if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack", "two-vm-loadtest", "azure-vm-loadtest"}:`, aggiungi rami specifici per i due loadtest PRIMA del return generico. Il blocco corrente:

```python
        if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack",
                                 "two-vm-loadtest", "azure-vm-loadtest"}:
            plan_request = request
            recipe = build_scenario_recipe(request.scenario)
            if (request.vm is None and recipe.requires_managed_vm) or (
                request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"}
                and request.loadgen_vm is None
            ):
                context = resolve_scenario_environment(self.paths.workspace_root, request)
                updates: dict[str, object] = {}
                if request.vm is None and recipe.requires_managed_vm:
                    updates["vm"] = context.vm_request
                if request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"} and request.loadgen_vm is None:
                    updates["loadgen_vm"] = loadgen_vm_request(context)
                plan_request = request.model_copy(update=updates)
            return ScenarioPlan(
                scenario=scenario,
                request=plan_request,
                steps=plan_recipe_steps(
                    self.paths.workspace_root,
                    plan_request,
                    request.scenario,
                    shell=self.shell,
                    manifest_root=self.manifest_root,
                    host_resolver=self._host_resolver,
                ),
            )
```

Diventa:

```python
        if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack",
                                 "two-vm-loadtest", "azure-vm-loadtest"}:
            plan_request = request
            recipe = build_scenario_recipe(request.scenario)
            if (request.vm is None and recipe.requires_managed_vm) or (
                request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"}
                and request.loadgen_vm is None
            ):
                context = resolve_scenario_environment(self.paths.workspace_root, request)
                updates: dict[str, object] = {}
                if request.vm is None and recipe.requires_managed_vm:
                    updates["vm"] = context.vm_request
                if request.scenario in {"two-vm-loadtest", "azure-vm-loadtest"} and request.loadgen_vm is None:
                    updates["loadgen_vm"] = loadgen_vm_request(context)
                plan_request = request.model_copy(update=updates)
            if request.scenario == "two-vm-loadtest":
                return build_two_vm_loadtest_plan(self, plan_request)
            if request.scenario == "azure-vm-loadtest":
                return build_azure_vm_loadtest_plan(self, plan_request)
            return ScenarioPlan(
                scenario=scenario,
                request=plan_request,
                steps=plan_recipe_steps(
                    self.paths.workspace_root,
                    plan_request,
                    request.scenario,
                    shell=self.shell,
                    manifest_root=self.manifest_root,
                    host_resolver=self._host_resolver,
                ),
            )
```

**3c.** Aggiorna il metodo `run()` (riga ~609) per chiamare `plan.run()` quando il plan è un builder:

Corrente:
```python
    def run(
        self,
        request: E2eRequest,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> ScenarioPlan:
        initial_count = self._recorded_command_count()
        plan = self.plan(request)
        self._discard_planning_commands(initial_count)
        self.execute(plan, event_listener=event_listener)
        return plan
```

Diventa:
```python
    def run(
        self,
        request: E2eRequest,
        *,
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> ScenarioPlan:
        initial_count = self._recorded_command_count()
        plan = self.plan(request)
        self._discard_planning_commands(initial_count)
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan)):
            plan.run()
        else:
            self.execute(plan, event_listener=event_listener)
        return plan
```

- [ ] **Step 4: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py tests/test_e2e_runner.py -v 2>&1 | tail -20
```
Expected: tutti i test passano.

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
        tools/controlplane/tests/test_scenario_builders.py
git commit -m "feat: e2e_runner.plan() returns builder plans for loadtest scenarios"
```

---

## Task 5: Aggiorna `build_scenario_flow()` per usare `plan.task_ids`

**File:** `tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py`

**Contesto:** Quando `build_scenario_flow()` riceve un `request` per `two-vm-loadtest` o `azure-vm-loadtest`, crea un `LocalFlowDefinition` con `task_ids=scenario_task_ids(scenario)`. Dopo questo task, usa invece `runner.plan(request).task_ids` per i due loadtest — così la TUI vede gli step IDs reali (incluso `functions.register`).

- [ ] **Step 1: Scrivi test**

Aggiungi a `tools/controlplane/tests/test_scenario_builders.py`:

```python
def test_build_scenario_flow_uses_plan_task_ids_for_two_vm(tmp_path: Path) -> None:
    """build_scenario_flow must use plan.task_ids (not scenario_task_ids) for two-vm-loadtest."""
    from controlplane_tool.scenario.scenario_flows import build_scenario_flow

    flow = build_scenario_flow(
        "two-vm-loadtest",
        repo_root=Path("/repo"),
        request=_make_request(),
    )

    assert "functions.register" in flow.task_ids
    assert "cli.fn_apply_selected" not in flow.task_ids
```

- [ ] **Step 2: Esegui per verificare che passa già** (grazie a Task 1)

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_build_scenario_flow_uses_plan_task_ids_for_two_vm -v
```

Se passa (grazie al fix di `scenario_task_ids` in Task 1): ottimo, il test documenta il comportamento ma non richiede ulteriori modifiche a `scenario_flows.py`.

Se fallisce: leggi `build_scenario_flow()` e aggiorna il ramo `if request is not None:` per `two-vm-loadtest` e `azure-vm-loadtest`:

```python
    if request is not None:
        if scenario in {"two-vm-loadtest", "azure-vm-loadtest"}:
            from controlplane_tool.e2e.e2e_runner import E2eRunner as _Runner
            _runner = _Runner(repo_root)
            _plan = _runner.plan(request)
            return LocalFlowDefinition(
                flow_id=flow_id,
                task_ids=_plan.task_ids,
                run=lambda: _runner.run(request, event_listener=event_listener),
            )
        return LocalFlowDefinition(
            flow_id=flow_id,
            task_ids=scenario_task_ids(scenario),
            run=lambda: E2eRunner(repo_root).run(request, event_listener=event_listener),
        )
```

- [ ] **Step 3: Esegui suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py tests/test_scenario_flows.py tests/test_e2e_runner.py -q 2>&1 | tail -10
```
Expected: tutti i test passano.

- [ ] **Step 4: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add tools/controlplane/src/controlplane_tool/scenario/scenario_flows.py \
        tools/controlplane/tests/test_scenario_builders.py
git commit -m "feat: build_scenario_flow uses plan.task_ids for loadtest scenarios"
```

---

## Verifica Finale

- [ ] **Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=no 2>&1 | tail -5
```
Expected: 1030+ passano, solo il pre-esistente `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce.

---

## Note su Piano 4

Piano 4 (futuro) completerà la migrazione:
- Builder concreti per `k3s-junit-curl`, `helm-stack`, `cli-stack` (richiede task aggiuntivi: image building, k8s tests, CLI platform operations)
- Rimozione del recipe system (`components/`, `scenario_planner.py`, `plan_recipe_steps`)
- Spostamento del task catalog in `workflow_tasks` sub-packages
