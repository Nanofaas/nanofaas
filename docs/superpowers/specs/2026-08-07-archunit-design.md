# ArchUnit per nanofaas — Design

> Date: 2026-08-07. Status: approved by user (procedi).

## Goal

Convertire l'architettura implicita del control-plane (core + moduli SPI) in regole ArchUnit eseguite come test a ogni `./gradlew test`, con gate verde sul codice attuale (tranne il fix sync-queue previsto). La motivazione: nessun tool oggi verifica le invarianti architetturali (SonarQube è locale, buildHealth è sulle dipendenze, GitNexus è consultativo).

## Scope

- `platform/control-plane` (core) + i 9 moduli in `platform/modules/*`.
- Esclusi: `sdks/java`, `sdks/java-lite`, `clients/cli` (runtime standalone, YAGNI), `functions/*`, `services/*` (esempi).

## Dipendenza e compatibilità

- `testImplementation 'com.tngtech.archunit:archunit-junit5:1.4.1'` (ultima su Maven Central al 2026-08-07).
- **Spike obbligatorio nel primo task**: dipendenza + regola banale + run del test sul toolchain Java 25 (class file major 69). Se ArchUnit 1.4.1 non regge i class file Java 25, valutare versione più recente o toolchain alternativa; il tranche si ferma alla decisione (comunicare all'utente).

## Posizionamento dei test (nessun modulo dedicato)

I moduli sono progetti Gradle separati e opzionali nel build del core (selettore `-PcontrolPlaneModules`, default `''` per i test): un test centralizzato nel core **non vedrebbe** le classi dei moduli col classpath di default. Quindi:

| Test | Posizione | Classpath che vede |
|---|---|---|
| Regole core (R1–R4) | `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/architecture/CoreArchitectureTest.java` | core + common |
| Regole per modulo (R5′, R6) | `ArchitectureTest.java` nei test di ciascun modulo (`src/test/java/.../architecture/`) | il proprio modulo + core + common |

Gira sempre con `./gradlew test` (già in CI gitops.yml) — nessuna modifica CI.

`@AnalyzeClasses(packages = ...)` per scope preciso; per il core: `it.unimib.datai.nanofaas.controlplane..`; per i moduli: `it.unimib.datai.nanofaas.modules.<nome>..`.

## Regole

### Core (`CoreArchitectureTest`)

| # | Regola | Invarianza |
|---|---|---|
| R1 | `slices().matching("..controlplane.(*)..").should().beFreeOfCycles()` — **IGNORATA al 2026-08-07** (`@ArchIgnore` + commento, decisione utente): il codice attuale ha 5 cicli reali (config↔service↔execution/sync/offload, deployment↔registry); il refactor è rimandato a un tranche futuro, poi l'annotazione si rimuove | nessun ciclo tra i package del core |
| R2 | `noClasses().that().resideOutsideOfPackage("..controlplane.api..").should().dependOnClassesThat().resideInAPackage("..controlplane.api..")` | api = entry point, i layer sotto non risalgono (verificato: nessuna dipendenza service/dispatch → api oggi) |
| R3 | `noClasses().that().resideInAPackage("..controlplane..").should().dependOnClassesThat().resideInAPackage("..modules..")` | **optionalità dei moduli**: il core funziona senza moduli (verificato: zero import core → modules) |
| R4 | `noClasses().that().resideInAnyPackage("..controlplane.dispatch..", "..controlplane.execution..", "..controlplane.deployment..").should().dependOnClassesThat().resideInAPackage("..controlplane.service..")` | direzione unica api → service → dispatch (verificato per dispatch; execution/deployment da confermare a implementazione: se violano, non adeguare la regola — riportarlo, può essere un difetto reale) |

### Moduli (per ogni `ArchitectureTest` del modulo)

| # | Regola | Invarianza |
|---|---|---|
| R5′ | `noClasses().that().resideInAPackage("..modules.(*)..").should().dependOnClassesThat().resideInAPackage("..modules..")` con **allowlist esplicita** di coppie consentite via `ignoreDependency(origin, target)` + commento | isolamento SPI di default; estensione tra moduli = decisione esplicita e reviewabile (una riga per coppia). Oggi: zero import incrociati nel main |
| R6 | Ownership del namespace: ogni classe che risiede in `it.unimib.datai.nanofaas.controlplane..` deve avere il file sorgente sotto `platform/control-plane/` — implementata nel `CoreArchitectureTest` con scope `it.unimib.datai.nanofaas..` e un `ArchCondition` custom su `getSource().getUri()` (le sole regole sui package non possono esprimere ownership: una classe risiede in un unico package). Sintassi esatta nel piano. **Verde solo dopo lo spostamento sync-queue** | il namespace del core è di proprietà esclusiva del core |

Nota R5′: la coppia sync-queue → async-queue esiste oggi a livello di build (`runtimeOnly project(':control-plane-modules:async-queue')`). A implementazione: verificare se è uso reale a runtime (config/reflection) o residuo morto; se morta, rimuoverla (coerente col tranche buildHealth) e la allowlist resta vuota.

## Task extra: spostamento namespace sync-queue

Le 8 classi di sync-queue vivono nel namespace del core:
`it.unimib.datai.nanofaas.controlplane.{config,scheduler,sync}` (8 file, vedi elenco sotto) — vanno spostate in `it.unimib.datai.nanofaas.modules.syncqueue.{config,scheduler,sync}`.

- File: `SyncQueueProperties` (config), `SyncScheduler` (scheduler), `SyncQueueItem`, `SyncQueueAdmissionController`, `SyncQueueAdmissionResult`, `SyncQueueMetrics`, `WaitEstimator`, `SyncQueueService` (sync).
- Verificato: nessun riferimento esterno al modulo (core→modules import = 0, moduli→moduli = 0) → refactor contenuto a package + import dentro il modulo, incluse le classi di test.
- **Comportamento invariato**: nessuna logica toccata, solo dichiarazione package + import.
- Gate: test del modulo sync-queue verdi (soprattutto `SyncQueueServiceTest`, `SyncSchedulerTest` se esistono).
- Nome package coerente con gli altri moduli (es. `modules.asyncqueue` flat; sync-queue mantiene i sottopackage config/scheduler/sync per minimizzare il diff).

## Vincoli globali

- Solo cambiamenti behavior-preserving; nessuna logica toccata nello spostamento sync-queue.
- Nessun `@SuppressWarnings`/NOSONAR salvo casi documentati nella spec.
- Regole ArchUnit nominate con `@ArchTest` e `as("<nome descrittivo>")` con un commento sull'invarianza protetta.
- Commit in inglese, senza trailer Co-Authored-By; comunicazione in italiano.
- Gate per task: suite completa `./gradlew test` (non solo il modulo toccato) + `gitnexus_detect_changes` prima del commit.
- Se una regola risulta violata dal codice attuale (oltre R6/sync-queue): NON modificare il codice per far passare la regola — fermarsi e riportare all'utente (una regola che richiede refactor di logica è fuori scope e va discussa).

## Verifica finale

1. `./gradlew test --no-parallel` completo su tutti i moduli (arch test inclusi).
2. `./gradlew buildHealth` — nessuna nuova segnalazione rispetto al baseline.
3. `gitnexus_detect_changes` prima del commit: scope = soli file architetturali + sync-queue.
4. Eventuale re-index gitnexus dopo il merge (hook automatico post-commit).

## Fuori scope (esplicito)

- Trivy/OWASP (separato, non richiesto in questo tranche).
- Clippy/Ruff CI (Tier 1, non richiesto in questo tranche).
- Regole architetturali per sdks/cli.
- Rimozione dipendenze morte oltre la coppia sync-queue→async-queue se risultasse morta (caso coperto da R5′ task).
