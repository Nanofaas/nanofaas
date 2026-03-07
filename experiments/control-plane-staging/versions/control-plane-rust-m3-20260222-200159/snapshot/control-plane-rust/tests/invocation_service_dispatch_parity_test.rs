#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::collections::HashSet;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::Duration;
use tokio::sync::Barrier;
use tower::util::ServiceExt;

fn sync_queue_env_guard() -> &'static Mutex<()> {
    static GUARD: OnceLock<Mutex<()>> = OnceLock::new();
    GUARD.get_or_init(|| Mutex::new(()))
}

struct EnvGuard {
    previous: std::collections::HashMap<&'static str, Option<String>>,
}

impl EnvGuard {
    fn set(vars: &[(&'static str, &str)]) -> Self {
        let mut previous = std::collections::HashMap::new();
        for (key, value) in vars {
            previous.insert(*key, std::env::var(key).ok());
            std::env::set_var(key, value);
        }
        Self { previous }
    }
}

impl Drop for EnvGuard {
    fn drop(&mut self) {
        for (key, value) in &self.previous {
            if let Some(value) = value {
                std::env::set_var(key, value);
            } else {
                std::env::remove_var(key);
            }
        }
    }
}

async fn register_function(
    app: &axum::Router,
    name: &str,
    image: &str,
    execution_mode: &str,
    endpoint_url: Option<&str>,
) {
    register_function_with_config(app, name, image, execution_mode, endpoint_url, 1, 20).await;
}

async fn register_function_with_retry_config(
    app: &axum::Router,
    name: &str,
    image: &str,
    execution_mode: &str,
    endpoint_url: Option<&str>,
    concurrency: usize,
    queue_size: usize,
    max_retries: u32,
) {
    let mut payload = json!({
        "name": name,
        "image": image,
        "executionMode": execution_mode,
        "runtimeMode": "HTTP",
        "concurrency": concurrency,
        "queueSize": queue_size,
        "maxRetries": max_retries
    });
    if let Some(endpoint) = endpoint_url {
        payload["endpointUrl"] = Value::String(endpoint.to_string());
    }

    let req = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);
}

async fn register_function_with_config(
    app: &axum::Router,
    name: &str,
    image: &str,
    execution_mode: &str,
    endpoint_url: Option<&str>,
    concurrency: usize,
    queue_size: usize,
) {
    let mut payload = json!({
        "name": name,
        "image": image,
        "executionMode": execution_mode,
        "runtimeMode": "HTTP",
        "concurrency": concurrency,
        "queueSize": queue_size
    });
    if let Some(endpoint) = endpoint_url {
        payload["endpointUrl"] = Value::String(endpoint.to_string());
    }

    let req = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);
}

async fn enqueue(app: &axum::Router, function_name: &str) -> String {
    let req = Request::builder()
        .method("POST")
        .uri(format!("/v1/functions/{function_name}:enqueue"))
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::ACCEPTED);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    payload["executionId"].as_str().unwrap().to_string()
}

async fn invoke_sync(app: &axum::Router, function_name: &str) -> axum::http::Response<Body> {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/v1/functions/{function_name}:invoke"))
                .header("content-type", "application/json")
                .body(Body::from(json!({"input":"payload"}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}

async fn drain_once(app: &axum::Router, function_name: &str) -> Value {
    let drain = Request::builder()
        .method("POST")
        .uri(format!("/v1/internal/functions/{function_name}:drain-once"))
        .body(Body::empty())
        .unwrap();
    let res = app.clone().oneshot(drain).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    serde_json::from_slice(&body).unwrap_or_else(|_| json!({}))
}

async fn execution_status(app: &axum::Router, execution_id: &str) -> String {
    let req = Request::builder()
        .method("GET")
        .uri(format!("/v1/executions/{execution_id}"))
        .body(Body::empty())
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    payload["status"].as_str().unwrap_or_default().to_string()
}

fn one_shot_json_runtime(body: &str) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind test runtime");
    let addr = listener.local_addr().expect("runtime local addr");
    let response_body = body.to_string();
    thread::spawn(move || {
        if let Ok((mut socket, _)) = listener.accept() {
            let mut buf = [0u8; 2048];
            let _ = socket.read(&mut buf);
            let reply = format!(
                "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                response_body.len(),
                response_body
            );
            let _ = socket.write_all(reply.as_bytes());
            let _ = socket.flush();
        }
    });
    format!("http://127.0.0.1:{}/invoke", addr.port())
}

fn delayed_json_runtime(body: &str, delay: Duration, accepts: usize) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind delayed runtime");
    let addr = listener.local_addr().expect("delayed runtime local addr");
    let response_body = body.to_string();
    thread::spawn(move || {
        for _ in 0..accepts {
            if let Ok((mut socket, _)) = listener.accept() {
                let mut buf = [0u8; 2048];
                let _ = socket.read(&mut buf);
                thread::sleep(delay);
                let reply = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    response_body.len(),
                    response_body
                );
                let _ = socket.write_all(reply.as_bytes());
                let _ = socket.flush();
            }
        }
    });
    format!("http://127.0.0.1:{}/invoke", addr.port())
}

