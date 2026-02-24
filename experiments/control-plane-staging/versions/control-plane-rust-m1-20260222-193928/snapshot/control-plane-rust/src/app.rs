use crate::dispatch::{DispatcherRouter, LocalDispatcher, PoolDispatcher};
use crate::execution::{ExecutionRecord, ExecutionState, ExecutionStore};
use crate::idempotency::IdempotencyStore;
use crate::model::{FunctionSpec, InvocationRequest, InvocationResponse};
use crate::queue::{InvocationTask, QueueManager};
use crate::rate_limiter::RateLimiter;
use crate::scheduler::Scheduler;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::{get, post};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    functions: Arc<Mutex<HashMap<String, FunctionSpec>>>,
    execution_store: Arc<Mutex<ExecutionStore>>,
    idempotency_store: Arc<Mutex<IdempotencyStore>>,
    queue_manager: Arc<Mutex<QueueManager>>,
    dispatcher_router: Arc<DispatcherRouter>,
    rate_limiter: Arc<Mutex<RateLimiter>>,
}

#[derive(Debug, Clone, Deserialize)]
struct CompletionRequest {
    status: String,
    #[serde(default)]
    output: Option<Value>,
}

pub fn build_app() -> Router {
    let dispatcher_router = DispatcherRouter::new(Box::new(LocalDispatcher), Box::new(PoolDispatcher));
    let state = AppState {
        functions: Arc::new(Mutex::new(HashMap::new())),
        execution_store: Arc::new(Mutex::new(ExecutionStore::new_with_durations(
            Duration::from_secs(300),
            Duration::from_secs(120),
            Duration::from_secs(600),
        ))),
        idempotency_store: Arc::new(Mutex::new(IdempotencyStore::new_with_ttl(Duration::from_secs(
            300,
        )))),
        queue_manager: Arc::new(Mutex::new(QueueManager::new(1024))),
        dispatcher_router: Arc::new(dispatcher_router),
        rate_limiter: Arc::new(Mutex::new(RateLimiter::new(10_000))),
    };

    Router::new()
        .route("/actuator/health", get(health))
        .route("/v1/functions", post(create_function).get(list_functions))
        .route(
            "/v1/functions/{name}",
            get(get_function).delete(delete_function).post(post_function_action),
        )
        .route(
            "/v1/internal/functions/{name}",
            post(post_internal_function_action),
        )
        .route(
            "/v1/internal/executions/{id}",
            post(post_internal_execution_action),
        )
        .route("/v1/executions/{id}", get(get_execution))
        .with_state(state)
}

async fn health() -> Json<Value> {
    Json(json!({ "status": "UP" }))
}

async fn create_function(
    State(state): State<AppState>,
    Json(spec): Json<FunctionSpec>,
) -> (StatusCode, Json<FunctionSpec>) {
    state
        .functions
        .lock()
        .expect("functions lock")
        .insert(spec.name.clone(), spec.clone());
    (StatusCode::CREATED, Json(spec))
}

async fn list_functions(State(state): State<AppState>) -> Json<Vec<FunctionSpec>> {
    let values = state
        .functions
        .lock()
        .expect("functions lock")
        .values()
        .cloned()
        .collect::<Vec<_>>();
    Json(values)
}

