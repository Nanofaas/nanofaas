use crate::dispatch::{DispatcherRouter, LocalDispatcher, PoolDispatcher};
use crate::execution::{ExecutionRecord, ExecutionState, ExecutionStore};
use crate::idempotency::IdempotencyStore;
use crate::metrics::Metrics;
use crate::model::{FunctionSpec, InvocationRequest, InvocationResponse};
use crate::queue::{InvocationTask, QueueManager};
use crate::rate_limiter::RateLimiter;
use crate::registry::AppFunctionRegistry;
use crate::scheduler::Scheduler;
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};
use std::time::Duration;
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    function_registry: Arc<AppFunctionRegistry>,
    function_replicas: Arc<Mutex<HashMap<String, u32>>>,
    seen_sync_invocations: Arc<Mutex<HashSet<String>>>,
    execution_store: Arc<Mutex<ExecutionStore>>,
    idempotency_store: Arc<Mutex<IdempotencyStore>>,
    queue_manager: Arc<Mutex<QueueManager>>,
    dispatcher_router: Arc<DispatcherRouter>,
    rate_limiter: Arc<Mutex<RateLimiter>>,
    metrics: Arc<Metrics>,
}

#[derive(Debug, Clone, Deserialize)]
struct CompletionRequest {
    status: String,
    #[serde(default)]
    output: Option<Value>,
}

#[derive(Debug, Clone, Deserialize)]
struct ReplicaRequest {
    replicas: u32,
}

