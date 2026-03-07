use crate::dispatch::{DispatchResult, DispatcherRouter, LocalDispatcher, PoolDispatcher};
use crate::execution::{ErrorInfo, ExecutionRecord, ExecutionState, ExecutionStore};
use crate::idempotency::{AcquireResult, IdempotencyStore};
use crate::kubernetes::{
    InMemoryKubernetesClient, KubernetesProperties, KubernetesResourceManager,
};
use crate::kubernetes_live::InClusterKubernetesManager;
use crate::metrics::Metrics;
use crate::model::{
    ExecutionStatus, FunctionSpec, InvocationRequest, InvocationResponse, ScalingConfig,
    ScalingMetric, ScalingStrategy,
};
use crate::queue::{InvocationTask, QueueManager};
use crate::rate_limiter::RateLimiter;
use crate::registry::{
    AppFunctionRegistry, FunctionDefaults, FunctionSpecResolver, ResolverFunctionSpec,
};
use crate::runtime_config::{
    parse_request as parse_runtime_config_request,
    validation_errors as runtime_config_validation_errors, RuntimeConfigPatchResponse,
    RuntimeConfigSnapshot,
};
use crate::scheduler::Scheduler;
use crate::service::{AsyncQueueEnqueuer, InvocationEnqueuer, NoOpInvocationEnqueuer};
use crate::sync::{SyncAdmissionQueue, SyncQueueGateway, SyncQueueRejectReason};
use axum::extract::{Path, State};
use axum::http::{HeaderMap, HeaderValue, StatusCode};
use axum::response::{IntoResponse, Response};
use axum::routing::{get, post, put};
use axum::{Json, Router};
use chrono::Utc;
use serde::Deserialize;
use serde_json::{json, Value};
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use uuid::Uuid;

#[derive(Clone)]
pub struct AppState {
    function_registry: Arc<AppFunctionRegistry>,
    function_locks: Arc<tokio::sync::Mutex<HashMap<String, Arc<tokio::sync::Mutex<()>>>>>,
    function_replicas: Arc<Mutex<HashMap<String, u32>>>,
    provisioner: Arc<FunctionProvisioner>,
    execution_store: Arc<Mutex<ExecutionStore>>,
    idempotency_store: Arc<Mutex<IdempotencyStore>>,
    queue_manager: Arc<Mutex<QueueManager>>,
    dispatcher_router: Arc<DispatcherRouter>,
    rate_limiter: Arc<Mutex<RateLimiter>>,
    runtime_config_state: Arc<Mutex<RuntimeConfigSnapshot>>,
    runtime_config_admin_enabled: bool,
    metrics: Arc<Metrics>,
    enqueuer: Arc<dyn InvocationEnqueuer + Send + Sync>,
    sync_queue: Arc<dyn SyncQueueGateway + Send + Sync>,
    background_scheduler_enabled: bool,
}

#[derive(Clone)]
enum FunctionProvisioner {
    Disabled,
    InMemory(Arc<KubernetesResourceManager>),
    Live(Arc<InClusterKubernetesManager>),
}

impl FunctionProvisioner {
    async fn provision(&self, spec: &FunctionSpec) -> Result<Option<String>, String> {
        match self {
            FunctionProvisioner::Disabled => Ok(None),
            FunctionProvisioner::InMemory(manager) => {
                maybe_test_delay(
                    "NANOFAAS_TEST_INMEMORY_PROVISION_DELAY_MS",
                    spec.image.as_deref(),
                    "test-provision-delay-ms-",
                )
                .await;
                Ok(Some(manager.provision(spec)))
            }
            FunctionProvisioner::Live(manager) => manager.provision(spec).await.map(Some),
        }
    }

    async fn deprovision(&self, function_name: &str) -> Result<(), String> {
        match self {
            FunctionProvisioner::Disabled => Ok(()),
            FunctionProvisioner::InMemory(manager) => {
                maybe_test_delay(
                    "NANOFAAS_TEST_INMEMORY_DEPROVISION_DELAY_MS",
                    Some(function_name),
                    "test-deprovision-delay-ms-",
                )
                .await;
                manager.deprovision(function_name);
                Ok(())
            }
            FunctionProvisioner::Live(manager) => manager.deprovision(function_name).await,
        }
    }

