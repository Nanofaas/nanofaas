#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::json;
use std::collections::HashMap;
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::{Mutex, OnceLock};
use std::thread;
use std::time::Duration;
use tower::util::ServiceExt;

fn sync_queue_env_guard() -> &'static Mutex<()> {
    static GUARD: OnceLock<Mutex<()>> = OnceLock::new();
    GUARD.get_or_init(|| Mutex::new(()))
}

struct EnvGuard {
    previous: HashMap<&'static str, Option<String>>,
}

impl EnvGuard {
    fn set(vars: &[(&'static str, &str)]) -> Self {
        let mut previous = HashMap::new();
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

fn delayed_json_runtime(body: &str, delay: Duration, max_requests: usize) -> String {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind test runtime");
    let addr = listener.local_addr().expect("runtime local addr");
    let response_body = body.to_string();
    thread::spawn(move || {
        for _ in 0..max_requests {
            let Ok((mut socket, _)) = listener.accept() else {
                break;
            };
            let body = response_body.clone();
            thread::spawn(move || {
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
            });
        }
    });
    format!("http://127.0.0.1:{}/invoke", addr.port())
}

async fn register_function(app: &axum::Router, name: &str, endpoint_url: &str, concurrency: usize) {
    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": name,
                "image": "sync-queue-live",
                "executionMode": "DEPLOYMENT",
                "runtimeMode": "HTTP",
                "endpointUrl": endpoint_url,
                "timeoutMs": 5000,
                "concurrency": concurrency,
                "queueSize": 20,
                "maxRetries": 1
            })
            .to_string(),
        ))
        .unwrap();
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);
}

#[tokio::test]
async fn syncInvokeReturns429WithRetryAfter() {
    let app = control_plane_rust::app::build_app();

    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": "echo",
                "image": "sync-reject-est-wait",
                "executionMode": "DEPLOYMENT",
                "runtimeMode": "HTTP"
            })
            .to_string(),
        ))
        .unwrap();
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);

    let invoke = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let res = app.clone().oneshot(invoke).await.unwrap();

    assert_eq!(res.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        res.headers()
            .get("Retry-After")
            .and_then(|v| v.to_str().ok()),
        Some("7")
    );
    assert_eq!(
        res.headers()
            .get("X-Queue-Reject-Reason")
            .and_then(|v| v.to_str().ok()),
        Some("est_wait")
    );
}

#[tokio::test]
async fn syncInvokeReturnsConfiguredRetryAfterForEstimatedWaitRejection() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let _env = EnvGuard::set(&[
        ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
        ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "1"),
        ("NANOFAAS_SYNC_QUEUE_MAX_DEPTH", "8"),
        ("NANOFAAS_SYNC_QUEUE_RETRY_AFTER_SECONDS", "7"),
        ("NANOFAAS_SYNC_QUEUE_MAX_ESTIMATED_WAIT_MS", "50"),
    ]);
    let app = control_plane_rust::app::build_app();
    let runtime = delayed_json_runtime(r#"{"message":"ok"}"#, Duration::from_millis(400), 2);
    register_function(&app, "echo-est-wait", &runtime, 1).await;

    let first = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo-est-wait:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload-1"}).to_string()))
        .unwrap();
    let app_for_first = app.clone();
    let first_handle = tokio::spawn(async move { app_for_first.oneshot(first).await.unwrap() });

    tokio::time::sleep(Duration::from_millis(50)).await;

    let second = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo-est-wait:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload-2"}).to_string()))
        .unwrap();
    let second_res = app.clone().oneshot(second).await.unwrap();

    assert_eq!(second_res.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        second_res
            .headers()
            .get("Retry-After")
            .and_then(|v| v.to_str().ok()),
        Some("7")
    );
    assert_eq!(
        second_res
            .headers()
            .get("X-Queue-Reject-Reason")
            .and_then(|v| v.to_str().ok()),
        Some("est_wait")
    );

    let first_res = first_handle.await.unwrap();
    assert_eq!(first_res.status(), StatusCode::OK);
}

#[tokio::test]
async fn syncInvokeReturnsDepthRejectionWhenConfiguredDepthWouldBeExceeded() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let _env = EnvGuard::set(&[
        ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
        ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "4"),
        ("NANOFAAS_SYNC_QUEUE_MAX_DEPTH", "1"),
        ("NANOFAAS_SYNC_QUEUE_RETRY_AFTER_SECONDS", "3"),
        ("NANOFAAS_SYNC_QUEUE_MAX_ESTIMATED_WAIT_MS", "5000"),
    ]);
    let app = control_plane_rust::app::build_app();
    let runtime = delayed_json_runtime(r#"{"message":"ok"}"#, Duration::from_millis(400), 2);
    register_function(&app, "echo-depth", &runtime, 4).await;

    let first = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo-depth:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload-1"}).to_string()))
        .unwrap();
    let app_for_first = app.clone();
    let first_handle = tokio::spawn(async move { app_for_first.oneshot(first).await.unwrap() });

    tokio::time::sleep(Duration::from_millis(50)).await;

    let second = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo-depth:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload-2"}).to_string()))
        .unwrap();
    let second_res = app.clone().oneshot(second).await.unwrap();

    assert_eq!(second_res.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        second_res
            .headers()
            .get("Retry-After")
            .and_then(|v| v.to_str().ok()),
        Some("3")
    );
    assert_eq!(
        second_res
            .headers()
            .get("X-Queue-Reject-Reason")
            .and_then(|v| v.to_str().ok()),
        Some("depth")
    );

    let first_res = first_handle.await.unwrap();
    assert_eq!(first_res.status(), StatusCode::OK);
}

#[tokio::test]
async fn syncInvokeWhenSyncQueueEnabled_recordsQueuedMetricsAndCompletesThroughQueuePath() {
    let _guard = sync_queue_env_guard()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    let _env = EnvGuard::set(&[
        ("NANOFAAS_SYNC_QUEUE_ENABLED", "true"),
        ("NANOFAAS_SYNC_QUEUE_MAX_CONCURRENCY", "1"),
        ("NANOFAAS_SYNC_QUEUE_MAX_DEPTH", "8"),
        ("NANOFAAS_SYNC_QUEUE_RETRY_AFTER_SECONDS", "2"),
        ("NANOFAAS_SYNC_QUEUE_MAX_ESTIMATED_WAIT_MS", "1000"),
    ]);
    let app = control_plane_rust::app::build_app();
    let runtime = delayed_json_runtime(r#"{"message":"ok"}"#, Duration::from_millis(50), 1);
    register_function(&app, "echo-queued", &runtime, 1).await;

    let invoke = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo-queued:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let invoke_res = app.clone().oneshot(invoke).await.unwrap();
    assert_eq!(invoke_res.status(), StatusCode::OK);

    let scrape = Request::builder()
        .method("GET")
        .uri("/actuator/prometheus")
        .body(Body::empty())
        .unwrap();
    let scrape_res = app.clone().oneshot(scrape).await.unwrap();
    assert_eq!(scrape_res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(scrape_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let text = String::from_utf8(body.to_vec()).unwrap();

    assert!(text.contains("function_enqueue_total{function=\"echo-queued\"}"));
}
