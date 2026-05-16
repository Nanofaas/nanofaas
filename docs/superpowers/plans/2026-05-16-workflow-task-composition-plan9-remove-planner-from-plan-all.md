# Workflow Task Composition — Piano 9: Remove ScenarioPlanner from plan_all() for k3s and helm

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminare la dipendenza da `ScenarioPlanner.vm_backed_steps()` per k3s-junit-curl e helm-stack in `plan_all()`, usando i builder factory direttamente — allineando `plan_all()` a `plan()` per questi due scenari.

**Architecture:** `plan_all()` per k3s e helm passa da `K3sJunitCurlPlan(steps=_planner.vm_backed_steps(...))` a `build_k3s_junit_curl_plan(self, request)`. L'ottimizzazione `include_bootstrap` non si applica a k3s e helm (li ignorano già nel planner). I branch k3s e helm in `vm_backed_steps()` diventano dead code e vengono rimossi. I metodi `k3s_junit_curl_tail_steps()` e `helm_stack_tail_steps()` restano (sono testati direttamente).

**Tech Stack:** Python 3.11+, dataclasses, builder pattern. Solo `e2e_runner.py`, `scenario_planner.py`, e `test_e2e_runner.py` modificati.

---

## Background — perché `include_bootstrap` non conta per k3s e helm

In `ScenarioPlanner.vm_backed_steps()`:

```python
def vm_backed_steps(self, request, *, include_bootstrap=True):
    if request.scenario == "k3s-junit-curl":
        return self.k3s_junit_curl_steps(request)  # include_bootstrap IGNORATO
    if request.scenario == "helm-stack":
        return [*self.k3s_vm_prelude_steps(request), *self.helm_stack_tail_steps(request)]  # IGNORATO
    steps = []
    if include_bootstrap:  # solo qui include_bootstrap conta
        steps.extend(self.vm_bootstrap_steps(request))
    steps.extend(self.vm_scenario_steps(request))
    return steps
```

Per k3s e helm, `include_bootstrap` viene ignorato — restituiscono sempre l'insieme completo di step. Quindi sostituirli con builder diretti non perde nessuna ottimizzazione.

**Nota sui mock nei test esistenti:**
I test `test_run_all_bootstraps_vm_once_and_reuses_it` e `test_run_all_tears_down_vm_when_cleanup_vm_true` monkeypatchard `runner._planner._k3s_curl_runner`. Dopo Piano 9, i builder usano un inner runner (creato dentro `plan_recipe_steps`) — il mock sull'outer runner diventa dead code. Tuttavia, con `RecordingShell` che restituisce successo per tutti i comandi, il verify step funziona ugualmente. I test continuano a passare (il mock è ora no-op, non viene rimosso in questo piano per non complicare lo scope).

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:419–437` — `plan_all()` per k3s e helm usa builder factory diretti
- `tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py:615–621` — rimuove branch k3s e helm da `vm_backed_steps()` (ora dead code)
- `tools/controlplane/tests/test_e2e_runner.py` — 1 test che verifica la consistenza plan()/plan_all() per k3s

---

## Task 1: `plan_all()` usa builder factory per k3s-junit-curl e helm-stack

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py:419–437`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py:615–621`
- Test: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Leggi il codice attuale**

```bash
sed -n '418,445p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py
sed -n '609,627p' /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py
```

- [ ] **Step 2: Aggiungi test che verifica la consistenza plan() / plan_all()**

Leggi le ultime righe di `tools/controlplane/tests/test_e2e_runner.py`, poi aggiungi:

```python
def test_plan_and_plan_all_produce_consistent_step_ids_for_k3s(tmp_path: Path) -> None:
    """plan() and plan_all() must return the same step IDs for k3s-junit-curl.

    Before this fix, plan() used the recipe builder (plan_recipe_steps) while
    plan_all() used _planner.vm_backed_steps() — two different implementations
    that could diverge. After this fix, both use the same builder factory.
    """
    runner = E2eRunner(repo_root=Path("/repo"), shell=RecordingShell(), manifest_root=tmp_path)
    request = E2eRequest(
        scenario="k3s-junit-curl",
        runtime="java",
        vm=VmRequest(lifecycle="multipass", name="nanofaas-e2e"),
    )

    plan_single = runner.plan(request)
    plans_all = runner.plan_all(only=["k3s-junit-curl"])

    assert len(plans_all) == 1
    assert plan_single.task_ids == plans_all[0].task_ids, (
        "plan() and plan_all() must produce the same step IDs for k3s-junit-curl"
    )
```

- [ ] **Step 3: Verifica che il test fallisca**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_and_plan_all_produce_consistent_step_ids_for_k3s -v 2>&1 | tail -8
```
Expected: `AssertionError` — i task_ids differiscono tra plan() (recipe-based) e plan_all() (planner-based).

- [ ] **Step 4: Aggiorna `plan_all()` in `e2e_runner.py`**

Trova questo blocco:

