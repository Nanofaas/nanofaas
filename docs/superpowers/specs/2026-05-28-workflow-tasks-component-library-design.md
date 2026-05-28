# Design: `workflow_tasks` come libreria di componenti componibili, `controlplane_tool` come assemblatore

**Data:** 2026-05-28
**Stato:** Approvato (design) — roadmap in 6 sotto-progetti
**Autore:** brainstorming con Claude

## Obiettivo

Trasformare `workflow_tasks` da libreria "minimale e generica" a **catalogo ricco di
componenti pronti e componibili** per costruire ed eseguire workflow. `controlplane_tool`
diventa l'**assemblatore**: usa le primitive e i componenti della libreria per comporre
workflow concreti ed eseguirli, più la UX (CLI Typer + TUI). Eliminare tutti gli script
bash di workflow: **una sola via** per ogni operazione, il console-script `controlplane-tool`.

## Motivazione

Oggi `controlplane_tool` contiene tre strati sovrapposti che parlano tutti di "workflow"
(`scenario/`, `orchestation/`, `workflow/`) e tiene prigionieri molti componenti riusabili
che sono materiale da libreria. `workflow_tasks` ha già le primitive ma è artificialmente
mantenuto generico. Il risultato è un orchestratore gonfio, confini sfumati e percorsi
paralleli (bash + CLI + scenario). Vogliamo invertire la narrativa: libreria ricca e
opinionata, orchestratore sottile, un solo entrypoint.

## Architettura target

### Regola del confine

> **"Come si fa una cosa"** vive nella libreria (`workflow_tasks`).
> **"Quali cose si fanno, in che ordine, con quali parametri"** vive in `controlplane_tool`.

La direzione delle dipendenze resta **unidirezionale**: `controlplane_tool → workflow_tasks`.
La libreria non deve **mai** importare `controlplane_tool` né `tui_toolkit`. Il contratto
import-linter e `tests/test_package_boundaries.py` restano in vigore, ma cambia la loro
narrativa: non più "prova di genericità" bensì "prova di direzione corretta delle dipendenze".

### `workflow_tasks` — catalogo componenti + runtime

Mantiene le primitive attuali (`phase/step/success/fail`, eventi, `WorkflowContext`,
`WorkflowState`, task specs, rendering, provider VM, loadtest models/adapters/prometheus,
integrazione Prefect). Assorbe da `controlplane_tool`:

- **Task builder concreti**: `scenario/tasks/{cli,functions,k8s,loadtest,vm}`
  → `workflow_tasks/components/{...}`
- **Componenti riusabili**: `scenario/components/{bootstrap,cleanup,helm,images,registry,
  namespace,verification,operations}` → libreria
- **Infra**: `infra/vm/ansible_adapter.py` (`AnsibleAdapter`) + runner VM + risoluzione path
  → `workflow_tasks/infra/`. Lo YAML Ansible **resta in `ops/`**; il path viene iniettato.
- **Runtime di esecuzione**: `orchestation/{prefect_runtime,prefect_models,adapters}` +
  `run_local_flow` → libreria, come runtime di esecuzione dei flow.
- **Logica oggi in bash**: native build (GraalVM/SDKMAN), build & push immagini OCI,
  experiment e2e/k6 → componenti di libreria.

### `controlplane_tool` — assemblatore + UX

Tiene solo:

- **Definizioni di scenario/recipe** (`scenario/scenarios/*`, catalogo, resolver parametri):
  *quali* componenti, in *quale* ordine, con *quali* parametri. Config di prodotto, non logica.
- **CLI Typer** (`cli/`) — unico entrypoint utente.
- **TUI** (`tui/`) — chiama internamente gli stessi comandi CLI.
- Sottili moduli di supporto: `core/`, `workspace/`.

Strati eliminati da controlplane:

- `workflow/` (re-export shim di `workflow_tasks.workflow.*`) → import diretti dalla libreria.
- `orchestation/` → runtime in libreria; resta solo il `flow_catalog` (assembly), che si
  fonde dentro `scenario/`. Il typo "orchestation" sparisce.