async fn get_function(
    Path(name): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<FunctionSpec>, StatusCode> {
    state
        .functions
        .lock()
        .expect("functions lock")
        .get(&name)
        .cloned()
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

async fn delete_function(Path(name): Path<String>, State(state): State<AppState>) -> StatusCode {
    let removed = state
        .functions
        .lock()
        .expect("functions lock")
        .remove(&name);
    if removed.is_some() {
        StatusCode::NO_CONTENT
    } else {
        StatusCode::NOT_FOUND
    }
}

async fn post_function_action(
    Path(name_or_action): Path<String>,
    State(state): State<AppState>,
    headers: HeaderMap,
    Json(request): Json<InvocationRequest>,
) -> Result<(StatusCode, Json<InvocationResponse>), StatusCode> {
    let (name, action) = parse_name_action(&name_or_action)?;
    match action {
        "invoke" => invoke_function(&name, state, headers, request),
        "enqueue" => enqueue_function(&name, state, request),
        _ => Err(StatusCode::NOT_FOUND),
    }
}

async fn post_internal_function_action(
    Path(name_or_action): Path<String>,
    State(state): State<AppState>,
) -> Result<(StatusCode, Json<Value>), StatusCode> {
    let (name, action) = parse_name_action(&name_or_action)?;
    match action {
        "drain-once" => {
            let dispatched = drain_once(&name, state).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
            Ok((StatusCode::OK, Json(json!({ "dispatched": dispatched }))))
        }
        _ => Err(StatusCode::NOT_FOUND),
    }
}

async fn post_internal_execution_action(
    Path(id_or_action): Path<String>,
    State(state): State<AppState>,
    Json(request): Json<CompletionRequest>,
) -> Result<(StatusCode, Json<Value>), StatusCode> {
    let (execution_id, action) = parse_name_action(&id_or_action)?;
    if action != "complete" {
        return Err(StatusCode::NOT_FOUND);
    }
    complete_execution(&execution_id, request, state)?;
    Ok((StatusCode::OK, Json(json!({ "ok": true }))))
}

fn invoke_function(
    name: &str,
    state: AppState,
    headers: HeaderMap,
    request: InvocationRequest,
) -> Result<(StatusCode, Json<InvocationResponse>), StatusCode> {
    let function_spec = state
        .functions
        .lock()
        .expect("functions lock")
        .get(name)
        .cloned()
        .ok_or(StatusCode::NOT_FOUND)?;

    let now = now_millis();
    if !state
        .rate_limiter
        .lock()
        .expect("rate limiter lock")
        .try_acquire_at(now)
    {
        return Err(StatusCode::TOO_MANY_REQUESTS);
    }

    if let Some(idem_key) = header_value(&headers, "Idempotency-Key") {
        if let Some(existing_id) = state
            .idempotency_store
            .lock()
            .expect("idempotency store lock")
            .get_execution_id(name, &idem_key, now)
        {
            if let Some(existing) = state
                .execution_store
                .lock()
                .expect("execution store lock")
                .get(&existing_id)
            {
                return Ok((
                    StatusCode::OK,
                    Json(InvocationResponse {
                        execution_id: existing.execution_id,
                        status: state_to_status(&existing.status).to_string(),
                        output: existing.output,
                    }),
                ));
            }
        }
    }

    let execution_id = Uuid::new_v4().to_string();
    let dispatch = state.dispatcher_router.dispatch(&function_spec, &request.input);
    let mut record = ExecutionRecord::new(
        &execution_id,
        name,
        match dispatch.status.as_str() {
            "SUCCESS" => ExecutionState::Success,
            "ERROR" => ExecutionState::Error,
            "TIMEOUT" => ExecutionState::Timeout,
            _ => ExecutionState::Error,
        },
    );
    let response_status = state_to_status(&record.status).to_string();
    record.output = dispatch.output.clone();
    state
        .execution_store
        .lock()
        .expect("execution store lock")
        .put_with_timestamp(record, now);

    if let Some(idem_key) = header_value(&headers, "Idempotency-Key") {
        let _ = state
            .idempotency_store
            .lock()
            .expect("idempotency store lock")
            .put_if_absent(name, &idem_key, &execution_id, now);
    }

    Ok((
        StatusCode::OK,
        Json(InvocationResponse {
            execution_id,
            status: response_status,
            output: dispatch.output,
        }),
    ))
}

fn enqueue_function(
    name: &str,
    state: AppState,
    _request: InvocationRequest,
) -> Result<(StatusCode, Json<InvocationResponse>), StatusCode> {
    if !state
        .functions
        .lock()
        .expect("functions lock")
        .contains_key(name)
    {
        return Err(StatusCode::NOT_FOUND);
    }
    let execution_id = Uuid::new_v4().to_string();
    let record = ExecutionRecord::new(&execution_id, name, ExecutionState::Queued);
    state
        .execution_store
        .lock()
        .expect("execution store lock")
        .put_now(record);
    state
        .queue_manager
        .lock()
        .expect("queue manager lock")
        .enqueue(
            name,
            InvocationTask {
                execution_id: execution_id.clone(),
                payload: _request.input,
            },
        )
        .map_err(|_| StatusCode::TOO_MANY_REQUESTS)?;
    Ok((
        StatusCode::ACCEPTED,
        Json(InvocationResponse {
            execution_id,
            status: "QUEUED".to_string(),
            output: None,
        }),
    ))
}

async fn get_execution(
    Path(id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<ExecutionRecord>, StatusCode> {
    state
        .execution_store
        .lock()
        .expect("execution store lock")
        .get(&id)
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

fn state_to_status(state: &ExecutionState) -> &'static str {
    match state {
        ExecutionState::Queued => "QUEUED",
        ExecutionState::Running => "RUNNING",
        ExecutionState::Success => "SUCCESS",
        ExecutionState::Error => "ERROR",
        ExecutionState::Timeout => "TIMEOUT",
    }
}

fn header_value(headers: &HeaderMap, name: &str) -> Option<String> {
    headers.get(name).and_then(|value| value.to_str().ok()).map(|value| value.to_string())
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}

fn parse_name_action(name_or_action: &str) -> Result<(String, &str), StatusCode> {
    if let Some((name, action)) = name_or_action.rsplit_once(':') {
        if name.is_empty() || action.is_empty() {
            return Err(StatusCode::NOT_FOUND);
        }
        return Ok((name.to_string(), action));
    }
    Err(StatusCode::NOT_FOUND)
}

fn drain_once(name: &str, state: AppState) -> Result<bool, String> {
    let functions_snapshot = state
        .functions
        .lock()
        .expect("functions lock")
        .clone();
    let mut queue = state.queue_manager.lock().expect("queue manager lock");
    let mut store = state.execution_store.lock().expect("execution store lock");
    let scheduler = Scheduler::new((*state.dispatcher_router).clone());
    scheduler.tick_once(name, &functions_snapshot, &mut queue, &mut store)
}

fn complete_execution(
    execution_id: &str,
    request: CompletionRequest,
    state: AppState,
) -> Result<(), StatusCode> {
    let mut store = state.execution_store.lock().expect("execution store lock");
    let mut record = store.get(execution_id).ok_or(StatusCode::NOT_FOUND)?;
    record.status = parse_execution_state(&request.status).ok_or(StatusCode::BAD_REQUEST)?;
    record.output = request.output;
    store.put_now(record);
    Ok(())
}

fn parse_execution_state(value: &str) -> Option<ExecutionState> {
    match value {
        "QUEUED" => Some(ExecutionState::Queued),
        "RUNNING" => Some(ExecutionState::Running),
        "SUCCESS" => Some(ExecutionState::Success),
        "ERROR" => Some(ExecutionState::Error),
        "TIMEOUT" => Some(ExecutionState::Timeout),
        _ => None,
    }
}
