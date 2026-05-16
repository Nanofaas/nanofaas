# Workflow Task Composition — Piano 4: Remaining Recipe Builders

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Aggiungere builder concreti per i 3 scenari recipe rimanenti (`k3s-junit-curl`, `helm-stack`, `cli-stack`) in modo che `E2eRunner.plan()` ritorni oggetti tipizzati per TUTTI gli scenari recipe-based — completando il pattern introdotto in Piano 3 per i due loadtest.

**Architecture:** I builder (`K3sJunitCurlPlan`, `HelmStackPlan`, `CliStackPlan`) seguono identicamente il pattern di `TwoVmLoadtestPlan`/`AzureVmLoadtestPlan`: dataclass con `scenario`, `request`, `steps`, `runner`; property `task_ids`; `run()` che delega a `runner._execute_steps()`. Dopo questo piano, il ramo `return ScenarioPlan(...)` in `plan()` diventa dead code per tutti i 5 scenari recipe — rimosso in Piano 5. `plan_recipe_steps` e `ScenarioPlanner` restano invariati.

**Tech Stack:** Python 3.11+, dataclasses, `ScenarioPlan` Protocol (da Piano 2), `plan_recipe_steps` + `E2eRunner` (esistenti), pytest.

---

## File Structure

**Creati:**
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py` — `K3sJunitCurlPlan` + `build_k3s_junit_curl_plan()`
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/helm_stack.py` — `HelmStackPlan` + `build_helm_stack_plan()`
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_stack.py` — `CliStackPlan` + `build_cli_stack_plan()`

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — `plan()` ritorna i nuovi builder per i 3 scenari; `run()` aggiorna l'isinstance check
- `tools/controlplane/tests/test_scenario_builders.py` — nuovi test per i 3 builder e per `e2e_runner.plan()`

---

## Background — pattern builder (da Piano 3)

Prima di scrivere codice, leggi `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` — i nuovi builder sono identici in struttura (cambia solo il nome classe e lo scenario).

Key imports usati dai builder:
```python
from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
# TYPE_CHECKING guard per E2eRunner (evita circular import)
if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
```

Pattern run():
```python
def run(self) -> None:
    from controlplane_tool.e2e.e2e_runner import ScenarioPlan
    legacy = ScenarioPlan(scenario=self.scenario, request=self.request, steps=self.steps)
    self.runner._execute_steps(legacy)
```

Pattern factory:
```python
def build_XPlan(runner: "E2eRunner", request: E2eRequest) -> XPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("scenario-name")
    steps = plan_recipe_steps(
        runner.paths.workspace_root, request, "scenario-name",
        shell=runner.shell, manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
    )
    return XPlan(scenario=scenario, request=request, steps=steps, runner=runner)
```

---

## Task 1: `K3sJunitCurlPlan` builder

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py`
- Test: `tools/controlplane/tests/test_scenario_builders.py`

- [ ] **Step 1: Leggi il file Two VM builder per reference**

```bash
cat tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py
```

- [ ] **Step 2: Aggiungi test al file esistente**

Leggi `tools/controlplane/tests/test_scenario_builders.py`, poi aggiungi in fondo:

```python
def _make_k3s_request() -> E2eRequest:
    return E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_k3s_junit_curl_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="vm.ensure_running")
    plan = K3sJunitCurlPlan(
        scenario=MagicMock(), request=_make_k3s_request(), steps=[step], runner=MagicMock()
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["vm.ensure_running"]


def test_build_k3s_junit_curl_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import (
        K3sJunitCurlPlan,
        build_k3s_junit_curl_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_k3s_junit_curl_plan(runner, _make_k3s_request())

    assert isinstance(plan, K3sJunitCurlPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "vm.ensure_running" in plan.task_ids
    assert "tests.run_k3s_curl_checks" in plan.task_ids
    assert "tests.run_k8s_junit" in plan.task_ids
    assert "vm.down" in plan.task_ids
```

