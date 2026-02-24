# Rust M6 Completion — drain_once JoinHandle Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Correggere gli 8 test falliti in `execution_completion_handler_parity_test.rs` facendo sì che `drain_once` aspetti il completamento del dispatch prima di rispondere.

**Architecture:** `Scheduler::tick_once` restituisce `Option<JoinHandle<()>>` invece di `bool`. Il handler `drain_once` awaita il JoinHandle. Il background scheduler dropa il handle (fire-and-forget invariato). Nessuna modifica ai test.

**Tech Stack:** Rust, Tokio, Axum, `tokio::task::JoinHandle`.

---

### Task 1: Modifica `scheduler.rs` — firma `tick_once`

**Files:**
- Modify: `experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust/src/scheduler.rs`

**Step 1: Verifica che i test falliscano prima del fix**

```bash
cd experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust
cargo test --test execution_completion_handler_parity_test -q 2>&1 | tail -15
```

Expected: `FAILED. 2 passed; 8 failed`

**Step 2: Aggiungi import e cambia firma di `tick_once`**

Nel file `src/scheduler.rs`, aggiungi `tokio::task::JoinHandle` all'import e modifica la firma:

Riga 7 (riga degli import `std::sync`):
```rust
use tokio::task::JoinHandle;
```

Riga 30–37, cambia la firma:
```rust
    pub async fn tick_once(
        &self,
        function_name: &str,
        functions: &HashMap<String, FunctionSpec>,
        queue: &Arc<Mutex<QueueManager>>,
        store: &Arc<Mutex<ExecutionStore>>,
        metrics: &Metrics,
    ) -> Result<Option<JoinHandle<()>>, String> {
```

**Step 3: Cambia i return value all'interno di `tick_once`**

Riga 52 — caso "nessun task disponibile" (return `false` → `None`):
```rust
        let Some(task) = task else {
            return Ok(None);
        };
```

Riga 58–63 — caso "function not found" (già `Err`, invariato):
```rust
            None => {
                queue
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .release_slot(function_name);
                return Err(format!("function not found: {function_name}"));
            }
```

Righe 86–101 — caso "dispatch avviato", cattura il JoinHandle:
```rust
        let handle = tokio::spawn(async move {
            let dispatch = router
                .dispatch(&function, &task.payload, &task.execution_id, None, None)
                .await;
            finalize_dispatch(
                &function_name,
                &function,
                task,
                dispatch,
                started_at,
                queue,
                store,
                metrics,
            );
        });
        Ok(Some(handle))
```

**Step 4: Verifica che compile**

```bash
cargo build 2>&1 | grep "^error" | head -20
```

Expected: errori su `app.rs` (usa ancora `bool`) — normale, da fixare nel Task 2.

---

### Task 2: Modifica `app.rs` — background scheduler loop

**Files:**
- Modify: `experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust/src/app.rs`

**Step 1: Aggiorna il background scheduler loop (intorno alla riga 395)**

Trova questo blocco:
```rust
                match scheduler
                    .tick_once(
                        &function_name,
                        &functions_snapshot,
                        &state.queue_manager,
                        &state.execution_store,
                        &state.metrics,
                    )
                    .await
                {
                    Ok(processed) => {
                        processed_any |= processed;
                    }
                    Err(err) => {
                        // Keep the loop alive even if a single function dispatch fails.
                        eprintln!("background scheduler tick error for {function_name}: {err}");
                    }
                }
```

Sostituiscilo con:
```rust
                match scheduler
                    .tick_once(
                        &function_name,
                        &functions_snapshot,
                        &state.queue_manager,
                        &state.execution_store,
                        &state.metrics,
                    )
                    .await
                {
                    Ok(handle) => {
                        processed_any |= handle.is_some();
                        // handle dropped: fire-and-forget, behavior invariato
                    }
                    Err(err) => {
                        // Keep the loop alive even if a single function dispatch fails.
                        eprintln!("background scheduler tick error for {function_name}: {err}");
                    }
                }
```

**Step 2: Verifica che compile**

```bash
cargo build 2>&1 | grep "^error" | head -20
```

Expected: errori residui solo su `drain_once` — da fixare nel Task 3.

---

### Task 3: Modifica `app.rs` — funzione `drain_once`

**Files:**
- Modify: `experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust/src/app.rs`

**Step 1: Aggiorna la funzione `drain_once` (intorno alla riga 1338)**

Trova:
```rust
async fn drain_once(name: &str, state: AppState) -> Result<bool, String> {
    let functions_snapshot = state.function_registry.as_map();
    let scheduler = Scheduler::new((*state.dispatcher_router).clone());
    scheduler
        .tick_once(
            name,
            &functions_snapshot,
            &state.queue_manager,
            &state.execution_store,
            &state.metrics,
        )
        .await
}
```

Sostituisci con:
```rust
async fn drain_once(name: &str, state: AppState) -> Result<bool, String> {
    let functions_snapshot = state.function_registry.as_map();
    let scheduler = Scheduler::new((*state.dispatcher_router).clone());
    let handle = scheduler
        .tick_once(
            name,
            &functions_snapshot,
            &state.queue_manager,
            &state.execution_store,
            &state.metrics,
        )
        .await?;
    let dispatched = handle.is_some();
    if let Some(h) = handle {
        let _ = h.await;
    }
    Ok(dispatched)
}
```

**Step 2: Verifica che compile senza errori**

```bash
cargo build 2>&1 | grep "^error"
```

Expected: nessun output (build pulita).

**Step 3: Esegui i test falliti**

```bash
cargo test --test execution_completion_handler_parity_test -q 2>&1 | tail -10
```

Expected: `test result: ok. 10 passed; 0 failed`

**Step 4: Esegui tutta la suite**

```bash
cargo test -q 2>&1 | grep "^test result"
```

Expected: tutte le righe mostrano `ok`, nessun `FAILED`.

**Step 5: Commit**

```bash
cd experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust
git add src/scheduler.rs src/app.rs
git commit -m "fix(rust-cp): drain_once awaits dispatch JoinHandle for deterministic completion

tick_once now returns Option<JoinHandle<()>> instead of bool.
drain_once awaits the handle so execution state is terminal before responding.
Background scheduler drops the handle (fire-and-forget, behavior unchanged).

Fixes 8 failing tests in execution_completion_handler_parity_test.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>"
```

---

## Note di scope

Questo piano corregge esclusivamente il completion model del path `drain_once`.
Gli item M6 restanti (sync invoke completion, ALLOWED_TRANSITIONS, CompletionRequest.error,
timeout handling) sono documentati nel design doc e pianificati per un ciclo successivo.
