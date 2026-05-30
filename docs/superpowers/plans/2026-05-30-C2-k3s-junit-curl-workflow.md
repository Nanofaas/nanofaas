# C2 — `k3s-junit-curl` come Workflow di Task (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Riscrivere `build_k3s_junit_curl_plan` perché assembli ed esegua un `workflow_tasks.Workflow` di Task **onesti** (niente callback nascoste), preservando ESATTAMENTE il comportamento del path recipe (stessi comandi, ordine, task_id, gestione `--no-cleanup-vm`). Scenario pilota della convergenza a un solo motore. Il path recipe resta in vita per gli altri scenari fino a C4.

**Architecture:** Modello identico a `scenario/scenarios/two_vm_loadtest.py`: avvia la VM (`EnsureVmRunning`), risolve l'host reale, costruisce i Task con i comandi già risolti (niente placeholder), assembla `Workflow(tasks=[...], cleanup_tasks=[...])` e fa `.run()`. I comandi vengono dai planner-componenti già in libreria (bootstrap/images/helm/namespace/cleanup/verification); host→`HostCommandTaskExecutor(shell)`, vm→`VmCommandTaskExecutor(OrchestratorVmRunner(vm_orch, vm_request))`; lifecycle→`EnsureVmRunning`/`DestroyVm`; verify k3s-curl→Task dedicato in controlplane che chiama il verifier host.

**Tech Stack:** Python 3.11+, workflow_tasks (Workflow/Task/CommandTask/executors), pytest, uv.

**Comandi:** controlplane `uv run --project tools/controlplane pytest <path>` (NIENTE `--no-cov`); libreria con `--no-cov` per singolo file.

**Baseline fallimenti pre-esistenti (NON nostri):** controlplane 3 (`test_e2e_runner.py::test_helm_stack_execute_resolves_vm_host_for_autoscaling_env`, `::test_run_all_bootstraps_vm_once_and_reuses_it`, `test_tui_choices.py::test_tui_proxmox_vm_loadtest_keeps_cleanup_phases_enabled`); libreria 1 (proxmox). Nessun task li aumenta.

**Fatti verificati (leggere queste fonti):**
- Template: `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` (pattern EnsureVmRunning→risolvi host→Workflow→run, cleanup DestroyVm).
- Path recipe attuale per k3s-junit-curl: `scenarios/k3s_junit_curl.py` (`build_k3s_junit_curl_plan` → `plan_recipe_steps` → `E2eRunner._execute_steps`). La recipe (ordine componenti) è in `scenario/components/recipes.py` chiave `"k3s-junit-curl"`.
- Esecuzione step attuale (`e2e/e2e_runner.py::_execute_step`): step con `action` = callback; step senza action = comando **host** via `shell.run` con risoluzione placeholder `<multipass-ip:name>` (`scenario/command_resolver.py`).
- I planner producono `RemoteCommandOperation` (`execution_target` "host"|"vm"). Vivono in `workflow_tasks.components.{bootstrap,images,helm,namespace,cleanup,verification}` (importati via shim da controlplane).
- Wiring executor: `HostCommandTaskExecutor(runner.shell)` (ShellBackend soddisfa `HostCommandRunner`); `VmCommandTaskExecutor(OrchestratorVmRunner(vm_orch, vm_request))` (riproduce `_on_remote_exec`→`vm_orch.exec_argv`).
- `OrchestratorVmRunner`, `VmCommandTaskExecutor`, `HostCommandTaskExecutor`, `CommandTask`, `EnsureVmRunning`, `DestroyVm`, `VmConfig`, `MultipassVmAdapter` sono in `workflow_tasks` / esportati.

**Ordine recipe k3s-junit-curl (da `recipes.py`):**
`vm.ensure_running, vm.provision_base, repo.sync_to_vm, registry.ensure_container, images.build_core, images.build_selected_functions, k3s.install, k3s.configure_registry, namespace.install, helm.deploy_control_plane, helm.deploy_function_runtime, tests.run_k3s_curl_checks, tests.run_k8s_junit, cleanup.uninstall_function_runtime, cleanup.uninstall_control_plane, namespace.uninstall, vm.down`.