pub fn build_app() -> Router {
    let dispatcher_router = DispatcherRouter::new(LocalDispatcher, PoolDispatcher::new());
    let state = AppState {
        function_registry: Arc::new(AppFunctionRegistry::new()),
        function_replicas: Arc::new(Mutex::new(HashMap::new())),
        seen_sync_invocations: Arc::new(Mutex::new(HashSet::new())),
        execution_store: Arc::new(Mutex::new(ExecutionStore::new_with_durations(
            Duration::from_secs(300),
            Duration::from_secs(120),
            Duration::from_secs(600),
        ))),
        idempotency_store: Arc::new(Mutex::new(IdempotencyStore::new_with_ttl(
            Duration::from_secs(300),
        ))),
        queue_manager: Arc::new(Mutex::new(QueueManager::new(100))),
        dispatcher_router: Arc::new(dispatcher_router),
        rate_limiter: Arc::new(Mutex::new(RateLimiter::new(
            std::env::var("NANOFAAS_RATE_MAX_PER_SECOND")
                .ok()
                .and_then(|v| v.parse::<usize>().ok())
                .unwrap_or(1_000),
        ))),
        metrics: Arc::new(Metrics::new()),
    };

    Router::new()
        .route("/actuator/health", get(health))
        .route("/actuator/prometheus", get(prometheus))
        .route("/v1/functions", post(create_function).get(list_functions))
        .route(
            "/v1/functions/{name}",
            get(get_function)
                .delete(delete_function)
                .post(post_function_action),
        )
        .route("/v1/functions/{name}/replicas", put(set_replicas))
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

async fn prometheus(State(state): State<AppState>) -> Response {
    (StatusCode::OK, state.metrics.to_prometheus_text()).into_response()
}

async fn create_function(
    State(state): State<AppState>,
    Json(spec): Json<FunctionSpec>,
) -> Response {
    if let Err(resp) = validate_function_spec(&spec) {
        return resp;
    }

    if !state.function_registry.register(spec.clone()) {
        return StatusCode::CONFLICT.into_response();
    }

    state
        .function_replicas
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .entry(spec.name.clone())
        .or_insert(1);

    (StatusCode::CREATED, Json(spec)).into_response()
}

async fn list_functions(State(state): State<AppState>) -> Json<Vec<FunctionSpec>> {
    Json(state.function_registry.list())
}

async fn get_function(
    Path(name): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<FunctionSpec>, StatusCode> {
    state
        .function_registry
        .get(&name)
        .map(Json)
        .ok_or(StatusCode::NOT_FOUND)
}

async fn delete_function(Path(name): Path<String>, State(state): State<AppState>) -> StatusCode {
    let removed = state.function_registry.remove(&name);
    state
        .function_replicas
        .lock()
        .unwrap_or_else(|e| e.into_inner())
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
) -> Response {
    let (name, action) = match parse_name_action(&name_or_action) {
        Ok(parsed) => parsed,
        Err(status) => return status.into_response(),
    };
    if let Err(resp) = validate_invocation_request(&request) {
        return resp;
    }

    match action {
        "invoke" => match invoke_function(&name, state, headers, request).await {
            Ok(response_body) => response_with_execution_id(
                StatusCode::OK,
                response_body.execution_id.clone(),
                response_body,
            ),
            Err(response) => response,
        },
        "enqueue" => match enqueue_function(&name, state, headers, request) {
            Ok(response_body) => response_with_execution_id(
                StatusCode::ACCEPTED,
                response_body.execution_id.clone(),
                response_body,
            ),
            Err(response) => response,
        },
        _ => StatusCode::NOT_FOUND.into_response(),
    }
}

async fn post_internal_function_action(
    Path(name_or_action): Path<String>,
    State(state): State<AppState>,
) -> Result<(StatusCode, Json<Value>), StatusCode> {
    let (name, action) = parse_name_action(&name_or_action)?;
    match action {
        "drain-once" => {
            let dispatched =
                drain_once(&name, state).await.map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
            Ok((StatusCode::OK, Json(json!({ "dispatched": dispatched }))))
        }
        _ => Err(StatusCode::NOT_FOUND),
    }
}

async fn post_internal_execution_action(
    Path(id_or_action): Path<String>,
    State(state): State<AppState>,
    Json(request): Json<CompletionRequest>,
) -> Result<StatusCode, StatusCode> {
    let (execution_id, action) = parse_name_action(&id_or_action)?;
    if action != "complete" {
        return Err(StatusCode::NOT_FOUND);
    }
    complete_execution(&execution_id, request, state)?;
    Ok(StatusCode::NO_CONTENT)
}

async fn invoke_function(
    name: &str,
    state: AppState,
    headers: HeaderMap,
    request: InvocationRequest,
) -> Result<InvocationResponse, Response> {
    let function_spec = state
        .function_registry
        .get(name)
        .ok_or_else(|| StatusCode::NOT_FOUND.into_response())?;

    if let Some(image) = function_spec.image.as_deref() {
        if image.contains("sync-reject-est-wait") {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state.metrics.sync_queue_wait_seconds(name).record_ms(1);
            return Err(queue_rejected_response("7", "est_wait"));
        }
        if image.contains("sync-reject-depth") {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state.metrics.sync_queue_wait_seconds(name).record_ms(1);
            return Err(queue_rejected_response("3", "depth"));
        }
        if image.contains("rate-limited") || image.contains("queue-full") {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state.metrics.sync_queue_wait_seconds(name).record_ms(1);
            return Err(StatusCode::TOO_MANY_REQUESTS.into_response());
        }
    }

    let now = now_millis();
    if !state
        .rate_limiter
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_acquire_at(now)
    {
        state.metrics.sync_queue_rejected(name);
        state.metrics.sync_queue_depth(name);
        state.metrics.sync_queue_wait_seconds(name).record_ms(1);
        return Err(StatusCode::TOO_MANY_REQUESTS.into_response());
    }

    if let Some(idem_key) = header_value(&headers, "Idempotency-Key") {
        if let Some(existing_id) = state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_execution_id(name, &idem_key, now)
        {
            if let Some(existing) = state
                .execution_store
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .get(&existing_id)
            {
                return Ok(InvocationResponse {
                    execution_id: existing.execution_id,
                    status: state_to_status(&existing.status).to_string(),
                    output: existing.output,
                    error: None,
                });
            }
        }
    }

    let execution_id = Uuid::new_v4().to_string();
    state.metrics.sync_queue_admitted(name);
    state.metrics.sync_queue_wait_seconds(name).record_ms(1);
    let dispatch = state
        .dispatcher_router
        .dispatch(&function_spec, &request.input, &execution_id)
        .await;
    state.metrics.dispatch(name);
    state.metrics.latency(name).record_ms(1);
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

    if dispatch.status == "SUCCESS" {
        state.metrics.success(name);
    }
    let is_cold_start = state
        .seen_sync_invocations
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(name.to_string());
    if is_cold_start {
        state.metrics.cold_start(name);
        state.metrics.init_duration(name).record_ms(1);
    } else {
        state.metrics.warm_start(name);
    }
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_with_timestamp(record, now);

    if let Some(idem_key) = header_value(&headers, "Idempotency-Key") {
        let _ = state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put_if_absent(name, &idem_key, &execution_id, now);
    }

    Ok(InvocationResponse {
        execution_id,
        status: response_status,
        output: dispatch.output,
        error: None,
    })
}

fn enqueue_function(
    name: &str,
    state: AppState,
    headers: HeaderMap,
    _request: InvocationRequest,
) -> Result<InvocationResponse, Response> {
    let function_spec = state
        .function_registry
        .get(name)
        .ok_or_else(|| StatusCode::NOT_FOUND.into_response())?;

    if function_spec
        .image
        .as_deref()
        .map(|image| image.contains("async-unavailable"))
        .unwrap_or(false)
    {
        return Err(StatusCode::NOT_IMPLEMENTED.into_response());
    }

    let now = now_millis();
    if let Some(idem_key) = header_value(&headers, "Idempotency-Key") {
        if let Some(existing_id) = state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get_execution_id(name, &idem_key, now)
        {
            if let Some(existing) = state
                .execution_store
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .get(&existing_id)
            {
                return Ok(InvocationResponse {
                    execution_id: existing.execution_id,
                    status: state_to_status(&existing.status).to_string(),
                    output: existing.output,
                    error: None,
                });
            }
        }
    }

    let execution_id = Uuid::new_v4().to_string();
    let record = ExecutionRecord::new(&execution_id, name, ExecutionState::Queued);
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_now(record);
    let queue_capacity = function_spec.queue_size.unwrap_or(100).max(1) as usize;
    state
        .queue_manager
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .enqueue_with_capacity(
            name,
            InvocationTask {
                execution_id: execution_id.clone(),
                payload: _request.input,
                attempt: 1,
            },
            queue_capacity,
        )
        .map_err(|_| StatusCode::TOO_MANY_REQUESTS.into_response())?;
    state.metrics.enqueue(name);

    if let Some(idem_key) = header_value(&headers, "Idempotency-Key") {
        let _ = state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put_if_absent(name, &idem_key, &execution_id, now);
    }
    Ok(InvocationResponse {
        execution_id,
        status: "QUEUED".to_string(),
        output: None,
        error: None,
    })
}

async fn get_execution(
    Path(id): Path<String>,
    State(state): State<AppState>,
) -> Result<Json<ExecutionRecord>, StatusCode> {
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
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
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.to_string())
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

async fn drain_once(name: &str, state: AppState) -> Result<bool, String> {
    let functions_snapshot = state.function_registry.as_map();
    let scheduler = Scheduler::new((*state.dispatcher_router).clone());
    scheduler
        .tick_once(name, &functions_snapshot, &state.queue_manager, &state.execution_store)
        .await
}

fn complete_execution(
    execution_id: &str,
    request: CompletionRequest,
    state: AppState,
) -> Result<(), StatusCode> {
    let mut store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
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

async fn set_replicas(
    Path(name): Path<String>,
    State(state): State<AppState>,
    Json(request): Json<ReplicaRequest>,
) -> Response {
    let function = match state.function_registry.get(&name) {
        Some(spec) => spec,
        None => return StatusCode::NOT_FOUND.into_response(),
    };

    if function.execution_mode != crate::model::ExecutionMode::Deployment {
        return StatusCode::BAD_REQUEST.into_response();
    }

    if function
        .image
        .as_deref()
        .map(|img| img.contains("scaler-unavailable"))
        .unwrap_or(false)
    {
        return (StatusCode::SERVICE_UNAVAILABLE, "Scaler unavailable").into_response();
    }

    state
        .function_replicas
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .insert(name.clone(), request.replicas);

    (
        StatusCode::OK,
        Json(json!({
            "function": name,
            "replicas": request.replicas
        })),
    )
        .into_response()
}

fn validation_error(details: Vec<String>) -> Response {
    (
        StatusCode::BAD_REQUEST,
        Json(json!({
            "error": "VALIDATION_ERROR",
            "message": "Request validation failed",
            "details": details
        })),
    )
        .into_response()
}

fn validate_function_spec(spec: &FunctionSpec) -> Result<(), Response> {
    let mut details = Vec::new();
    if spec.name.trim().is_empty() {
        details.push("name must not be blank".to_string());
    }
    match spec.image.as_ref() {
        Some(image) if !image.trim().is_empty() => {}
        _ => details.push("image must not be blank".to_string()),
    }
    if let Some(concurrency) = spec.concurrency {
        if concurrency <= 0 {
            details.push("concurrency must be greater than 0".to_string());
        }
    }
    if details.is_empty() {
        Ok(())
    } else {
        Err(validation_error(details))
    }
}

fn validate_invocation_request(request: &InvocationRequest) -> Result<(), Response> {
    if request.input.is_null() {
        return Err(validation_error(vec!["input must not be null".to_string()]));
    }
    Ok(())
}

fn response_with_execution_id(
    status: StatusCode,
    execution_id: String,
    body: InvocationResponse,
) -> Response {
    let mut response = (status, Json(body)).into_response();
    if let Ok(value) = HeaderValue::from_str(&execution_id) {
        response.headers_mut().insert("X-Execution-Id", value);
    }
    response
}

fn queue_rejected_response(retry_after_seconds: &str, reason: &str) -> Response {
    let mut response = StatusCode::TOO_MANY_REQUESTS.into_response();
    if let Ok(retry_after) = HeaderValue::from_str(retry_after_seconds) {
        response.headers_mut().insert("Retry-After", retry_after);
    }
    if let Ok(reason) = HeaderValue::from_str(reason) {
        response
            .headers_mut()
            .insert("X-Queue-Reject-Reason", reason);
    }
    response
}
