# Workflow Task Composition — Piano 15: Final cleanup (tre rough edges)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Risolvere tre rough edges rimasti dopo Piano 14: (1) `run_all()` non forwarda `event_listener`; (2) `plan_all()` costruisce `CliVmPlan`/`CliHostPlan` inline invece di usare le factory (cli-stack escluso — usa recipe diversa); (3) `plan()` ha due blocchi di dispatch separati invece di un'unica catena.

**Architecture:** Tre modifiche indipendenti in `e2e_runner.py` + due builder files. Nessuna dipendenza tra i task — possono essere eseguiti in ordine ma ognuno è autonomo.

**Tech Stack:** Python 3.11+. File toccati: `e2e_runner.py`, `cli_vm.py`, `cli_host.py`, `test_e2e_runner.py`.

---

## Background — stato corrente

### Punto 1 — `run_all()` non forwarda `event_listener` (riga 686)
```python
for plan in plans:
    if isinstance(plan, E2ePlan):
        self._execute_steps(plan)          # ← no event_listener
    else:
        plan.run()                          # ← no event_listener
```
`run()` single-scenario forwarda correttamente `event_listener=event_listener`. `run_all()` non ha nemmeno il parametro nella firma.

### Punto 2 — `plan_all()` costruisce cli/cli-host inline (righe 446–456)
```python
steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
vm_bootstrap_planned = True
if scenario.name == "cli-stack":
    plans.append(CliStackPlan(..., steps=steps, runner=self))
elif scenario.name == "cli":
    plans.append(CliVmPlan(..., steps=steps, runner=self))     # ← inline
elif scenario.name == "cli-host":
    plans.append(CliHostPlan(..., steps=steps, runner=self))   # ← inline
```
I 4 builder recipe-based usano factory (`build_k3s_junit_curl_plan(self, request)`). I builder cli/cli-host sono costruiti inline. `cli-stack` rimane inline (la sua factory usa `plan_recipe_steps()` — step diversi da `vm_backed_steps()`, inconsistenza più profonda fuori scope).

**Fix:** aggiungere `include_bootstrap: bool = True` a `build_cli_vm_plan()` e `build_cli_host_plan()`, poi usarle in `plan_all()`.

### Punto 3 — `plan()` due blocchi (righe 318–357)
```python
# Blocco 1: scenario in set → VM resolution + dispatch recipe-based
if request.scenario in {"k3s-junit-curl", "helm-stack", "cli-stack",
                         "two-vm-loadtest", "azure-vm-loadtest"}:
    plan_request = request
    recipe = build_scenario_recipe(request.scenario)
    if (request.vm is None and recipe.requires_managed_vm) or ...:
        ...
        plan_request = request.model_copy(update=updates)
    if request.scenario == "two-vm-loadtest": return ...
    ...
    if request.scenario == "cli-stack": return ...

# Blocco 2: fallback VM → cli/cli-host
if scenario.requires_vm:
    if request.scenario == "cli": return ...
    if request.scenario == "cli-host": return ...
    raise ValueError(...)
steps = self._planner.local_steps(request)
return E2ePlan(...)
```

**Fix:** estrarre la VM resolution in `_prepare_recipe_request()`, appiattire `plan()` in una singola catena if-elif.

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py` — tutti e tre i task
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_vm.py` — Task 2
- `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_host.py` — Task 2
- `tools/controlplane/tests/test_e2e_runner.py` — test per ogni task

---

## Task 1: Fix `run_all()` — aggiungi e forwarda `event_listener`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Aggiungi test che fallisce**

Leggi le ultime righe di `test_e2e_runner.py`, poi aggiungi:

```python
def test_run_all_forwards_event_listener_to_builder_plans(tmp_path: Path) -> None:
    """run_all() must forward event_listener to plan.run() for builder plans."""
    from unittest.mock import patch
    from controlplane_tool.scenario.scenarios.cli_vm import CliVmPlan
    from controlplane_tool.scenario.components.executor import ScenarioPlanStep

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    vm = VmRequest(lifecycle="multipass", name="nanofaas-e2e")
    fake_steps = [ScenarioPlanStep(summary="s", command=["echo", "hi"], step_id="s.one")]
    fake_plan = CliVmPlan(
        scenario=runner.plan(E2eRequest(scenario="cli", runtime="java", vm=vm)).scenario,
        request=E2eRequest(scenario="cli", runtime="java", vm=vm),
        steps=fake_steps,
        runner=runner,
    )
    received_events: list = []

    def listener(event):
        received_events.append(event)

    with patch.object(runner, "plan_all", return_value=[fake_plan]):
        with patch.object(fake_plan, "run") as mock_run:
            runner.run_all(event_listener=listener)

    mock_run.assert_called_once_with(event_listener=listener)
```

