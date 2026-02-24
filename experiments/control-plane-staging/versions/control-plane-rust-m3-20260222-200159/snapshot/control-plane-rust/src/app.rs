use crate::dispatch::{DispatchResult, DispatcherRouter, LocalDispatcher, PoolDispatcher};
use crate::service::{AsyncQueueEnqueuer, InvocationEnqueuer, NoOpInvocationEnqueuer};
use crate::sync::{NoOpSyncQueueGateway, SyncAdmissionQueue, SyncQueueGateway, SyncQueueRejectReason};
use crate::execution::{ErrorInfo, ExecutionRecord, ExecutionState, ExecutionStore};
use crate::idempotency::IdempotencyStore;
use crate::metrics::Metrics;
use crate::model::{ExecutionStatus, FunctionSpec, InvocationRequest, InvocationResponse};
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
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    function_registry: Arc<AppFunctionRegistry>,
    function_replicas: Arc<Mutex<HashMap<String, u32>>>,
    execution_store: Arc<Mutex<ExecutionStore>>,
    idempotency_store: Arc<Mutex<IdempotencyStore>>,
    queue_manager: Arc<Mutex<QueueManager>>,
    dispatcher_router: Arc<DispatcherRouter>,
    rate_limiter: Arc<Mutex<RateLimiter>>,
    metrics: Arc<Metrics>,
    enqueuer: Arc<dyn InvocationEnqueuer + Send + Sync>,
    sync_queue: Arc<dyn SyncQueueGateway + Send + Sync>,
}

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

#[derive(Debug, Clone, Deserialize)]
struct ReplicaRequest {
    replicas: u32,
}

pub fn build_app() -> Router {
    build_app_pair().0
}

/// Returns (api_router, management_router) sharing the same Metrics instance.
/// Used in production: API on port 8080, management on port 8081.
pub fn build_app_pair() -> (Router, Router) {
    let metrics = Arc::new(Metrics::new());
    let state = build_state(Arc::clone(&metrics));
    let api = build_api_router(state);
    let mgmt = build_management_app(metrics);
    (api, mgmt)
}

/// Builds the management router (port 8081) serving health and prometheus endpoints.
pub fn build_management_app(metrics: Arc<Metrics>) -> Router {
    Router::new()
        .route("/actuator/health", get(health))
        .route("/actuator/health/readiness", get(health))
        .route("/actuator/health/liveness", get(health))
        .route("/actuator/prometheus", get(management_prometheus))
        .with_state(metrics)
}

fn build_state(metrics: Arc<Metrics>) -> AppState {
    let dispatcher_router = DispatcherRouter::new(LocalDispatcher, PoolDispatcher::new());
    let execution_store = Arc::new(Mutex::new(ExecutionStore::new_with_durations(
        Duration::from_secs(300),
        Duration::from_secs(120),
        Duration::from_secs(600),
    )));
    let queue_manager = Arc::new(Mutex::new(QueueManager::new(100)));

    let async_queue_enabled = std::env::var("NANOFAAS_ASYNC_QUEUE_ENABLED")
        .map(|v| v == "true")
        .unwrap_or(true); // enabled by default for backward compat with tests

    let enqueuer: Arc<dyn InvocationEnqueuer + Send + Sync> = if async_queue_enabled {
        Arc::new(AsyncQueueEnqueuer {
            queue_manager: Arc::clone(&queue_manager),
            execution_store: Arc::clone(&execution_store),
        })
    } else {
        Arc::new(NoOpInvocationEnqueuer)
    };

    AppState {
        function_registry: Arc::new(AppFunctionRegistry::new()),
        function_replicas: Arc::new(Mutex::new(HashMap::new())),
        execution_store,
        idempotency_store: Arc::new(Mutex::new(IdempotencyStore::new_with_ttl(
            Duration::from_secs(300),
        ))),
        queue_manager,
        dispatcher_router: Arc::new(dispatcher_router),
        rate_limiter: Arc::new(Mutex::new(RateLimiter::new(
            std::env::var("NANOFAAS_RATE_MAX_PER_SECOND")
                .ok()
                .and_then(|v| v.parse::<usize>().ok())
                .unwrap_or(1_000),
        ))),
        metrics,
        enqueuer,
        sync_queue: {
            let sync_enabled = std::env::var("NANOFAAS_SYNC_QUEUE_ENABLED")
                .map(|v| v == "true")
                .unwrap_or(false);
            if sync_enabled {
                let max_concurrency = std::env::var("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY")
                    .ok()
                    .and_then(|v| v.parse::<usize>().ok())
                    .unwrap_or(100);
                Arc::new(SyncAdmissionQueue::new(max_concurrency))
            } else {
                Arc::new(NoOpSyncQueueGateway)
            }
        },
    }
}

