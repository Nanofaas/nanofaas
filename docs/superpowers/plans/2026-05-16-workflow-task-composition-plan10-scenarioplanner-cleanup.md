# Workflow Task Composition — Piano 10: ScenarioPlanner Dead Code Cleanup

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rimuovere i metodi di `ScenarioPlanner` ora irraggiungibili (k3s e helm step-building), eliminare i test che li testano direttamente, e ripulire i mock dead-code su `_planner._k3s_curl_runner`.

**Architecture:** Dopo Piano 9, `k3s_junit_curl_steps()`, `k3s_vm_prelude_steps()`, `k3s_junit_curl_tail_steps()`, e `helm_stack_tail_steps()` non sono più chiamati da nessun path di produzione — tutti i scenari recipe usano i builder factory che invocano `plan_recipe_steps()`. I test che testano questi metodi direttamente sono deprecati; il comportamento è già coperto da test di livello superiore su `runner.plan()`. I 3 mock `runner._planner._k3s_curl_runner` sono no-op dopo Piano 9 (il builder usa un inner runner) e vengono rimossi.

**Tech Stack:** Python 3.11+, pytest. Solo `scenario_planner.py`, `test_scenario_planner.py`, `test_e2e_runner.py` modificati.

---

## Background — cosa è dead e perché

### Metodi dead in ScenarioPlanner

| Metodo | Ultimo chiamante rilevante | Stato dopo Piano 9 |
|--------|---------------------------|---------------------|
| `k3s_junit_curl_steps()` | `vm_backed_steps()` — RIMOSSO Piano 9 | Dead — nessun caller |
| `k3s_vm_prelude_steps()` | `k3s_junit_curl_steps()` — dead | Dead transitivo |
| `k3s_junit_curl_tail_steps()` | `k3s_junit_curl_steps()` — dead | Dead transitivo |
| `helm_stack_tail_steps()` | `vm_backed_steps()` — RIMOSSO Piano 9 | Dead — nessun caller |

### Test da rimuovere

| Test | File | Perché rimuovere |
|------|------|------------------|
| `test_helm_stack_tail_exposes_k6_install_before_loadtest` | `test_scenario_planner.py` | Testa `helm_stack_tail_steps()` direttamente — già coperto da `test_helm_stack_plan_shares_k3s_junit_curl_prelude` in test_e2e_runner.py |
| `test_k3s_junit_curl_tail_steps_use_explicit_step_id_values` | `test_e2e_runner.py` | Testa `k3s_junit_curl_tail_steps()` — già coperto da `test_k3s_junit_curl_plan_uses_unified_python_and_junit_steps` |
| `test_k3s_junit_curl_tail_steps_use_explicit_step_id_values_without_cleanup` | `test_e2e_runner.py` | Stesso metodo dead |

### Mock no-op da rimuovere (3 occorrenze in test_e2e_runner.py)

```python
runner._planner._k3s_curl_runner = lambda request: type(  # ← no-op: l'inner runner usa il suo planner
    "_Verifier",
    (),
    {"verify_existing_stack": staticmethod(lambda resolved: None)},
)()
```

Questi mock erano necessari prima di Piano 9 quando `run_all()` usava `_planner.vm_backed_steps()` per k3s. Ora `plan_all()` per k3s usa il builder factory, che crea un inner runner con il proprio `_k3s_curl_runner`. Il mock sull'outer planner non ha effetto. Il vero mock è già il `patch.object(K3sCurlRunner, "verify_existing_stack", ...)` aggiunto dal Piano 9 implementer.

---

## File Structure

**Modificati:**
- `tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py` — rimozione di 4 metodi (righe ~288–512 per i 4 metodi)
- `tools/controlplane/tests/test_scenario_planner.py` — rimozione di `test_helm_stack_tail_exposes_k6_install_before_loadtest`
- `tools/controlplane/tests/test_e2e_runner.py` — rimozione di 2 test su tail_steps + 3 mock no-op

---

## Task 1: Rimuovi test che testano metodi dead

**Files:**
- Modify: `tools/controlplane/tests/test_scenario_planner.py`
- Modify: `tools/controlplane/tests/test_e2e_runner.py`

- [ ] **Step 1: Verifica che il comportamento è già coperto a livello superiore**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_e2e_runner.py -k "helm_stack_plan_shares or k3s_junit_curl_plan_uses" -v 2>&1 | tail -8
```
Expected: i test di livello superiore passano — confermano che il comportamento (step IDs di k3s tail e helm tail) è già verificato tramite `runner.plan()`.

- [ ] **Step 2: Rimuovi `test_helm_stack_tail_exposes_k6_install_before_loadtest` da `test_scenario_planner.py`**

READ `tools/controlplane/tests/test_scenario_planner.py`. Il file contiene solo 2 test (righe 8–44). Rimuovi il secondo test (`test_helm_stack_tail_exposes_k6_install_before_loadtest`, righe 21–44). Lascia solo il primo (`test_scenario_planner_local_steps_returns_list`).

Il file risultante deve essere:

```python
from pathlib import Path
from unittest.mock import MagicMock
from controlplane_tool.scenario.scenario_planner import ScenarioPlanner
from controlplane_tool.e2e.e2e_models import E2eRequest


