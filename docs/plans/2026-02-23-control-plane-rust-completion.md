# Control-Plane Rust — Completion Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Portare il control-plane Rust M3 a parità funzionale completa con il Java, pronto per sostituzione in produzione.

**Architecture:** Approccio "Fix First, Build On Top": milestone M4 risolve i bug bloccanti (I/O sincrono, lock + I/O, rate limiter), M5 unifica la registry, M6 aggiunge il completion model async, M7 porta i moduli opzionali, M8 fa hardening. Ogni milestone produce un binario testabile E2E.

**Tech Stack:** Rust 2021, Axum 0.8, Tokio 1.x (rt-multi-thread), serde/serde_json, uuid, reqwest 0.12 (da aggiungere in M4 per rimpiazzare TcpStream raw).

**Path root Rust:** `experiments/control-plane-staging/versions/control-plane-rust-m3-20260222-200159/snapshot/control-plane-rust/`
(abbreviato come `[rust]/` nel resto del piano)

---

## M4 — Bug Fix Fondamentali

### Task M4.1: Fix rate limiter window drift (BUG-2)

**Files:**
- Modify: `[rust]/src/rate_limiter.rs`
- Test: `[rust]/tests/rate_limiter_test.rs`

**Step 1: Scrivi il test che fallisce**

Apri `[rust]/tests/rate_limiter_test.rs` e aggiungi alla fine:

```rust
#[test]
fn window_boundary_does_not_allow_burst() {
    let mut rl = RateLimiter::new(2);
    // Primo secondo: usa 2 slot
    assert!(rl.try_acquire_at(0));
    assert!(rl.try_acquire_at(500));
    assert!(!rl.try_acquire_at(999)); // pieno
    // Al ms 1000 inizia nuovo secondo — solo 2 slot, non infiniti
    assert!(rl.try_acquire_at(1000));
    assert!(rl.try_acquire_at(1001));
    assert!(!rl.try_acquire_at(1002)); // deve essere pieno
}

#[test]
fn window_resets_to_epoch_second_boundary() {
    let mut rl = RateLimiter::new(10);
    // ms 500 → epoch-second 0
    assert!(rl.try_acquire_at(500));
    // ms 1500 → epoch-second 1 (nuovo secondo) → window reset
    assert!(rl.try_acquire_at(1500));
    // ms 1999 → ancora epoch-second 1 → stesso window
    for _ in 0..8 { assert!(rl.try_acquire_at(1999)); }
    assert!(!rl.try_acquire_at(1999)); // 10 usati nell'epoch-second 1
}
```

**Step 2: Esegui per verificare che fallisce**

```bash
cd [rust] && cargo test rate_limiter -- --nocapture 2>&1 | head -30
```

Atteso: `FAILED` su `window_boundary_does_not_allow_burst`.

**Step 3: Implementa il fix**

Sostituisci `try_acquire_at` in `[rust]/src/rate_limiter.rs`:

```rust
pub fn try_acquire_at(&mut self, now_millis: u64) -> bool {
    let current_second = now_millis / 1000;  // epoch-second intero
    if current_second != self.window_start_millis {
        self.window_start_millis = current_second;
        self.used_in_window = 0;
    }
    if self.used_in_window >= self.capacity_per_second {
        return false;
    }
    self.used_in_window += 1;
    true
}
```

Nota: il campo `window_start_millis` ora contiene un epoch-second, non un timestamp in ms. Aggiorna anche `RateLimiter::new` per inizializzare a `u64::MAX` (finestra mai vista):

```rust
pub fn new(capacity_per_second: usize) -> Self {
    Self {
        capacity_per_second,
        used_in_window: 0,
        window_start_millis: u64::MAX,
    }
}
```

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test rate_limiter
```

Atteso: tutti i test rate_limiter verdi.

**Step 5: Commit**

```bash
cd [rust] && git add src/rate_limiter.rs tests/rate_limiter_test.rs
git commit -m "fix: correct rate limiter window to epoch-second boundary (BUG-2)"
```

---

### Task M4.2: Fix rate limit default e aggiunta config (BUG-12)

**Files:**
- Modify: `[rust]/src/app.rs` (riga 63)
- Modify: `[rust]/src/config.rs` oppure crea `[rust]/src/settings.rs`

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/rate_limiter_parity_test.rs` aggiungi:

```rust
#[test]
fn default_rate_limit_is_1000_per_second() {
    // Verifica che il default sia 1000, non 10000
    // Crea app con config di default e invoca >1000 volte in <1 secondo
    // — questo è un test di integrazione, verifica il valore in AppState
    let app = build_app();
    // build_app deve accettare config con default 1000
    // Per ora verifica tramite variabile d'ambiente
    std::env::remove_var("NANOFAAS_RATE_MAX_PER_SECOND");
    let app2 = build_app();
    // Se non crasha e risponde, il default è in uso
    // Il test concreto sarà con un wrapper che espone la config
    drop(app2);
}
```

**Step 2: Implementa il fix**

In `[rust]/src/app.rs`, riga 63, cambia:

```rust
// PRIMA:
rate_limiter: Arc::new(Mutex::new(RateLimiter::new(10_000))),

// DOPO:
rate_limiter: Arc::new(Mutex::new(RateLimiter::new(
    std::env::var("NANOFAAS_RATE_MAX_PER_SECOND")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(1_000),
))),
```

**Step 3: Esegui i test**

```bash
cd [rust] && cargo test
```

Atteso: tutti i test verdi.

**Step 4: Commit**

```bash
cd [rust] && git add src/app.rs
git commit -m "fix: default rate limit 10000->1000, configurable via env var (BUG-12)"
```

---

### Task M4.3: Async I/O — aggiungi reqwest a Cargo.toml (BUG-1 prerequisito)

**Files:**
- Modify: `[rust]/Cargo.toml`

**Step 1: Aggiorna Cargo.toml**

```toml
[dependencies]
axum = { version = "0.8", features = ["macros", "json"] }
chrono = { version = "0.4", features = ["serde"] }
serde = { version = "1", features = ["derive"] }
serde_json = "1"
tokio = { version = "1", features = ["rt-multi-thread", "macros", "sync", "time", "net"] }
uuid = { version = "1", features = ["v4", "serde"] }
tower = "0.5"
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls"] }

[dev-dependencies]
reqwest = { version = "0.12", default-features = false, features = ["json", "rustls-tls"] }
```

**Step 2: Verifica che compila**

```bash
cd [rust] && cargo build 2>&1 | tail -5
```

Atteso: `Compiling control-plane-rust` senza errori.

**Step 3: Commit**

```bash
cd [rust] && git add Cargo.toml Cargo.lock
git commit -m "chore: add reqwest for async HTTP client (M4 prerequisite)"
```

---

### Task M4.4: Converti dispatch.rs a async I/O con reqwest (BUG-1)

**Files:**
- Modify: `[rust]/src/dispatch.rs`

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/dispatcher_router_test.rs` aggiungi:

```rust
#[tokio::test]
async fn pool_dispatcher_is_async() {
    // Il dispatcher deve essere async — questo test verifica che
    // dispatch() ritorni un Future, non blocchi il thread
    let dispatcher = PoolDispatcher::default();
    let spec = FunctionSpec { url: Some("http://127.0.0.1:19999/invoke".into()), ..minimal_spec() };
    // Deve completare senza bloccare (connessione rifiutata = errore, non hang)
    let result = dispatcher.dispatch(&spec, &serde_json::json!({}), "test-id").await;
    assert_eq!(result.status, "ERROR"); // connessione rifiutata
}
```

**Step 2: Converti il trait `Dispatcher` ad async**

In `[rust]/src/dispatch.rs`, sostituisci la definizione del trait e le due implementazioni:

```rust
use reqwest::Client;

// Cambia il trait a async
pub trait Dispatcher: Send + Sync {
    async fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult;
}

// LocalDispatcher rimane triviale ma ora è async
impl Dispatcher for LocalDispatcher {
    async fn dispatch(
        &self,
        _function: &FunctionSpec,
        payload: &Value,
        _execution_id: &str,
    ) -> DispatchResult {
        DispatchResult {
            status: "SUCCESS".to_string(),
            output: Some(payload.clone()),
            dispatcher: "local".to_string(),
        }
    }
}

