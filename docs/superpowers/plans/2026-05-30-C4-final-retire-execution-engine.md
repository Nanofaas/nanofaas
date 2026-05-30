# C4-final — Ritirare il motore d'esecuzione recipe (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Convertire gli ULTIMI 3 consumatori del path d'esecuzione legacy (scenari LOCAL via `E2ePlan`/`_execute_steps`; `cli_stack_runner` via `plan_recipe_steps`; `cli_test_runner` via il suo loop su `e2e_plan.steps`), poi **cancellare** `plan_recipe_steps`, `E2eRunner._execute_steps`/`_execute_step`, e `executor.py::operation_to_plan_step`/`operations_to_plan_steps` + le callback `on_*`. Risultato: **un solo motore d'esecuzione (Workflow) ovunque.**

**Architettura — cosa RESTA vs cosa si CANCELLA:**
- RESTA (definizione scenari, corretto): `recipes.py`, `composer.compose_recipe`, i component-planner, `ScenarioPlanStep` (usato da `vm_backed_steps`/`local_steps` + display), `vm_backed_steps`/`local_steps` (sorgenti di step).
- SI CANCELLA (esecuzione legacy): `plan_recipe_steps` (+ le sue callback `on_*` e i rewrite proxmox locali), `E2eRunner._execute_steps`/`_execute_step`, `executor.py::operation_to_plan_step`/`operations_to_plan_steps`/`_SUMMARY_OVERRIDES` (già copiato in `_workflow_assembly`).

**Comandi:** controlplane `uv run --project tools/controlplane pytest <path>` (NIENTE `--no-cov`).
**Baseline (2):** `test_run_all_bootstraps_vm_once_and_reuses_it`, `test_tui_proxmox_vm_loadtest_keeps_cleanup_phases_enabled`.

**Fatti verificati:**
- `E2ePlan` (e2e_runner.py:83) ha `steps: list[ScenarioPlanStep]`, `executor: Callable|None`, `.run()` = `self.executor(self)`. Costruito SOLO per scenari LOCAL (plan() riga 507, plan_all() riga 626) con `local_steps(request)` (1-poche command host; `request.vm is None` → niente risoluzione placeholder).
- `run_all`/`execute` chiamano `self._execute_steps(plan)` se `isinstance(plan, E2ePlan)` (righe 796, 855), altrimenti `plan.run()`.
- `plan_recipe_steps` callers in produzione: SOLO `cli_stack_runner.py` (+ oracoli test). (proxmox/_workflow_assembly sono solo riferimenti in docstring.)
- `cli_test_runner._execute_steps` è un metodo PROPRIO (riga 54), itera `plan.steps` (= `[gradle_step] + e2e_plan.steps`). `e2e_plan = self.e2e_runner.plan(...)` (un plan Workflow). Esecuzione parallela al Workflow del plan.

---

