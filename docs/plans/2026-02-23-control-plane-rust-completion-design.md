# Design: Control-Plane Rust — Completamento verso Production Parity

**Data:** 2026-02-23
**Scope:** Port completo del control-plane Java in Rust per sostituzione in produzione
**Approccio:** Fix First, Build On Top (Approccio B)

---

## Contesto

Il milestone M3 del port Rust (`experiments/control-plane-staging/`) ha prodotto:
- L'ossatura HTTP (routing Axum, modelli, trait)
- 252 test "parity" mappati dalla suite Java
- Un binario compilabile con Dockerfile

Ma presenta gap critici che impediscono il deployment in produzione:
- I/O di rete bloccante sul runtime Tokio async (`std::net::TcpStream`)
- Nessun modello di completion async (manca `CompletableFuture` equivalente)
- Due registry delle funzioni disconnesse
- Moduli opzionali non implementati (async-queue, sync-queue, autoscaler, ecc.)
- Bug strutturali nel rate limiter, lock + I/O, cold-start detection

---

## Decisioni di Design

### Modello Async
Usare `tokio::sync::oneshot` per il completion model: il dispatcher invia la richiesta al pod,
l'handler aspetta sul receiver con `tokio::time::timeout(timeoutMs)`. Il callback
`POST /v1/internal/executions/{id}:complete` invia sul sender. Questo replica la semantica
di `CompletableFuture` Java senza shared state.

### Registry Unificata
Rimuovere `AppState.functions: Mutex<HashMap>` e collegare tutto a `FunctionService`
(già in `registry.rs`). Usare `RwLock` per read-heavy workload.

### Moduli Opzionali
Abilitati via env var (es. `NANOFAAS_ASYNC_QUEUE_ENABLED=true`), disabilitati di default
(no-op come Java). Ogni modulo sostituisce il corrispondente no-op trait.

---

## Milestone Plan

### M4 — Bug Fix Fondamentali
**Prerequisito:** nessuno
**Criterio di accettazione:** `cargo test -q` verde + `e2e_dockerized_flow_test.rs` verde

1. BUG-1: `dispatch.rs` — `std::net::TcpStream` → `tokio::net::TcpStream` + `.await`.
   Propagare `async` su `Dispatcher::dispatch`, `invoke_function`, `enqueue_function`, `drain_once`.
2. BUG-3: `scheduler.rs` / `app.rs` — Ristrutturare `drain_once` per non tenere lock
   durante il dispatch I/O: estrai dati, rilascia lock, dispatcha, riacquisisci per update.
3. BUG-2: `rate_limiter.rs` — Reset window via epoch-second (come Java).
   Fix default da 10.000 → 1.000. Aggiungere test concorrente.
4. BUG-12: Rate limit leggibile da config/env var.
5. BUG-13: `QueueManager.enqueue()` riceve `queue_size` dal `FunctionSpec` invece del globale.
6. CS-7: Sostituire `expect("lock")` con `unwrap_or_else(|e| e.into_inner())` ovunque.

---

### M5 — Registry & Invocation Core
**Prerequisito:** M4
**Criterio di accettazione:** `FunctionControllerTest`, `ValidationTest`, `GlobalExceptionHandlerTest`
verdi senza stub image-name.

1. Registry unificata: rimuovere `AppState.functions`, collegare a `FunctionService`.
   `RwLock` invece di `Mutex` per read-heavy.
2. `FunctionService.register()` rollback: `registry.remove(&name)` su errore di
   `image_validator.validate()` o `resource_manager.provision()`.
3. `set_replicas()` non-panic: `Result<_, AppError>` invece di `panic!()`.
4. `GlobalExceptionHandler`: handler centralizzato per `ValidationError`,
   `FunctionNotFoundError`, `ImageValidationError`, `QueueFullError`, `RateLimitError`
   → status code + JSON body strutturato.
5. `InvocationResponse.error` popolato quando `status = ERROR`.
6. `X-Execution-Id` header nella response (invoke + enqueue).
7. `X-Timeout-Ms` header in ingresso → override del timeout.
8. `kubernetes.rs` `build_env_vars()`: iniettare `WATCHDOG_CMD` se `spec.runtime_command` non-blank.
9. Rimuovere `seen_sync_invocations` HashSet (sostituito dalla cold-start detection in M6).
10. Test parity: sbloccare/correggere `function_controller_parity_test.rs`,
    `invocation_controller_parity_test.rs`, `validation_parity_test.rs`,
    `global_exception_handler_parity_test.rs`.

---

### M6 — Completion Model & Execution Lifecycle
**Prerequisito:** M5
**Criterio di accettazione:** `ExecutionCompletionHandlerTest`, `InvocationServiceDispatchTest`,
`PoolDispatcher*` verdi con fake HTTP server (non stub).

1. `ExecutionRecord.completion`: aggiungere `oneshot::Sender<DispatchResult>`.
   `complete(result)` invia sul sender.
2. `invoke_function` async wait: dopo dispatch iniziale, aspettare su receiver con
   `tokio::time::timeout(timeoutMs)`.
3. `ExecutionRecord.mark_running()`: chiamare prima del dispatch (setta `started_at`,
   transizione a `RUNNING`).
4. State transition validation: `ALLOWED_TRANSITIONS` mappa con log warning per
   transizioni invalide.