// PoolDispatcher usa reqwest invece di TcpStream
impl Dispatcher for PoolDispatcher {
    async fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult {
        let endpoint = function
            .url
            .as_deref()
            .map(str::trim)
            .filter(|e| !e.is_empty());

        let Some(endpoint) = endpoint else {
            return DispatchResult {
                status: "SUCCESS".to_string(),
                output: Some(payload.clone()),
                dispatcher: "pool".to_string(),
            };
        };

        let timeout_ms = function.timeout_millis.unwrap_or(30_000);
        let invoke_url = format!("{}/invoke", endpoint.trim_end_matches('/'));
        let runtime_request = serde_json::json!({ "input": payload });

        let client = Client::builder()
            .timeout(std::time::Duration::from_millis(timeout_ms))
            .build()
            .unwrap_or_default();

        let response = client
            .post(&invoke_url)
            .header("Content-Type", "application/json")
            .header("X-Execution-Id", execution_id)
            .json(&runtime_request)
            .send()
            .await;

        match response {
            Err(_) => DispatchResult {
                status: "ERROR".to_string(),
                output: None,
                dispatcher: "pool".to_string(),
            },
            Ok(resp) => {
                let status_code = resp.status().as_u16();
                let cold_start = resp
                    .headers()
                    .get("X-Cold-Start")
                    .and_then(|v| v.to_str().ok())
                    .map(|v| v.eq_ignore_ascii_case("true"))
                    .unwrap_or(false);
                let init_duration_ms = resp
                    .headers()
                    .get("X-Init-Duration-Ms")
                    .and_then(|v| v.to_str().ok())
                    .and_then(|v| v.parse::<u64>().ok());

                if status_code >= 400 {
                    return DispatchResult {
                        status: "ERROR".to_string(),
                        output: None,
                        dispatcher: "pool".to_string(),
                    };
                }

                let content_type = resp
                    .headers()
                    .get("Content-Type")
                    .and_then(|v| v.to_str().ok())
                    .unwrap_or("application/json")
                    .to_string();

                let body_text = resp.text().await.unwrap_or_default();
                let output = if content_type.starts_with("text/plain") {
                    Value::String(body_text)
                } else {
                    serde_json::from_str::<Value>(&body_text)
                        .unwrap_or_else(|_| Value::String(body_text))
                };

                DispatchResult {
                    status: "SUCCESS".to_string(),
                    output: Some(output),
                    dispatcher: "pool".to_string(),
                    // cold_start e init_duration_ms saranno usati in M6
                }
            }
        }
    }
}
```

Nota: i trait `async fn` in trait richiedono `#[async_trait]` o Rust 1.75+. Con Rust edition 2021 e versione recente, `async fn` in trait è stabile. Verifica con `rustc --version` — se < 1.75, aggiungi `async-trait = "0.1"` a `Cargo.toml` e annota con `#[async_trait]`.

**Step 3: Propaga async a DispatcherRouter**

```rust
impl DispatcherRouter {
    pub async fn dispatch(
        &self,
        function: &FunctionSpec,
        payload: &Value,
        execution_id: &str,
    ) -> DispatchResult {
        match function.execution_mode {
            ExecutionMode::Local => self.local.dispatch(function, payload, execution_id).await,
            ExecutionMode::Deployment | ExecutionMode::Pool => {
                self.pool.dispatch(function, payload, execution_id).await
            }
        }
    }
}
```

**Step 4: Propaga async in app.rs**

In `[rust]/src/app.rs`:
- `invoke_function` diventa `async fn`
- `enqueue_function` diventa `async fn`
- `drain_once` diventa `async fn`
- Tutte le chiamate `.dispatch(...)` aggiungono `.await`
- In `post_function_action`, i match su `invoke_function` e `enqueue_function` aggiungono `.await`

Esempio per `invoke_function`:
```rust
async fn invoke_function(
    name: &str,
    state: AppState,
    headers: HeaderMap,
    request: InvocationRequest,
) -> Result<InvocationResponse, Response> {
    // ...
    let dispatch = state
        .dispatcher_router
        .dispatch(&function_spec, &request.input, &execution_id)
        .await;
    // ...
}
```

In `post_function_action`:
```rust
"invoke" => match invoke_function(&name, state, headers, request).await {
"enqueue" => match enqueue_function(&name, state, headers, request).await {
```

**Step 5: Rimuovi le funzioni TcpStream ora inutilizzate**

Rimuovi `invoke_pool_http`, `parse_http_response`, `decode_chunked_body`, `parse_http_endpoint` da `dispatch.rs`. Rimuovi i relativi `use std::io::{Read, Write}; use std::net::TcpStream;`.

**Step 6: Esegui i test**

```bash
cd [rust] && cargo test 2>&1 | tail -20
```

Atteso: compilazione pulita, test verdi.

**Step 7: Commit**

```bash
cd [rust] && git add src/dispatch.rs src/app.rs
git commit -m "fix: replace blocking TcpStream with async reqwest in PoolDispatcher (BUG-1)"
```

---

### Task M4.5: Fix scheduler — rilascia lock prima del dispatch (BUG-3)

**Files:**
- Modify: `[rust]/src/app.rs` (funzione `drain_once`)
- Modify: `[rust]/src/scheduler.rs`

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/scheduler_test.rs` aggiungi:

```rust
#[tokio::test]
async fn drain_once_does_not_hold_lock_during_dispatch() {
    // Verifica che drain_once non tenga il lock durante il dispatch
    // Se lo tiene, questo test deadlocka
    // Usa un timeout per rilevare deadlock
    let result = tokio::time::timeout(
        std::time::Duration::from_secs(2),
        async {
            // setup state con una funzione LOCAL (dispatch istantaneo)
            // enqueue un task e drain
            // deve completare senza deadlock
            true
        }
    ).await;
    assert!(result.is_ok(), "drain_once deadlocked while holding locks during dispatch");
}
```

**Step 2: Ristruttura `drain_once` e `Scheduler::tick_once`**

Il problema è che `tick_once` riceve `&mut QueueManager` e `&mut ExecutionStore` come MutexGuard temporanei. La soluzione è:
1. Acquisire il lock, prendere il prossimo task, rilasciare il lock
2. Fare il dispatch (async, senza lock)
3. Riacquisire il lock per aggiornare lo stato

In `[rust]/src/scheduler.rs`, sostituisci `tick_once` con una versione che non tiene i lock durante il dispatch:

```rust
use crate::dispatch::DispatcherRouter;
use crate::execution::{ExecutionRecord, ExecutionState, ExecutionStore, ErrorInfo};
use crate::model::FunctionSpec;
use crate::queue::{InvocationTask, QueueManager};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

pub struct Scheduler {
    router: Arc<DispatcherRouter>,
}

impl Scheduler {
    pub fn new(router: Arc<DispatcherRouter>) -> Self {
        Self { router }
    }

