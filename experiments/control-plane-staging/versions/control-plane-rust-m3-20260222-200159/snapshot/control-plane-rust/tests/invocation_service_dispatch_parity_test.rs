#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::collections::HashSet;
use std::thread;
use std::time::Duration;
use tokio::sync::Barrier;
use tower::util::ServiceExt;

async fn register_function(
    app: &axum::Router,
    name: &str,
    image: &str,
    execution_mode: &str,
    endpoint_url: Option<&str>,
) {
    let mut payload = json!({
        "name": name,
        "image": image,
        "executionMode": execution_mode,
        "runtimeMode": "HTTP"
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
    register_function(
        &app,
        "dispatch-error",
        "img",
        "POOL",
        Some("http://127.0.0.1:9/invoke"),
    )
    .await;

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