### Task 1: Scenari LOCAL — `E2ePlan.run()` esegue un Workflow

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py`

- [ ] **Step 1: `E2ePlan.run()` costruisce ed esegue un Workflow di Host CommandTask**

Cambia `E2ePlan.run()` perché, invece di chiamare `self.executor(self)`, costruisca un `Workflow` di Host `CommandTask` dai suoi `steps` (riusa `host_command_task_from_step` o `command_task_from_operation`/`CommandTaskSpec` direttamente da `_workflow_assembly`) ed esegua `.run()`. Gli step LOCAL non hanno `action` né placeholder (request.vm is None) → conversione diretta: per ogni step, `CommandTask(task_id=step.step_id, title=step.summary, spec=CommandTaskSpec(argv=tuple(step.command), env=step.env, target="host"), executor=HostCommandTaskExecutor(<shell>))`. Serve uno shell: aggiungi a `E2ePlan` un riferimento allo shell (o passa il runner). Opzione pulita: aggiungi un campo opzionale `runner`/`shell` a E2ePlan popolato in `plan()`/`plan_all()`, e usa `runner.shell`. Rimuovi (o deprecane) il campo `executor`.

- [ ] **Step 2: `run_all`/`execute` chiamano sempre `plan.run()`**

Alle righe ~796 e ~855, rimuovi il ramo `if isinstance(plan, E2ePlan): self._execute_steps(plan)` → chiama sempre `plan.run(event_listener=...)` (ora E2ePlan ha un `.run()` funzionante via Workflow). VERIFICA che `test_e2e_runner.py::test_run_all_calls_plan_run...` resti verde (anzi rafforzato).

- [ ] **Step 3: Test local + e2e**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_container_local_runner.py tools/controlplane/tests/test_deploy_host_runner.py -v` (e qualunque test docker/buildpack/local) → nessun nuovo fallimento. Se un test costruiva `E2ePlan(executor=...)` o asseriva su `_execute_steps` per local, aggiornalo a `plan.run()`/Workflow.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py tools/controlplane/tests/
git commit -m "refactor(controlplane): local-scenario E2ePlan.run() executes a Workflow of host CommandTasks

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `cli_stack_runner` — niente `plan_recipe_steps`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli_validation/cli_stack_runner.py`

- [ ] **Step 1: Sostituisci `plan_recipe_steps("cli-stack")`**

`cli_stack_runner.plan_steps()` chiama `plan_recipe_steps(repo_root, request, "cli-stack", release=...)` e `run()` itera gli step risolvendoli a mano. Sostituisci con: costruisci il Workflow cli-stack riusando l'infrastruttura condivisa — preferibilmente delega a `build_cli_stack_plan(self.e2e_runner-equivalente, request).run()` se il runner ha accesso a un E2eRunner; ALTRIMENTI costruisci i tasks via `build_command_tasks` con la recipe `cli-stack` + lo stesso `context_selector`/`special_handler` di `scenarios/cli_stack.py`, e fai `Workflow(...).run()`. Mantieni la firma/comportamento di `run()` (phase "Verify", reporter child per step, errore su fallimento). Se `cli_stack_runner` non ha un E2eRunner, costruiscine uno o riusa i suoi `repo_root`/`shell`/`vm` per alimentare `build_command_tasks`.

- [ ] **Step 2: Test cli-stack runner**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_test_runner.py tools/controlplane/tests/test_cli_stack_runner.py -v` (se esistono) → nessun nuovo fallimento. Aggiorna i test che asserivano su `plan_recipe_steps`/gli step.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/cli_validation/cli_stack_runner.py tools/controlplane/tests/
git commit -m "refactor(controlplane): cli_stack_runner builds a Workflow instead of plan_recipe_steps

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `cli_test_runner` — usa il Workflow del plan e2e

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/cli_validation/cli_test_runner.py`

- [ ] **Step 1: Esegui via Workflow invece del loop proprio**

`cli_test_runner.run()` costruisce `CliTestPlan(steps=[gradle_step] + e2e_plan.steps)` ed esegue via il suo `_execute_steps`. Cambia: esegui il `gradle_step` come un Host `CommandTask`/Workflow, poi chiama `e2e_plan.run(event_listener=...)` (il Workflow del plan e2e, che ora esiste) invece di re-iterare `e2e_plan.steps`. Se serve unificare progress/teardown, costruisci un unico `Workflow([gradle_task, *<i task del plan e2e>])` — ma se è troppo intricato, basta: esegui gradle task, poi `e2e_plan.run()`. Rimuovi il metodo `_execute_steps` di `cli_test_runner` se non più usato (CONTROLLA che `legacy_e2e_scenario` casi siano coperti). Mantieni il comportamento di teardown.

- [ ] **Step 2: Test**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_cli_test_runner.py -v` → nessun nuovo fallimento.

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/cli_validation/cli_test_runner.py tools/controlplane/tests/
git commit -m "refactor(controlplane): cli_test_runner runs the e2e plan's Workflow instead of its own step loop

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Oracoli → snapshot (per poter cancellare `plan_recipe_steps`)

**Files:**
- Modify: gli oracoli `tests/test_{k3s_junit_curl,helm_stack,cli_stack,proxmox_prelude}_workflow.py` + `test_recipe_execution_hooks.py`

- [ ] **Step 1: Sostituisci i confronti vs `plan_recipe_steps` con valori attesi LETTERALI**