Run per verificare che fallisce:
```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_run_all_forwards_event_listener_to_builder_plans -v 2>&1 | tail -8
```
Expected: FAIL — `run_all()` non ha il parametro `event_listener`.

- [ ] **Step 2: Aggiorna firma di `run_all()`**

Leggi la firma di `run_all()` (righe 652–663). Aggiungi `event_listener` come parametro keyword-only:

```python
    def run_all(
        self,
        *,
        only: list[str] | None = None,
        skip: list[str] | None = None,
        runtime: str = "java",
        vm_request: VmRequest | None = None,
        loadgen_vm_request: VmRequest | None = None,
        cleanup_vm: bool = True,
        namespace: str | None = None,
        local_registry: str = "localhost:5000",
        event_listener: Callable[[ScenarioStepEvent], None] | None = None,
    ) -> list[ScenarioPlan]:
```

- [ ] **Step 3: Forwarda `event_listener` nel loop**

Trova il loop (riga ~682):
```python
            for plan in plans:
                if isinstance(plan, E2ePlan):
                    self._execute_steps(plan)
                else:
                    plan.run()
```

Sostituisci con:
```python
            for plan in plans:
                if isinstance(plan, E2ePlan):
                    self._execute_steps(plan, event_listener=event_listener)
                else:
                    plan.run(event_listener=event_listener)
```

- [ ] **Step 4: Verifica che il test passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_run_all_forwards_event_listener_to_builder_plans -v 2>&1 | tail -6
```
Expected: PASS.

- [ ] **Step 5: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```
Expected: 1068 passed, 1 pre-existing failure.

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
fix: run_all() now accepts and forwards event_listener to plan execution

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `include_bootstrap` nei factory cli/cli-host, rimuovi inline da `plan_all()`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_vm.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_host.py`
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Aggiorna `build_cli_vm_plan()` in `cli_vm.py`**

Leggi `cli_vm.py`. Trova:
```python
def build_cli_vm_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> CliVmPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("cli")
    steps = runner._planner.vm_backed_steps(request)
    return CliVmPlan(scenario=scenario, request=request, steps=steps, runner=runner)
```

Sostituisci con:
```python
def build_cli_vm_plan(
    runner: "E2eRunner",
    request: E2eRequest,
    include_bootstrap: bool = True,
) -> CliVmPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("cli")
    steps = runner._planner.vm_backed_steps(request, include_bootstrap=include_bootstrap)
    return CliVmPlan(scenario=scenario, request=request, steps=steps, runner=runner)
```

- [ ] **Step 2: Aggiorna `build_cli_host_plan()` in `cli_host.py`**

Stessa modifica — aggiungi `include_bootstrap: bool = True` e passalo a `vm_backed_steps`.

- [ ] **Step 3: Aggiorna `plan_all()` — usa factory per cli e cli-host**

Leggi le righe 446–459 di `e2e_runner.py`. Trova il blocco:
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
                    raise ValueError(f"Unsupported VM-backed scenario in plan_all(): {scenario.name!r}")
                continue
```

Sostituisci con:
```python
                if scenario.name == "cli-stack":
                    steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
                    plans.append(CliStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "cli":
                    from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
                    plans.append(build_cli_vm_plan(self, request, include_bootstrap=not vm_bootstrap_planned))
                elif scenario.name == "cli-host":
                    from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
                    plans.append(build_cli_host_plan(self, request, include_bootstrap=not vm_bootstrap_planned))
                else:
                    raise ValueError(f"Unsupported VM-backed scenario in plan_all(): {scenario.name!r}")
                vm_bootstrap_planned = True
                continue
```

**Nota:** `vm_bootstrap_planned = True` si sposta dopo il dispatch (si applica a tutti i rami). cli-stack rimane inline perché la sua factory usa `plan_recipe_steps()` — step diversi da `vm_backed_steps()`.