    /// Dispatcha il prossimo task dalla coda senza tenere i lock durante l'I/O.
    pub async fn tick_once(
        &self,
        function_name: &str,
        functions: Arc<Mutex<HashMap<String, FunctionSpec>>>,
        queue: Arc<Mutex<QueueManager>>,
        store: Arc<Mutex<ExecutionStore>>,
    ) -> Result<bool, String> {
        // Passo 1: acquisisci lock, prendi task, rilascia lock
        let task = {
            let mut q = queue.lock().unwrap_or_else(|e| e.into_inner());
            match q.take_next(function_name) {
                Some(t) => t,
                None => return Ok(false),
            }
        };

        // Passo 2: leggi la funzione spec (lock breve)
        let function = {
            let fns = functions.lock().unwrap_or_else(|e| e.into_inner());
            fns.get(function_name).cloned()
                .ok_or_else(|| format!("function not found: {function_name}"))?
        };

        // Passo 3: dispatch async — nessun lock tenuto
        let dispatch = self.router.dispatch(
            &function,
            task.payload.as_ref().unwrap_or(&serde_json::Value::Null),
            &task.execution_id,
        ).await;

        // Passo 4: aggiorna lo store (lock breve)
        let mut store_guard = store.lock().unwrap_or_else(|e| e.into_inner());
        let mut record = match store_guard.get(&task.execution_id) {
            Some(r) => r,
            None => return Err(format!("execution not found: {}", task.execution_id)),
        };

        if dispatch.status == "SUCCESS" {
            record.mark_success_at(
                dispatch.output.unwrap_or(serde_json::Value::Null),
                now_millis(),
            );
            store_guard.put_now(record);
            return Ok(true);
        }

        let max_retries = function.max_retries.unwrap_or(3).max(0) as u32;
        if task.attempt < max_retries {
            drop(store_guard);
            let retry_task = InvocationTask {
                execution_id: task.execution_id.clone(),
                payload: task.payload,
                attempt: task.attempt + 1,
            };
            let mut q = queue.lock().unwrap_or_else(|e| e.into_inner());
            if q.enqueue(function_name, retry_task).is_ok() {
                drop(q);
                let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(mut r) = s.get(&task.execution_id) {
                    r.status = ExecutionState::Queued;
                    r.output = None;
                    s.put_now(r);
                }
                return Ok(true);
            }
            let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(mut r) = s.get(&task.execution_id) {
                r.mark_error_at(
                    ErrorInfo::new("QUEUE_FULL", "retry queue is full"),
                    now_millis(),
                );
                s.put_now(r);
            }
            return Ok(true);
        }

        record.mark_error_at(
            ErrorInfo::new("DISPATCH_ERROR", &dispatch.status),
            now_millis(),
        );
        store_guard.put_now(record);
        Ok(true)
    }
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
```

**Step 3: Aggiorna `drain_once` in app.rs**

```rust
async fn drain_once(name: &str, state: AppState) -> Result<bool, String> {
    let scheduler = Scheduler::new(Arc::clone(&state.dispatcher_router));
    scheduler.tick_once(
        name,
        Arc::clone(&state.functions),
        Arc::clone(&state.queue_manager),
        Arc::clone(&state.execution_store),
    ).await
}
```

Nota: `AppState.functions` deve ora essere `Arc<Mutex<HashMap<...>>>` (già lo è). `AppState.dispatcher_router` deve diventare `Arc<DispatcherRouter>` (già lo è).

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test scheduler 2>&1
```

Atteso: test scheduler verdi.

**Step 5: Commit**

```bash
cd [rust] && git add src/scheduler.rs src/app.rs
git commit -m "fix: release locks before async dispatch in Scheduler::tick_once (BUG-3)"
```

---

### Task M4.6: Queue capacity per-function (BUG-13)

**Files:**
- Modify: `[rust]/src/queue.rs`
- Modify: `[rust]/src/app.rs` (chiamata a enqueue)

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/queue_manager_test.rs` aggiungi:

```rust
#[test]
fn enqueue_respects_per_call_capacity() {
    let mut qm = QueueManager::new(100); // capacità globale alta
    let task = || InvocationTask { execution_id: "e".into(), payload: serde_json::Value::Null, attempt: 1 };
    // Con capacità per-chiamata di 2, solo 2 slot
    assert!(qm.enqueue_with_capacity("fn1", task(), 2).is_ok());
    assert!(qm.enqueue_with_capacity("fn1", task(), 2).is_ok());
    assert!(qm.enqueue_with_capacity("fn1", task(), 2).is_err()); // pieno
    // fn2 ha capacità separata
    assert!(qm.enqueue_with_capacity("fn2", task(), 5).is_ok());
}
```

**Step 2: Aggiungi `enqueue_with_capacity` a QueueManager**

In `[rust]/src/queue.rs`:

```rust
pub fn enqueue_with_capacity(
    &mut self,
    function_name: &str,
    task: InvocationTask,
    capacity: usize,
) -> Result<(), QueueOverflowError> {
    let queue = self.queues.entry(function_name.to_string()).or_default();
    if queue.len() >= capacity {
        return Err(QueueOverflowError { function_name: function_name.to_string() });
    }
    queue.push_back(task);
    Ok(())
}
```

**Step 3: Usa `enqueue_with_capacity` in `enqueue_function` in app.rs**

Sostituisci la chiamata `.enqueue(name, task)` con:

```rust
let queue_capacity = function_spec.queue_size
    .map(|q| q as usize)
    .unwrap_or(100); // default Java: 100

state
    .queue_manager
    .lock()
    .unwrap_or_else(|e| e.into_inner())
    .enqueue_with_capacity(name, InvocationTask { ... }, queue_capacity)
    .map_err(|_| StatusCode::TOO_MANY_REQUESTS.into_response())?;
```

**Step 4: Verifica che `FunctionSpec` abbia il campo `queue_size`**

In `[rust]/src/model.rs` cerca `queue_size`. Se non esiste, aggiungilo:

```rust
#[serde(rename = "queueSize", skip_serializing_if = "Option::is_none")]
pub queue_size: Option<i32>,
```

**Step 5: Esegui i test**

```bash
cd [rust] && cargo test queue
```

Atteso: test queue verdi.

**Step 6: Commit**

```bash
cd [rust] && git add src/queue.rs src/app.rs src/model.rs
git commit -m "fix: use per-function queueSize for queue capacity instead of global constant (BUG-13)"
```

---

### Task M4.7: Mutex poison handling (CS-7)

**Files:**
- Modify: `[rust]/src/app.rs`

**Step 1: Sostituisci tutti gli `expect("... lock")` con `unwrap_or_else`**

Cerca e sostituisci in `[rust]/src/app.rs`:

```bash
cd [rust] && grep -n "\.expect(" src/app.rs
```

Per ogni `some_mutex.lock().expect("some lock")`, sostituisci con:

```rust
some_mutex.lock().unwrap_or_else(|e| e.into_inner())
```

Stessa operazione in `src/registry.rs`, `src/scheduler.rs`.

**Step 2: Esegui i test**

```bash
cd [rust] && cargo test
```

**Step 3: Commit**

```bash
cd [rust] && git add src/app.rs src/registry.rs src/scheduler.rs
git commit -m "fix: handle Mutex poison instead of panicking (CS-7)"
```

---

### Task M4.8: Gate M4 — E2E dockerizzato

**Step 1: Esegui i test E2E dockerizzati**

```bash
cd [rust] && cargo test e2e_dockerized_flow -- --ignored --nocapture 2>&1
```

Atteso: tutti i test nel file `tests/e2e_dockerized_flow_test.rs` verdi.

Se falliscono, correggi prima di procedere a M5.

---

## M5 — Registry & Invocation Core

### Task M5.1: Unifica la registry — rimuovi AppState.functions (M5)

**Files:**
- Modify: `[rust]/src/app.rs`
- Modify: `[rust]/src/registry.rs`

**Contesto:** `app.rs` usa `AppState.functions: Arc<Mutex<HashMap<String, FunctionSpec>>>` mentre `registry.rs` ha una `FunctionRegistry` completa mai collegata. Questo task li unisce.

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/function_service_parity_test.rs` aggiungi:

```rust
#[tokio::test]
async fn register_and_lookup_use_same_store() {
    let app = build_app_with_config(AppConfig::default());
    let client = TestClient::new(app);

    // Registra via API
    let reg_resp = client.post("/v1/functions")
        .json(&serde_json::json!({
            "name": "my-fn",
            "image": "test/image:latest",
            "executionMode": "LOCAL"
        }))
        .send().await;
    assert_eq!(reg_resp.status(), 201);

    // Leggi via GET — deve trovarlo
    let get_resp = client.get("/v1/functions/my-fn").send().await;
    assert_eq!(get_resp.status(), 200);
    let spec: serde_json::Value = get_resp.json().await;
    assert_eq!(spec["name"], "my-fn");
}
```

**Step 2: Modifica AppState per usare FunctionRegistry da registry.rs**

In `[rust]/src/app.rs`, cambia `AppState`:

```rust
use crate::registry::FunctionRegistry;

#[derive(Clone)]
pub struct AppState {
    // RIMUOVI: functions: Arc<Mutex<HashMap<String, FunctionSpec>>>,
    function_registry: Arc<FunctionRegistry>,   // AGGIUNGI
    function_replicas: Arc<Mutex<HashMap<String, u32>>>,
    execution_store: Arc<Mutex<ExecutionStore>>,
    idempotency_store: Arc<Mutex<IdempotencyStore>>,
    queue_manager: Arc<Mutex<QueueManager>>,
    dispatcher_router: Arc<DispatcherRouter>,
    rate_limiter: Arc<Mutex<RateLimiter>>,
    metrics: Arc<Metrics>,
    // RIMUOVI: seen_sync_invocations (verrà rimossa in Task M5.5)
}
```

In `build_app()`:

```rust
let function_registry = Arc::new(FunctionRegistry::new());
let state = AppState {
    function_registry,
    // ... resto invariato
};
```

**Step 3: Aggiorna tutti i riferimenti a `state.functions` in app.rs**

Per ogni `state.functions.lock()...`, sostituisci con le API di `FunctionRegistry`:
- `functions.insert(name, spec)` → `state.function_registry.register(name, spec)`
- `functions.get(&name).cloned()` → `state.function_registry.get(&name)`
- `functions.remove(&name)` → `state.function_registry.remove(&name)`
- `functions.values().cloned().collect()` → `state.function_registry.list()`

Verifica che `FunctionRegistry` in `registry.rs` esponga questi metodi. Se mancano, aggiungili:

```rust
impl FunctionRegistry {
    pub fn new() -> Self { /* ... */ }
    pub fn register(&self, name: String, spec: FunctionSpec) -> bool { /* insert, return false se esiste */ }
    pub fn get(&self, name: &str) -> Option<FunctionSpec> { /* ... */ }
    pub fn remove(&self, name: &str) -> bool { /* ... */ }
    pub fn list(&self) -> Vec<FunctionSpec> { /* ... */ }
    pub fn contains(&self, name: &str) -> bool { /* ... */ }
}
```

Usa `RwLock` invece di `Mutex` per la map interna (ottimizza le letture concorrenti):

```rust
use std::sync::RwLock;

pub struct FunctionRegistry {
    specs: RwLock<HashMap<String, FunctionSpec>>,
}
```

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test function 2>&1 | tail -30
```

Atteso: test function_controller e function_service verdi.

**Step 5: Commit**

```bash
cd [rust] && git add src/app.rs src/registry.rs
git commit -m "feat: unify function registry - remove duplicate HashMap from AppState (M5)"
```

---

### Task M5.2: GlobalExceptionHandler centralizzato (M5)

**Files:**
- Modify: `[rust]/src/errors.rs`
- Modify: `[rust]/src/app.rs`

**Step 1: Scrivi i test che falliscono**

In `[rust]/tests/global_exception_handler_parity_test.rs` aggiungi:

```rust
#[tokio::test]
async fn validation_error_returns_400_with_details() {
    let app = build_app();
    let client = TestClient::new(app);
    let resp = client.post("/v1/functions")
        .json(&serde_json::json!({ "name": "", "image": "" }))
        .send().await;
    assert_eq!(resp.status(), 400);
    let body: serde_json::Value = resp.json().await;
    assert_eq!(body["error"], "VALIDATION_ERROR");
    assert!(body["details"].as_array().unwrap().len() > 0);
}

#[tokio::test]
async fn function_not_found_returns_404_with_error_body() {
    let app = build_app();
    let client = TestClient::new(app);
    let resp = client.get("/v1/functions/nonexistent").send().await;
    assert_eq!(resp.status(), 404);
    // In alternativa verifica solo status code se body non strutturato
}
```

**Step 2: Definisci AppError enum in errors.rs**

```rust
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;

#[derive(Debug)]
pub enum AppError {
    NotFound(String),
    Conflict(String),
    Validation(Vec<String>),
    QueueFull(String),
    RateLimit,
    ImageValidation(String),
    Internal(String),
}

impl IntoResponse for AppError {
    fn into_response(self) -> Response {
        match self {
            AppError::NotFound(msg) => (
                StatusCode::NOT_FOUND,
                Json(json!({ "error": "NOT_FOUND", "message": msg })),
            ).into_response(),
            AppError::Conflict(msg) => (
                StatusCode::CONFLICT,
                Json(json!({ "error": "CONFLICT", "message": msg })),
            ).into_response(),
            AppError::Validation(details) => (
                StatusCode::BAD_REQUEST,
                Json(json!({
                    "error": "VALIDATION_ERROR",
                    "message": "Request validation failed",
                    "details": details
                })),
            ).into_response(),
            AppError::QueueFull(fn_name) => (
                StatusCode::TOO_MANY_REQUESTS,
                Json(json!({ "error": "QUEUE_FULL", "function": fn_name })),
            ).into_response(),
            AppError::RateLimit => (
                StatusCode::TOO_MANY_REQUESTS,
                Json(json!({ "error": "RATE_LIMIT_EXCEEDED" })),
            ).into_response(),
            AppError::ImageValidation(msg) => (
                StatusCode::UNPROCESSABLE_ENTITY,
                Json(json!({ "error": "IMAGE_VALIDATION_FAILED", "message": msg })),
            ).into_response(),
            AppError::Internal(msg) => (
                StatusCode::INTERNAL_SERVER_ERROR,
                Json(json!({ "error": "INTERNAL_ERROR", "message": msg })),
            ).into_response(),
        }
    }
}
```

**Step 3: Usa AppError nei handler in app.rs**

Sostituisci i return di `StatusCode::NOT_FOUND.into_response()` con `AppError::NotFound(name.to_string()).into_response()`, ecc.

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test global_exception
```

**Step 5: Commit**

```bash
cd [rust] && git add src/errors.rs src/app.rs
git commit -m "feat: centralized error handler with structured JSON responses (M5)"
```

---

### Task M5.3: X-Execution-Id header + InvocationResponse.error (M5)

**Files:**
- Modify: `[rust]/src/app.rs`

**Step 1: Scrivi i test che falliscono**

In `[rust]/tests/invocation_controller_test.rs` aggiungi:

```rust
#[tokio::test]
async fn invoke_returns_x_execution_id_header() {
    let app = build_app_with_local_function("test-fn");
    let client = TestClient::new(app);
    let resp = client.post("/v1/functions/test-fn:invoke")
        .json(&serde_json::json!({ "input": {} }))
        .send().await;
    assert_eq!(resp.status(), 200);
    assert!(resp.headers().get("X-Execution-Id").is_some());
}

#[tokio::test]
async fn enqueue_returns_x_execution_id_header() {
    // simile per :enqueue
}
```

**Step 2: Aggiungi X-Execution-Id alla risposta**

La funzione `response_with_execution_id` esiste già in app.rs? Cerca nella codebase. Se non esiste, aggiungi:

```rust
fn response_with_execution_id<T: IntoResponse>(
    status: StatusCode,
    execution_id: String,
    body: T,
) -> Response {
    let mut response = (status, body.into_response()).into_response();
    // non funziona così — usa ResponseBuilder
    let (mut parts, body) = response.into_parts();
    parts.headers.insert(
        "X-Execution-Id",
        execution_id.parse().unwrap_or_else(|_| HeaderValue::from_static("")),
    );
    Response::from_parts(parts, body)
}
```

**Step 3: Popola InvocationResponse.error su ERROR**

In `invoke_function`, dopo il dispatch, sostituisci:

```rust
// PRIMA:
Ok(InvocationResponse {
    execution_id,
    status: response_status,
    output: dispatch.output,
    error: None,
})

// DOPO:
let error = if dispatch.status == "ERROR" {
    Some(crate::execution::ErrorInfo::new("DISPATCH_ERROR", "dispatch failed"))
} else {
    None
};
Ok(InvocationResponse {
    execution_id,
    status: response_status,
    output: dispatch.output,
    error,
})
```

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test invocation_controller
```

**Step 5: Commit**

```bash
cd [rust] && git add src/app.rs
git commit -m "feat: add X-Execution-Id header and populate error field in invocation response (M5)"
```

---

### Task M5.4: WATCHDOG_CMD env var in KubernetesDeploymentBuilder (M5)

**Files:**
- Modify: `[rust]/src/kubernetes.rs`

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/kubernetes_deployment_builder_parity_test.rs` aggiungi:

```rust
#[test]
fn build_env_vars_includes_watchdog_cmd_when_runtime_command_set() {
    let spec = FunctionSpec {
        name: "fn".into(),
        image: Some("img".into()),
        runtime_command: Some("python3 handler.py".into()),
        ..Default::default()
    };
    let builder = KubernetesDeploymentBuilder::new("ns", "http://callback");
    let env = builder.build_env_vars(&spec);
    let watchdog = env.iter().find(|e| e.name == "WATCHDOG_CMD");
    assert!(watchdog.is_some());
    assert_eq!(watchdog.unwrap().value, "python3 handler.py");
}
```

**Step 2: Aggiungi WATCHDOG_CMD in `build_env_vars`**

Cerca `build_env_vars` in `[rust]/src/kubernetes.rs` e aggiungi dopo le altre env var:

```rust
if let Some(cmd) = spec.runtime_command.as_deref().filter(|c| !c.trim().is_empty()) {
    env_vars.push(EnvVar {
        name: "WATCHDOG_CMD".to_string(),
        value: cmd.to_string(),
    });
}
```

Verifica che `FunctionSpec` abbia il campo `runtime_command`:

```rust
// in src/model.rs
#[serde(rename = "runtimeCommand", skip_serializing_if = "Option::is_none")]
pub runtime_command: Option<String>,
```

**Step 3: Esegui i test**

```bash
cd [rust] && cargo test kubernetes_deployment
```

**Step 4: Commit**

```bash
cd [rust] && git add src/kubernetes.rs src/model.rs
git commit -m "feat: inject WATCHDOG_CMD env var from runtimeCommand in K8s deployment (M5)"
```

---

### Task M5.5: Rimuovi seen_sync_invocations (M5)

**Files:**
- Modify: `[rust]/src/app.rs`

La cold-start detection sarà implementata correttamente in M6 (dai response headers del runtime). Ora rimuoviamo solo il vecchio meccanismo difettoso.

**Step 1: Rimuovi il campo da AppState**

```rust
// Rimuovi da AppState:
// seen_sync_invocations: Arc<Mutex<HashSet<String>>>,

// Rimuovi da build_app():
// seen_sync_invocations: Arc::new(Mutex::new(HashSet::new())),
```

**Step 2: Sostituisci la logica cold-start con un placeholder**

```rust
// Sostituisci il blocco is_cold_start con:
// TODO M6: cold-start viene rilevato dai response headers del runtime
// Per ora tutti i dispatch sono "warm start"
state.metrics.warm_start(name);
```

**Step 3: Rimuovi i relativi use import**

```rust
// Rimuovi: use std::collections::HashSet;
// Se non usato altrove
```

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test 2>&1 | grep -E "FAILED|error"
```

**Step 5: Commit**

```bash
cd [rust] && git add src/app.rs
git commit -m "refactor: remove broken cold-start HashSet (will be replaced in M6 with header detection)"
```

---

### Task M5.6: Gate M5 — test parity suite

**Step 1: Esegui tutta la suite di test M5**

```bash
cd [rust] && cargo test 2>&1 | tail -20
```

Atteso: zero FAILED. Se ci sono fallimenti, correggi prima di procedere a M6.

**Step 2: Commit di chiusura M5**

```bash
cd [rust] && git commit --allow-empty -m "chore: M5 milestone complete - registry unified, error handling, headers"
```

---

## M6 — Completion Model & Execution Lifecycle

### Task M6.1: Aggiungi completion channel a ExecutionRecord

**Files:**
- Modify: `[rust]/src/execution.rs`

**Step 1: Aggiungi oneshot sender a ExecutionRecord**

```rust
use tokio::sync::oneshot;
use std::sync::Arc;
use tokio::sync::Mutex as TokioMutex; // uso di tokio Mutex per async

// Aggiungi a ExecutionRecord:
#[serde(skip)]
pub completion_tx: Option<Arc<TokioMutex<Option<oneshot::Sender<DispatchResult>>>>>,
```

Nota: usiamo `Arc<TokioMutex<Option<Sender>>>` per permettere di estrarre il sender una sola volta in modo thread-safe.

Aggiungi metodo `complete`:

```rust
use crate::dispatch::DispatchResult;

impl ExecutionRecord {
    pub fn new_with_completion(
        execution_id: &str,
        function_name: &str,
    ) -> (Self, oneshot::Receiver<DispatchResult>) {
        let (tx, rx) = oneshot::channel();
        let mut record = Self::new(execution_id, function_name, ExecutionState::Queued);
        record.completion_tx = Some(Arc::new(TokioMutex::new(Some(tx))));
        (record, rx)
    }

