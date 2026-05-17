# Workflow Task Composition — Piano 14: Rinomina legacy ScenarioPlan → E2ePlan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Risolvere il conflitto di nomi tra il dataclass legacy `ScenarioPlan` (in `e2e_runner.py`) e il Protocol `ScenarioPlan` (in `scenarios/__init__.py`), rinominando il dataclass in `E2ePlan` e importando il Protocol con il suo nome canonico per annotazioni API pubbliche accurate.

**Architecture:** Il dataclass legacy viene rinominato `E2ePlan`. Il Protocol `ScenarioPlan` (da `scenarios/__init__.py`) diventa il tipo di ritorno pubblico di `plan()`, `plan_all()`, `run()`, `run_all()`. I metodi interni (`_execute_steps`, `execute`) mantengono `E2ePlan` come tipo di parametro concreto — accedono a `plan.steps` che non è nel Protocol. Aggiornato anche `E2ePlan.run(self, event_listener=None)` per soddisfare strutturalmente il Protocol.

**Tech Stack:** Python 3.11+, dataclasses. 10 file toccati: `e2e_runner.py` + 7 builder files + 2 test files.

---

## Background — conflitto nomi

```python
# scenarios/__init__.py — Protocol pubblico
@runtime_checkable
class ScenarioPlan(Protocol):
    @property
    def task_ids(self) -> list[str]: ...
    def run(self, event_listener=None) -> None: ...

# e2e_runner.py — dataclass concreto (STESSO NOME)
@dataclass(frozen=True)
class ScenarioPlan:          # ← rinominato in E2ePlan
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    executor: "Callable[[ScenarioPlan], None] | None" = ...

    def run(self) -> None:   # ← aggiunto event_listener=None
        ...
```

**Dopo Piano 14:**
```python
# e2e_runner.py
from controlplane_tool.scenario.scenarios import ScenarioPlan  # Protocol

@dataclass(frozen=True)
class E2ePlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    executor: "Callable[[E2ePlan], None] | None" = ...

    def run(self, event_listener=None) -> None:
        if self.executor is None:
            raise RuntimeError("E2ePlan.run() requires an executor — use E2eRunner.execute(plan)")
        self.executor(self)

# Annotazioni pubbliche ora corrette:
def plan(self, request: E2eRequest) -> ScenarioPlan: ...
def plan_all(self, ...) -> list[ScenarioPlan]: ...
def run(self, ...) -> ScenarioPlan: ...
def run_all(self, ...) -> list[ScenarioPlan]: ...

# Dispatch usa tipo concreto:
isinstance(plan, E2ePlan)  # in run() e run_all()

# Metodi interni usano tipo concreto:
def _execute_steps(self, plan: E2ePlan, ...) -> None: ...
def execute(self, plan: E2ePlan, ...) -> None: ...
```

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — classe rinominata, import Protocol, annotazioni
- 7 builder files (tutti i file in `scenario/scenarios/*.py` che importano `ScenarioPlan` da `e2e_runner`)
- `tools/controlplane/tests/test_e2e_runner.py` — riga 1185
- `tools/controlplane/tests/test_recipe_execution_hooks.py` — righe 124 e 139

---

## Task 1: Rinomina in `e2e_runner.py`

**File:** `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

- [ ] **Step 1: Aggiungi test che verifica il tipo di ritorno pubblico**

Leggi le ultime righe di `test_e2e_runner.py`, poi aggiungi:

```python
def test_plan_returns_scenario_plan_protocol(tmp_path: Path) -> None:
    """plan() must return an object satisfying the ScenarioPlan Protocol."""
    from controlplane_tool.scenario.scenarios import ScenarioPlan as ScenarioPlanProtocol

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(scenario="docker", runtime="java"))

    assert isinstance(plan, ScenarioPlanProtocol), f"Expected ScenarioPlanProtocol, got {type(plan)}"