def test_scenario_planner_local_steps_returns_list() -> None:
    vm = MagicMock()
    shell = MagicMock()
    paths = MagicMock()
    paths.workspace_root = Path("/repo")
    planner = ScenarioPlanner(paths=paths, vm=vm, shell=shell, manifest_root=Path("/repo/runs/manifests"))
    request = E2eRequest(scenario="docker", runtime="java")

    steps = planner.local_steps(request)

    assert isinstance(steps, list)
```

- [ ] **Step 3: Rimuovi i 2 test su `k3s_junit_curl_tail_steps` da `test_e2e_runner.py`**

Leggi `test_e2e_runner.py` righe 808-846. Rimuovi entrambi i test:
- `test_k3s_junit_curl_tail_steps_use_explicit_step_id_values` (righe 808–825)
- `test_k3s_junit_curl_tail_steps_use_explicit_step_id_values_without_cleanup` (righe 828–846)

**Nota:** questi test accedono `runner._planner.k3s_junit_curl_tail_steps(...)` — un metodo che verrà rimosso nel Task 2.

- [ ] **Step 4: Rimuovi i 3 mock no-op `_planner._k3s_curl_runner` da `test_e2e_runner.py`**

Cerca le 3 occorrenze di `runner._planner._k3s_curl_runner = lambda`. Rimuovi SOLO le righe del mock, lasciando intatto il resto del test.

**Occorrenza 1** (`test_run_all_bootstraps_vm_once_and_reuses_it`, righe 619–623):
```python
    runner._planner._k3s_curl_runner = lambda request: type(  # type: ignore[assignment]
        "_Verifier",
        (),
        {"verify_existing_stack": staticmethod(lambda resolved: None)},
    )()
```
Rimuovi queste 5 righe. Lascia il `with patch.object(K3sCurlRunner, ...)` intatto.

**Occorrenza 2** (`test_run_all_tears_down_vm_when_cleanup_vm_true`, righe 665–669):
Stessa struttura — rimuovi le 5 righe del mock lambda.

**Occorrenza 3** (`test_run_all_dispatches_builder_plans_via_plan_run`, righe 1099–1103):
Stessa struttura — rimuovi le 5 righe del mock lambda.

- [ ] **Step 5: Verifica che la suite passa dopo le rimozioni dei test**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_planner.py tests/test_e2e_runner.py -q --tb=short 2>&1 | tail -8
```
Expected: stesso numero di passing meno i test rimossi (circa -5). Nessun nuovo failure.

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/tests/test_scenario_planner.py \
    tools/controlplane/tests/test_e2e_runner.py && \
git commit -m "$(cat <<'EOF'
test: remove tests for dead ScenarioPlanner methods and no-op mocks

Removes tests for k3s_junit_curl_tail_steps() and helm_stack_tail_steps()
(both dead after Piano 9). Behavior is already covered at the plan() level.
Removes no-op _k3s_curl_runner mock assignments (builders use inner runner).

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Rimuovi i metodi dead da ScenarioPlanner

**File:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py`

- [ ] **Step 1: Verifica che nessun caller esiste**

```bash
grep -rn "k3s_junit_curl_steps\|k3s_vm_prelude_steps\|k3s_junit_curl_tail_steps\|helm_stack_tail_steps" \
    /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/ \
    /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/tests/ \
    --include="*.py" | grep -v "__pycache__"
```
Expected: NESSUN output (tutti i caller rimossi nel Task 1). Se ci sono ancora reference, non procedere e investigare.

- [ ] **Step 2: Rimuovi `helm_stack_tail_steps()` da `scenario_planner.py`**

READ `tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py` righe 477–512. Rimuovi il metodo completo:

```python
    def helm_stack_tail_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        context = resolve_scenario_environment(
            self.paths.workspace_root,
            request,
            manifest_root=self.manifest_root,
        )
        vm_env = self._vm_env(request)
        if request.helm_noninteractive:
            vm_env = {**vm_env, "E2E_K3S_HELM_NONINTERACTIVE": "true"}
        vm_env = self._with_manifest_env(request, vm_env)
        controlplane_tool_project = self.paths.workspace_root / "tools" / "controlplane"
        return [
            operation_to_plan_step(
                plan_loadtest_install_k6(context)[0],
                request=request,
            ),
            operation_to_plan_step(
                plan_loadtest_run(context)[0],
                request=request,
                on_remote_exec=lambda argv, env: self._run_remote_operation(request, argv, env),
            ),
            self._step(
                "Run autoscaling experiment (Python)",
                [...],
                env=vm_env,
                step_id="experiments.autoscaling",
            ),
        ]