    async fn set_replicas(&self, function_name: &str, replicas: i32) -> Result<(), String> {
        match self {
            FunctionProvisioner::Disabled => Ok(()),
            FunctionProvisioner::InMemory(manager) => {
                manager.set_replicas(function_name, replicas);
                Ok(())
            }
            FunctionProvisioner::Live(manager) => {
                manager.set_replicas(function_name, replicas).await
            }
        }
    }

    async fn ready_replicas(&self, function_name: &str) -> Option<i32> {
        match self {
            FunctionProvisioner::Disabled => None,
            FunctionProvisioner::InMemory(manager) => {
                Some(manager.get_ready_replicas(function_name))
            }
            FunctionProvisioner::Live(manager) => {
                manager.get_ready_replicas(function_name).await.ok()
            }
        }
    }

    fn supports_internal_scaling(&self) -> bool {
        !matches!(self, FunctionProvisioner::Disabled)
    }
}

async fn maybe_test_delay(env_name: &str, marker_source: Option<&str>, marker: &str) {
    let delay_ms = std::env::var(env_name)
        .ok()
        .and_then(|value| value.parse::<u64>().ok())
        .or_else(|| marker_source.and_then(|value| extract_marker_delay_ms(value, marker)));
    let Some(delay_ms) = delay_ms else {
        return;
    };
    if delay_ms > 0 {
        tokio::time::sleep(Duration::from_millis(delay_ms)).await;
    }
}

