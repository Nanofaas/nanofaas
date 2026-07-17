# Modulo `offload` — proxy trasparente condizionale verso istanza nanofaas remota

## Context

Vogliamo che il control-plane locale (edge) possa **offloadare invocazioni sincrone** verso un'altra istanza nanofaas (cloud) quando condizioni di pressione locale lo giustificano, o quando la funzione è marcata per l'offload. Decisioni prese in brainstorming con l'utente:

- **Proxy trasparente** (opzione A): il control-plane locale chiama il `:invoke` remoto, aspetta e restituisce la risposta; il client non se ne accorge; l'esecuzione resta tracciata nell'`ExecutionStore` locale.
- **Solo path sincrono** in v1 (`:invoke`); async esplicitamente fuori scope.
- **Strategie**: (1) saturazione locale, (2) attesa stimata sopra soglia, (3) policy per-funzione.
- **Nessun fallback locale**: se l'offload fallisce, errore diretto al client (502; 504 su timeout remoto).
- **Registrazione remota assunta presente**: 404 remoto = errore di configurazione (502 con messaggio chiaro).
- **Endpoint**: default globale + override per-funzione nel `FunctionSpec`.
- **Niente catene di offload**: un nodo che riceve una richiesta offloadata non può a sua volta re-inoltrarla (single hop).
- **Architettura approvata**: SPI `OffloadGateway` nel core (pattern `SyncQueueGateway`), decisione in `ReactiveInvocationCoordinator` prima dell'ammissione; modulo `platform/modules/offload/` con l'implementazione.

**Insight chiave** (dalla lettura del codice): le strategie 1 e 2 esistono già come rifiuti dell'admission controller del sync-queue — `SyncQueueAdmissionController.evaluate` rigetta con `SyncQueueRejectReason.DEPTH` (saturazione) o `EST_WAIT` (attesa stimata). Quindi non duplico segnali: **l'offload su pressione = intercettare la `SyncQueueRejectedException` nel coordinator e offloadare invece di propagare il 429.** Le strategie 1–2 richiedono il modulo sync-queue attivo (documentato); la strategia 3 (eager) funziona sempre.

## File chiave (letti)

- `platform/control-plane/.../service/ReactiveInvocationCoordinator.java` — punto d'aggancio (admitIfNew + attesa su `record.completion()`)
- `platform/control-plane/.../service/InvocationService.java` — costruttore convenience che istanzia il coordinator a mano (da aggiornare)
- `platform/control-plane/.../sync/SyncQueueGateway.java` — pattern SPI da imitare
- `platform/control-plane/.../config/HttpClientConfig.java` — bean `WebClient` già pronto da riusare
- `platform/control-plane/.../api/GlobalExceptionHandler.java` — dove mappare le nuove eccezioni
- `platform/common/.../model/FunctionSpec.java` — record da estendere con blocco `offload`
- `platform/modules/sync-queue/` — modello per layout modulo (Module/Configuration/build.gradle/META-INF services)
- `settings.gradle` — auto-scopre le directory sotto `platform/modules/`; `all` include automaticamente il nuovo modulo (nessuna modifica)

## Design

### 1. SPI nel core: `controlplane/offload/OffloadGateway.java`

```java
public interface OffloadGateway {
    boolean enabled();
    /** Strategia 3: la policy della funzione impone offload immediato. */
    boolean shouldOffloadEagerly(FunctionSpec spec);
    /** Strategie 1-2: il sync-queue ha rigettato (DEPTH/EST_WAIT); offloadare? */
    boolean shouldOffloadOnPressure(FunctionSpec spec, SyncQueueRejectReason reason);
    /** Proxy verso il :invoke remoto. Errori infra → OffloadFailedException nel Mono. */
    Mono<InvocationResult> invokeRemote(ExecutionRecord record, FunctionSpec spec);
    static OffloadGateway noOp() { ... } // enabled=false
}
```

Più `OffloadFailedException` nel core (campo `gatewayTimeout` boolean per distinguere 502/504), mappata in `GlobalExceptionHandler` → 502 BAD_GATEWAY / 504 GATEWAY_TIMEOUT.

### 2. Aggancio in `ReactiveInvocationCoordinator.invoke`