fn delayed_json_runtime_with_max_in_flight(
    body: &str,
    delay: Duration,
    max_requests: usize,
) -> (String, std::sync::Arc<AtomicUsize>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind delayed runtime");
    let addr = listener.local_addr().expect("runtime local addr");
    let response_body = body.to_string();
    let in_flight = std::sync::Arc::new(AtomicUsize::new(0));
    let max_in_flight = std::sync::Arc::new(AtomicUsize::new(0));
    let in_flight_for_loop = std::sync::Arc::clone(&in_flight);
    let max_for_loop = std::sync::Arc::clone(&max_in_flight);
    thread::spawn(move || {
        for _ in 0..max_requests {
            let Ok((mut socket, _)) = listener.accept() else {
                break;
            };
            let body = response_body.clone();
            let in_flight = std::sync::Arc::clone(&in_flight_for_loop);
            let max_in_flight = std::sync::Arc::clone(&max_for_loop);
            thread::spawn(move || {
                let current = in_flight.fetch_add(1, Ordering::SeqCst) + 1;
                loop {
                    let observed = max_in_flight.load(Ordering::SeqCst);
                    if current <= observed {
                        break;
                    }
                    if max_in_flight
                        .compare_exchange(observed, current, Ordering::SeqCst, Ordering::SeqCst)
                        .is_ok()
                    {
                        break;
                    }
                }

                let mut buf = [0u8; 2048];
                let _ = socket.read(&mut buf);
                thread::sleep(delay);
                let reply = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = socket.write_all(reply.as_bytes());
                let _ = socket.flush();
                in_flight.fetch_sub(1, Ordering::SeqCst);
            });
        }
    });
    (
        format!("http://127.0.0.1:{}/invoke", addr.port()),
        max_in_flight,
    )
}

async fn invoke_sync_with_headers(
    app: &axum::Router,
    function_name: &str,
    idem_key: Option<&str>,
    timeout_ms: Option<u64>,
) -> axum::http::Response<Body> {
    let mut builder = Request::builder()
        .method("POST")
        .uri(format!("/v1/functions/{function_name}:invoke"))
        .header("content-type", "application/json");
    if let Some(idem) = idem_key {
        builder = builder.header("Idempotency-Key", idem);
    }
    if let Some(timeout) = timeout_ms {
        builder = builder.header("X-Timeout-Ms", timeout.to_string());
    }
    app.clone()
        .oneshot(
            builder
                .body(Body::from(json!({"input":"payload"}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}

async fn response_json(response: axum::http::Response<Body>) -> Value {
    let body = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    serde_json::from_slice(&body).unwrap()
}

#[tokio::test]
async fn dispatch_whenExecutionRecordMissing_releasesSlotAndSkipsRouter() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "dispatch-missing", "img-ok", "LOCAL", None).await;
    let payload = drain_once(&app, "dispatch-missing").await;
    assert_eq!(payload["dispatched"], false);
}

#[tokio::test]
async fn dispatch_whenRouterThrowsSynchronously_completesExecutionWithError() {
    let app = control_plane_rust::app::build_app();
    let req = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": "dispatch-error",
                "image": "img",
                "executionMode": "POOL",
                "runtimeMode": "HTTP",
                "endpointUrl": "http://127.0.0.1:9/invoke",
                "maxRetries": 1
            })
            .to_string(),
        ))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);

    let execution_id = enqueue(&app, "dispatch-error").await;
    let _ = drain_once(&app, "dispatch-error").await;
    assert_eq!("error", execution_status(&app, &execution_id).await);
}