5. Cold-start da response headers: `PoolDispatcher` legge `X-Cold-Start` e
   `X-Init-Duration-Ms` dalla risposta del runtime.
6. `ExecutionStatus` response completa: rimuovere `#[serde(skip_serializing)]` dai
   campi timing, aggiungere mappatura equivalente a `InvocationService.toStatus()`.
7. `CompletionRequest.error`: aggiungere `Option<ErrorInfo>` (con `code` e `message`).
8. Timeout handling: `mark_timeout()` + risposta `status: TIMEOUT` su receiver scaduto.
9. `X-Trace-Id` e `Idempotency-Key` forwarding in `PoolDispatcher`.
10. BUG-6 fix: validare state transition in `complete_execution` prima di aggiornare.
11. Test parity M6.

---

### M7 — Moduli Opzionali
**Prerequisito:** M6
**Criterio di accettazione:** `e2e_dockerized_flow_test.rs` verde con async-queue + sync-queue
abilitati. K8s parity test con autoscaler.

#### M7a — async-queue
- `AsyncQueueModule`: sostituisce `NoOpInvocationEnqueuer` con vero `InvocationEnqueuer`
  backed da `QueueManager` + `Scheduler`.
- Il path invoke consulta l'enqueuer se async-queue è abilitato.
- `POST /v1/functions/{name}:enqueue` funzionale.
- Test: `invocation_service_retry_queue_full_parity_test.rs` reale.

#### M7b — sync-queue (prerequisito: M7a)
- `SyncQueueModule`: sostituisce `NoOpSyncQueueGateway` con admission queue backpressure.
- `enqueue_or_throw()` con stima `est_wait` e `depth`.
- Response 429 strutturata con `est_wait`, `queue_depth`.
- Test: `sync_queue_backpressure_api_parity_test.rs` senza hack image-name.

#### M7c — autoscaler (prerequisito: M7a)
- `AutoscalerModule`: sostituisce `NoOpScalingMetricsSource`.
- `ScalingMetricsSource::get_metrics()` alimenta scale-up/down.
- Integrazione con `KubernetesResourceManager` per patch repliche.

#### M7d — runtime-config
- `GET/PUT /v1/admin/runtime-config` endpoint.
- Hot-reload defaults (timeout, concurrency, queueSize).
- Abilitato da `NANOFAAS_ADMIN_RUNTIME_CONFIG_ENABLED=true`.

#### M7e — image-validator (prerequisito: M5)
- `ImageValidatorModule`: verifica esistenza immagine via K8s API prima di registrare.
- Test: `kubernetes_resource_manager_parity_test.rs` con mock K8s client.

---

### M8 — Hardening, Metriche Complete, Dead Code
**Prerequisito:** M7 (può parzialmente procedere in parallelo)
**Criterio di accettazione:** `e2e_k8s_parity_test.rs` verde. Prometheus espone tutti i 15
counter/timer. `cargo clippy -- -D warnings` pulito.

1. Metriche mancanti: `function_error_total`, `function_retry_total`,
   `function_timeout_total`, `function_queue_rejected_total` con chiamate nel lifecycle.
2. `HttpClientProperties` applicato: connect timeout + read timeout su HTTP client async
   da env var.
3. Dead code cleanup: rimuovere `application.rs`, `config.rs`,
   adapter ridondanti in `core_defaults.rs`.
4. BUG-11 fix: `with_function_lock` ABA race → contatore atomico esplicito invece
   di `Arc::strong_count`.
5. BUG-8 fix: `store.put_now()` solo dopo enqueue riuscito (o rollback esplicito).
6. CS-9 fix: loggare warning su `application/json` con body non-JSON.
7. `IdempotencyStore.put()` incondizionato per refresh mapping stale.
8. Test E2E K8s: `e2e_k8s_parity_test.rs` verde.
9. Prometheus endpoint: verifica tutti i 15 counter/timer Java presenti.

---

## Dipendenze tra Milestone

```
M4 (Bug Fix Fondamentali)
  └─> M5 (Registry & Invocation Core)
        └─> M6 (Completion Model)
              └─> M7a (async-queue)
                    ├─> M7b (sync-queue)
                    └─> M7c (autoscaler)
              └─> M7d (runtime-config) [indipendente]
        └─> M7e (image-validator) [dipende da M5]
M8 (Hardening) — può iniziare dopo M6, completa dopo M7
```

---

## File Principali Interessati

| File | Milestone |
|------|-----------|
| `src/dispatch.rs` | M4, M6 |
| `src/app.rs` | M4, M5, M6, M8 |
| `src/rate_limiter.rs` | M4 |
| `src/queue.rs` | M4, M7a |
| `src/scheduler.rs` | M4, M7a |
| `src/registry.rs` | M5 |
| `src/errors.rs` | M5 |
| `src/execution.rs` | M6 |
| `src/metrics.rs` | M8 |
| `src/kubernetes.rs` | M5, M7e |
| `src/sync.rs` | M7b |
| `src/service.rs` | M7a, M7c |
| `src/application.rs` | M8 (remove) |
| `src/config.rs` | M8 (remove) |
| `src/core_defaults.rs` | M8 (cleanup) |
| `Cargo.toml` | M4 (tokio features) |