- Costruttore: nuovo param `@Nullable OffloadGateway` (default no-op). **Attenzione**: aggiornare anche il costruttore convenience di `InvocationService` che fa `new ReactiveInvocationCoordinator(...)`, e i test che istanziano entrambi (lezione nota: i cambi di firma rompono i test).
- **Anti-loop (single hop)**: il remote invoker aggiunge l'header `X-NanoFaaS-Offload-Hop: 1` alla richiesta inoltrata; `InvocationController` lo legge e passa un flag `offloadedRequest` giù fino al coordinator (stesso veicolo dei header W3C: campo opzionale su task/record). Se il flag è attivo, il coordinator salta ENTRAMBI i branch di offload — il nodo ricevente si comporta come se il modulo non esistesse (se è saturo risponde 429 all'edge, che lo restituisce al client come `OffloadFailedException` → 502).
- Prima di `admitIfNew`: se `!offloadedRequest && enabled() && shouldOffloadEagerly(spec)` → ramo offload.
- Nel `catch` dell'ammissione: se l'eccezione è `SyncQueueRejectedException` e `shouldOffloadOnPressure(spec, reason)` → ramo offload invece di `Mono.error(ex)`.
- **Ramo offload** (catena separata, non passa dalla coda → zero slot di concorrenza locali consumati):
  ```
  gateway.invokeRemote(record, spec)
      .timeout(spec.timeoutMs) → su timeout: OffloadFailedException(gatewayTimeout=true)
      .doOnNext(result -> completionHandler.completeExecution(executionId, result))  // sblocca i waiter idempotenti
      .map(result -> responseMapper.toResponse(record, result))
      .onErrorMap/…: OffloadFailedException → record.markError + metrics.error + rilancio (→ 502/504)
  ```
  Il replay idempotente resta invariato (il record è un normale record locale).

### 3. Estensione `FunctionSpec` (platform/common)

Nuovo componente record `OffloadPolicy offload` (nullable):

```java
public record OffloadPolicy(Boolean enabled, String targetUrl, String mode) {} // mode: "pressure" (default) | "always"
```

Semantica: blocco assente → la funzione segue il default globale (offload su pressione se il modulo è attivo); `enabled=false` → mai offload per questa funzione; `mode="always"` → eager (strategia 3); `targetUrl` → override del target globale.

**Aggiornare TUTTI i costruttori/chiamanti**: `grep -rn 'new FunctionSpec(' platform clients` — aggiungere il campo in coda + mantenere il costruttore convenience esistente (delega con `null`), come già fatto per `imagePullSecrets`.

### 4. Modulo `platform/modules/offload/`

Layout speculare a sync-queue:

