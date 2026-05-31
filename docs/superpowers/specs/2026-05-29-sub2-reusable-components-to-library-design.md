# Design: Sotto-progetto 2 — Componenti riusabili in `workflow_tasks`

**Data:** 2026-05-29
**Stato:** Approvato (direzione) — in attesa di review dello spec
**Contesto:** secondo dei 6 sotto-progetti di
[workflow_tasks component library](2026-05-28-workflow-tasks-component-library-design.md).
Il sotto-progetto 1 (kernel) è in PR #85.

## Obiettivo

Spostare i **componenti di scenario riusabili** da `controlplane_tool/scenario/components/`
e i **task builder** da `controlplane_tool/scenario/tasks/` in `workflow_tasks/components/`,
appoggiandosi al kernel già migrato (shell, operations/models, infra/ansible, vm/orchestrator).
I componenti devono diventare riusabili senza trascinare in libreria i **tipi di request di
prodotto** (`E2eRequest`, `CliTestRequest`). Re-export shim in controlplane → comportamento
invariato.

## Decisione architetturale: context neutro (Approccio A)

I componenti (`bootstrap`, `cleanup`, `helm`, `images`, `namespace`, `registry`,
`verification`, `two_vm_loadtest`) ricevono tutti un `ScenarioExecutionContext`. L'esplorazione
ha stabilito:

- **Nessuno legge `context.request`** — né i componenti né i runner (verificato: gli `.request`
  in `e2e_runner` sono su `plan.request`, oggetto diverso). Il campo
  `request: E2eRequest | CliTestRequest` è **vestigiale**: `vm_cluster_workflows` lo riempie
  già con un valore fittizio (`request=cast(Any, vm_request)`). → **si cancella** (semplificazione).
- I componenti accedono a `context.resolved_scenario` per **due** cose (correzione rispetto alla
  prima stesura): `.namespace` (9 volte) **e** `.functions[].{key, family, runtime, image}`
  (in `images.py`, via `function_image_specs`). Quindi serve un **Protocol a 2 livelli**, non
  solo `.namespace`.
- I campi letti dai componenti sono: `vm_request` (26), `resolved_scenario` (.namespace + .functions),
  `local_registry` (13), `namespace` (12), `repo_root` (9), `manifest_path` (5), `release` (4),
  `runtime` (3), `scenario_name` (1).

Quindi:

- La libreria definisce un **`ScenarioExecutionContext` neutro** (in `workflow_tasks/components/
  context.py`) con i soli campi che i componenti leggono, **senza** il campo `request`.
  `resolved_scenario` è tipizzato con due `Protocol` strutturali in libreria:
  `ResolvedScenarioView` (`namespace: str | None`, `functions: Sequence[ResolvedFunctionView]`)
  e `ResolvedFunctionView` (`key: str`, `family: str | None`, `runtime: str`, `image: str | None`).
  **`ResolvedScenario`/`ResolvedFunction` NON salgono in libreria**: restano in controlplane e
  soddisfano i Protocol strutturalmente.
- `runtime` nel context diventa `runtime: str` (evita di far salire `RuntimeKind`).
- La factory `resolve_scenario_environment` + `default_managed_vm_request` + `_managed_vm_request`
  (che conoscono `E2eRequest|CliTestRequest`, `scenario_defaults`, `scenario_manifest`,
  `VM_BACKED_SCENARIOS`, `build_scenario_recipe`) **restano in controlplane** come assembly e
  costruiscono il context neutro della libreria.
- `E2eRequest` / `CliTestRequest` restano **request di prodotto in controlplane**.

Regola del confine confermata: *come si fa* (componenti) → libreria; *quali request, con quali
parametri, come si risolvono i default* (factory, request types) → controlplane.

## Contratti condivisi da spostare con i componenti

Alcune dipendenze sono contratti dati/utility realmente condivisibili e salgono in libreria:

- `loadtest/remote_k6.py` (`RemoteK6RunConfig`, `build_k6_command`) → `workflow_tasks/loadtest/`
  (usato da `two_vm_loadtest`). È un builder di comando k6, non logica di prodotto.
- `scenario/two_vm_loadtest_config.py` → valutare in 2b: ha 8 importatori; la parte usata dai
  componenti (`remap_loadtest_component_id` e affini) sale, il resto resta se è assembly.
- `RuntimeKind` → tipo banale in libreria.

## Componenti per gruppo e sequenza (2a / 2b / 2c)

> Scoperte che raffinano l'ordine: `composer._load_all_components()` importa **tutti** i
> componenti concreti all'init → `composer` si sposta **per ultimo**. `cleanup` importa
> `verification.plan_verify_cli_platform_status_fails` → va con `verification` (2b), non è
> "pulito". `recipes` è dati di prodotto (quali componenti per scenario) → decisione rimandata
> (potrebbe restare in controlplane come catalogo di assembly).

