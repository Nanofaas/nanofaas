# Design: un solo motore di composizione scenari — Workflow di Task

**Data:** 2026-05-29
**Stato:** Approvato (direzione + decomposizione) — in attesa di review dello spec
**Contesto:** estende e in parte **assorbe** la roadmap di
[workflow_tasks component library](2026-05-28-workflow-tasks-component-library-design.md)
(sotto-progetti 3-4). Emerge da una scoperta durante il sotto-progetto 2.

## Problema

Gli scenari E2E/loadtest sono composti **in due modi paralleli e ridondanti**:

1. **Recipe di component-planner** (`scenario/components/*` + `composer` + `recipes` + `executor`):
   uno scenario = lista di `component_id`; ogni id → un planner che produce
   `RemoteCommandOperation`; `compose_recipe` + `executor.py` le trasformano in
   `ScenarioPlanStep`; `E2eRunner._execute_steps` le esegue con callback iniettate
   (`on_ensure_running`, `on_vm_down`, `on_remote_exec`, `on_k3s_curl_verify`). Guida 4 scenari:
   `k3s-junit-curl`, `helm-stack`, `cli-stack`, `cli`.
2. **Workflow di Task** (`scenario/scenarios/{two,azure,proxmox}-vm-loadtest.py`): lo scenario
   istanzia Task tipizzati della libreria (`EnsureVmRunning`, `InstallK6`, `RunK6`,
   `FetchVmResults`, `CapturePrometheusSnapshot`, `WriteK6Report`, `DestroyVm`), li assembla in
   `workflow_tasks.Workflow(tasks=..., cleanup_tasks=...)` e fa `.run()`. Guida 3 scenari loadtest.

`two-vm-loadtest` è perfino dichiarato in **entrambi** (recipe + Workflow), con il Workflow come
path realmente eseguito. Due implementazioni della stessa cosa: confusione e doppia manutenzione.

## Obiettivo

**Un solo modello di composizione: `workflow_tasks.Workflow` di `Task`.** Tutti gli scenari
assemblano un `Workflow` di Task della libreria e lo eseguono. Il motore recipe/component-planner
(`recipes`, `composer`, `executor`, il path recipe di `E2eRunner`) viene **ritirato**.

Questo realizza la visione originale: la libreria offre **componenti pronti (Task)**, controlplane
li **assembla in workflow ed esegue**.

## Contratto della libreria (già esistente)

- `Task` = Protocol `{task_id: str, title: str, run() -> Any}`.
- `Workflow = {tasks: list[Task], cleanup_tasks: list[Task]}`; `.run()` esegue in ordine,
  si ferma al primo errore in `tasks`, esegue SEMPRE i `cleanup_tasks`, avvolge ogni task in
  `workflow_step(task_id, title)` (integrazione TUI/eventi).
- Esecuzione comandi: `CommandTaskSpec` (argv/env/target host|vm/cwd/remote_dir/expected_exit_codes)
  + `HostCommandTaskExecutor`/`VmCommandTaskExecutor` (prendono un runner, eseguono lo spec,
  ritornano `TaskResult`) + `operation_to_task_spec(RemoteCommandOperation) -> CommandTaskSpec`.
- Task concreti già pronti: `EnsureVmRunning`, `DestroyVm` (lifecycle); `InstallK6`, `RunK6`,
  `FetchVmResults`, `CapturePrometheusSnapshot`, `WriteK6Report` (loadtest).

## Tassello mancante: `CommandTask`

Gli executor NON sono Task (non hanno `task_id`/`title`/`run()`): sono il meccanismo d'esecuzione.
Serve un `Task` generico che incapsuli spec+executor:

```python
@dataclass
class CommandTask:
    task_id: str
    title: str
    spec: CommandTaskSpec
    executor: HostCommandTaskExecutor | VmCommandTaskExecutor

    def run(self) -> TaskResult:
        result = self.executor.run(self.spec)
        if result.status != "passed":
            raise RuntimeError(
                f"{self.task_id} failed (exit {result.return_code}): {result.stderr or result.stdout}"
            )
        return result
```

Con `CommandTask`, ogni passo-shell (helm/docker/kubectl/ansible/gradle/nanofaas-cli) di k3s/helm/cli
diventa un Task. La `CommandTaskSpec` si ottiene dai **planner dei componenti già migrati**
(bootstrap/cleanup/helm/images/namespace/verification, che producono `RemoteCommandOperation`)
via `operation_to_task_spec`. → il lavoro di migrazione del sotto-progetto 2 **si riusa** come
"costruttori di spec"; cambia solo il guscio d'esecuzione (Workflow+CommandTask al posto di
recipe+executor+_execute_steps).