- [ ] **Step 3: Esegui per verificare che falliscono (ImportError atteso)**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_k3s_junit_curl_plan_satisfies_protocol -v 2>&1 | head -10
```

- [ ] **Step 4: Crea `scenario/scenarios/k3s_junit_curl.py`**

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
class K3sJunitCurlPlan:
    """ScenarioPlan Protocol implementation for k3s-junit-curl."""

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


def build_k3s_junit_curl_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> K3sJunitCurlPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("k3s-junit-curl")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "k3s-junit-curl",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
    )
    return K3sJunitCurlPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
```

- [ ] **Step 5: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -k "k3s" -v 2>&1 | tail -10
```
Expected: entrambi i test `k3s` passano.

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py \
    tools/controlplane/tests/test_scenario_builders.py && \
git commit -m "$(cat <<'EOF'
feat: add K3sJunitCurlPlan builder

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `HelmStackPlan` builder

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/helm_stack.py`
- Test: `tools/controlplane/tests/test_scenario_builders.py`

- [ ] **Step 1: Aggiungi test**

Leggi il file, poi aggiungi:

```python
def _make_helm_stack_request() -> E2eRequest:
    return E2eRequest(
        scenario="helm-stack",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )


def test_helm_stack_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="loadtest.install_k6")
    plan = HelmStackPlan(
        scenario=MagicMock(), request=_make_helm_stack_request(), steps=[step], runner=MagicMock()
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["loadtest.install_k6"]


def test_build_helm_stack_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.helm_stack import (
        HelmStackPlan,
        build_helm_stack_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_helm_stack_plan(runner, _make_helm_stack_request())

    assert isinstance(plan, HelmStackPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "vm.ensure_running" in plan.task_ids
    assert "helm.deploy_control_plane" in plan.task_ids
    assert "loadtest.install_k6" in plan.task_ids
    assert "loadtest.run" in plan.task_ids
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_helm_stack_plan_satisfies_protocol -v 2>&1 | head -10
```

- [ ] **Step 3: Crea `scenario/scenarios/helm_stack.py`**

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
class HelmStackPlan:
    """ScenarioPlan Protocol implementation for helm-stack."""

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


def build_helm_stack_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> HelmStackPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("helm-stack")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "helm-stack",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
    )
    return HelmStackPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
```

- [ ] **Step 4: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -k "helm_stack" -v 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/helm_stack.py \
    tools/controlplane/tests/test_scenario_builders.py && \
git commit -m "$(cat <<'EOF'
feat: add HelmStackPlan builder

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `CliStackPlan` builder

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_stack.py`
- Test: `tools/controlplane/tests/test_scenario_builders.py`

**Nota:** `cli-stack` usa `cli.fn_apply_selected` (la CLI, non REST API) — non c'è remapping a `functions.register` per questo scenario. Il loadtest remapping (Piano 2) è specifico per `two-vm-loadtest` e `azure-vm-loadtest`.

- [ ] **Step 1: Aggiungi test**

Leggi il file, poi aggiungi:

```python
def _make_cli_stack_request() -> E2eRequest:
    return E2eRequest(
        scenario="cli-stack",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
        namespace="nanofaas-cli-stack-e2e",
    )


def test_cli_stack_plan_satisfies_protocol() -> None:
    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    step = ScenarioPlanStep(summary="x", command=["echo"], step_id="cli.build_install_dist")
    plan = CliStackPlan(
        scenario=MagicMock(), request=_make_cli_stack_request(), steps=[step], runner=MagicMock()
    )
    assert isinstance(plan, ScenarioPlanProtocol)
    assert plan.task_ids == ["cli.build_install_dist"]


def test_build_cli_stack_plan_returns_correct_type(tmp_path: Path) -> None:
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.cli_stack import (
        CliStackPlan,
        build_cli_stack_plan,
    )
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = build_cli_stack_plan(runner, _make_cli_stack_request())

    assert isinstance(plan, CliStackPlan)
    assert isinstance(plan, ScenarioPlanProtocol)
    assert len(plan.task_ids) > 0
    assert "vm.ensure_running" in plan.task_ids
    assert "cli.build_install_dist" in plan.task_ids
    assert "cli.fn_apply_selected" in plan.task_ids
    assert "vm.down" in plan.task_ids
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_cli_stack_plan_satisfies_protocol -v 2>&1 | head -10
```