- `build.gradle` — copia da sync-queue senza la dipendenza async-queue (deps: `:common`, `:control-plane`, spring-boot-starter, actuator, webflux già transitiva dal control-plane; test: spring-boot-starter-test, mockwebserver come nei PoolDispatcherTest)
- `src/main/resources/META-INF/services/it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule` → `it.unimib.datai.nanofaas.modules.offload.OffloadModule`
- `OffloadModule` (2 righe, come `SyncQueueModule`)
- `OffloadConfiguration` — `@EnableConfigurationProperties(OffloadProperties)`, `@Bean @Primary OffloadGateway` (riusa il bean `WebClient` del core)
- `OffloadProperties` (`nanofaas.offload.*`): `enabled` (default `true` quando il modulo è caricato), `target-url` (default globale, obbligatoria se enabled — validazione all'avvio), `pressure-enabled` (default `true`, interruttore per strategie 1-2)
- `DefaultOffloadGateway`:
  - `shouldOffloadEagerly`: `spec.offload() != null && enabled!=false && "always".equals(mode)`
  - `shouldOffloadOnPressure`: `pressure-enabled && (spec.offload() == null || enabled!=false)` (il reason DEPTH/EST_WAIT è già la strategia; nessuna soglia duplicata)
  - `invokeRemote`: `POST {target}/v1/functions/{name}:invoke` col payload originale (`record.task()`); 2xx → mappa body a `InvocationResult`; 404 → `OffloadFailedException("function not registered on remote …")`; altri 4xx/5xx/connessione → `OffloadFailedException`; il timeout lo gestisce il coordinator
  - **Tracing dell'offload**: inoltra `X-Trace-Id` (correlazione esistente edge↔cloud) e fa pass-through di `traceparent`/`tracestate` se presenti nella richiesta originale (non li genera: adozione W3C/OTel completa = follow-up di piattaforma con micrometer-tracing, fuori scope). La risposta dell'edge per un'invocazione offloadata include l'header `X-NanoFaaS-Offloaded: <target-url>` (nota: serve propagare i due header W3C dal controller fino a `InvocationTask`/record, oggi arriva solo il trace-id — passarli come mappa headers opzionale nel task)
  - Metrica micrometer: `nanofaas_offload_total{function,trigger=eager|depth|est_wait}` e `nanofaas_offload_failure_total{function}` (pattern `SyncQueueMetrics`)

### 5. Fuori scope (documentato nel design doc)

Async offload, propagazione automatica del FunctionSpec al remoto, multi-target/round-robin, fallback locale, soglie di pressione proprie del modulo (si riusa l'admission sync-queue), adozione completa di W3C Trace Context/OTel (micrometer-tracing) a livello piattaforma — qui solo pass-through.

## Passi di implementazione

1. **Design doc**: scrivere `docs/superpowers/specs/2026-07-17-offload-module-design.md` (contenuto = sezione Design sopra + decisioni/fuori-scope) e committarlo.
2. **GitNexus**: `gitnexus_impact` su `ReactiveInvocationCoordinator.invoke`, `InvocationService`, `FunctionSpec` prima delle modifiche (obbligo CLAUDE.md); riportare blast radius.
3. **Core — SPI**: `OffloadGateway` + `NoOpOffloadGateway` + `OffloadFailedException` in `controlplane/offload/`; mapping 502/504 in `GlobalExceptionHandler`.
4. **Core — coordinator**: modifica `ReactiveInvocationCoordinator` (nuovo param + due branch + ramo offload); aggiornare il costruttore convenience di `InvocationService`.
5. **Common — FunctionSpec**: aggiungere `OffloadPolicy`; aggiornare tutti i `new FunctionSpec(` (test inclusi).
6. **Modulo**: creare `platform/modules/offload/` come da design (settings.gradle lo scopre da solo).
7. **Test** (TDD dove sensato):
   - Core: test del coordinator con gateway mock — eager, pressure-su-rejection, offload disabilitato (429 invariato), fallimento remoto → 502/504, waiter idempotente sbloccato, richiesta con `X-NanoFaaS-Offload-Hop` mai re-offloadata.
   - Modulo: `DefaultOffloadGateway` con MockWebServer (2xx/404/5xx/connection refused) + unit test delle decisioni; test proprietà (target-url mancante → fail-fast).
8. **Suite completa**: `./gradlew test` (lezione: mai solo `:control-plane:test` quando si toccano firme condivise in common). Poi `uv run --project tools/controlplane pytest tools/controlplane/tests` (gate Python su script/moduli).
9. **Docs**: aggiungere `offload` alla lista moduli in CLAUDE.md/AGENTS.md + chiavi `nanofaas.offload.*` nella sezione configurazione.
10. **GitNexus post**: `gitnexus_detect_changes()` prima del commit.

## Verifica end-to-end

Smoke manuale con due istanze locali:

```bash
# istanza "cloud" su :9090/:9091
SERVER_PORT=9090 MANAGEMENT_SERVER_PORT=9091 ./scripts/controlplane.sh run --profile core &
# istanza "edge" con modulo offload puntato al cloud
./scripts/controlplane.sh run --profile all -- --args='--nanofaas.offload.target-url=http://localhost:9090'
```

1. Registrare la stessa funzione (mode LOCAL o POOL) su entrambe.
2. Policy eager: registrare sull'edge con `offload: {mode: "always"}` → invocare → risposta ok con header `X-NanoFaaS-Offloaded`, contatore `nanofaas_offload_total{trigger="eager"}` incrementato su :8081, esecuzione remota visibile sulle metriche del cloud; invocando con `curl -H 'X-Trace-Id: t-123'` lo stesso trace id compare nei log di entrambe le istanze.
3. Pressione: saturare l'edge (concurrency 1 + burst) → verificare che le richieste che prima prendevano 429 ora completano via offload (`trigger="depth"|"est_wait"`).
4. Fallimento: spegnere l'istanza cloud → invocare con policy eager → 502 col messaggio di offload; funzione non registrata sul cloud → 502 "not registered on remote".