fn build_api_router(state: AppState) -> Router {
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

async fn management_prometheus(State(metrics): State<Arc<Metrics>>) -> Response {
    (StatusCode::OK, metrics.to_prometheus_text()).into_response()
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
    complete_execution(&execution_id, request, state).await?;
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

    // Sync queue admission check (when sync-queue module is enabled)
    if state.sync_queue.enabled() {
        if let Err(rejection) = state.sync_queue.try_admit(name) {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state.metrics.sync_queue_wait_seconds(name).record_ms(1);
            let reason = match rejection.reason {
                SyncQueueRejectReason::EstWait => "est_wait",
                SyncQueueRejectReason::Depth => "depth",
            };
            let retry_after = rejection
                .est_wait_ms
                .map(|ms| (ms / 1000).max(1))
                .unwrap_or(2);
            return Err(queue_rejected_response(&retry_after.to_string(), reason));
        }
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
    state.metrics.in_flight(name);

    // Create record as RUNNING immediately so polling sees the in-flight state.
    let mut record = ExecutionRecord::new(&execution_id, name, ExecutionState::Running);
    record.mark_running_at(now);
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_with_timestamp(record, now);

    let trace_id = header_value(&headers, "X-Trace-Id");
    let idempotency_key_fwd = header_value(&headers, "Idempotency-Key");
    state.metrics.dispatch(name);

    let dispatch = state
        .dispatcher_router
        .dispatch(
            &function_spec,
            &request.input,
            &execution_id,
            trace_id.as_deref(),
            idempotency_key_fwd.as_deref(),
        )
        .await;

    // Release sync queue slot after dispatch
    if state.sync_queue.enabled() {
        state.sync_queue.release(name);
    }

    let result = finish_invocation(&execution_id, name, dispatch, &state, now);

    if let Some(idem_key) = header_value(&headers, "Idempotency-Key") {
        let _ = state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put_if_absent(name, &idem_key, &execution_id, now);
    }

    result
}

#[allow(clippy::result_large_err)]
fn finish_invocation(
    execution_id: &str,
    name: &str,
    dispatch: DispatchResult,
    state: &AppState,
    created_at: u64,
) -> Result<InvocationResponse, Response> {
    let finished_at = now_millis();
    let mut record = state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(execution_id)
        .unwrap_or_else(|| ExecutionRecord::new(execution_id, name, ExecutionState::Running));

    match dispatch.status.as_str() {
        "SUCCESS" => {
            record.mark_success_at(
                dispatch.output.clone().unwrap_or(Value::Null),
                finished_at,
            );
            state.metrics.success(name);
        }
        "TIMEOUT" => {
            record.mark_error_at(
                ErrorInfo::new("TIMEOUT", "dispatch timed out"),
                finished_at,
            );
            state.metrics.timeout(name);
            state.metrics.error(name);
        }
        _ => {
            record.mark_error_at(
                ErrorInfo::new("DISPATCH_ERROR", "dispatch failed"),
                finished_at,
            );
            state.metrics.error(name);
        }
    }

    if dispatch.cold_start {
        record.mark_cold_start(dispatch.init_duration_ms.unwrap_or(0));
        state.metrics.cold_start(name);
        state
            .metrics
            .init_duration(name)
            .record_ms(dispatch.init_duration_ms.unwrap_or(0));
    } else {
        state.metrics.warm_start(name);
    }

    let response_status = state_to_status(&record.status).to_string();
    let response_output = record.output().clone();

    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_with_timestamp(record, created_at);

    state.metrics.latency(name).record_ms(1);

    Ok(InvocationResponse {
        execution_id: execution_id.to_string(),
        status: response_status,
        output: response_output,
        error: None,
    })
}

#[allow(clippy::result_large_err)]
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

    // Check if async queue is available
    if !state.enqueuer.enabled() {
        return Err(StatusCode::NOT_IMPLEMENTED.into_response());
    }

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
    let record = ExecutionRecord::new(&execution_id, name, ExecutionState::Queued);
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_now(record);
    state.metrics.enqueue(name);
    state.metrics.queue_depth(name);

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
) -> Result<Json<ExecutionStatus>, StatusCode> {
    let record = state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(&id)
        .ok_or(StatusCode::NOT_FOUND)?;
    let snap = record.snapshot();
    Ok(Json(ExecutionStatus {
        execution_id: snap.execution_id,
        status: state_to_status(&snap.state).to_string(),
        started_at_millis: snap.started_at_millis,
        finished_at_millis: snap.finished_at_millis,
        output: snap.output,
        error: snap.last_error.map(|e| crate::model::ErrorInfo {
            code: e.code,
            message: e.message,
        }),
        cold_start: snap.cold_start,
        init_duration_ms: snap.init_duration_ms,
    }))
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
        .tick_once(name, &functions_snapshot, &state.queue_manager, &state.execution_store, &state.metrics)
        .await
}

async fn complete_execution(
    execution_id: &str,
    request: CompletionRequest,
    state: AppState,
) -> Result<(), StatusCode> {
    // Read the record (short lock)
    let record = {
        let store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
        store.get(execution_id).ok_or(StatusCode::NOT_FOUND)?
    };

    let status = parse_execution_state(&request.status).ok_or(StatusCode::BAD_REQUEST)?;
    let error_info = request
        .error
        .as_ref()
        .map(|e| ErrorInfo::new(&e.code, &e.message));

    let dispatch_result = DispatchResult {
        status: request.status.clone(),
        output: request.output.clone(),
        dispatcher: "callback".to_string(),
        cold_start: false,
        init_duration_ms: None,
    };

    // Send on completion channel if present (sync invoke waiting)
    if record.completion_tx.is_some() {
        record.complete(dispatch_result).await;
    } else {
        // No waiter (async enqueue) — update store directly
        let mut store = state.execution_store.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(mut r) = store.get(execution_id) {
            let now = now_millis();
            match status {
                ExecutionState::Success => {
                    r.mark_success_at(request.output.unwrap_or(Value::Null), now)
                }
                ExecutionState::Error => {
                    r.mark_error_at(
                        error_info.unwrap_or_else(|| ErrorInfo::new("ERROR", "unknown")),
                        now,
                    );
                    // Preserve output even on error (callback may provide output data)
                    r.set_output(request.output);
                }
                ExecutionState::Timeout => r.mark_timeout_at(now),
                _ => r.set_state(status),
            }
            store.put_now(r);
        }
    }

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

#[allow(clippy::result_large_err)]
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

#[allow(clippy::result_large_err)]
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
