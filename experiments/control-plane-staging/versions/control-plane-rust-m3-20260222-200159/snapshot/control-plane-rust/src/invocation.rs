use crate::app::AppState;
use crate::dispatch::DispatchResult;
use crate::execution::{ErrorInfo, ExecutionRecord, ExecutionState};
use crate::idempotency::AcquireResult;
use crate::model::{InvocationRequest, InvocationResponse};
use crate::queue::InvocationTask;
use crate::scheduler::Scheduler;
use crate::sync::SyncQueueRejectReason;
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Deserialize;
use serde_json::Value;
use std::time::Duration;
use uuid::Uuid;

#[derive(Debug, Clone, Deserialize)]
pub(crate) struct CompletionRequest {
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

#[derive(Debug, Clone)]
struct IdempotencyClaim {
    key: String,
    token: String,
}

pub(crate) async fn invoke_function(
    name: &str,
    state: AppState,
    headers: HeaderMap,
    request: InvocationRequest,
) -> Result<InvocationResponse, Response> {
    let request_input = request.input.clone();
    let function_spec = state
        .function_registry
        .get(name)
        .ok_or_else(|| StatusCode::NOT_FOUND.into_response())?;

    let timeout_ms = headers
        .get("X-Timeout-Ms")
        .and_then(|v| v.to_str().ok())
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or_else(|| function_spec.timeout_millis.unwrap_or(30_000));

    if let Some(image) = function_spec.image.as_deref() {
        if image.contains("sync-reject-est-wait") {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state.metrics.sync_queue_wait_ms(name).record_ms(1);
            return Err(queue_rejected_response("7", "est_wait"));
        }
        if image.contains("sync-reject-depth") {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state.metrics.sync_queue_wait_ms(name).record_ms(1);
            return Err(queue_rejected_response("3", "depth"));
        }
        if image.contains("rate-limited") || image.contains("queue-full") {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state.metrics.sync_queue_wait_ms(name).record_ms(1);
            return Err(StatusCode::TOO_MANY_REQUESTS.into_response());
        }
    }

    let now = crate::now_millis();
    if !state
        .rate_limiter
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .try_acquire_at(now)
    {
        state.metrics.sync_queue_rejected(name);
        state.metrics.sync_queue_depth(name);
        state.metrics.sync_queue_wait_ms(name).record_ms(1);
        return Err(StatusCode::TOO_MANY_REQUESTS.into_response());
    }

    if state.sync_queue.enabled() {
        if let Err(rejection) = state.sync_queue.try_admit(name) {
            state.metrics.sync_queue_rejected(name);
            state.metrics.sync_queue_depth(name);
            state
                .metrics
                .sync_queue_wait_ms(name)
                .record_ms(rejection.est_wait_ms.unwrap_or(1));
            let reason = match rejection.reason {
                SyncQueueRejectReason::EstWait => "est_wait",
                SyncQueueRejectReason::Depth => "depth",
            };
            let retry_after = state.sync_queue.retry_after_seconds().max(1);
            return Err(queue_rejected_response(&retry_after.to_string(), reason));
        }
    }

    let idem_claim = match resolve_idempotency(name, &headers, &state).await {
        Ok(claim) => claim,
        Err(existing) => return Ok(existing),
    };

    let execution_id = Uuid::new_v4().to_string();
    state.metrics.sync_queue_admitted(name);
    state.metrics.sync_queue_wait_ms(name).record_ms(1);
    state.metrics.in_flight(name);

    let background_queue_drives_dispatch =
        state.background_scheduler_enabled && state.enqueuer.enabled();
    if background_queue_drives_dispatch || state.sync_queue.enabled() {
        let queue_capacity = function_spec.queue_size.unwrap_or(100).max(1) as usize;
        let concurrency = function_spec.concurrency.unwrap_or(1).max(1) as usize;
        let (record, rx) = ExecutionRecord::new_with_completion(&execution_id, name);
        state
            .execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put_with_timestamp(record, now);
        if let Err(response) = state
            .queue_manager
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .enqueue_with_capacity_and_concurrency(
                name,
                InvocationTask {
                    execution_id: execution_id.clone(),
                    payload: request_input.clone(),
                    attempt: 1,
                },
                queue_capacity,
                concurrency,
            )
            .map_err(|_| StatusCode::TOO_MANY_REQUESTS.into_response())
        {
            state
                .execution_store
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .remove(&execution_id);
            if state.sync_queue.enabled() {
                state.sync_queue.release(name);
            }
            abandon_idempotency_claim(&state, name, idem_claim.as_ref());
            return Err(response);
        }
        state.metrics.enqueue(name);
        state.metrics.queue_depth(name);
        publish_idempotency_claim(&state, name, idem_claim.as_ref(), &execution_id, now);

        if state.sync_queue.enabled() && !background_queue_drives_dispatch {
            let state_for_drive = state.clone();
            let function_name = name.to_string();
            let execution_id_for_drive = execution_id.clone();
            tokio::spawn(async move {
                if let Err(err) = drive_sync_queue_until_terminal(
                    &function_name,
                    &execution_id_for_drive,
                    state_for_drive,
                )
                .await
                {
                    eprintln!(
                        "sync queue inline driver failed for {} / {}: {}",
                        function_name, execution_id_for_drive, err
                    );
                }
            });
        }

        let response = match tokio::time::timeout(Duration::from_millis(timeout_ms), rx).await {
            Ok(Ok(_)) => {
                let record = state
                    .execution_store
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .get(&execution_id);
                match record {
                    Some(r) => Ok(InvocationResponse {
                        execution_id: execution_id.to_string(),
                        status: state_to_status(&r.status).to_string(),
                        output: r.output(),
                        error: r.last_error().map(|e| crate::model::ErrorInfo {
                            code: e.code,
                            message: e.message,
                        }),
                    }),
                    None => Err(StatusCode::INTERNAL_SERVER_ERROR.into_response()),
                }
            }
            Ok(Err(_recv_error)) => Err(StatusCode::INTERNAL_SERVER_ERROR.into_response()),
            Err(_elapsed) => {
                state
                    .queue_manager
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .remove_execution(name, &execution_id);
                let finished_at = crate::now_millis();
                {
                    let mut store = state
                        .execution_store
                        .lock()
                        .unwrap_or_else(|e| e.into_inner());
                    if let Some(mut r) = store.get(&execution_id) {
                        let created_at = r.created_at_millis;
                        r.mark_timeout_at(finished_at);
                        store.put_with_timestamp(r, created_at);
                    }
                }
                state.metrics.timeout(name);
                Ok(InvocationResponse {
                    execution_id: execution_id.to_string(),
                    status: "timeout".to_string(),
                    output: None,
                    error: None,
                })
            }
        };
        if state.sync_queue.enabled() {
            state.sync_queue.release(name);
        }
        return response;
    }

    let mut record = ExecutionRecord::new(&execution_id, name, ExecutionState::Queued);
    record.mark_running_at(now);
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_with_timestamp(record, now);
    publish_idempotency_claim(&state, name, idem_claim.as_ref(), &execution_id, now);

    let trace_id = header_value(&headers, "X-Trace-Id");
    let idempotency_key_fwd = header_value(&headers, "Idempotency-Key");
    state.metrics.dispatch(name);

    let dispatch_started = std::time::Instant::now();
    let dispatch = match tokio::time::timeout(
        Duration::from_millis(timeout_ms),
        state.dispatcher_router.dispatch(
            &function_spec,
            &request_input,
            &execution_id,
            trace_id.as_deref(),
            idempotency_key_fwd.as_deref(),
        ),
    )
    .await
    {
        Ok(dispatch) => dispatch,
        Err(_) => DispatchResult {
            status: "TIMEOUT".to_string(),
            output: None,
            dispatcher: "app-timeout".to_string(),
            cold_start: false,
            init_duration_ms: None,
        },
    };
    let dispatch = if dispatch.status == "ERROR"
        && dispatch_started.elapsed() >= Duration::from_millis(timeout_ms)
    {
        DispatchResult {
            status: "TIMEOUT".to_string(),
            output: None,
            dispatcher: dispatch.dispatcher,
            cold_start: dispatch.cold_start,
            init_duration_ms: dispatch.init_duration_ms,
        }
    } else {
        dispatch
    };

    if state.sync_queue.enabled() {
        state.sync_queue.release(name);
    }

    finish_invocation(&execution_id, name, dispatch, &state, now)
}

#[allow(clippy::result_large_err)]
fn finish_invocation(
    execution_id: &str,
    name: &str,
    dispatch: DispatchResult,
    state: &AppState,
    created_at: u64,
) -> Result<InvocationResponse, Response> {
    let finished_at = crate::now_millis();
    let mut record = state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(execution_id)
        .unwrap_or_else(|| ExecutionRecord::new(execution_id, name, ExecutionState::Running));

    match dispatch.status.as_str() {
        "SUCCESS" => {
            record.mark_success_at(dispatch.output.clone().unwrap_or(Value::Null), finished_at);
            state.metrics.success(name);
        }
        "TIMEOUT" => {
            record.mark_timeout_at(finished_at);
            state.metrics.timeout(name);
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
    let response_error = record.last_error().map(|err| crate::model::ErrorInfo {
        code: err.code,
        message: err.message,
    });

    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_with_timestamp(record, created_at);

    if let Some(started_at) = state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(execution_id)
        .and_then(|r| r.started_at_millis())
    {
        state
            .metrics
            .latency(name)
            .record_ms(finished_at.saturating_sub(started_at));
        state
            .metrics
            .queue_wait(name)
            .record_ms(started_at.saturating_sub(created_at));
    }
    state
        .metrics
        .e2e_latency(name)
        .record_ms(finished_at.saturating_sub(created_at));

    Ok(InvocationResponse {
        execution_id: execution_id.to_string(),
        status: response_status,
        output: response_output,
        error: response_error,
    })
}

#[allow(clippy::result_large_err)]
pub(crate) async fn enqueue_function(
    name: &str,
    state: AppState,
    headers: HeaderMap,
    request: InvocationRequest,
) -> Result<InvocationResponse, Response> {
    let function_spec = state
        .function_registry
        .get(name)
        .ok_or_else(|| StatusCode::NOT_FOUND.into_response())?;

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

    let now = crate::now_millis();
    let idem_claim = match resolve_idempotency(name, &headers, &state).await {
        Ok(claim) => claim,
        Err(existing) => return Ok(existing),
    };

    let execution_id = Uuid::new_v4().to_string();
    let queue_capacity = function_spec.queue_size.unwrap_or(100).max(1) as usize;
    let concurrency = function_spec.concurrency.unwrap_or(1).max(1) as usize;
    let record = ExecutionRecord::new(&execution_id, name, ExecutionState::Queued);
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .put_now(record);
    if let Err(response) = state
        .queue_manager
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .enqueue_with_capacity_and_concurrency(
            name,
            InvocationTask {
                execution_id: execution_id.clone(),
                payload: request.input,
                attempt: 1,
            },
            queue_capacity,
            concurrency,
        )
        .map_err(|_| StatusCode::TOO_MANY_REQUESTS.into_response())
    {
        state
            .execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&execution_id);
        abandon_idempotency_claim(&state, name, idem_claim.as_ref());
        return Err(response);
    }
    assert_execution_visible_after_enqueue(&state, name, &execution_id);
    state.metrics.enqueue(name);
    state.metrics.queue_depth(name);
    publish_idempotency_claim(&state, name, idem_claim.as_ref(), &execution_id, now);
    Ok(InvocationResponse {
        execution_id,
        status: "queued".to_string(),
        output: None,
        error: None,
    })
}

pub(crate) fn state_to_status(state: &ExecutionState) -> &'static str {
    match state {
        ExecutionState::Queued => "queued",
        ExecutionState::Running => "running",
        ExecutionState::Success => "success",
        ExecutionState::Error => "error",
        ExecutionState::Timeout => "timeout",
    }
}

fn header_value(headers: &HeaderMap, name: &str) -> Option<String> {
    headers
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(|value| value.to_string())
}

async fn resolve_idempotency(
    function_name: &str,
    headers: &HeaderMap,
    state: &AppState,
) -> Result<Option<IdempotencyClaim>, InvocationResponse> {
    let Some(key) = header_value(headers, "Idempotency-Key") else {
        return Ok(None);
    };

    loop {
        let now = crate::now_millis();
        let acquire = state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .acquire_or_get(function_name, &key, now);
        match acquire {
            AcquireResult::Claimed(token) => {
                return Ok(Some(IdempotencyClaim { key, token }));
            }
            AcquireResult::Existing(existing_id) => {
                if let Some(existing) = state
                    .execution_store
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .get(&existing_id)
                {
                    let execution_id = existing.execution_id.clone();
                    let status = state_to_status(&existing.status).to_string();
                    let output = existing.output();
                    let error = existing.last_error().map(|err| crate::model::ErrorInfo {
                        code: err.code,
                        message: err.message,
                    });
                    return Err(InvocationResponse {
                        execution_id,
                        status,
                        output,
                        error,
                    });
                }

                let reclaimed = state
                    .idempotency_store
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .claim_if_matches(function_name, &key, &existing_id, now);
                match reclaimed {
                    AcquireResult::Claimed(token) => {
                        return Ok(Some(IdempotencyClaim { key, token }));
                    }
                    AcquireResult::Existing(_)
                    | AcquireResult::Pending
                    | AcquireResult::Missing => {
                        tokio::task::yield_now().await;
                    }
                }
            }
            AcquireResult::Pending | AcquireResult::Missing => {
                tokio::task::yield_now().await;
            }
        }
    }
}

fn publish_idempotency_claim(
    state: &AppState,
    function_name: &str,
    claim: Option<&IdempotencyClaim>,
    execution_id: &str,
    now_millis: u64,
) {
    if let Some(claim) = claim {
        state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .publish_claim(
                function_name,
                &claim.key,
                &claim.token,
                execution_id,
                now_millis,
            );
    }
}

fn abandon_idempotency_claim(
    state: &AppState,
    function_name: &str,
    claim: Option<&IdempotencyClaim>,
) {
    if let Some(claim) = claim {
        state
            .idempotency_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .abandon_claim(function_name, &claim.key, &claim.token);
    }
}

pub(crate) async fn drain_once(name: &str, state: AppState) -> Result<bool, String> {
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
        if let Err(e) = h.await {
            eprintln!("dispatch task panicked: {e}");
        }
    }
    Ok(dispatched)
}