- [ ] **Step 4: Verifica che i test di consistenza plan/plan_all ancora passino**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "consistent_step_ids" -v 2>&1 | tail -8
```
Expected: 3 passed (k3s, cli, cli-host).

- [ ] **Step 5: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```
Expected: 1068 passed (o 1069 se il test di Task 1 è già presente), 1 failure pre-existing.

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_vm.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/cli_host.py \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py && \
git commit -m "$(cat <<'EOF'
refactor: use factory functions for cli/cli-host in plan_all() with include_bootstrap

build_cli_vm_plan() and build_cli_host_plan() now accept include_bootstrap
so plan_all() can delegate to factory functions instead of inline construction.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Unifica `plan()` — estrai `_prepare_recipe_request()`, appiattisci dispatch

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Aggiungi test di non-regressione**

```python
def test_plan_docker_returns_e2e_plan(tmp_path: Path) -> None:
    """plan() for local scenario returns E2ePlan (non-VM path)."""
    from controlplane_tool.e2e.e2e_runner import E2ePlan

    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    plan = runner.plan(E2eRequest(scenario="docker", runtime="java"))

    assert isinstance(plan, E2ePlan)
```

Run per confermare che già passa (non è un test failing-first per la refactoring strutturale):
```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_docker_returns_e2e_plan -v 2>&1 | tail -4
```

- [ ] **Step 2: Estrai `_prepare_recipe_request()` in `e2e_runner.py`**

Leggi le righe 318–347 di `e2e_runner.py` (il blocco della VM resolution nel metodo `plan()`).

Aggiungi un nuovo metodo privato dopo `__init__` e prima di `plan()`:

```python
    def _prepare_recipe_request(self, request: E2eRequest) -> E2eRequest:
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
            return request.model_copy(update=updates)
        return request
```

- [ ] **Step 3: Riscrivi `plan()` con dispatch piatto**

Trova il metodo `plan()` (righe 312–357). Sostituisci il corpo con la versione unificata:

```python
    def plan(self, request: E2eRequest) -> ScenarioPlan:
        scenario = resolve_scenario(request.scenario)
        if request.runtime not in scenario.supported_runtimes:
            raise ValueError(
                f"Scenario '{request.scenario}' does not support runtime '{request.runtime}'"
            )
        if request.scenario == "two-vm-loadtest":
            from controlplane_tool.scenario.scenarios.two_vm_loadtest import build_two_vm_loadtest_plan
            return build_two_vm_loadtest_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "azure-vm-loadtest":
            from controlplane_tool.scenario.scenarios.azure_vm_loadtest import build_azure_vm_loadtest_plan
            return build_azure_vm_loadtest_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "k3s-junit-curl":
            from controlplane_tool.scenario.scenarios.k3s_junit_curl import build_k3s_junit_curl_plan
            return build_k3s_junit_curl_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "helm-stack":
            from controlplane_tool.scenario.scenarios.helm_stack import build_helm_stack_plan
            return build_helm_stack_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "cli-stack":
            from controlplane_tool.scenario.scenarios.cli_stack import build_cli_stack_plan
            return build_cli_stack_plan(self, self._prepare_recipe_request(request))
        if request.scenario == "cli":
            from controlplane_tool.scenario.scenarios.cli_vm import build_cli_vm_plan
            return build_cli_vm_plan(self, request)
        if request.scenario == "cli-host":
            from controlplane_tool.scenario.scenarios.cli_host import build_cli_host_plan
            return build_cli_host_plan(self, request)
        if scenario.requires_vm:
            raise ValueError(f"Unsupported VM-backed scenario: {request.scenario!r}")
        steps = self._planner.local_steps(request)
        return E2ePlan(scenario=scenario, request=request, steps=steps)
```

- [ ] **Step 4: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: tutti i test esistenti passano (la refactoring strutturale non cambia il comportamento).

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
refactor: unify plan() dispatch — extract _prepare_recipe_request(), flatten if-chain

Removes the set-membership guard and two-block structure. All scenario
dispatches are now a flat if-chain in order. VM resolution logic extracted
to _prepare_recipe_request() private helper.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q 2>&1 | tail -5
# → 1069+ passed, 1 failed (pre-existing)

grep -n "if request.scenario in {" \
    /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
# → nessuna occorrenza (set-check rimosso)
```

Dopo questo piano:
- `run_all()` forwarda `event_listener` a tutti i piani
- `plan_all()` usa factory per cli e cli-host (con `include_bootstrap`)
- `plan()` è una singola catena if lineare — nessun blocco annidato, nessun set-check
- `_prepare_recipe_request()` contiene la logica di VM resolution per scenari recipe-based