## Passi speciali → Task

- VM up/down → `EnsureVmRunning` / `DestroyVm` (già esistenti). `--no-cleanup-vm` → si omette
  `DestroyVm` dai `cleanup_tasks` (o lo si rende no-op).
- exec remoto generico → `CommandTask` con `VmCommandTaskExecutor`.
- `tests.run_k3s_curl_checks` / `tests.run_k8s_junit` → `CommandTask` (eseguono comandi/script
  nella VM) o Task dedicato se serve logica extra.
- `cleanup.verify_cli_platform_status_fails` ("il comando DEVE fallire") → `CommandTask` con
  `expected_exit_codes` che includono il fallimento atteso, oppure un piccolo Task dedicato
  `ExpectRemoteFailure`.

## Architettura target

- `scenario/scenarios/<name>.py` è l'**unico** posto dove uno scenario si assembla: costruisce un
  `Workflow` di Task (lifecycle + `CommandTask` per i passi-shell + Task specifici) e lo esegue.
- La libreria fornisce tutti i Task/primitive (`CommandTask`, `EnsureVmRunning`, `DestroyVm`,
  loadtest Task) + i spec-builder (planner migrati).
- `E2eRunner` resta il punto d'ingresso (validazione request, risoluzione VM, dispatch a
  `build_<scenario>_plan`), ma il path recipe (`_execute_steps`, `plan_recipe_steps`,
  `ScenarioPlanStep`, callback `on_*`) sparisce: ogni `build_<scenario>_plan` ritorna un oggetto
  con `.run()` che esegue un `Workflow`.

## Decomposizione (sotto-progetti C1–C5, ognuno verde prima del successivo)

- **C1 — `CommandTask` in libreria.** Aggiungi `CommandTask` (+ eventuale helper
  `command_task_from_operation(operation, executor, *, title=None)`), con test. Nessun cambiamento
  di comportamento altrove.
- **C2 — Scenario pilota: `k3s-junit-curl` come Workflow.** Riscrivi `build_k3s_junit_curl_plan`
  per assemblare un `Workflow` di Task (EnsureVmRunning + CommandTask dai planner + verify Task +
  DestroyVm). Verifica equivalenza col path recipe (stessi task_id/ordine/comandi). Il path recipe
  resta in vita per gli altri scenari finché C4.
- **C3 — `helm-stack`, `cli-stack`, `cli` come Workflow.** Stessa tecnica.
- **C4 — Ritiro del motore recipe.** Elimina `recipes.py`, `composer.py`, `executor.py`
  (`ScenarioPlanStep`, `operations_to_plan_steps`), `registry.py`, il path recipe di `E2eRunner`
  (`_execute_steps` recipe, `plan_recipe_steps`, `scenario_task_ids` ramo recipe, callback `on_*`),
  e la dichiarazione recipe ridondante di two-vm/azure/proxmox.
- **C5 — Consolidamento planner.** Verifica che i planner-componenti migrati siano usati solo come
  spec-builder; rimuovi/accorpa ciò che resta inutilizzato (es. component-def constants ora inutili).

## Vincoli e invarianti

- Direzione dipendenze invariata: `controlplane_tool → workflow_tasks`; la libreria non importa
  controlplane (import-linter + `test_package_boundaries.py`).
- Ogni passo mantiene **comportamento utente invariato**: stessi comandi eseguiti, stessi task_id
  (per TUI/report), stesso ordine, stesso trattamento `--no-cleanup-vm`. Gli scenari riscritti si
  validano contro il comportamento del path recipe prima di ritirarlo (C4).
- Coverage libreria: gate temporaneamente a 70 (vedi sotto-progetto 2); da ripristinare a 90 con
  i test dei nuovi Task.
- Java/control-plane non toccati: solo tooling Python in `tools/` (controlplane + workflow-tasks).

## Fuori scope

- Modifiche al control-plane Java, function-runtime, SDK.
- Cambiamenti funzionali agli scenari (solo cambio del motore di composizione, comportamento
  identico).
- Bash (sotto-progetti 5-6 della roadmap originale) e runtime Prefect: indipendenti; restano come
  da roadmap, ma C4 rende inutile parte della "pulizia scenario" prevista al sotto-progetto 4.

## Criteri di successo

- Un solo motore: tutti gli scenari sono `Workflow` di Task assemblati in `scenario/scenarios/*`.
- `recipes`/`composer`/`executor`/`registry` e il path recipe di `E2eRunner` rimossi.
- `CommandTask` in libreria con test; i passi-shell degli scenari lo usano.
- Test controlplane + libreria verdi a ogni passo; import-linter 0 broken; comportamento utente
  (comandi/ordine/task_id) invariato rispetto a prima.