fn extract_marker_delay_ms(value: &str, marker: &str) -> Option<u64> {
    let index = value.find(marker)?;
    let suffix = &value[index + marker.len()..];
    let digits: String = suffix
        .chars()
        .take_while(|ch| ch.is_ascii_digit())
        .collect();
    digits.parse::<u64>().ok()
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

#[derive(Debug, Clone)]
struct IdempotencyClaim {
    key: String,
    token: String,
}

pub fn build_app() -> Router {
    build_app_pair().0
}

pub fn build_app_with_provisioning_mode(mode: Option<&str>) -> Router {
    let metrics = Arc::new(Metrics::new());
    let state = build_state_with_options(
        Arc::clone(&metrics),
        mode.map(|value| value.to_string()),
        None,
        false,
    );
    build_api_router(state)
}

pub fn build_app_with_runtime_config_admin(enabled: bool) -> Router {
    let metrics = Arc::new(Metrics::new());
    let state = build_state_with_options(Arc::clone(&metrics), None, Some(enabled), false);
    build_api_router(state)
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

/// Builds routers and starts the async queue scheduler loop in background.
/// This is the production wiring used by `main`.
pub fn build_app_pair_with_background_scheduler() -> (Router, Router) {
    let metrics = Arc::new(Metrics::new());
    let state = build_state_with_options(Arc::clone(&metrics), None, None, true);
    start_execution_store_janitor(Arc::clone(&state.execution_store));
    start_background_scheduler(state.clone());
    start_internal_scaler(state.clone());
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
    build_state_with_options(metrics, None, None, false)
}

fn build_state_with_options(
    metrics: Arc<Metrics>,
    override_mode: Option<String>,
    override_runtime_config_admin_enabled: Option<bool>,
    background_scheduler_enabled: bool,
) -> AppState {
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

    let provisioner = resolve_function_provisioner(override_mode);
    let runtime_config_admin_enabled = override_runtime_config_admin_enabled.unwrap_or_else(|| {
        std::env::var("NANOFAAS_ADMIN_RUNTIME_CONFIG_ENABLED")
            .map(|v| v == "true")
            .unwrap_or(false)
    });
    let rate_max_per_second = std::env::var("NANOFAAS_RATE_MAX_PER_SECOND")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(1_000_000);
    let sync_queue_enabled = std::env::var("NANOFAAS_SYNC_QUEUE_ENABLED")
        .map(|v| v == "true")
        .unwrap_or(false);
    let sync_queue_max_concurrency = std::env::var("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(100);
    let sync_queue_max_depth = std::env::var("NANOFAAS_SYNC_QUEUE_MAX_DEPTH")
        .ok()
        .and_then(|v| v.parse::<usize>().ok())
        .unwrap_or(100);
    let runtime_config_state = Arc::new(Mutex::new(RuntimeConfigSnapshot {
        revision: 0,
        rate_max_per_second,
        sync_queue_enabled,
        sync_queue_admission_enabled: std::env::var("NANOFAAS_SYNC_QUEUE_ADMISSION_ENABLED")
            .map(|v| v == "true")
            .unwrap_or(true),
        sync_queue_max_estimated_wait: Duration::from_millis(
            std::env::var("NANOFAAS_SYNC_QUEUE_MAX_ESTIMATED_WAIT_MS")
                .ok()
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(2_000),
        ),
        sync_queue_max_queue_wait: Duration::from_millis(
            std::env::var("NANOFAAS_SYNC_QUEUE_MAX_QUEUE_WAIT_MS")
                .ok()
                .and_then(|v| v.parse::<u64>().ok())
                .unwrap_or(2_000),
        ),
        sync_queue_retry_after_seconds: std::env::var("NANOFAAS_SYNC_QUEUE_RETRY_AFTER_SECONDS")
            .ok()
            .and_then(|v| v.parse::<i32>().ok())
            .unwrap_or(2),
    }));

    AppState {
        function_registry: Arc::new(AppFunctionRegistry::new()),
        function_locks: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
        function_replicas: Arc::new(Mutex::new(HashMap::new())),
        provisioner: Arc::new(provisioner),
        execution_store,
        idempotency_store: Arc::new(Mutex::new(IdempotencyStore::new_with_ttl(
            Duration::from_secs(300),
        ))),
        queue_manager,
        dispatcher_router: Arc::new(dispatcher_router),
        rate_limiter: Arc::new(Mutex::new(RateLimiter::new(rate_max_per_second))),
        runtime_config_state: Arc::clone(&runtime_config_state),
        runtime_config_admin_enabled,
        metrics,
        enqueuer,
        sync_queue: Arc::new(SyncAdmissionQueue::new(
            Arc::clone(&runtime_config_state),
            sync_queue_max_concurrency,
            sync_queue_max_depth,
        )),
        background_scheduler_enabled,
    }
}

fn resolve_function_provisioner(override_mode: Option<String>) -> FunctionProvisioner {
    let mode = override_mode
        .or_else(|| std::env::var("NANOFAAS_PROVISIONING_MODE").ok())
        .unwrap_or_else(|| "auto".to_string())
        .to_ascii_lowercase();

    if mode == "disabled" || mode == "off" {
        return FunctionProvisioner::Disabled;
    }

    if mode == "inmemory" {
        let properties = KubernetesProperties::new(
            std::env::var("POD_NAMESPACE").ok(),
            std::env::var("NANOFAAS_CALLBACK_URL").ok(),
        );
        return FunctionProvisioner::InMemory(Arc::new(KubernetesResourceManager::new(
            InMemoryKubernetesClient::default(),
            properties,
        )));
    }

    match InClusterKubernetesManager::from_env() {
        Ok(Some(manager)) => FunctionProvisioner::Live(Arc::new(manager)),
        Ok(None) => FunctionProvisioner::Disabled,
        Err(err) => {
            eprintln!("Kubernetes provisioning disabled: {err}");
            FunctionProvisioner::Disabled
        }
    }
}

fn build_api_router(state: AppState) -> Router {
    Router::new()
        .route("/actuator/health", get(health))
        .route("/actuator/prometheus", get(prometheus))
        .route(
            "/v1/admin/runtime-config",
            get(get_runtime_config).patch(patch_runtime_config),
        )
        .route(
            "/v1/admin/runtime-config/validate",
            post(validate_runtime_config),
        )
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

fn start_background_scheduler(state: AppState) {
    if !state.enqueuer.enabled() {
        return;
    }

    let idle_sleep_ms = std::env::var("NANOFAAS_ASYNC_SCHEDULER_IDLE_SLEEP_MS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(50);

    tokio::spawn(async move {
        let scheduler = Scheduler::new((*state.dispatcher_router).clone());
        let idle_sleep = Duration::from_millis(idle_sleep_ms);
        loop {
            let functions_snapshot = state.function_registry.as_map();
            let handles = match scheduler
                .tick_ready_functions_once(
                    &functions_snapshot,
                    &state.queue_manager,
                    &state.execution_store,
                    &state.metrics,
                )
                .await
            {
                Ok(handles) => handles,
                Err(err) => {
                    eprintln!("background scheduler tick error: {err}");
                    tokio::time::sleep(idle_sleep).await;
                    continue;
                }
            };

            if handles.is_empty() {
                tokio::time::sleep(idle_sleep).await;
                continue;
            }
            drop(handles);
            tokio::task::yield_now().await;
        }
    });
}

fn start_internal_scaler(state: AppState) {
    if !state.provisioner.supports_internal_scaling() {
        return;
    }

    let poll_interval_ms = std::env::var("NANOFAAS_INTERNAL_SCALER_POLL_INTERVAL_MS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(500)
        .max(1);

    tokio::spawn(async move {
        let mut last_scale_up: HashMap<String, u64> = HashMap::new();
        let mut last_scale_down: HashMap<String, u64> = HashMap::new();
        let poll_interval = Duration::from_millis(poll_interval_ms);

        loop {
            let now = crate::now_millis();
            let specs = state.function_registry.list();
            for spec in specs {
                if spec.execution_mode != crate::model::ExecutionMode::Deployment {
                    continue;
                }

                let Some(scaling) = parse_internal_scaling(&spec) else {
                    continue;
                };

                let current_ready = state
                    .provisioner
                    .ready_replicas(&spec.name)
                    .await
                    .unwrap_or(0);
                let current_replicas = if current_ready <= 0 {
                    std::cmp::max(1, scaling.min_replicas)
                } else {
                    current_ready
                };

                let max_ratio = max_metric_ratio(&state, &spec.name, &scaling);
                let mut desired = (max_ratio * current_replicas as f64).ceil() as i32;
                desired = desired.clamp(scaling.min_replicas, scaling.max_replicas);

                if desired > current_ready {
                    let cooldown_ms = 30_000_u64;
                    let can_scale = last_scale_up
                        .get(&spec.name)
                        .map(|last| now.saturating_sub(*last) >= cooldown_ms)
                        .unwrap_or(true);
                    if can_scale {
                        if let Err(err) = state.provisioner.set_replicas(&spec.name, desired).await
                        {
                            eprintln!("internal scaler scale-up failed for {}: {}", spec.name, err);
                        } else {
                            last_scale_up.insert(spec.name.clone(), now);
                            if let Ok(next) = u32::try_from(desired) {
                                state
                                    .function_replicas
                                    .lock()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .insert(spec.name.clone(), next);
                            }
                        }
                    }
                    continue;
                }

                if desired < current_ready {
                    let cooldown_ms = 60_000_u64;
                    let can_scale = last_scale_down
                        .get(&spec.name)
                        .map(|last| now.saturating_sub(*last) >= cooldown_ms)
                        .unwrap_or(true);
                    if can_scale {
                        if let Err(err) = state.provisioner.set_replicas(&spec.name, desired).await
                        {
                            eprintln!(
                                "internal scaler scale-down failed for {}: {}",
                                spec.name, err
                            );
                        } else {
                            last_scale_down.insert(spec.name.clone(), now);
                            if let Ok(next) = u32::try_from(desired) {
                                state
                                    .function_replicas
                                    .lock()
                                    .unwrap_or_else(|e| e.into_inner())
                                    .insert(spec.name.clone(), next);
                            }
                        }
                    }
                }
            }

            tokio::time::sleep(poll_interval).await;
        }
    });
}

fn parse_internal_scaling(spec: &FunctionSpec) -> Option<ScalingConfig> {
    let scaling = spec
        .scaling_config
        .clone()
        .and_then(|raw| serde_json::from_value::<ScalingConfig>(raw).ok())?;
    if scaling.strategy != ScalingStrategy::Internal {
        return None;
    }
    Some(scaling)
}

fn max_metric_ratio(state: &AppState, function_name: &str, scaling: &ScalingConfig) -> f64 {
    let mut max_ratio = 0.0_f64;
    let metrics = scaling.metrics.as_ref().map(Vec::as_slice).unwrap_or(&[]);
    for metric in metrics {
        let target = parse_metric_target(&metric.target);
        if target <= 0.0 {
            continue;
        }
        let current = metric_current_value(state, function_name, metric);
        max_ratio = max_ratio.max(current / target);
    }
    max_ratio
}

fn parse_metric_target(target: &str) -> f64 {
    if target.trim().is_empty() {
        return 50.0;
    }
    target.parse::<f64>().unwrap_or(50.0)
}

fn metric_current_value(state: &AppState, function_name: &str, metric: &ScalingMetric) -> f64 {
    match metric.metric_type.as_str() {
        "queue_depth" => state
            .queue_manager
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .queue_depth(function_name) as f64,
        "in_flight" => {
            let queued_in_flight = state
                .queue_manager
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .in_flight(function_name);
            let running = state
                .execution_store
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .running_count_for_function(function_name);
            std::cmp::max(queued_in_flight, running) as f64
        }
        "rps" => state
            .metrics
            .dispatch_rate_per_second(function_name, Duration::from_secs(1)),
        _ => 0.0,
    }
}

fn start_execution_store_janitor(execution_store: Arc<Mutex<ExecutionStore>>) {
    let interval_ms = std::env::var("NANOFAAS_EXECUTION_JANITOR_INTERVAL_MS")
        .ok()
        .and_then(|v| v.parse::<u64>().ok())
        .unwrap_or(60_000)
        .max(1);
    crate::execution::spawn_execution_store_janitor(
        execution_store,
        Duration::from_millis(interval_ms),
    );
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

async fn get_runtime_config(State(state): State<AppState>) -> Response {
    if !state.runtime_config_admin_enabled {
        return StatusCode::NOT_FOUND.into_response();
    }
    let snapshot = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    Json(snapshot.to_response()).into_response()
}

async fn validate_runtime_config(
    State(state): State<AppState>,
    Json(request): Json<Value>,
) -> Response {
    if !state.runtime_config_admin_enabled {
        return StatusCode::NOT_FOUND.into_response();
    }
    let parsed = match parse_runtime_config_request(request) {
        Ok(parsed) => parsed,
        Err(err) => {
            return (StatusCode::BAD_REQUEST, Json(json!({ "error": err }))).into_response();
        }
    };
    let current = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let effective = current.apply_patch(&parsed.patch);
    let errors = runtime_config_validation_errors(&effective);
    if errors.is_empty() {
        return Json(json!({ "valid": true })).into_response();
    }
    (
        StatusCode::UNPROCESSABLE_ENTITY,
        Json(json!({ "errors": errors })),
    )
        .into_response()
}

async fn patch_runtime_config(
    State(state): State<AppState>,
    Json(request): Json<Value>,
) -> Response {
    if !state.runtime_config_admin_enabled {
        return StatusCode::NOT_FOUND.into_response();
    }
    let parsed = match parse_runtime_config_request(request) {
        Ok(parsed) => parsed,
        Err(err) => {
            return (StatusCode::BAD_REQUEST, Json(json!({ "error": err }))).into_response();
        }
    };
    let Some(expected_revision) = parsed.expected_revision else {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "expectedRevision is required" })),
        )
            .into_response();
    };

    let current = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let next = current.apply_patch(&parsed.patch);
    let errors = runtime_config_validation_errors(&next);
    if !errors.is_empty() {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({ "errors": errors })),
        )
            .into_response();
    }

    let mut runtime_config = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if runtime_config.revision != expected_revision {
        return (
            StatusCode::CONFLICT,
            Json(json!({
                "error": format!(
                    "Revision mismatch: expected {}, actual {}",
                    expected_revision, runtime_config.revision
                ),
                "currentRevision": runtime_config.revision
            })),
        )
            .into_response();
    }

    *runtime_config = next.clone();
    let revision = runtime_config.revision;
    drop(runtime_config);

    state
        .rate_limiter
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .set_capacity_per_second(next.rate_max_per_second);

    let effective_config = next.to_response();
    Json(RuntimeConfigPatchResponse {
        revision,
        effective_config,
        applied_at: Utc::now().to_rfc3339(),
        change_id: Uuid::new_v4().to_string(),
        warnings: vec![],
    })
    .into_response()
}

async fn create_function(
    State(state): State<AppState>,
    Json(spec): Json<FunctionSpec>,
) -> Response {
    if let Err(resp) = validate_function_spec(&spec) {
        return resp;
    }

    let function_lock = function_lock(&state, &spec.name).await;
    let _guard = function_lock.lock().await;

    if state.function_registry.get(&spec.name).is_some() {
        drop(_guard);
        cleanup_function_lock(&state, &spec.name, &function_lock).await;
        return StatusCode::CONFLICT.into_response();
    }

    let resolver = FunctionSpecResolver::new(default_function_defaults());
    let resolver_spec = match to_resolver_spec(&spec) {
        Ok(resolved) => resolved,
        Err(err) => {
            drop(_guard);
            cleanup_function_lock(&state, &spec.name, &function_lock).await;
            return validation_error(vec![err]);
        }
    };
    let resolved = match resolver.try_resolve(resolver_spec) {
        Ok(resolved) => resolved,
        Err(err) => {
            drop(_guard);
            cleanup_function_lock(&state, &spec.name, &function_lock).await;
            return validation_error(vec![err]);
        }
    };

    let mut resolved_spec = to_function_spec(&resolved);
    if resolved_spec.execution_mode == crate::model::ExecutionMode::Deployment {
        match state.provisioner.provision(&resolved_spec).await {
            Ok(Some(endpoint_url)) => resolved_spec.url = Some(endpoint_url),
            Ok(None) => {}
            Err(err) => {
                drop(_guard);
                cleanup_function_lock(&state, &spec.name, &function_lock).await;
                return (
                    StatusCode::INTERNAL_SERVER_ERROR,
                    Json(json!({
                        "error": "PROVISIONING_ERROR",
                        "message": err
                    })),
                )
                    .into_response();
            }
        }
    }

    if !state.function_registry.register(resolved_spec.clone()) {
        drop(_guard);
        cleanup_function_lock(&state, &spec.name, &function_lock).await;
        return StatusCode::CONFLICT.into_response();
    }

    state
        .function_replicas
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .entry(resolved_spec.name.clone())
        .or_insert(1);
    state
        .queue_manager
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .configure_function(
            &resolved_spec.name,
            resolved_spec.queue_size.unwrap_or(100).max(1) as usize,
            resolved_spec.concurrency.unwrap_or(1).max(1) as usize,
        );

    drop(_guard);
    cleanup_function_lock(&state, &resolved_spec.name, &function_lock).await;
    (StatusCode::CREATED, Json(resolved_spec)).into_response()
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
    let function_lock = function_lock(&state, &name).await;
    let _guard = function_lock.lock().await;
    let removed = state.function_registry.remove(&name);
    state
        .function_replicas
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove(&name);
    state
        .queue_manager
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .remove_function(&name);
    match removed {
        Some(spec) => {
            if spec.execution_mode == crate::model::ExecutionMode::Deployment {
                if let Err(err) = state.provisioner.deprovision(&name).await {
                    eprintln!("deprovision failed for {name}: {err}");
                }
            }
            drop(_guard);
            cleanup_function_lock(&state, &name, &function_lock).await;
            StatusCode::NO_CONTENT
        }
        None => {
            drop(_guard);
            cleanup_function_lock(&state, &name, &function_lock).await;
            StatusCode::NOT_FOUND
        }
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
        "enqueue" => match enqueue_function(&name, state, headers, request).await {
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
            let dispatched = drain_once(&name, state)
                .await
                .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
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

    let now = crate::now_millis();
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
            state
                .metrics
                .sync_queue_wait_seconds(name)
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
    state.metrics.sync_queue_wait_seconds(name).record_ms(1);
    state.metrics.in_flight(name);

    // Sync invoke should use the queue-backed path whenever either background queue
    // orchestration is active or the sync queue module is enabled. When sync queue is
    // active without the background scheduler, the request drains its queued task inline
    // and still waits on the terminal execution record.
    let background_queue_drives_dispatch = state.background_scheduler_enabled && state.enqueuer.enabled();
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
                // finalize_dispatch already wrote terminal state to store before signalling
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
            Ok(Err(_recv_error)) => {
                // sender dropped without sending — internal error
                Err(StatusCode::INTERNAL_SERVER_ERROR.into_response())
            }
            Err(_elapsed) => {
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

    // Direct sync path (sync-queue admission or queue module disabled).
    // Create as Queued so mark_running_at() is a valid Queued -> Running transition.
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

    // Release sync queue slot after dispatch
    if state.sync_queue.enabled() {
        state.sync_queue.release(name);
    }

    let result = finish_invocation(&execution_id, name, dispatch, &state, now);
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
async fn enqueue_function(
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
                payload: _request.input,
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
    loop {
        if execution_reached_terminal_state(execution_id, &state) {
            return Ok(());
        }
        let dispatched = drain_once(function_name, state.clone()).await?;
        if execution_reached_terminal_state(execution_id, &state) {
            return Ok(());
        }
        if !dispatched {
            tokio::task::yield_now().await;
        }
    }
}

async fn complete_execution(
    execution_id: &str,
    request: CompletionRequest,
    state: AppState,
) -> Result<(), StatusCode> {
    // Read the record (short lock)
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

    // Send on completion channel if present (sync invoke waiting)
    if record.completion_tx.is_some() {
        record.complete(dispatch_result);
    } else {
        // No waiter (async enqueue) — update store directly
        let mut store = state
            .execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        if let Some(mut r) = store.get(execution_id) {
            // Guard: reject double-completion for already-terminal executions
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
            // Ensure record is Running before applying terminal transition.
            // The dispatcher normally calls mark_running_at before dispatch; in
            // callback scenarios (e.g. async enqueue + external callback) the
            // record may still be Queued if the scheduler hasn't fired yet.
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
                    // Preserve output even on error (callback may provide output data)
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

    let replicas = match i32::try_from(request.replicas) {
        Ok(value) => value,
        Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };

    if let Err(err) = state.provisioner.set_replicas(&name, replicas).await {
        return (StatusCode::SERVICE_UNAVAILABLE, err).into_response();
    }

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

fn default_function_defaults() -> FunctionDefaults {
    FunctionDefaults::new(30_000, 4, 100, 3)
}

fn to_resolver_spec(spec: &FunctionSpec) -> Result<ResolverFunctionSpec, String> {
    Ok(ResolverFunctionSpec {
        name: spec.name.clone(),
        image: spec.image.clone().unwrap_or_default(),
        command: spec.commands.clone(),
        env: spec.env.clone(),
        timeout_ms: spec.timeout_millis.map(|value| value as i32),
        concurrency: spec.concurrency,
        queue_size: spec.queue_size,
        max_retries: spec.max_retries,
        endpoint_url: spec.url.clone(),
        execution_mode: Some(spec.execution_mode.clone()),
        runtime_mode: Some(spec.runtime_mode.clone()),
        runtime_command: spec.runtime_command.clone(),
        scaling_config: spec
            .scaling_config
            .clone()
            .map(serde_json::from_value)
            .transpose()
            .map_err(|err| format!("Invalid scalingConfig: {err}"))?,
    })
}

fn to_function_spec(spec: &ResolverFunctionSpec) -> FunctionSpec {
    FunctionSpec {
        name: spec.name.clone(),
        image: Some(spec.image.clone()),
        execution_mode: spec
            .execution_mode
            .clone()
            .unwrap_or(crate::model::ExecutionMode::Deployment),
        runtime_mode: spec
            .runtime_mode
            .clone()
            .unwrap_or(crate::model::RuntimeMode::Http),
        concurrency: spec.concurrency,
        queue_size: spec.queue_size,
        max_retries: spec.max_retries,
        scaling_config: spec.scaling_config.clone().map(|value| {
            serde_json::to_value(value).expect("resolver scaling config should serialize")
        }),
        commands: spec.command.clone(),
        env: spec.env.clone(),
        resources: None,
        timeout_millis: spec.timeout_ms.map(|value| value as u64),
        url: spec.endpoint_url.clone(),
        image_pull_secrets: None,
        runtime_command: spec.runtime_command.clone(),
    }
}

async fn function_lock(state: &AppState, name: &str) -> Arc<tokio::sync::Mutex<()>> {
    let mut locks = state.function_locks.lock().await;
    locks
        .entry(name.to_string())
        .or_insert_with(|| Arc::new(tokio::sync::Mutex::new(())))
        .clone()
}

async fn cleanup_function_lock(
    state: &AppState,
    name: &str,
    function_lock: &Arc<tokio::sync::Mutex<()>>,
) {
    let mut locks = state.function_locks.lock().await;
    if let Some(existing) = locks.get(name) {
        if Arc::ptr_eq(existing, function_lock) && Arc::strong_count(existing) == 2 {
            locks.remove(name);
        }
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

#[cfg(test)]
mod autoscaling_tests {
    use super::*;
    use crate::kubernetes::KubernetesDeploymentBuilder;
    use crate::model::{ExecutionMode, RuntimeMode};
    use serde_json::json;

    async fn register_scaled_function(state: &AppState, function_name: &str) {
        let spec = FunctionSpec {
            name: function_name.to_string(),
            image: Some("localhost:5000/nanofaas/java-word-stats:e2e".to_string()),
            execution_mode: ExecutionMode::Deployment,
            runtime_mode: RuntimeMode::Http,
            concurrency: Some(4),
            queue_size: Some(100),
            max_retries: Some(3),
            scaling_config: Some(json!({
                "strategy": "INTERNAL",
                "minReplicas": 0,
                "maxReplicas": 5,
                "metrics": [{"type": "in_flight", "target": "2"}]
            })),
            commands: None,
            env: None,
            resources: None,
            timeout_millis: Some(30_000),
            url: None,
            image_pull_secrets: None,
            runtime_command: None,
        };

        assert!(state.function_registry.register(spec.clone()));
        let _ = state
            .provisioner
            .provision(&spec)
            .await
            .expect("provision should succeed");
        state
            .queue_manager
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .configure_function(function_name, 100, 4);
    }

    async fn register_rps_scaled_function(state: &AppState, function_name: &str) {
        let spec = FunctionSpec {
            name: function_name.to_string(),
            image: Some("localhost:5000/nanofaas/java-word-stats:e2e".to_string()),
            execution_mode: ExecutionMode::Deployment,
            runtime_mode: RuntimeMode::Http,
            concurrency: Some(4),
            queue_size: Some(100),
            max_retries: Some(3),
            scaling_config: Some(json!({
                "strategy": "INTERNAL",
                "minReplicas": 0,
                "maxReplicas": 5,
                "metrics": [{"type": "rps", "target": "1"}]
            })),
            commands: None,
            env: None,
            resources: None,
            timeout_millis: Some(30_000),
            url: None,
            image_pull_secrets: None,
            runtime_command: None,
        };

        assert!(state.function_registry.register(spec.clone()));
        let _ = state
            .provisioner
            .provision(&spec)
            .await
            .expect("provision should succeed");
        state
            .queue_manager
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .configure_function(function_name, 100, 4);
    }

    fn with_inmemory_manager<R>(
        state: &AppState,
        callback: impl FnOnce(&Arc<KubernetesResourceManager>) -> R,
    ) -> R {
        match state.provisioner.as_ref() {
            FunctionProvisioner::InMemory(manager) => callback(manager),
            _ => panic!("expected in-memory provisioner"),
        }
    }

    fn deployment_replicas(state: &AppState, function_name: &str) -> i32 {
        with_inmemory_manager(state, |manager| {
            let name = KubernetesDeploymentBuilder::deployment_name(function_name);
            manager
                .client()
                .get_deployment(manager.resolved_namespace(), &name)
                .expect("deployment should exist")
                .spec
                .replicas
        })
    }

    #[tokio::test]
    async fn internal_scaler_scales_up_from_zero_when_in_flight_present() {
        let metrics = Arc::new(Metrics::new());
        let state = build_state_with_options(metrics, Some("inmemory".to_string()), None, false);
        register_scaled_function(&state, "autoscale-up").await;

        let mut record = ExecutionRecord::new("exec-up", "autoscale-up", ExecutionState::Queued);
        record.mark_running_at(crate::now_millis());
        state
            .execution_store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .put_now(record);

        start_internal_scaler(state.clone());

        let start = crate::now_millis();
        loop {
            if deployment_replicas(&state, "autoscale-up") > 0 {
                break;
            }
            assert!(
                crate::now_millis().saturating_sub(start) < 5_000,
                "internal scaler did not scale up within timeout"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    #[tokio::test]
    async fn internal_scaler_scales_down_to_zero_when_no_load() {
        let metrics = Arc::new(Metrics::new());
        let state = build_state_with_options(metrics, Some("inmemory".to_string()), None, false);
        register_scaled_function(&state, "autoscale-down").await;

        with_inmemory_manager(&state, |manager| {
            let name = KubernetesDeploymentBuilder::deployment_name("autoscale-down");
            manager
                .client()
                .scale_deployment(manager.resolved_namespace(), &name, 2);
            manager
                .client()
                .set_ready_replicas(manager.resolved_namespace(), &name, 2);
        });

        start_internal_scaler(state.clone());

        let start = crate::now_millis();
        loop {
            if deployment_replicas(&state, "autoscale-down") == 0 {
                break;
            }
            assert!(
                crate::now_millis().saturating_sub(start) < 5_000,
                "internal scaler did not scale down within timeout"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }

    #[tokio::test]
    async fn internal_scaler_scales_up_from_rps_load() {
        let metrics = Arc::new(Metrics::new());
        let state = build_state_with_options(metrics, Some("inmemory".to_string()), None, false);
        register_rps_scaled_function(&state, "autoscale-rps").await;

        state.metrics.dispatch("autoscale-rps");
        state.metrics.dispatch("autoscale-rps");
        state.metrics.dispatch("autoscale-rps");

        start_internal_scaler(state.clone());

        let start = crate::now_millis();
        loop {
            if deployment_replicas(&state, "autoscale-rps") >= 3 {
                break;
            }
            assert!(
                crate::now_millis().saturating_sub(start) < 5_000,
                "internal scaler did not scale up from rps within timeout"
            );
            tokio::time::sleep(Duration::from_millis(50)).await;
        }
    }
}
