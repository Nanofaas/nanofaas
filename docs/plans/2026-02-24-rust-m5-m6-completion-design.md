# Design: Rust M5 + M6 Completion

**Data:** 2026-02-24
**Scope:** Chiusura dei gap rimanenti in M5 (Registry & Invocation Core) e M6 (Completion Model)
**Approccio:** Sequenziale M5 → M6; ogni milestone verificabile con `cargo test`

---

## M5 — Registry & Invocation Core (5 item)

### M5.2 — Registry rollback su provision failure

In `app.rs`, nel handler di registrazione funzione, se `provision()` fallisce dopo che la
funzione è già stata aggiunta al registry, aggiungere `registry.remove(&name)` nel branch
di errore. Evita lo stato "registrata senza endpoint".

### M5.3 — `set_replicas()` non-panic

In `registry.rs`, la riga con `panic!()` per mode check diventa `return Err(AppError::...)`.
Il caller in `app.rs` gestisce il Result con propagazione dell'errore.

### M5.4 — GlobalExceptionHandler

Centralizzare i mapping errore→HTTP status in una funzione `into_response(AppError)` o
tramite implementazione di `IntoResponse` per `AppError`. Gli handler attuali già usano
`StatusCode` espliciti; si tratta di fattorizzare la logica in un punto unico.

### M5.5 — `InvocationResponse.error` popolato

In `wait_for_sync_completion()` e nel path async scheduler (`finalize_dispatch`), quando
`status = ERROR`, popolare `error: Some(ErrorInfo { code, message })`.
Attualmente il campo rimane `None` anche su esecuzione fallita.

### M5.7 — `X-Timeout-Ms` header override

In `invoke_function()`, leggere l'header `X-Timeout-Ms` e sovrascrivere il timeout del
`FunctionSpec` se presente. Parsing: `header.parse::<u64>()`, fallback al timeout del spec.

---

## M6 — Completion Model & Execution Lifecycle (4 item)

### M6.2 — Oneshot receiver per sync invoke (refactor dal polling)

`invoke_function()` chiama `ExecutionRecord::new_with_completion()` per ottenere `(record, rx)`.
Il record entra nello store con il sender. Il dispatch avviene normalmente.
L'handler aspetta `tokio::time::timeout(timeout_ms, rx).await`.
Il callback `POST /v1/internal/executions/{id}:complete` chiama `record.complete(result)`
che invia sul sender, sbloccando il chiamante.

La funzione `wait_for_sync_completion()` (polling loop ~10ms) viene rimossa.

### M6.4 — State transition validation

In `execution.rs`, aggiungere:
```rust
const ALLOWED_TRANSITIONS: &[(ExecutionState, ExecutionState)] = &[
    (ExecutionState::Queued, ExecutionState::Running),
    (ExecutionState::Running, ExecutionState::Success),
    (ExecutionState::Running, ExecutionState::Error),
    (ExecutionState::Running, ExecutionState::Timeout),
    (ExecutionState::Error, ExecutionState::Queued),   // retry
];

fn is_valid_transition(from: &ExecutionState, to: &ExecutionState) -> bool {
    ALLOWED_TRANSITIONS.iter().any(|(f, t)| f == from && t == to)
}
```

Nei metodi `mark_*`, loggare `eprintln!("invalid transition: {:?} -> {:?}", from, to)` senza
panicking se la transizione è invalida.

### M6.6 — ExecutionStatus timing nei campi serializzati

In `execution.rs`, rimuovere `#[serde(skip_serializing)]` o il `skip_serializing_if` dai
campi `started_at_millis`, `finished_at_millis`, `dispatched_at_millis` così che appaiano
nella response `GET /v1/executions/{id}`.

### M6.10 — BUG-6: validate state in `complete_execution`

In `app.rs`, `complete_execution()` aggiunge un check prima di aggiornare lo store:
se il record non è in stato `RUNNING`, ignorare la completion e loggare un warning.
Previene aggiornamenti di stato su esecuzioni già terminate.

---

## File principali interessati

| File | Milestone |
|------|-----------|
| `src/app.rs` | M5.2, M5.5, M5.7, M6.2, M6.10 |
| `src/registry.rs` | M5.3, M5.4 |
| `src/errors.rs` | M5.4 |
| `src/execution.rs` | M6.4, M6.6 |

---

## Criteri di accettazione

- `cargo test -q` → 0 fallimenti dopo M5
- `cargo test -q` → 0 fallimenti dopo M6
- Nessuna regression sui test parity già verdi