#[tokio::test]
async fn dispatch_poolMode_routesToPoolDispatcherAndCompletesSuccess() {
    let app = control_plane_rust::app::build_app();
    let endpoint = one_shot_json_runtime("{\"result\":\"ok\"}");
    register_function(&app, "dispatch-pool-ok", "img", "POOL", Some(&endpoint)).await;
    let execution_id = enqueue(&app, "dispatch-pool-ok").await;
    let _ = drain_once(&app, "dispatch-pool-ok").await;
    assert_eq!("success", execution_status(&app, &execution_id).await);
}

#[tokio::test]
async fn invokeSync_whenSyncQueueAndEnqueuerDisabled_dispatchesInline() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "inline-a", "img", "LOCAL", None).await;

    let res = invoke_sync(&app, "inline-a").await;
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(payload["status"], "success");
}

#[tokio::test]
async fn invokeSync_whenSyncQueueGatewayMissingAndEnqueuerDisabled_dispatchesInline() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "inline-b", "img", "LOCAL", None).await;

    let res = invoke_sync(&app, "inline-b").await;
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(payload["status"], "success");
}

#[tokio::test]
async fn invokeSyncReactive_whenSyncQueueEnabled_usesSyncQueueOnly() {
    let app = control_plane_rust::app::build_app();
    register_function(
        &app,
        "sync-reject-a",
        "sync-reject-est-wait",
        "DEPLOYMENT",
        None,
    )
    .await;

    let res = invoke_sync(&app, "sync-reject-a").await;
    assert_eq!(res.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        res.headers()
            .get("X-Queue-Reject-Reason")
            .and_then(|v| v.to_str().ok()),
        Some("est_wait")
    );
}

#[tokio::test]
async fn invokeSync_whenSyncQueueEnabled_usesSyncQueueOnlyAndReturnsSuccess() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "sync-ok", "img-ok", "DEPLOYMENT", None).await;

    let res = invoke_sync(&app, "sync-ok").await;
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(payload["status"], "success");
}

#[tokio::test]
async fn invokeSync_whenBackgroundSchedulerAndSyncQueueEnabled_usesQueueBackedDispatch() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let _env = EnvGuard::set(&[
        ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
        ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "8"),
        ("NANOFAAS_SYNC_QUEUE_MAX_DEPTH", "8"),
    ]);

    let app = control_plane_rust::app::build_app_pair_with_background_scheduler().0;
    let (endpoint, max_in_flight) = delayed_json_runtime_with_max_in_flight(
        "{\"result\":\"ok\"}",
        Duration::from_millis(250),
        2,
    );
    register_function_with_config(
        &app,
        "sync-queued-bg",
        "img",
        "DEPLOYMENT",
        Some(&endpoint),
        1,
        20,
    )
    .await;

    let app_for_first = app.clone();
    let first = tokio::spawn(async move { invoke_sync(&app_for_first, "sync-queued-bg").await });
    tokio::time::sleep(Duration::from_millis(50)).await;
    let second = tokio::spawn({
        let app_for_second = app.clone();
        async move { invoke_sync(&app_for_second, "sync-queued-bg").await }
    });

    let first_payload = response_json(first.await.unwrap()).await;
    let second_payload = response_json(second.await.unwrap()).await;

    assert_eq!(first_payload["status"], "success");
    assert_eq!(second_payload["status"], "success");
    let first_execution_id = first_payload["executionId"].as_str().unwrap();
    let second_execution_id = second_payload["executionId"].as_str().unwrap();
    assert_eq!(execution_status(&app, first_execution_id).await, "success");
    assert_eq!(execution_status(&app, second_execution_id).await, "success");
    assert_eq!(
        max_in_flight.load(Ordering::SeqCst),
        1,
        "sync invoke should honor function queue concurrency when sync queue is enabled"
    );
}

#[tokio::test]
async fn invokeSync_whenSyncQueueEnabled_preservesTimeoutSemantics() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let _env = EnvGuard::set(&[
        ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
        ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "1"),
        ("NANOFAAS_SYNC_QUEUE_MAX_DEPTH", "8"),
    ]);

    let app = control_plane_rust::app::build_app();
    let endpoint = delayed_json_runtime("{\"result\":\"slow\"}", Duration::from_millis(250), 1);
    register_function_with_config(
        &app,
        "sync-timeout",
        "img",
        "DEPLOYMENT",
        Some(&endpoint),
        1,
        20,
    )
    .await;

    let response = invoke_sync_with_headers(&app, "sync-timeout", None, Some(50)).await;
    assert_eq!(response.status(), StatusCode::OK);
    let payload = response_json(response).await;
    assert_eq!(payload["status"], "timeout");
    let execution_id = payload["executionId"].as_str().unwrap();
    assert_eq!(execution_status(&app, execution_id).await, "timeout");
}