**Mappa step → Task (preservare comportamento reale, NON l'operazione dichiarata):**
| component_id | Task |
|---|---|
| `vm.ensure_running` | `EnsureVmRunning(lifecycle=MultipassVmAdapter(vm_orch)|adapter, config=VmConfig(...))` |
| `vm.provision_base`, `repo.sync_to_vm` | Host `CommandTask` (comando dal planner, placeholder `<multipass-ip>` risolto con host reale) |
| `registry.ensure_container`, `images.*`, `k3s.*`, `namespace.install/uninstall`, `helm.*`, `cleanup.uninstall_*`, `tests.run_k8s_junit` | Vm `CommandTask` (planner→operation→`command_task_from_operation(op, VmCommandTaskExecutor(OrchestratorVmRunner(vm_orch, vm_request)), remote_dir=remote_dir)`) |
| `tests.run_k3s_curl_checks` | Task dedicato `K3sCurlVerifyTask` in controlplane: `.run()` chiama `runner._planner._k3s_curl_runner(request).verify_existing_stack(request.resolved_scenario)` |
| `vm.down` | `DestroyVm` in `cleanup_tasks`, SOLO se `request.cleanup_vm` |

---

### Task 1: Test di equivalenza (oracolo) — PRIMA dell'implementazione

**Files:**
- Create: `tools/controlplane/tests/test_k3s_junit_curl_workflow.py`

- [ ] **Step 1: Scrivi il test che confronta Workflow vs recipe**

Obiettivo: con gli stessi mock usati da `test_e2e_runner.py`/`test_scenario_builders.py` (RecordingShell + fake multipass/host_resolver, `dry_run`), costruisci il piano k3s-junit-curl e verifica che la **sequenza di task_id** e i **comandi risolti** del nuovo `Workflow` coincidano con quelli prodotti oggi da `plan_recipe_steps`. LEGGI `test_e2e_runner.py` e `test_scenario_builders.py` per riusare le loro fixture (come istanziano `E2eRunner`, il fake VM, il `host_resolver`, la `E2eRequest` per k3s-junit-curl).

Struttura (adatta ai nomi reali delle fixture):
```python
def test_k3s_workflow_task_ids_match_recipe_steps(<fixtures>) -> None:
    # 1) build the recipe steps (current path)
    recipe_steps = plan_recipe_steps(repo_root, request, "k3s-junit-curl", shell=..., host_resolver=..., multipass_client=...)
    recipe_ids = [s.step_id for s in recipe_steps if s.step_id]
    # 2) build the new workflow plan
    plan = build_k3s_junit_curl_plan(runner, request)
    workflow_ids = plan.workflow_task_ids  # expose for assertion (see Task 2)
    assert workflow_ids == recipe_ids
```
NOTA: questo test FALLISCE finché Task 2 non è implementato (il nuovo build produce ancora il path recipe). Va bene: è l'oracolo. Se non riesci a confrontare i *comandi* senza una VM reale, limita l'asserzione ai **task_id** + (dove possibile in dry-run) agli argv risolti dei comandi host/vm catturati dal RecordingShell / fake exec.

- [ ] **Step 2: Esegui — deve FALLIRE (oracolo non ancora soddisfatto)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_k3s_junit_curl_workflow.py -v`
Expected: FAIL (build_k3s_junit_curl_plan non espone ancora `workflow_task_ids`, o gli id non coincidono). Conferma che il test gira ed è significativo.

- [ ] **Step 3: Commit del test**

```bash
git add tools/controlplane/tests/test_k3s_junit_curl_workflow.py
git commit -m "test(controlplane): equivalence oracle for k3s-junit-curl workflow vs recipe (xfail until C2)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```
(Se preferisci, marca il test `@pytest.mark.xfail(reason="until C2 Task 2")` per non rompere la suite tra i commit.)

---

### Task 2: Riscrivi `build_k3s_junit_curl_plan` come Workflow

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py`

- [ ] **Step 1: Leggi il template e i planner**

Leggi `scenarios/two_vm_loadtest.py` (pattern), `e2e/e2e_runner.py::plan_recipe_steps` (selezione `vm_orch`, `remote_dir`, `_on_remote_exec`, `_on_ensure_running`, `_on_vm_down`, `on_k3s_curl_verify`, il resolver dei placeholder), `scenario/command_resolver.py` (formato `<multipass-ip:name>` e come si risolve), e i planner in `workflow_tasks.components.{bootstrap,images,helm,namespace,cleanup,verification}` per sapere quali operazioni emette ogni component_id.

- [ ] **Step 2: Implementa il nuovo `K3sJunitCurlPlan` + builder**

Riscrivi `K3sJunitCurlPlan` perché in `.run()` costruisca ed esegua un `Workflow`:
1. Seleziona `vm_orch` come fa `plan_recipe_steps` (per k3s-junit-curl è multipass → `runner.vm`). `remote_dir = vm_orch.remote_project_dir(vm_request)`.
2. `EnsureVmRunning(task_id="vm.ensure_running", title=..., lifecycle=MultipassVmAdapter(vm_orch), config=VmConfig(name=vm_request.name, cpus=..., memory=..., disk=...))` → `.run()` per primo (fuori dal Workflow o come primo task), poi **risolvi l'host reale** (`host_resolver(vm_request)` se presente, else `vm_orch.connection_host(vm_request)`).
3. Costruisci la `ScenarioExecutionContext` (via `resolve_scenario_environment`) per alimentare i planner.
4. Per ogni component_id della recipe (in ordine, esclusi ensure_running e vm.down), chiama il planner corrispondente → operazioni; per ciascuna:
   - se `execution_target == "vm"`: `command_task_from_operation(op, VmCommandTaskExecutor(OrchestratorVmRunner(vm_orch, vm_request)), remote_dir=remote_dir)`.
   - se host: risolvi i placeholder `<multipass-ip:name>` nell'argv/env con l'host reale (riusa la logica di `command_resolver`), poi `command_task_from_operation(resolved_op, HostCommandTaskExecutor(runner.shell))`.
   - `tests.run_k3s_curl_checks`: usa il Task dedicato `K3sCurlVerifyTask` (sotto), NON un CommandTask.
5. Cleanup: se `request.cleanup_vm`, aggiungi `DestroyVm(task_id="vm.down", lifecycle=..., info=<info da ensure_running>)` ai `cleanup_tasks`; altrimenti ometti (equivale a "Skipping VM teardown").
6. `Workflow(tasks=[...], cleanup_tasks=[...]).run()`.
7. Definisci `K3sCurlVerifyTask` (dataclass con `task_id`, `title`, e un callable o i riferimenti per chiamare `verify_existing_stack`) il cui `.run()` esegue la verifica host e solleva su fallimento.
8. Esponi `workflow_task_ids` (proprietà) che ritorna gli `task_id` nell'ordine del Workflow (ensure_running + tasks + cleanup), per l'oracolo del Task 1. Mantieni anche `task_ids` se altri lo usano.

Mantieni la firma di `build_k3s_junit_curl_plan(runner, request)` e il fatto che ritorni un oggetto con `.run(event_listener=None)`.

- [ ] **Step 3: Soddisfa l'oracolo**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_k3s_junit_curl_workflow.py -v`
Expected: PASS (task_id + comandi coincidono con la recipe). Se hai usato `xfail`, rimuovilo.

- [ ] **Step 4: Test E2E/builder esistenti**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_scenario_builders.py tools/controlplane/tests/test_recipe_execution_hooks.py -v`
Expected: nessun nuovo fallimento oltre ai 3 baseline. In particolare i test che eseguono k3s-junit-curl in dry-run/mock devono passare col nuovo path.

- [ ] **Step 5: import-linter (controlplane) + suite completa controlplane**

Run: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter` → 0 broken.
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests 2>&1 | tail -4` → solo i 3 baseline.

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/k3s_junit_curl.py tools/controlplane/tests/test_k3s_junit_curl_workflow.py
git commit -m "refactor(controlplane): rewrite k3s-junit-curl as a Workflow of honest Tasks

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Verifica E2E reale (opzionale, se l'ambiente lo consente)

- [ ] **Step 1: Dry-run / mock E2E**

Se esiste un test/comando che esercita k3s-junit-curl in dry-run senza una VM reale (es. `controlplane-tool e2e run k3s-junit-curl --dry-run` o un test dedicato), eseguilo e confronta l'output (comandi/ordine) con quello del path recipe prima della modifica (git stash / branch).
Expected: comandi e ordine identici.

- [ ] **Step 2 (solo se hai un ambiente Multipass): E2E reale**

`./scripts/controlplane.sh e2e run k3s-junit-curl --no-cleanup-vm` (o l'equivalente CLI). Verifica che lo scenario passi end-to-end come prima. NB: richiede Multipass; salta se non disponibile e segnalalo.

---

## Note di esecuzione

- Il path recipe (`plan_recipe_steps`, `_execute_steps`, `ScenarioPlanStep`, `composer`, `recipes`) RESTA in vita: lo si ritira in C4, dopo che tutti gli scenari recipe (k3s/helm/cli) sono Workflow.
- `K3sCurlVerifyTask` vive in controlplane (è product-specific: usa `k3s_curl_runner`). Va benissimo: `scenarios/*` è assembly di controlplane.
- L'oracolo (Task 1) è la rete di sicurezza principale: se task_id/comandi divergono, il Workflow non è equivalente — itera finché coincidono.
- Modello suggerito: opus (richiede giudizio/integrazione multi-file).
- Prima di modificare simboli, esegui `gitnexus_impact` come da CLAUDE.md.
