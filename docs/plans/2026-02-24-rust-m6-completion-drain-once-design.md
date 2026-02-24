# Design: Rust M6 — Completion Model per drain_once

**Data:** 2026-02-24
**Scope:** Fix del completion model nel path `drain_once` / scheduler per chiudere gli 8 test falliti in `execution_completion_handler_parity_test.rs`

---

## Contesto

Il Rust control-plane M3 ha 8 test falliti in `execution_completion_handler_parity_test.rs`.
Tutti i fallimenti mostrano lo stesso pattern: dopo `drain_once`, l'esecuzione resta in stato
`running` invece di transitare a `success`/`error`/`queued`.

**Causa radice:** `Scheduler::tick_once` usa `tokio::spawn(finalize_dispatch(...))` per il dispatch,
che è fire-and-forget. Quando il handler `drain_once` risponde, il task asincrono non ha ancora
aggiornato lo stato dell'esecuzione. I test controllano lo stato immediatamente dopo `drain_once`,
trovando sempre `running`.

---

## Approccio scelto: JoinHandle da tick_once

`tick_once` viene modificato per restituire `Option<tokio::task::JoinHandle<()>>` invece di `bool`.
Il handler `drain_once` awaita il JoinHandle prima di rispondere.
Il background scheduler dropa l'handle (fire-and-forget, behavior production invariato).

---

## Modifiche

### `src/scheduler.rs`

Cambio firma di `tick_once`:

```rust
// Prima
pub async fn tick_once(...) -> Result<bool, AppError>

// Dopo
pub async fn tick_once(...) -> Result<Option<tokio::task::JoinHandle<()>>, AppError>
```

- Se non c'è slot o coda vuota → `Ok(None)` (era `Ok(false)`)
- Se viene dispatchato un task → `Ok(Some(handle))` dove `handle = tokio::spawn(finalize_dispatch(...))`

### `src/app.rs`

**Handler `drain_once`:**

```rust
// Prima
let dispatched = Scheduler::tick_once(...).await?;
return Ok(Json(json!({ "dispatched": dispatched })));

// Dopo
let handle = Scheduler::tick_once(...).await?;
let dispatched = handle.is_some();
if let Some(h) = handle {
    h.await.ok(); // aspetta completamento dispatch prima di rispondere
}
return Ok(Json(json!({ "dispatched": dispatched })));
```

**Background scheduler loop:**

```rust
// Prima
let did_work = Scheduler::tick_once(...).await?;
if did_work { /* yield */ }

// Dopo
let handle = Scheduler::tick_once(...).await?;
let did_work = handle.is_some();
// handle dropped here: fire-and-forget, behavior invariato
if did_work { /* yield */ }
```

---

## File interessati

| File | Modifica |
|------|---------|
| `src/scheduler.rs` | Firma `tick_once`: `bool` → `Option<JoinHandle<()>>` |
| `src/app.rs` | drain_once handler: await handle; background loop: drop handle |

---

## Criteri di accettazione

- `cargo test -q` → 0 fallimenti (tutti i 252+ test verdi)
- `execution_completion_handler_parity_test` → tutti e 10 i test verdi
- `background_scheduler_runtime_test` → nessuna regressione
- Nessuna modifica ai test

---

## Scope escluso

Questo fix chiude solo M6 completion per il path `drain_once`.
I seguenti item M6 sono fuori scope per questo ciclo:
- `invoke_function` async wait (sync path con oneshot receiver)
- State transition validation (`ALLOWED_TRANSITIONS`)
- `CompletionRequest.error` con `ErrorInfo`
- Timeout handling con `mark_timeout()`

Sono pianificati come M6 completo in un successivo ciclo.