```python
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                if scenario.name == "k3s-junit-curl":
                    from controlplane_tool.scenario.scenarios.k3s_junit_curl import K3sJunitCurlPlan
                    plans.append(K3sJunitCurlPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "helm-stack":
                    from controlplane_tool.scenario.scenarios.helm_stack import HelmStackPlan
                    plans.append(HelmStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                elif scenario.name == "cli-stack":
                    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
                    plans.append(CliStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                else:
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

Sostituisci con:

```python
                if scenario.name == "k3s-junit-curl":
                    from controlplane_tool.scenario.scenarios.k3s_junit_curl import build_k3s_junit_curl_plan
                    plans.append(build_k3s_junit_curl_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                if scenario.name == "helm-stack":
                    from controlplane_tool.scenario.scenarios.helm_stack import build_helm_stack_plan
                    plans.append(build_helm_stack_plan(self, request))
                    vm_bootstrap_planned = True
                    continue
                steps = self._planner.vm_backed_steps(request, include_bootstrap=not vm_bootstrap_planned)
                vm_bootstrap_planned = True
                if scenario.name == "cli-stack":
                    from controlplane_tool.scenario.scenarios.cli_stack import CliStackPlan
                    plans.append(CliStackPlan(scenario=scenario, request=request, steps=steps, runner=self))
                else:
                    plans.append(ScenarioPlan(scenario=scenario, request=request, steps=steps))
                continue
```

**Nota:** k3s e helm ora usano i builder factory — allineati a `plan()`. cli-stack e altri scenari VM restano con `_planner.vm_backed_steps()` (include_bootstrap conta per loro).

- [ ] **Step 5: Verifica che il nuovo test passi**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py::test_plan_and_plan_all_produce_consistent_step_ids_for_k3s -v 2>&1 | tail -5
```
Expected: `1 passed`.

- [ ] **Step 6: Verifica tests esistenti su plan_all/run_all**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "plan_all or run_all or bootstrap or teardown or dispatches or typed_builder" -q 2>&1 | tail -8
```
Expected: tutti passano.

**Nota sui mock `_planner._k3s_curl_runner`:** i test che mockano `runner._planner._k3s_curl_runner` diventano no-op (il builder usa un inner runner). Con `RecordingShell`, i comandi SSH restituiscono successo — i test passano lo stesso. Il mock resta (cleanup non è in scope di Piano 9).

- [ ] **Step 7: Rimuovi i branch k3s e helm da `ScenarioPlanner.vm_backed_steps()`**

In `tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py`, trova:

```python
    def vm_backed_steps(
        self,
        request: E2eRequest,
        *,
        include_bootstrap: bool = True,
    ) -> list[ScenarioPlanStep]:
        if request.scenario == "k3s-junit-curl":
            return self.k3s_junit_curl_steps(request)
        if request.scenario == "helm-stack":
            return [
                *self.k3s_vm_prelude_steps(request),
                *self.helm_stack_tail_steps(request),
            ]
        steps = []
        if include_bootstrap:
            steps.extend(self.vm_bootstrap_steps(request))
        steps.extend(self.vm_scenario_steps(request))
        return steps
```

Sostituisci con (rimuovi i due branch):

```python
    def vm_backed_steps(
        self,
        request: E2eRequest,
        *,
        include_bootstrap: bool = True,
    ) -> list[ScenarioPlanStep]:
        steps = []
        if include_bootstrap:
            steps.extend(self.vm_bootstrap_steps(request))
        steps.extend(self.vm_scenario_steps(request))
        return steps
```

**Perché è sicuro:** `k3s_junit_curl_steps()` e `helm_stack_tail_steps()` restano nel file (sono testati direttamente). Solo i branch in `vm_backed_steps()` vengono rimossi — non sono più raggiunti da nessun caller dopo lo Step 4.

- [ ] **Step 8: Verifica suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1062+ passano, solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce (pre-esistente).

**Se `test_run_all_bootstraps_vm_once_and_reuses_it` fallisce:**
Il builder usa un inner runner il cui `_k3s_curl_runner` non è mockato. Il verify step gira con RecordingShell (successo). Se il test fallisce per un motivo diverso, leggi l'errore. Probabilmente il verify step tenta di leggere un file o connettere a un host che non esiste. In tal caso, aggiungi un mock a livello di `K3sCurlRunner`:

```python
from unittest.mock import patch
from controlplane_tool.e2e.k3s_curl_runner import K3sCurlRunner
with patch.object(K3sCurlRunner, "verify_existing_stack", return_value=None):
    runner.run_all(only=["k3s-junit-curl"])
```

- [ ] **Step 9: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py \
    tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
refactor: plan_all() uses builder factories for k3s-junit-curl and helm-stack

Eliminates ScenarioPlanner.vm_backed_steps() for these two scenarios,
aligning plan_all() with plan() — both now use the same recipe builder path.
Removes dead k3s/helm branches from vm_backed_steps().

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py tests/test_scenario_planner.py -q 2>&1 | tail -5
```

Dopo questo piano:
- `plan()` e `plan_all()` per k3s e helm usano lo stesso builder factory (step IDs identici)
- `ScenarioPlanner.vm_backed_steps()` non ha più i branch k3s e helm (ridotto a ~8 righe)
- `ScenarioPlanner` resta ancora (usato per cli-stack, cli, cli-host, e il fallback generico)

---

## Note su Piano 10

Piano 10 (futuro) potrà considerare:
- Rimozione dei mock dead code `runner._planner._k3s_curl_runner` dai test
- Migrazione di cli-stack in `plan_all()` ai builder (richiede gestione include_bootstrap)
- Migrazione di cli, cli-host in `plan_all()` / `plan()` ai builder
- Rimozione di `ScenarioPlanner.k3s_junit_curl_steps()` e `k3s_vm_prelude_steps()` se non più chiamati
- Rimozione di `plan_recipe_steps` da `e2e_runner.py` se tutti i builder sono migrati