**2a — Fondamenta: context neutro + registry.**
- Migra `registry.py` (ComponentRegistry, dipende solo dal kernel `models`) → libreria. Warm-up
  pulito, nessun context.
- Crea `workflow_tasks/components/context.py`: `ScenarioExecutionContext` neutro + i Protocol
  `ResolvedScenarioView`/`ResolvedFunctionView`, senza il campo `request`. Ricabla i 3
  costruttori/consumatori in controlplane: `environment.resolve_scenario_environment` (factory,
  rimuove `request=`), `infra/vm/vm_cluster_workflows.py` (rimuove `request=cast(Any,...)`),
  `e2e/e2e_runner.py` (il punto che passa `context.resolved_scenario` a `CliComponentContext`
  usa `request.resolved_scenario`, stesso oggetto). Test verdi.

**2b — Componenti context-consumer + contratti condivisi.**
- Migra `images`, `namespace` (solo kernel + context neutro).
- Sposta `remote_k6` → `workflow_tasks/loadtest/`; parte condivisa di `two_vm_loadtest_config`.
- Migra `bootstrap` (path da `repo_root` iniettato), `helm`, `two_vm_loadtest`, `verification`
  (command-builder `platform_status_command`/`k8s_e2e_test_vm_script` iniettati o spostati se
  generici), e `cleanup` (con `verification`).

**2c — Casi speciali + chiusura.**
- `tasks/{cli,functions,k8s,vm}` → libreria (kernel VmOrchestrator già migrato).
- `tasks/loadtest.py`: `two_vm_loadtest_runner` è **orchestrazione** → resta; migra solo la parte
  componibile, iniettando il runner.
- `cli.py`: context proprio (`control_plane_endpoint`) + `cli_validation` + `scenario_helpers`.
  Default: **resta in controlplane** salvo parte chiaramente generica.
- `composer.py` (per ultimo, eager-load di tutti i componenti) e decisione su `recipes`.

## Semplificazione e cancellazione (portata onesta)

L'esplorazione ha verificato che i file di plumbing di `scenario/`
(`scenario_loader` 8 importatori, `scenario_helpers` 7, `scenario_models` 19, `catalog` 11,
`two_vm_loadtest_config` 8, `scenario_planner`/`command_resolver` usati da `e2e_runner` e CLI, …)
**sono vivi e usati**, non codice morto. La loro fusione/eliminazione appartiene al
**sotto-progetto 4** ed è esclusa da qui per non introdurre rischio.

Semplificazioni effettivamente in scope per il sotto-progetto 2:
1. **Disaccoppiamento via context neutro**: i componenti perdono la dipendenza dai request di
   prodotto — semplificazione strutturale reale.
2. **Rimozione di codice morto per-componente**: durante ogni spostamento, eliminare
   helper/export non più referenziati, **verificando caso per caso** (grep dei consumatori prima
   di cancellare). Nessuna cancellazione a tappeto.
3. **Niente nuova indirezione ridondante**: un solo context (quello neutro) per i componenti;
   evitare di duplicare context/DTO.
4. Se durante 2c emerge che `cli.py` ha un context separato fondibile senza rischio, unificarlo;
   altrimenti lasciarlo e annotarlo per il sotto-progetto 4.

## Vincoli e invarianti

- Direzione dipendenze: `controlplane_tool → workflow_tasks`, mai il contrario
  (import-linter + `test_package_boundaries.py`; aggiungere asserzioni per i nuovi moduli).
- `workflow_tasks` non importa `controlplane_tool` né `tui_toolkit`.
- Coverage libreria ≥ 90% mantenuto: i test dei componenti migrano/vengono scritti con loro.
- Comportamento utente invariato: gli shim preservano ogni nome pubblico; i consumatori
  controlplane continuano a funzionare senza modifiche.
- Gli shim creati qui sono **temporanei** (rimossi nel sotto-progetto 4).

## Fuori scope

- Fusione/eliminazione del plumbing di `scenario/` (sotto-progetto 4).
- Spostamento di `E2eRequest`/`CliTestRequest`/`ResolvedScenario`/`scenario_defaults`/
  `scenario_manifest`/`cli_validation` in libreria (restano assembly di prodotto).
- Runtime Prefect (sotto-progetto 3), bash (sotto-progetti 5-6).
- Modifiche al control-plane Java.

## Criteri di successo

- I componenti riusabili vivono in `workflow_tasks/components/` con un context neutro chiaro;
  hanno test propri in libreria.
- Nessun componente migrato importa `controlplane_tool`.
- La factory e i request di prodotto restano in controlplane; gli shim mantengono i consumatori
  verdi.
- Test libreria + controlplane verdi a ogni passo; import-linter 0 broken su entrambi.
- Eventuale codice morto incontrato durante lo spostamento è rimosso (verificato), senza toccare
  il plumbing vivo di `scenario/`.