- File di plumbing ridondanti di `scenario/` (`scenario_loader`, `scenario_manifest`,
  `scenario_planner`, `scenario_runtime`, `scenario_helpers`, `command_resolver`,
  `selection_resolution`) si fondono/snelliscono attorno al modello recipe→componenti.

Risultato netto: da ~5-6 sottosistemi a sostanzialmente **3** (`cli/`, `tui/`, `scenario/`
= assembly+definizioni) più supporto.

### Una sola via

- Unico entrypoint: console-script **`controlplane-tool`** (+ TUI che chiama gli stessi comandi).
- Nessun percorso parallelo bash/CLI/scenario.
- Gli unici `.sh` superstiti sono quelli eseguiti **dentro la VM via Ansible** (provisioning,
  in `ops/`): sono bootstrap d'ambiente, non workflow.

## Flusso di esecuzione (invariato nel comportamento)

```
comando utente (CLI/TUI)
  → catalogo controlplane: nome → recipe (lista componenti + parametri)
  → composizione: recipe → LocalFlowDefinition (componenti dalla libreria)
  → runtime libreria: run_local_flow(...) esegue via Prefect
  → eventi/progress (workflow_tasks.workflow.*) → UX
```

## Roadmap (6 sotto-progetti sequenziali)

Ogni sotto-progetto ha il proprio spec→plan→implementazione, mantiene la CLI funzionante e
i test verdi, e usa GitNexus `impact` prima di spostare simboli (come da CLAUDE.md).

1. **Fondamenta libreria — componenti di esecuzione.** Sposta `scenario/tasks/*` e i
   `scenario/components/*` riusabili in `workflow_tasks/components/`. controlplane re-importa
   via shim temporanei. Aggiorna import-linter. Nessun cambiamento di comportamento.
2. **Infra in libreria.** `AnsibleAdapter` + runner VM + risoluzione path →
   `workflow_tasks/infra/`. YAML resta in `ops/`, path iniettato.
3. **Runtime in libreria.** `orchestation/prefect_*` + `run_local_flow` + `adapters` →
   `workflow_tasks`. `flow_catalog` resta in controlplane (assembly).
4. **Pulizia controlplane.** Elimina shim `workflow/` e re-export; fondi/snellisci plumbing
   di `scenario/`; rimuovi `orchestation/` da controlplane; rimuovi gli shim temporanei dei
   passi 1-3.
5. **Porta i bash con logica.** `native-build`, `build-push-images`, `experiments/e2e-*`,
   `experiments/k6/run-all` → componenti libreria + comandi CLI Typer.
6. **Elimina i wrapper bash + cablaggio finale.** Rimuovi tutti i `.sh` wrapper; aggiorna
   CLAUDE.md, docs e CI che li invocano; verifica che la TUI usi gli stessi comandi CLI.

## Vincoli e invarianti

- Direzione dipendenze: `controlplane_tool → workflow_tasks`, mai il contrario.
- `workflow_tasks` non importa `controlplane_tool` né `tui_toolkit` (test + import-linter).
- Coverage `workflow_tasks` ≥ 90% (gate esistente in pyproject) va mantenuto man mano che i
  componenti migrano: i test corrispondenti migrano con loro.
- Comportamento utente invariato durante i passi 1-4 (refactor puro); nuovi comandi solo al
  passo 5; rimozione percorsi vecchi solo al passo 6.
- Java 21 / control-plane non toccati: questo lavoro riguarda solo il tooling Python in
  `tools/` e gli script in `scripts/`+`experiments/`.

## Fuori scope

- Modifiche al control-plane Java, function-runtime, SDK.
- Cambiamenti funzionali agli scenari o ai loadtest (solo ricollocazione del codice).
- Riprogettazione della TUI oltre il farle chiamare gli stessi comandi CLI.

## Criteri di successo

- `workflow_tasks` espone un catalogo di componenti riusabili + runtime; i componenti hanno
  interfacce chiare e test propri.
- `controlplane_tool` contiene solo assembly (definizioni recipe/scenario) + CLI + TUI.
- Zero script bash di workflow; un solo entrypoint `controlplane-tool`.
- Tutti i test verdi a ogni passo; CLI e TUI funzionalmente equivalenti a prima (passi 1-4),
  con i nuovi comandi `native-build`/`build-push-images`/experiment al passo 5.