#[tokio::test]
async fn invokeSync_whenSyncQueueEnabled_waitsForRetryToReachTerminalState() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let _env = EnvGuard::set(&[
        ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
        ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "1"),
        ("NANOFAAS_SYNC_QUEUE_MAX_DEPTH", "8"),
    ]);

    let app = control_plane_rust::app::build_app();
    register_function_with_retry_config(
        &app,
        "sync-retry-terminal",
        "img",
        "POOL",
        Some("http://127.0.0.1:9/invoke"),
        1,
        20,
        2,
    )
    .await;

    let response = invoke_sync_with_headers(&app, "sync-retry-terminal", None, Some(250)).await;
    assert_eq!(response.status(), StatusCode::OK);
    let payload = response_json(response).await;
    assert_eq!(payload["status"], "error");
    let execution_id = payload["executionId"].as_str().unwrap();
    assert_eq!(execution_status(&app, execution_id).await, "error");
}

#[tokio::test]
async fn invokeSyncReactive_whenSyncQueueRejects_emitsReactiveError() {
    let app = control_plane_rust::app::build_app();
    register_function(
        &app,
        "sync-reject-b",
        "sync-reject-depth",
        "DEPLOYMENT",
        None,
    )
    .await;

    let res = invoke_sync(&app, "sync-reject-b").await;
    assert_eq!(res.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        res.headers()
            .get("X-Queue-Reject-Reason")
            .and_then(|v| v.to_str().ok()),
        Some("depth")
    );
}

#[tokio::test]
async fn invokeWithCompetingIdempotencyKey_allocatesOnce() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let app = control_plane_rust::app::build_app();
    let contenders = 8usize;
    let endpoint = delayed_json_runtime("{\"result\":\"ok\"}", Duration::from_millis(120), contenders);
    register_function(&app, "competing-idem", "img", "POOL", Some(&endpoint)).await;

    let barrier = std::sync::Arc::new(Barrier::new(contenders + 1));
    let mut handles = Vec::with_capacity(contenders);
    for _ in 0..contenders {
        let app_handle = app.clone();
        let barrier_handle = barrier.clone();
        handles.push(tokio::spawn(async move {
            barrier_handle.wait().await;
            let res =
                invoke_sync_with_headers(&app_handle, "competing-idem", Some("idem-race"), None)
                    .await;
            assert_eq!(res.status(), StatusCode::OK);
            let body = axum::body::to_bytes(res.into_body(), usize::MAX).await.unwrap();
            serde_json::from_slice::<Value>(&body).unwrap()
        }));
    }

    barrier.wait().await;
    let mut execution_ids = HashSet::new();
    for handle in handles {
        let payload = handle.await.unwrap();
        execution_ids.insert(payload["executionId"].as_str().unwrap().to_string());
    }

    assert_eq!(
        execution_ids.len(),
        1,
        "all competing requests should converge on the same execution id"
    );
}

#[tokio::test(flavor = "current_thread")]
async fn invokeWithCompetingIdempotencyKey_onCurrentThreadRuntime_doesNotStarveExecutor() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let app = control_plane_rust::app::build_app();
    let contenders = 4usize;
    let endpoint =
        delayed_json_runtime("{\"result\":\"ok\"}", Duration::from_millis(120), contenders);
    register_function(
        &app,
        "competing-idem-current-thread",
        "img",
        "POOL",
        Some(&endpoint),
    )
    .await;

    let barrier = std::sync::Arc::new(Barrier::new(contenders + 1));
    let completion = tokio::time::timeout(Duration::from_secs(2), async {
        let mut handles = Vec::with_capacity(contenders);
        for _ in 0..contenders {
            let app_handle = app.clone();
            let barrier_handle = barrier.clone();
            handles.push(tokio::spawn(async move {
                barrier_handle.wait().await;
                let res = invoke_sync_with_headers(
                    &app_handle,
                    "competing-idem-current-thread",
                    Some("idem-current-thread"),
                    None,
                )
                .await;
                assert_eq!(res.status(), StatusCode::OK);
                response_json(res).await
            }));
        }

        barrier.wait().await;

        let mut execution_ids = HashSet::new();
        for handle in handles {
            let payload = handle.await.unwrap();
            execution_ids.insert(payload["executionId"].as_str().unwrap().to_string());
        }
        execution_ids
    })
    .await;

    let execution_ids = completion.expect("idempotency contention should not starve the runtime");
    assert_eq!(execution_ids.len(), 1);
}