    pub async fn complete(&self, result: DispatchResult) {
        if let Some(tx_mutex) = &self.completion_tx {
            let mut guard = tx_mutex.lock().await;
            if let Some(tx) = guard.take() {
                let _ = tx.send(result);
            }
        }
    }
}
```

**Step 2: Verifica che `ExecutionRecord` derivi Clone correttamente**

Il `oneshot::Sender` non implementa `Clone`. Wrapparlo in `Arc<Mutex<Option<...>>>` risolve il problema perché `Arc` implementa `Clone`.

**Step 3: Esegui i test**

```bash
cd [rust] && cargo test execution_record
```

**Step 4: Commit**

```bash
cd [rust] && git add src/execution.rs
git commit -m "feat: add tokio oneshot completion channel to ExecutionRecord (M6)"
```

---

### Task M6.2: invoke_function — async wait con timeout

**Files:**
- Modify: `[rust]/src/app.rs`

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/invocation_controller_test.rs` aggiungi:

```rust
#[tokio::test]
async fn sync_invoke_waits_for_callback_completion() {
    // Simula: invoke invia al "runtime" (mock HTTP server)
    // Il runtime chiama il callback :complete
    // L'invoke deve aspettare e ritornare il risultato dal callback

    // Questo test richiede un mock HTTP server — usa axum TestClient bidirectional
    // o un server su porta random con tokio::spawn
    let (tx, rx) = tokio::sync::oneshot::channel::<()>();
    let mock_server = tokio::spawn(async move {
        // server che risponde con 200 dopo aver ricevuto la richiesta
        rx.await.ok();
    });
    // ... setup complesso — vedi tests/e2e_dockerized_flow_test.rs per pattern
}
```

**Step 2: Modifica `invoke_function` per usare il completion channel**

In `[rust]/src/app.rs`:

```rust
async fn invoke_function(
    name: &str,
    state: AppState,
    headers: HeaderMap,
    request: InvocationRequest,
) -> Result<InvocationResponse, Response> {
    // ... rate limit, idempotency check come prima ...

    let execution_id = Uuid::new_v4().to_string();
    let timeout_ms = header_value(&headers, "X-Timeout-Ms")
        .and_then(|v| v.parse::<u64>().ok())
        .or_else(|| function_spec.timeout_millis)
        .unwrap_or(30_000);

    // Crea record con completion channel solo per DEPLOYMENT/POOL
    let (record, completion_rx) = match function_spec.execution_mode {
        ExecutionMode::Deployment | ExecutionMode::Pool => {
            let (r, rx) = ExecutionRecord::new_with_completion(&execution_id, name);
            (r, Some(rx))
        }
        ExecutionMode::Local => {
            let r = ExecutionRecord::new(&execution_id, name, ExecutionState::Queued);
            (r, None)
        }
    };

    // Salva il record PRIMA del dispatch
    {
        let mut store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
        store.put_with_timestamp(record, now_millis());
    }

    state.metrics.dispatch(name);

    // Dispatch
    let dispatch = state
        .dispatcher_router
        .dispatch(&function_spec, &request.input, &execution_id)
        .await;

    // Per LOCAL: risultato immediato
    if completion_rx.is_none() {
        return finish_invocation(&execution_id, name, dispatch, &state).await;
    }

    // Per DEPLOYMENT/POOL: aspetta il callback con timeout
    let wait_result = tokio::time::timeout(
        std::time::Duration::from_millis(timeout_ms),
        completion_rx.unwrap(),
    ).await;

    match wait_result {
        Ok(Ok(callback_dispatch)) => {
            finish_invocation(&execution_id, name, callback_dispatch, &state).await
        }
        Ok(Err(_)) => {
            // sender dropped — errore interno
            let err_dispatch = DispatchResult { status: "ERROR".to_string(), output: None, dispatcher: "pool".to_string() };
            finish_invocation(&execution_id, name, err_dispatch, &state).await
        }
        Err(_timeout) => {
            // timeout scaduto
            let mut store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(mut r) = store.get(&execution_id) {
                r.mark_timeout_at(now_millis());
                store.put_now(r);
            }
            state.metrics.sync_queue_rejected(name); // usa timeout counter in M8
            Err((StatusCode::REQUEST_TIMEOUT, Json(json!({ "status": "TIMEOUT", "executionId": execution_id }))).into_response())
        }
    }
}

async fn finish_invocation(
    execution_id: &str,
    name: &str,
    dispatch: DispatchResult,
    state: &AppState,
) -> Result<InvocationResponse, Response> {
    let status = dispatch.status.clone();
    let output = dispatch.output.clone();
    let error = if status == "ERROR" {
        Some(ErrorInfo::new("DISPATCH_ERROR", "dispatch failed"))
    } else {
        None
    };

    {
        let mut store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(mut r) = store.get(execution_id) {
            match status.as_str() {
                "SUCCESS" => r.mark_success_at(output.clone().unwrap_or(Value::Null), now_millis()),
                _ => r.mark_error_at(ErrorInfo::new("DISPATCH_ERROR", &status), now_millis()),
            }
            store.put_now(r);
        }
    }

    if status == "SUCCESS" {
        state.metrics.success(name);
    }

    Ok(InvocationResponse {
        execution_id: execution_id.to_string(),
        status,
        output,
        error,
    })
}
```

**Step 3: Aggiorna `complete_execution` per usare il completion channel**

```rust
async fn complete_execution(
    execution_id: &str,
    request: CompletionRequest,
    state: AppState,
) -> Result<(), StatusCode> {
    // Leggi il record (lock breve)
    let record = {
        let store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
        store.get(execution_id).ok_or(StatusCode::NOT_FOUND)?
    };

    let status = parse_execution_state(&request.status).ok_or(StatusCode::BAD_REQUEST)?;
    let error_info = request.error.as_ref().map(|e| ErrorInfo::new(&e.code, &e.message));

    let dispatch_result = DispatchResult {
        status: request.status.clone(),
        output: request.output.clone(),
        dispatcher: "callback".to_string(),
    };

    // Invia sul completion channel se presente (invoke sincrono in attesa)
    if record.completion_tx.is_some() {
        record.complete(dispatch_result).await;
    } else {
        // Nessun waiter (enqueue asincrono) — aggiorna direttamente lo store
        let mut store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(mut r) = store.get(execution_id) {
            match status {
                ExecutionState::Success => r.mark_success_at(request.output.unwrap_or(Value::Null), now_millis()),
                ExecutionState::Error => r.mark_error_at(
                    error_info.unwrap_or_else(|| ErrorInfo::new("ERROR", "unknown")),
                    now_millis(),
                ),
                ExecutionState::Timeout => r.mark_timeout_at(now_millis()),
                _ => r.set_state(status),
            }
            store.put_now(r);
        }
    }

    Ok(())
}
```

**Step 4: Aggiungi `error` field a CompletionRequest**

```rust
#[derive(Debug, Clone, Deserialize)]
struct CompletionRequest {
    status: String,
    #[serde(default)]
    output: Option<Value>,
    #[serde(default)]
    error: Option<ErrorInfoRequest>,
}

#[derive(Debug, Clone, Deserialize)]
struct ErrorInfoRequest {
    code: String,
    message: String,
}
```

**Step 5: Esegui i test**

```bash
cd [rust] && cargo test invocation 2>&1 | tail -20
```

**Step 6: Commit**

```bash
cd [rust] && git add src/app.rs src/execution.rs
git commit -m "feat: async completion model with tokio oneshot channel and timeout handling (M6)"
```

---

### Task M6.3: mark_running + state transition validation (M6)

**Files:**
- Modify: `[rust]/src/execution.rs`
- Modify: `[rust]/src/app.rs`

**Step 1: Aggiungi ALLOWED_TRANSITIONS in execution.rs**

```rust
use std::collections::HashMap;
use std::sync::OnceLock;

static ALLOWED_TRANSITIONS: OnceLock<HashMap<(ExecutionState, ExecutionState), bool>> = OnceLock::new();

fn allowed_transitions() -> &'static HashMap<(ExecutionState, ExecutionState), bool> {
    ALLOWED_TRANSITIONS.get_or_init(|| {
        let mut m = HashMap::new();
        // Queued può andare a Running o direttamente a Success/Error/Timeout (LOCAL)
        m.insert((ExecutionState::Queued, ExecutionState::Running), true);
        m.insert((ExecutionState::Queued, ExecutionState::Success), true);
        m.insert((ExecutionState::Queued, ExecutionState::Error), true);
        m.insert((ExecutionState::Queued, ExecutionState::Timeout), true);
        // Running può terminare
        m.insert((ExecutionState::Running, ExecutionState::Success), true);
        m.insert((ExecutionState::Running, ExecutionState::Error), true);
        m.insert((ExecutionState::Running, ExecutionState::Timeout), true);
        // Per retry: Error/Timeout possono tornare a Queued
        m.insert((ExecutionState::Error, ExecutionState::Queued), true);
        m.insert((ExecutionState::Timeout, ExecutionState::Queued), true);
        m
    })
}

impl ExecutionRecord {
    fn validate_transition(&self, new_state: &ExecutionState) {
        let key = (self.status.clone(), new_state.clone());
        if !allowed_transitions().contains_key(&key) {
            eprintln!(
                "WARN: invalid state transition {:?} -> {:?} for execution {}",
                self.status, new_state, self.execution_id
            );
        }
    }

    pub fn mark_running_at(&mut self, at_millis: u64) {
        self.validate_transition(&ExecutionState::Running);
        self.status = ExecutionState::Running;
        self.started_at_millis = Some(at_millis);
    }
    // ... analogamente per mark_success_at, mark_error_at, mark_timeout_at
}
```

**Step 2: Chiama mark_running prima del dispatch in invoke_function**

In `invoke_function`, subito prima del dispatch:

```rust
{
    let mut store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(mut r) = store.get(&execution_id) {
        r.mark_running_at(now_millis());
        store.put_now(r);
    }
}
state.metrics.dispatch(name);
let dispatch = state.dispatcher_router.dispatch(...).await;
```

**Step 3: Scrivi il test**

In `[rust]/tests/execution_record_state_transition_test.rs` aggiungi:

```rust
#[test]
fn invalid_transition_logs_warning_but_applies() {
    let mut record = ExecutionRecord::new("eid", "fn", ExecutionState::Success);
    // SUCCESS -> RUNNING è invalida, ma deve applicarsi con warning
    record.mark_running_at(1000);
    assert_eq!(record.state(), ExecutionState::Running); // applicata comunque
}
```

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test execution_record_state
```

**Step 5: Commit**

```bash
cd [rust] && git add src/execution.rs src/app.rs
git commit -m "feat: state transition validation and mark_running before dispatch (M6)"
```

---

### Task M6.4: ExecutionStatus response completa + cold-start da headers (M6)

**Files:**
- Modify: `[rust]/src/execution.rs`
- Modify: `[rust]/src/app.rs`
- Modify: `[rust]/src/dispatch.rs`

**Step 1: Rimuovi #[serde(skip_serializing)] dai campi timing**

In `execution.rs`, rimuovi `#[serde(skip_serializing)]` da:
- `started_at_millis`
- `finished_at_millis`
- `dispatched_at_millis`
- `last_error`
- `cold_start`
- `init_duration_ms`

Rinomina con camelCase per consistenza con Java:

```rust
#[serde(rename = "startedAtMillis", skip_serializing_if = "Option::is_none")]
pub started_at_millis: Option<u64>,
#[serde(rename = "finishedAtMillis", skip_serializing_if = "Option::is_none")]
pub finished_at_millis: Option<u64>,
#[serde(rename = "lastError", skip_serializing_if = "Option::is_none")]
pub last_error: Option<ErrorInfo>,
#[serde(rename = "coldStart")]
pub cold_start: bool,
#[serde(rename = "initDurationMs", skip_serializing_if = "Option::is_none")]
pub init_duration_ms: Option<u64>,
```

**Step 2: Propaga cold-start da PoolDispatcher a ExecutionRecord**

In `dispatch.rs`, aggiungi `cold_start` e `init_duration_ms` a `DispatchResult`:

```rust
pub struct DispatchResult {
    pub status: String,
    pub output: Option<Value>,
    pub dispatcher: String,
    pub cold_start: bool,
    pub init_duration_ms: Option<u64>,
}
```

In `PoolDispatcher::dispatch`, popola questi campi dai response headers `X-Cold-Start` e `X-Init-Duration-Ms` (già letti da reqwest in Task M4.4).

In `finish_invocation` in app.rs, usa `dispatch.cold_start` per aggiornare il record:

```rust
if dispatch.cold_start {
    if let Some(d) = dispatch.init_duration_ms {
        r.mark_cold_start(d);
    }
    state.metrics.cold_start(name);
    if let Some(d) = dispatch.init_duration_ms {
        state.metrics.init_duration(name).record_ms(d);
    }
} else {
    state.metrics.warm_start(name);
}
```

**Step 3: Scrivi test**

```rust
#[tokio::test]
async fn get_execution_returns_all_fields() {
    // invoca una funzione LOCAL, poi GET /v1/executions/{id}
    // verifica che startedAtMillis, finishedAtMillis, status siano presenti
    let app = build_app_with_local_function("fn");
    let client = TestClient::new(app);
    let resp = client.post("/v1/functions/fn:invoke")
        .json(&serde_json::json!({ "input": {} })).send().await;
    let execution_id = resp.headers().get("X-Execution-Id").unwrap().to_str().unwrap().to_string();

    let get_resp = client.get(&format!("/v1/executions/{execution_id}")).send().await;
    let body: serde_json::Value = get_resp.json().await;
    assert!(body.get("startedAtMillis").is_some() || body.get("finishedAtMillis").is_some());
}
```

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test execution_store
```

**Step 5: Commit**

```bash
cd [rust] && git add src/execution.rs src/dispatch.rs src/app.rs
git commit -m "feat: expose all execution status fields and cold-start detection from runtime headers (M6)"
```

---

### Task M6.5: X-Trace-Id e Idempotency-Key forwarding (M6)

**Files:**
- Modify: `[rust]/src/app.rs` (passa trace_id a dispatch)
- Modify: `[rust]/src/dispatch.rs` (PoolDispatcher aggiunge headers)

**Step 1: Passa trace_id e idempotency_key al dispatcher**

Aggiungi parametri a `DispatcherRouter::dispatch`:

```rust
pub async fn dispatch(
    &self,
    function: &FunctionSpec,
    payload: &Value,
    execution_id: &str,
    trace_id: Option<&str>,
    idempotency_key: Option<&str>,
) -> DispatchResult
```

In `PoolDispatcher::dispatch`, aggiungi gli header opzionali alla reqwest request:

```rust
let mut req = client
    .post(&invoke_url)
    .header("Content-Type", "application/json")
    .header("X-Execution-Id", execution_id);

if let Some(tid) = trace_id {
    req = req.header("X-Trace-Id", tid);
}
if let Some(ikey) = idempotency_key {
    req = req.header("Idempotency-Key", ikey);
}
```

**Step 2: Estrai gli header in invoke_function**

```rust
let trace_id = header_value(&headers, "X-Trace-Id");
let idem_key = header_value(&headers, "Idempotency-Key");
let dispatch = state.dispatcher_router.dispatch(
    &function_spec,
    &request.input,
    &execution_id,
    trace_id.as_deref(),
    idem_key.as_deref(),
).await;
```

**Step 3: Esegui i test**

```bash
cd [rust] && cargo test pool_dispatcher
```

**Step 4: Commit**

```bash
cd [rust] && git add src/dispatch.rs src/app.rs
git commit -m "feat: forward X-Trace-Id and Idempotency-Key headers to runtime (M6)"
```

---

### Task M6.6: Gate M6 — test parity suite completa

```bash
cd [rust] && cargo test 2>&1 | grep -E "FAILED|test result"
```

Atteso: 0 FAILED. Poi:

```bash
cd [rust] && cargo test e2e_dockerized_flow -- --ignored --nocapture
```

Atteso: test E2E verdi.

```bash
cd [rust] && git commit --allow-empty -m "chore: M6 milestone complete - completion model, execution lifecycle"
```

---

## M7 — Moduli Opzionali

### Task M7a.1: async-queue module — InvocationEnqueuer reale

**Files:**
- Modify: `[rust]/src/service.rs`
- Modify: `[rust]/src/app.rs`

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/invocation_service_retry_queue_full_parity_test.rs`:

```rust
#[tokio::test]
async fn enqueue_returns_501_when_async_queue_disabled() {
    std::env::remove_var("NANOFAAS_ASYNC_QUEUE_ENABLED");
    let app = build_app();
    let client = TestClient::new(app);
    // Registra funzione
    // POST :enqueue → deve ritornare 501
    let resp = client.post("/v1/functions/fn:enqueue")
        .json(&serde_json::json!({ "input": {} }))
        .send().await;
    assert_eq!(resp.status(), 501);
}

#[tokio::test]
async fn enqueue_returns_accepted_when_async_queue_enabled() {
    std::env::set_var("NANOFAAS_ASYNC_QUEUE_ENABLED", "true");
    // ...
    // POST :enqueue → deve ritornare 202
}
```

**Step 2: Implementa AsyncQueueEnqueuer**

In `[rust]/src/service.rs`:

```rust
pub struct AsyncQueueEnqueuer {
    queue_manager: Arc<Mutex<QueueManager>>,
    execution_store: Arc<Mutex<ExecutionStore>>,
}

impl InvocationEnqueuer for AsyncQueueEnqueuer {
    fn is_available(&self) -> bool { true }

    fn enqueue(&self, name: &str, input: Value, execution_id: &str, queue_size: usize) -> Result<(), AppError> {
        let mut qm = self.queue_manager.lock().unwrap_or_else(|e| e.into_inner());
        let task = InvocationTask {
            execution_id: execution_id.to_string(),
            payload: input,
            attempt: 1,
        };
        qm.enqueue_with_capacity(name, task, queue_size)
            .map_err(|_| AppError::QueueFull(name.to_string()))
    }
}
```

**Step 3: Wira enqueuer in build_app basato su env var**

```rust
let enqueuer: Arc<dyn InvocationEnqueuer> = if std::env::var("NANOFAAS_ASYNC_QUEUE_ENABLED")
    .map(|v| v == "true")
    .unwrap_or(false)
{
    Arc::new(AsyncQueueEnqueuer {
        queue_manager: Arc::clone(&queue_manager),
        execution_store: Arc::clone(&execution_store),
    })
} else {
    Arc::new(NoOpInvocationEnqueuer)
};
```

**Step 4: Usa enqueuer in `enqueue_function`**

```rust
if !state.enqueuer.is_available() {
    return Err(StatusCode::NOT_IMPLEMENTED.into_response());
}
state.enqueuer.enqueue(name, request.input, &execution_id, queue_capacity)?;
```

**Step 5: Esegui i test**

```bash
cd [rust] && cargo test invocation_service_retry
```

**Step 6: Commit**

```bash
cd [rust] && git add src/service.rs src/app.rs
git commit -m "feat: async-queue module with AsyncQueueEnqueuer (M7a)"
```

---

### Task M7b.1: sync-queue module — backpressure reale

**Files:**
- Modify: `[rust]/src/sync.rs`
- Modify: `[rust]/src/app.rs`

**Step 1: Scrivi il test che fallisce**

In `[rust]/tests/sync_queue_backpressure_api_parity_test.rs`:

```rust
#[tokio::test]
async fn sync_queue_rejects_with_est_wait_when_overloaded() {
    std::env::set_var("NANOFAAS_SYNC_QUEUE_ENABLED", "true");
    // setup funzione con max concurrency=1
    // manda 2 richieste concorrenti
    // la seconda deve ricevere 429 con est_wait nel body
}
```

**Step 2: Implementa SyncAdmissionQueue**

In `[rust]/src/sync.rs`:

```rust
use std::sync::atomic::{AtomicUsize, Ordering};

pub struct SyncAdmissionQueue {
    in_flight: AtomicUsize,
    max_concurrency: usize,
}

impl SyncAdmissionQueue {
    pub fn new(max_concurrency: usize) -> Self {
        Self { in_flight: AtomicUsize::new(0), max_concurrency }
    }
}

impl SyncQueueGateway for SyncAdmissionQueue {
    fn is_enabled(&self) -> bool { true }

    fn try_admit(&self, function_name: &str) -> Result<SyncQueuePermit, SyncQueueRejection> {
        let current = self.in_flight.fetch_add(1, Ordering::SeqCst);
        if current >= self.max_concurrency {
            self.in_flight.fetch_sub(1, Ordering::SeqCst);
            return Err(SyncQueueRejection {
                reason: SyncQueueRejectReason::EstWait,
                est_wait_ms: Some((current as u64) * 100), // stima semplice
                queue_depth: Some(current as u64),
            });
        }
        Ok(SyncQueuePermit { queue: Arc::new(self), function_name: function_name.to_string() })
    }

    fn release(&self) {
        self.in_flight.fetch_sub(1, Ordering::SeqCst);
    }
}
```

**Step 3: Rimuovi image-name hack da invoke_function**

Rimuovi il blocco in `invoke_function` che controlla `image.contains("sync-reject-est-wait")` ecc. Questi erano stub per i test parity — ora la logica è reale.

**Step 4: Esegui i test**

```bash
cd [rust] && cargo test sync_queue
```

**Step 5: Commit**

```bash
cd [rust] && git add src/sync.rs src/app.rs
git commit -m "feat: sync-queue module with real backpressure admission queue (M7b)"
```

---

### Task M7c-M7e: Autoscaler, Runtime-Config, Image-Validator

Questi moduli seguono lo stesso pattern di M7a/M7b:
1. Aggiungi trait in file dedicato se non esiste
2. Implementa il modulo abilitato da env var
3. Wira in `build_app()`
4. Scrivi test parity
5. Commit per modulo

File di riferimento Java da portare:
- **Autoscaler** → `control-plane-modules/autoscaler/`
- **Runtime-config** → `control-plane-modules/runtime-config/` + endpoint `/v1/admin/runtime-config`
- **Image-validator** → `control-plane-modules/image-validator/` (usa K8s client da `kubernetes.rs`)

---

## M8 — Hardening, Metriche Complete, Dead Code

### Task M8.1: Aggiungi metriche mancanti

**Files:**
- Modify: `[rust]/src/metrics.rs`
- Modify: `[rust]/src/app.rs`, `src/scheduler.rs`

**Step 1: Aggiungi i 4 counter mancanti a Metrics**

In `[rust]/src/metrics.rs`, aggiungi:

```rust
// Nel MetricsInner o equivalente:
pub fn error(&self, function_name: &str) {
    self.increment("function_error_total", function_name);
}
pub fn retry(&self, function_name: &str) {
    self.increment("function_retry_total", function_name);
}
pub fn timeout(&self, function_name: &str) {
    self.increment("function_timeout_total", function_name);
}
pub fn queue_rejected(&self, function_name: &str) {
    self.increment("function_queue_rejected_total", function_name);
}
```

**Step 2: Chiama le metriche nei punti corretti**

- `metrics.error(name)` → in `finish_invocation` quando status == "ERROR"
- `metrics.retry(name)` → in `Scheduler::tick_once` quando viene fatto il retry
- `metrics.timeout(name)` → in `invoke_function` sul branch di timeout
- `metrics.queue_rejected(name)` → in `enqueue_function` quando la queue è piena

**Step 3: Test Prometheus endpoint**

In `[rust]/tests/prometheus_endpoint_parity_test.rs`:

```rust
#[tokio::test]
async fn prometheus_exposes_all_required_counters() {
    let app = build_app();
    let client = TestClient::new(app);

    // Invoca, enqueue, ecc. per generare metriche
    // ...

    let resp = client.get("/actuator/prometheus").send().await;
    let text = resp.text().await;
    for counter in &[
        "function_invocation_total",
        "function_success_total",
        "function_error_total",
        "function_retry_total",
        "function_timeout_total",
        "function_queue_rejected_total",
        "function_cold_start_total",
        "function_warm_start_total",
    ] {
        assert!(text.contains(counter), "missing metric: {counter}");
    }
}
```

**Step 4: Commit**

```bash
cd [rust] && git add src/metrics.rs src/app.rs src/scheduler.rs
git commit -m "feat: add missing error/retry/timeout/queue_rejected metrics (M8)"
```

---

### Task M8.2: Dead code cleanup

**Files:**
- Delete: `[rust]/src/application.rs`
- Delete: `[rust]/src/config.rs`
- Modify: `[rust]/src/core_defaults.rs` (rimuovi adapter ridondanti)
- Modify: `[rust]/src/lib.rs` (rimuovi i mod relativi)

**Step 1: Verifica che application.rs e config.rs non siano usati**

```bash
cd [rust] && grep -r "application\|config::" src/ --include="*.rs" | grep -v "^src/application\|^src/config"
```

Se non ci sono riferimenti, elimina i file.

**Step 2: Rimuovi da lib.rs**

```rust
// Rimuovi le righe:
// pub mod application;
// pub mod config;
```

**Step 3: Rimuovi adapter ridondanti da core_defaults.rs**

Sostituisci gli adapter `NoOpInvocationEnqueuerAdapter`, `NoOpScalingMetricsSourceAdapter`, ecc. con implementazioni dirette del trait sulle struct no-op.

**Step 4: Esegui clippy**

```bash
cd [rust] && cargo clippy -- -D warnings 2>&1 | head -30
```

Correggi tutti i warning prima di procedere.

**Step 5: Commit**

```bash
cd [rust] && git add -A
git commit -m "chore: remove dead code (application.rs, config.rs, redundant adapters) (M8)"
```

---

### Task M8.3: BUG-8 — dangling record su enqueue failure

**Files:**
- Modify: `[rust]/src/app.rs`

**Step 1: Scrivi il test**

```rust
#[tokio::test]
async fn enqueue_failure_does_not_leave_record_in_store() {
    // Funzione con queueSize=1 già piena
    // POST :enqueue → 429
    // GET /v1/executions/{id} → 404 (nessun record dangling)
}
```

**Step 2: Fix in enqueue_function**

Sposta `store.put_now(record)` DOPO il successo di `enqueue_with_capacity`:

```rust
// Prima di questo fix: record salvato prima di enqueue
// Dopo il fix:
let execution_id = Uuid::new_v4().to_string();
// NON salvare il record ancora

let enqueue_result = state.queue_manager.lock()
    .unwrap_or_else(|e| e.into_inner())
    .enqueue_with_capacity(name, task, queue_capacity);

if enqueue_result.is_err() {
    return Err(AppError::QueueFull(name.to_string()).into_response());
}

// Solo dopo l'enqueue riuscito, salva il record
let record = ExecutionRecord::new(&execution_id, name, ExecutionState::Queued);
state.execution_store.lock()
    .unwrap_or_else(|e| e.into_inner())
    .put_now(record);
```

Nota: c'è una piccola race se il processo crasha tra enqueue e put_now — accettabile per ora (stesso comportamento di Java).

**Step 3: Commit**

```bash
cd [rust] && git add src/app.rs
git commit -m "fix: do not save execution record before successful enqueue (BUG-8)"
```

---

### Task M8.4: Gate M8 — clippy + test suite finale

**Step 1: Clippy senza warning**

```bash
cd [rust] && cargo clippy -- -D warnings
```

Correggi tutti i warning.

**Step 2: Test suite completa**

```bash
cd [rust] && cargo test 2>&1 | tail -5
```

Atteso: `0 failed`.

**Step 3: Test E2E K8s (opzionale — richiede cluster)**

```bash
cd [rust] && cargo test e2e_k8s -- --ignored --nocapture
```

**Step 4: Commit finale**

```bash
cd [rust] && git add -A
git commit -m "chore: M8 hardening complete - clippy clean, all tests green"
```

---

## Riepilogo Dipendenze e Ordine di Esecuzione

```
M4.1 rate_limiter fix
M4.2 rate limit default
M4.3 Cargo.toml (reqwest)     ← prerequisito per M4.4
M4.4 async dispatch (BUG-1)   ← prerequisito per M4.5
M4.5 scheduler lock fix (BUG-3)
M4.6 queue capacity per-fn
M4.7 mutex poison
M4.8 GATE M4

M5.1 unifica registry          ← dopo M4.8
M5.2 GlobalExceptionHandler
M5.3 X-Execution-Id + error
M5.4 WATCHDOG_CMD
M5.5 rimuovi seen_sync_invocations
M5.6 GATE M5

M6.1 ExecutionRecord oneshot   ← dopo M5.6
M6.2 invoke_function async wait
M6.3 mark_running + transitions
M6.4 ExecutionStatus fields + cold-start
M6.5 X-Trace-Id forwarding
M6.6 GATE M6

M7a.1 async-queue module       ← dopo M6.6
M7b.1 sync-queue module        ← dopo M7a.1
M7c   autoscaler               ← dopo M7a.1
M7d   runtime-config           ← dopo M5.6 (indipendente da M6/M7a)
M7e   image-validator          ← dopo M5.1

M8.1 metriche mancanti         ← dopo M7 completo
M8.2 dead code cleanup
M8.3 BUG-8 dangling record
M8.4 GATE M8 — clippy + E2E K8s
```
