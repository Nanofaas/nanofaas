# Task A5 — Ritenzione idempotente e recupero dell'esito

**Stato:** implementato, testato, pronto per il commit. Nessun fallimento introdotto.

## Cosa è stato fatto

1. **La chiave resta vincolata per tutta la vita dell'esecuzione.** `ExecutionRecord`
   ora cattura la chiave al momento della costruzione (stabile attraverso i retry
   interni, che sostituiscono il task con uno a chiave `null`). `ExecutionStore.settle()`
   notifica un listener `onTerminal`, registrato da `InvocationExecutionFactory`, che
   chiama `IdempotencyStore.markTerminal(...)` — la transizione al vincolo terminale.

2. **Ritenzione terminale a partire dal completamento.** `IdempotencyStore` usa una
   scadenza *per stato*: `published` vive `maxLifetime` (muore con l'esecuzione),
   `terminal` vive `ttl` *dal completamento*. Chiude la finestra in cui una chiave
   pubblicata a t=0 e archiviata a t=29m scadeva a t=30m (vecchio
   `max(ttl, maxLifetime)`), riaprendo la doppia esecuzione.

3. **DEDUP separato da PAYLOAD (tombstone + HTTP 410).** Se l'esito di un'esecuzione
   conclusa viene espulso per capacità (`max-outcomes`) prima della fine della
   finestra, la chiave terminale resta come tombstone (execution id + scadenza). Il
   replay **non** riesegue la funzione: torna `410 Gone` con header `X-Execution-Id`.
   Mappato in `InvocationController` (sync e async), documentato in OpenAPI
   (`openapi/core.yaml`: risposte `410`/`429` + descrizione del parametro
   `Idempotency-Key`).

4. **Budget delle chiavi (`max-keys`).** A budget esaurito una **nuova** ammissione
   con chiave viene rifiutata con `429 Too Many Requests` *prima del dispatch*; i
   replay delle chiavi già presenti restano servibili. Il budget è separato da
   `max-outcomes`, così un tetto in byte sugli esiti non espelle mai la
   deduplicazione.

## Giudizi di progettazione (decisioni aperte risolte col minimo difendibile)

- **Rivendicazione stantia vs. tombstone.** Il ramo "chiave pubblicata che punta a
  un'esecuzione sparita senza essersi mai conclusa" (ammissione abbandonata dopo la
  pubblicazione) va distinto dal tombstone. Ho reintrodotto `claimIfMatches`, ma ora
  **solo** un vincolo `published` può essere rivendicato: un vincolo `terminal` non
  è mai rivendicabile (il CAS su `keys.replace` fa da guardia contro la corsa
  publish→terminal). I due test preesistenti `InvocationServiceDispatchTest.*staleIdempotencyMapping*`
  tornano verdi e il tombstone resta chiuso per sempre.
- **HTTP 429 (non 503) per il budget esaurito**, coerente con l'attuale backpressure
  (`QueueFullException` → 429).
- **Corpo vuoto su 410 con `X-Execution-Id`**, coerente con gli altri error handler
  esistenti; l'esplicitezza è data da status + header + OpenAPI.
- **Costruttori `(props, Ticker)` resi public** su `ExecutionStore` e
  `IdempotencyStore`: necessari per i test a tempo controllato (senza sleep fragili)
  dal package `service`, dove vive il test di contratto (il package `execution` non
  può dipendere da `service` — regola ArchUnit `lower_layers_do_not_depend_on_service`).
- **`ExecutionStoreProperties` senza costruttore extra.** Il costruttore compat a 4
  argomenti rompeva il binding `@ConfigurationProperties` di Spring ("No default
  constructor found"), come già avvertiva il commento del record. Sostituito con la
  factory statica `of(ttl, maxLifetime, syncTtl, maxOutcomes)`.

## File modificati

- `platform/control-plane/.../config/ExecutionStoreProperties.java` — `maxKeys` + `of(...)` 4-arg
- `platform/control-plane/.../execution/ExecutionRecord.java` — campo `idempotencyKey`
- `platform/control-plane/.../execution/ExecutionStore.java` — listener `onTerminal`, costruttore ticker public
- `platform/control-plane/.../execution/IdempotencyStore.java` — riscrittura (scadenza per stato, `markTerminal`, `claimIfMatches`, budget `maxKeys`, flag `terminal` in `AcquireResult`)
- `platform/control-plane/.../service/OutcomeGoneException.java` (nuovo)
- `platform/control-plane/.../service/IdempotencyBudgetExhaustedException.java` (nuovo)
- `platform/control-plane/.../service/InvocationExecutionFactory.java` — ramo `gone` vs `claimIfMatches`
- `platform/control-plane/.../service/ReactiveInvocationCoordinator.java` — `gone` → `OutcomeGoneException`
- `platform/control-plane/.../service/InvocationService.java` — `invokeAsync` `gone` → throw
- `platform/control-plane/.../api/InvocationController.java` — mapping 410/429
- `openapi/core.yaml`, `platform/control-plane/src/main/resources/application.yml`
- `docs/control-plane.md` — nuova sezione "Idempotency and outcome retention"
- test: `IdempotentRetentionContractTest` (nuovo, package `service`), `IdempotencyKeyLifetimeTest`
  (riscritto), `InvocationControllerTest` (+410/429), `InvocationServiceAsyncReplayTest`
  (+gone), aggiornamenti a `ExecutionStoreEvictionTest`, `ExecutionStoreAdministrativeExpiryTest`,
  `ExecutionCompletionHandlerAdministrativeExpiryTest` (factory `of(...)`).

## Test — celle di accettazione

`IdempotentRetentionContractTest` (tempo controllato, `Ticker` atomico, nessuno sleep):
esecuzione lunga oltre il vecchio orizzonte + TTL terminale; retry interni; espulsione
per capacità → `gone`; budget esaurito; replay concorrenti; nuova esecuzione dopo la
scadenza documentata. `InvocationServiceAsyncReplayTest` copre il ramo async (throw +
nessun re-dispatch: `verify(enqueuer, times(2))`). `InvocationControllerTest` copre la
mappa HTTP 410 (sync/async) e 429.

**Risultato suite completa:** `:control-plane:test` 488 test, **2 falliti** (entrambi
preesistenti, non corretti), 3 skipped; `:control-plane-modules:async-queue:test` e
`:control-plane-modules:sync-queue:test` verdi.

## Test falliti noti (preesistenti, NON corretti)

- `RateLimiterTest.allow_windowRolledOverButCounterNotYetReset_wronglyRejectsAFreshRequest` (A7)
- `ExecutionCompletionHandlerRetryMetricsRegressionTest.e2eLatencyAfterARetry_shouldReflectTheOriginalAdmissionTime_notJustTheLastAttempt` (M2)

## Rischi / limitazioni

- Il budget `maxKeys` è controllato prima di `putIfAbsent`, quindi sotto ammissioni
  concorrenti può sforare transitoriamente (soft bound), come il `maximumSize` di
  Caffeine; la garanzia richiesta ("rifiutare le NUOVE a budget esaurito, servire i
  replay") è rispettata.
- `gitnexus detect_changes` riporta "critical" per ampiezza (percorso d'invocazione),
  coerentemente con la portata del task; i simboli/flussi toccati sono tutti attesi.