- [ ] **Step 3: Crea `scenario/scenarios/cli_stack.py`**

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
class CliStackPlan:
    """ScenarioPlan Protocol implementation for cli-stack."""

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


def build_cli_stack_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> CliStackPlan:
    from controlplane_tool.e2e.e2e_runner import plan_recipe_steps
    from controlplane_tool.scenario.catalog import resolve_scenario
    scenario = resolve_scenario("cli-stack")
    steps = plan_recipe_steps(
        runner.paths.workspace_root,
        request,
        "cli-stack",
        shell=runner.shell,
        manifest_root=runner.manifest_root,
        host_resolver=runner._host_resolver,
    )
    return CliStackPlan(
        scenario=scenario,
        request=request,
        steps=steps,
        runner=runner,
    )
```

- [ ] **Step 4: Esegui i test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -k "cli_stack" -v 2>&1 | tail -10
```

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_stack.py \
    tools/controlplane/tests/test_scenario_builders.py && \
git commit -m "$(cat <<'EOF'
feat: add CliStackPlan builder

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Aggiorna `e2e_runner.plan()` e `run()` per i 3 nuovi builder

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

**Contesto:** Attualmente nel ramo `if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack", ...}`, dopo i check per two-vm e azure-vm, c'è ancora `return ScenarioPlan(...)` che viene usato per k3s, helm, cli-stack. Questo task lo sostituisce con i nuovi builder.

Leggi il file prima di modificarlo — in particolare le righe 309-355 (`plan()`) e 615-635 (`run()`).

- [ ] **Step 1: Aggiungi test**

Leggi `tools/controlplane/tests/test_scenario_builders.py`, poi aggiungi:

```python
def test_e2e_runner_plan_returns_k3s_junit_curl_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return K3sJunitCurlPlan for k3s-junit-curl."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_k3s_request())

    assert isinstance(plan, K3sJunitCurlPlan)
    assert "vm.ensure_running" in plan.task_ids
    assert "tests.run_k3s_curl_checks" in plan.task_ids


def test_e2e_runner_plan_returns_helm_stack_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return HelmStackPlan for helm-stack."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_helm_stack_request())

    assert isinstance(plan, HelmStackPlan)
    assert "loadtest.install_k6" in plan.task_ids


def test_e2e_runner_plan_returns_cli_stack_builder(tmp_path: Path) -> None:
    """E2eRunner.plan() must return CliStackPlan for cli-stack."""
    from controlplane_tool.e2e.e2e_runner import E2eRunner
    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
    from controlplane_tool.core.shell_backend import RecordingShell

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(_make_cli_stack_request())

    assert isinstance(plan, CliStackPlan)
    assert "cli.build_install_dist" in plan.task_ids
```

- [ ] **Step 2: Esegui per verificare che falliscono**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py::test_e2e_runner_plan_returns_k3s_junit_curl_builder -v 2>&1 | head -10
```
Expected: `AssertionError` — `plan()` ritorna ancora `ScenarioPlan` generico.

- [ ] **Step 3: Aggiungi i 3 builder dispatch in `plan()`**

In `e2e_runner.py`, nel metodo `plan()`, trova il blocco:
```python
            if request.scenario == "two-vm-loadtest":
                return build_two_vm_loadtest_plan(self, plan_request)
            if request.scenario == "azure-vm-loadtest":
                return build_azure_vm_loadtest_plan(self, plan_request)
            return ScenarioPlan(
                scenario=scenario,
                request=plan_request,
                steps=plan_recipe_steps(
                    ...
                ),
            )