```

Run per verificare che fallisce (il docker plan è un `E2ePlan` che non ha ancora `run(event_listener=None)`):
```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_returns_scenario_plan_protocol -v 2>&1 | tail -6
```
Expected: potrebbe già PASSARE (runtime check solo su attributo `run`, non firma). Verifica e prendi nota.

- [ ] **Step 2: Esegui la rinomina globale in `e2e_runner.py`**

Leggi il file intero (o almeno le sezioni rilevanti). Poi applica questi cambi con Edit tool:

**2a. Aggiungi import del Protocol (dopo gli import esistenti da `scenario.scenarios`):**

Cerca la riga:
```python
from controlplane_tool.scenario.catalog import (
```
o la zona degli import. Aggiungi:
```python
from controlplane_tool.scenario.scenarios import ScenarioPlan
```

**2b. Rinomina la classe `ScenarioPlan` → `E2ePlan`:**

Trova:
```python
@dataclass(frozen=True)
class ScenarioPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    executor: "Callable[[ScenarioPlan], None] | None" = field(
        default=None, repr=False, compare=False
    )

    @property
    def task_ids(self) -> list[str]:
        """Step IDs in execution order, for TUI dry-run planning."""
        return [s.step_id for s in self.steps if s.step_id]

    def run(self) -> None:
        if self.executor is None:
            raise RuntimeError(
                "ScenarioPlan.run() requires an executor — use E2eRunner.execute(plan)"
            )
        self.executor(self)
```

Sostituisci con:
```python
@dataclass(frozen=True)
class E2ePlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    executor: "Callable[[E2ePlan], None] | None" = field(
        default=None, repr=False, compare=False
    )

    @property
    def task_ids(self) -> list[str]:
        return [s.step_id for s in self.steps if s.step_id]

    def run(self, event_listener=None) -> None:
        if self.executor is None:
            raise RuntimeError(
                "E2ePlan.run() requires an executor — use E2eRunner.execute(plan)"
            )
        self.executor(self)
```

**2c. Aggiorna le annotazioni dei metodi interni (usano tipo concreto):**

- `def execute(self, plan: ScenarioPlan, ...)` → `def execute(self, plan: E2ePlan, ...)`
- `def _execute_steps(self, plan: ScenarioPlan, ...)` → `def _execute_steps(self, plan: E2ePlan, ...)`
- `def _execute_step(self, plan: ScenarioPlan, ...)` → `def _execute_step(self, plan: E2ePlan, ...)`

**2d. Aggiorna i costruttori `ScenarioPlan(...)` → `E2ePlan(...)`:**

Ci sono due costruttori nel metodo `plan()` e `plan_all()`:
- `return ScenarioPlan(scenario=scenario, request=request, steps=steps)` (in `plan()` — local path)
- `plans.append(ScenarioPlan(scenario=scenario, request=request, steps=self._planner.local_steps(request)))` (in `plan_all()`)

**2e. Aggiorna i `isinstance` in `run()` e `run_all()`:**
- `isinstance(plan, ScenarioPlan)` → `isinstance(plan, E2ePlan)` (2 occorrenze)

**2f. Aggiorna le annotazioni dei metodi pubblici (usano Protocol):**
- `def plan(self, request: E2eRequest) -> ScenarioPlan:` — ora `ScenarioPlan` è il Protocol ✓ (no change needed to text, but semantics change)
- `def plan_all(self, ...) -> list[ScenarioPlan]:` ✓
- `def run(self, ...) -> ScenarioPlan:` ✓
- `def run_all(self, ...) -> list[ScenarioPlan]:` ✓
- `plans: list[ScenarioPlan] = []` — usare `list[ScenarioPlan]` (Protocol) è corretto ✓

**2g. Verifica import: `ScenarioPlan` ora è il Protocol da `scenarios/__init__.py`. Assicurati che non ci siano altri usi di `ScenarioPlan` che si riferiscano al vecchio dataclass.**

- [ ] **Step 3: Verifica che la suite non sia rotta**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -10
```
Expected: errori di ImportError negli 7 builder files (importano ancora `ScenarioPlan` da `e2e_runner`). Non sono 1066+ ancora.

- [ ] **Step 4: Commit parziale di `e2e_runner.py`**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
refactor: rename legacy ScenarioPlan dataclass to E2ePlan in e2e_runner

Imports ScenarioPlan Protocol from scenarios/__init__.py for public API
annotations. E2ePlan is the concrete internal execution plan data holder.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Aggiorna builder files e test files

**Files:**
- 7 builder files in `tools/controlplane/src/controlplane_tool/scenario/scenarios/`
- `tools/controlplane/tests/test_recipe_execution_hooks.py`
- `tools/controlplane/tests/test_e2e_runner.py` (riga 1185)

- [ ] **Step 1: Aggiorna i 7 builder files**

In OGNUNO dei seguenti file, trova il metodo `run()` e cambia:
```python
        from controlplane_tool.e2e.e2e_runner import ScenarioPlan
        legacy = ScenarioPlan(
```
con:
```python
        from controlplane_tool.e2e.e2e_runner import E2ePlan
        legacy = E2ePlan(
```

File da aggiornare:
- `scenario/scenarios/two_vm_loadtest.py`
- `scenario/scenarios/azure_vm_loadtest.py`
- `scenario/scenarios/k3s_junit_curl.py`
- `scenario/scenarios/helm_stack.py`
- `scenario/scenarios/cli_stack.py`
- `scenario/scenarios/cli_vm.py`
- `scenario/scenarios/cli_host.py`

- [ ] **Step 2: Aggiorna `test_recipe_execution_hooks.py`**

Leggi le righe 120–145 di `test_recipe_execution_hooks.py`. Trova le due righe:
```python
from controlplane_tool.e2e.e2e_runner import ScenarioPlan
```
e cambia entrambe in:
```python
from controlplane_tool.e2e.e2e_runner import E2ePlan
```
Aggiorna anche gli usi: `ScenarioPlan(...)` → `E2ePlan(...)`.

- [ ] **Step 3: Aggiorna `test_e2e_runner.py` riga 1185**

Trova:
```python
    from controlplane_tool.e2e.e2e_runner import ScenarioPlan as LegacyPlan
```
Cambia in:
```python
    from controlplane_tool.e2e.e2e_runner import E2ePlan as LegacyPlan
```

- [ ] **Step 4: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1067 passed (il nuovo test `test_plan_returns_scenario_plan_protocol` aggiunge 1), solo il pre-existing failure rimane.

- [ ] **Step 5: Verifica che `E2ePlan` non sia esportato accidentalmente**

```bash
grep -rn "from controlplane_tool.e2e.e2e_runner import ScenarioPlan" /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/ /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/tests/ 2>/dev/null
```
Expected: nessun risultato (tutti gli import di `ScenarioPlan` da `e2e_runner` sono stati rimossi o aggiornati).

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/helm_stack.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_stack.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_vm.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_host.py \
    tools/controlplane/tests/test_recipe_execution_hooks.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
refactor: update all ScenarioPlan → E2ePlan imports in builders and tests

Completes Piano 14: builders and test files now import E2ePlan (the
concrete execution plan) instead of the old ScenarioPlan dataclass name.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
# Nessun riferimento rimasto all'import del vecchio nome
grep -rn "from controlplane_tool.e2e.e2e_runner import ScenarioPlan" \
    /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/ 2>/dev/null
# → nessun output

# Suite
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q 2>&1 | tail -5
# → 1067 passed, 1 failed (pre-existing)
```

Dopo questo piano:
- `ScenarioPlan` in `e2e_runner.py` e nel resto del codebase significa sempre il **Protocol** pubblico
- `E2ePlan` è il tipo concreto interno usato da `_execute_steps`, `execute`, e dai builder come wrapper
- Le annotazioni dei metodi pubblici (`plan()`, `run()`, ecc.) sono ora strutturalmente accurate
- `isinstance(plan, E2ePlan)` nei dispatch è esplicito sul tipo concreto

---

## Note su Piano 15

Piano 15 (futuro, se necessario): `plan_all()` usa ancora `_planner.vm_backed_steps(request, include_bootstrap=...)` per cli/cli-host/cli-stack inline invece di factory functions. Questo è l'unico punto di inconsistenza rimasto. Si potrebbe aggiungere un parametro `include_bootstrap: bool = True` ai factory `build_cli_vm_plan` / `build_cli_host_plan` / `build_cli_stack_plan` per unificare. Basso impatto — lasciare per valutazione futura.