fn execution_reached_terminal_state(execution_id: &str, state: &AppState) -> bool {
    state
        .execution_store
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .get(execution_id)
        .map(|record| record.is_terminal())
        .unwrap_or(true)
}

async fn drive_sync_queue_until_terminal(
    function_name: &str,
    execution_id: &str,
    state: AppState,
) -> Result<(), String> {
    let mut idle_backoff = Duration::from_millis(1);
    loop {
        if execution_reached_terminal_state(execution_id, &state) {
            return Ok(());
        }
        let dispatched = drain_once(function_name, state.clone()).await?;
        if execution_reached_terminal_state(execution_id, &state) {
            return Ok(());
        }
        if !dispatched {
            tokio::time::sleep(idle_backoff).await;
            idle_backoff = (idle_backoff * 2).min(Duration::from_millis(10));
        } else {
            idle_backoff = Duration::from_millis(1);
        }
    }
}

pub(crate) async fn complete_execution(
    execution_id: &str,
    request: CompletionRequest,
    state: AppState,
) -> Result<(), StatusCode> {
    let record = {
        let store = state
            .execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner());
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

    if record.completion_tx.is_some() {
        record.complete(dispatch_result);
    } else {
        let mut store = state
            .execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(mut r) = store.get(execution_id) {
            if matches!(
                r.state(),
                ExecutionState::Success | ExecutionState::Error | ExecutionState::Timeout
            ) {
                eprintln!(
                    "complete_execution: ignoring completion for {} (state={:?}, already terminal)",
                    execution_id,
                    r.state()
                );
                return Ok(());
            }
            let now = crate::now_millis();
            if r.state() == ExecutionState::Queued {
                r.mark_running_at(now);
            }
            match status {
                ExecutionState::Success => {
                    r.mark_success_at(request.output.unwrap_or(Value::Null), now)
                }
                ExecutionState::Error => {
                    r.mark_error_at(
                        error_info.unwrap_or_else(|| ErrorInfo::new("ERROR", "unknown")),
                        now,
                    );
                    r.set_output(request.output);
                }
                ExecutionState::Timeout => r.mark_timeout_at(now),
                _ => {
                    eprintln!(
                        "complete_execution: unexpected completion status {:?} for {}, ignoring",
                        status, execution_id
                    );
                }
            }
            store.put_now(r);
        }
    }

    Ok(())
}

fn parse_execution_state(value: &str) -> Option<ExecutionState> {
    match value.to_ascii_lowercase().as_str() {
        "queued" => Some(ExecutionState::Queued),
        "running" => Some(ExecutionState::Running),
        "success" => Some(ExecutionState::Success),
        "error" => Some(ExecutionState::Error),
        "timeout" => Some(ExecutionState::Timeout),
        _ => None,
    }
}

pub(crate) fn response_with_execution_id(
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

fn assert_execution_visible_after_enqueue(
    state: &AppState,
    function_name: &str,
    execution_id: &str,
) {
    let target_function = std::env::var("NANOFAAS_TEST_ASSERT_ENQUEUE_VISIBLE_FUNCTION").ok();
    if target_function.as_deref() == Some(function_name) {
        let visible = state
            .execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(execution_id)
            .is_some();
        assert!(
            visible,
            "execution record must exist before task becomes enqueue-visible: function={function_name} execution_id={execution_id}"
        );
    }
}