Negli oracoli che chiamano `plan_recipe_steps`, prima ESEGUI il test e CATTURA i `recipe_ids`/comandi attesi attuali (sono la verità di riferimento), poi sostituisci la chiamata a `plan_recipe_steps` con quei valori **letterali** (snapshot) come `expected_ids`/`expected_commands`. L'asserzione diventa `workflow_task_ids == expected_ids` ecc. Così l'oracolo non dipende più da `plan_recipe_steps`. (cli/cli-host usano `vm_backed_steps` che RESTA → quegli oracoli NON cambiano.)
NB: `test_recipe_execution_hooks.py` testa specificamente `plan_recipe_steps` (i rewrite proxmox). Questi test diventano obsoleti col delete: spostane le asserzioni rilevanti (rewrite proxmox) nell'oracolo `test_proxmox_prelude_workflow.py` (che ora testa la versione Workflow), poi CANCELLA `test_recipe_execution_hooks.py` in Task 5.

- [ ] **Step 2: Verifica** che gli oracoli passino con gli snapshot e NON importino più `plan_recipe_steps`. `grep -rn "plan_recipe_steps" tools/controlplane/tests/` → solo `test_recipe_execution_hooks.py` (che si cancella in Task 5).

- [ ] **Step 3: Commit**

```bash
git add tools/controlplane/tests/
git commit -m "test(controlplane): convert workflow oracles to literal snapshots (decouple from plan_recipe_steps)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: CANCELLA il motore d'esecuzione legacy

**Files:**
- Modify: `e2e/e2e_runner.py`, `scenario/components/executor.py`
- Delete: `tests/test_recipe_execution_hooks.py` (se interamente su plan_recipe_steps)

- [ ] **Step 1: Pre-check — nessun consumatore residuo**

`grep -rn "plan_recipe_steps\|operation_to_plan_step\|operations_to_plan_steps" tools/controlplane/src` → VUOTO (solo le definizioni stesse). `grep -rn "_execute_steps\|_execute_step\b" tools/controlplane/src` → solo le definizioni in e2e_runner.py (nessun chiamante; cli_test_runner non lo usa più). Se c'è ancora un chiamante, FERMATI e convertilo prima.

- [ ] **Step 2: Cancella**

- In `e2e/e2e_runner.py`: rimuovi `plan_recipe_steps` (intera funzione + le sue closure `on_*`, i rewrite proxmox locali, `cli_context`), `_execute_steps`, `_execute_step`, e il campo `executor` di E2ePlan se ora inutile. Rimuovi import diventati inutilizzati (`compose_recipe` se non più usato qui, `operations_to_plan_steps`, `CliComponentContext` se non più usato qui, ecc.).
- In `scenario/components/executor.py`: rimuovi `operation_to_plan_step`, `operations_to_plan_steps`, `_SUMMARY_OVERRIDES` (ora in `_workflow_assembly`). MANTIENI `ScenarioPlanStep` (ancora usato da vm_backed_steps/local_steps/display) — se executor.py resta solo con ScenarioPlanStep, va bene; altrimenti spostalo dove serve.

- [ ] **Step 3: Verifica finale completa**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -5` → solo i 2 baseline.
Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests 2>&1 | tail -2` → invariato.
Run: i due `lint-imports` → 0 broken.
Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/` → clean.
`grep -rn "plan_recipe_steps\|_execute_steps\|operation_to_plan_step" tools/controlplane/src` → VUOTO.

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/e2e/e2e_runner.py tools/controlplane/src/controlplane_tool/scenario/components/executor.py tools/controlplane/tests/
git rm tools/controlplane/tests/test_recipe_execution_hooks.py  # se obsoleto
git commit -m "refactor(controlplane): delete the legacy recipe execution engine (plan_recipe_steps, _execute_steps, operation_to_plan_step)

Single execution model achieved: all scenarios compose recipes/steps and run them as workflow_tasks.Workflow of honest Tasks.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Escalation
Ad ogni task, se la conversione non preserva il comportamento (test rossi nuovi) o emerge un consumatore inatteso del path legacy, FERMATI e riporta BLOCKED. La CANCELLAZIONE (Task 5) va fatta SOLO dopo che i grep dei consumatori sono vuoti e tutto è verde. Non falsare i test.

## Note di esecuzione
- `recipes.py`/`composer.compose_recipe`/component-planner/`ScenarioPlanStep`/`vm_backed_steps`/`local_steps` RESTANO.
- Esegui i task in ordine (1→5); la cancellazione è l'ultimo.
- Modello: opus per i task 1-3 e 5 (giudizio/integrazione), sonnet ok per il task 4 (snapshot).
- Prima di modificare/cancellare simboli, esegui `gitnexus_impact` come da CLAUDE.md.