```

- [ ] **Step 3: Rimuovi `k3s_junit_curl_tail_steps()` da `scenario_planner.py`**

READ le righe del metodo `k3s_junit_curl_tail_steps` (righe 365–469). Rimuovi il metodo completo (è lungo ~105 righe).

**Prima di rimuovere:** verifica che `plan_loadtest_install_k6` e `plan_loadtest_run` siano ancora importati da `scenario_planner.py` (sono usati da `helm_stack_tail_steps`). Se li rimuovi entrambi, controlla se gli import diventano inutilizzati.

```bash
grep -n "^from\|^import" tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py | head -20
grep -n "plan_loadtest_install_k6\|plan_loadtest_run\|operation_to_plan_step" \
    tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py
```

Se `plan_loadtest_install_k6`, `plan_loadtest_run`, e `operation_to_plan_step` sono usati SOLO dai metodi rimossi, rimuovi anche i loro import.

- [ ] **Step 4: Rimuovi `k3s_vm_prelude_steps()` da `scenario_planner.py`**

READ e rimuovi il metodo `k3s_vm_prelude_steps` (righe ~288–364). Questo metodo usa `build_vm_cluster_prelude_plan`. Verifica dopo la rimozione se `build_vm_cluster_prelude_plan` è ancora usato altrove:

```bash
grep -n "build_vm_cluster_prelude_plan" tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py
```

Se non è più usato, rimuovi anche il suo import.

- [ ] **Step 5: Rimuovi `k3s_junit_curl_steps()` da `scenario_planner.py`**

READ e rimuovi il metodo `k3s_junit_curl_steps` (righe ~471–475):

```python
    def k3s_junit_curl_steps(self, request: E2eRequest) -> list[ScenarioPlanStep]:
        return [
            *self.k3s_vm_prelude_steps(request),
            *self.k3s_junit_curl_tail_steps(request),
        ]
```

- [ ] **Step 6: Rimuovi import orfani se necessario**

Dopo tutte le rimozioni, verifica che gli import rimasti in `scenario_planner.py` siano tutti usati:

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run python -c "import controlplane_tool.scenario.scenario_planner" 2>&1
```
Expected: nessun errore.

Poi run linting:
```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run ruff check src/controlplane_tool/scenario/scenario_planner.py 2>&1 | head -10
```
Se segnala import non usati, rimuovili.

- [ ] **Step 7: Suite completa**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -8
```
Expected: 1056+ passano (teniamo conto dei test rimossi nel Task 1), solo `test_default_two_vm_k6_script_reads_payload_in_init_context` fallisce (pre-esistente).

- [ ] **Step 8: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenario_planner.py && \
git commit -m "$(cat <<'EOF'
refactor: remove dead ScenarioPlanner methods for k3s and helm

k3s_junit_curl_steps(), k3s_vm_prelude_steps(), k3s_junit_curl_tail_steps(),
and helm_stack_tail_steps() are now unreachable — all recipe scenarios use
builder factories that call plan_recipe_steps() instead.
Removes orphaned imports if any.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Verifica Finale

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_scenario_planner.py tests/test_e2e_runner.py tests/test_scenario_flows.py -q 2>&1 | tail -5
```

Dopo questo piano:
- `ScenarioPlanner` contiene solo: `local_steps()`, `vm_backed_steps()`, `vm_bootstrap_steps()`, `vm_scenario_steps()`, `_k3s_curl_runner()` e metodi helper privati
- I 4 metodi rimossi erano irraggiungibili da tutti i path di produzione
- I test di comportamento rimasti coprono il comportamento a livello di `runner.plan()`/`runner.plan_all()`
- I mock no-op rimossi rendono i test più leggibili

---

## Mappa lavoro futuro (post Piano 10)

### Piano 11 — cli e cli-host builders (opzionale)
- `cli` e `cli-host` usano ancora `_planner.vm_backed_steps()` in `plan()` e `plan_all()`
- Entrambi producono un singolo passo ("Run CLI E2E workflow") — molto semplice
- Se si vogliono builder tipizzati per questi, richiedono builder factory minimali
- **Alternativa:** documentare come fallback intenzionale per scenari non-recipe

### Piano 12 — Rimozione di `plan_recipe_steps` da `e2e_runner.py`
- `plan_recipe_steps` è ancora usato da tutti e 5 i builder factory + `cli_stack_runner.py`
- Non può essere rimosso finché i builder non cambiano implementazione interna
- Richiede decisione: riscrivere i builder per non usare `plan_recipe_steps`, oppure tenere `plan_recipe_steps` come implementazione condivisa

### Piano 13 — Rimozione di `ScenarioPlanner` (se desiderata)
- `ScenarioPlanner` sarà necessario finché `cli` e `cli-host` non hanno builder (Piano 11)
- E finché `local_steps()` è usato per docker, buildpack, container-local, deploy-host
- Per rimuoverlo completamente servono builder per TUTTI gli scenari
- **Probabilmente non necessario:** `ScenarioPlanner` dopo Piano 10 è piccolo e pulito

### Piano 14 — Migrazione task catalog in `workflow_tasks` sub-packages
- Goal originale: muovere `scenario/tasks/` (vm.py, k8s.py, loadtest.py, functions.py, cli.py) in `workflow_tasks`
- Richiederebbe Protocol injection per le dipendenze dirette (VmOrchestrator, ecc.)
- Grande refactor — separato e indipendente dal cleanup di ScenarioPlanner