```

Sostituisci con:
```python
            if request.scenario == "two-vm-loadtest":
                return build_two_vm_loadtest_plan(self, plan_request)
            if request.scenario == "azure-vm-loadtest":
                return build_azure_vm_loadtest_plan(self, plan_request)
            if request.scenario == "k3s-junit-curl":
                from controlplane_tool.scenario.scenarios.k3s_junit_curl import build_k3s_junit_curl_plan
                return build_k3s_junit_curl_plan(self, plan_request)
            if request.scenario == "helm-stack":
                from controlplane_tool.scenario.scenarios.helm_stack import build_helm_stack_plan
                return build_helm_stack_plan(self, plan_request)
            if request.scenario == "cli-stack":
                from controlplane_tool.scenario.scenarios.cli_stack import build_cli_stack_plan
                return build_cli_stack_plan(self, plan_request)
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

**Nota:** il `return ScenarioPlan(...)` finale diventa dead code per tutti i 5 scenari recipe — verrà rimosso in Piano 5. Lasciarlo ora evita di dover aggiornare test che non sono in scope.

- [ ] **Step 4: Aggiorna `run()` per includere i 3 nuovi builder**

Trova:
```python
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan)):
            plan.run()
        else:
            self.execute(plan, event_listener=event_listener)
```

Sostituisci con:
```python
        if isinstance(plan, (TwoVmLoadtestPlan, AzureVmLoadtestPlan)):
            plan.run()
        elif isinstance(plan, (K3sJunitCurlPlan, HelmStackPlan, CliStackPlan)):
            plan.run()
        else:
            self.execute(plan, event_listener=event_listener)
```

Aggiungi le lazy imports nella `run()` — le import per i 3 nuovi builder devono essere aggiunte accanto ai 2 esistenti, già lazy. Guarda come sono fatte le esistenti per TwoVmLoadtestPlan e AzureVmLoadtestPlan all'inizio di `run()` e replica il pattern per i 3 nuovi.

**Alternativa più pulita** (suggerita se le lazy import all'inizio di `run()` diventano troppe):
```python
        from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
        from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
        from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
        _BUILDER_TYPES = (TwoVmLoadtestPlan, AzureVmLoadtestPlan, K3sJunitCurlPlan, HelmStackPlan, CliStackPlan)
        if isinstance(plan, _BUILDER_TYPES):
            plan.run()
        else:
            self.execute(plan, event_listener=event_listener)
```

- [ ] **Step 5: Esegui test_scenario_builders.py + test_e2e_runner.py**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py tests/test_e2e_runner.py -q 2>&1 | tail -15
```
Expected: tutti i test passano (inclusi quelli esistenti su k3s, helm-stack, cli-stack che accedono `plan.steps` — sono compatibili con i builder).

- [ ] **Step 6: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=no 2>&1 | tail -5
```
Expected: 1050+ passano, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce (pre-esistente).

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_scenario_builders.py && \
git commit -m "$(cat <<'EOF'
feat: e2e_runner.plan() returns builder plans for k3s, helm-stack, cli-stack scenarios

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_builders.py -v 2>&1 | tail -25
```

Expected: tutti i test in `test_scenario_builders.py` passano (ora ~18 test totali).

---

## Note su Piano 5

Piano 5 (futuro) completerà la rimozione del vecchio sistema:
- Rimozione del `return ScenarioPlan(...)` dead code in `plan()` per il ramo recipe
- Rimozione del parametro `plan_recipe_steps` da `cli_stack_runner.py` (migrazione a `build_cli_stack_plan`)
- Eventuale rimozione di `ScenarioPlanner.vm_backed_steps` per k3s-junit-curl e helm-stack (ora dead code in `plan()`, ma usato da `plan_all()`)
- Migrazione di `plan_all()` a usare i builder
- Rimozione di `plan_recipe_steps` da `e2e_runner.py`
- Rimozione di `scenario_planner.py` se non usato altrove
