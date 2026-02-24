#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;
use tower::util::ServiceExt;

async fn register_pool_function(
    app: &axum::Router,
    function_name: &str,
    endpoint_url: &str,
    max_retries: u32,
    image: &str,
) {
    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": function_name,
                "image": image,
                "executionMode": "POOL",
                "runtimeMode": "HTTP",
                "endpointUrl": endpoint_url,
                "maxRetries": max_retries
            })
            .to_string(),
        ))
        .unwrap();
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);
}

async fn register_local_function(app: &axum::Router, function_name: &str) {
    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": function_name,
                "image": "nanofaas/function-runtime:test",
                "executionMode": "LOCAL",
                "runtimeMode": "HTTP"
            })
            .to_string(),
        ))
        .unwrap();
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);
}

async fn enqueue(
    app: &axum::Router,
    function_name: &str,
    idem_key: Option<&str>,
) -> (StatusCode, Value) {
    let mut builder = Request::builder()
        .method("POST")
        .uri(format!("/v1/functions/{function_name}:enqueue"))
        .header("content-type", "application/json");
    if let Some(idem) = idem_key {
        builder = builder.header("Idempotency-Key", idem);
    }
    let request = builder
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let response = app.clone().oneshot(request).await.unwrap();
    let status = response.status();
    let body = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload = serde_json::from_slice::<Value>(&body).unwrap_or_else(|_| json!({}));
    (status, payload)
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

async fn execution(app: &axum::Router, execution_id: &str) -> Value {
    let get_exec = Request::builder()
        .method("GET")
        .uri(format!("/v1/executions/{execution_id}"))
        .body(Body::empty())
        .unwrap();
    let get_exec_res = app.clone().oneshot(get_exec).await.unwrap();
    assert_eq!(get_exec_res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(get_exec_res.into_body(), usize::MAX)
        .await
        .unwrap();
    serde_json::from_slice(&body).unwrap()
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

#[tokio::test]
async fn dispatch_whenExecutionRecordMissing_releasesSlotAndSkipsRouter() {
    let app = control_plane_rust::app::build_app();
    register_local_function(&app, "missing-record").await;
    let payload = drain_once(&app, "missing-record").await;
    assert_eq!(payload["dispatched"], false);
}

#[tokio::test]
async fn dispatch_whenRouterThrowsSynchronously_completesExecutionWithError() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "router-throws", "http://127.0.0.1:9/invoke", 1, "img").await;

    let (_, body) = enqueue(&app, "router-throws", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    let _ = drain_once(&app, "router-throws").await;
    let status = execution(&app, &execution_id).await["status"]
        .as_str()
        .unwrap_or_default()
        .to_string();
    assert_eq!(status, "error");
}

#[tokio::test]
async fn dispatch_poolMode_routesToPoolDispatcherAndCompletesSuccess() {
    let app = control_plane_rust::app::build_app();
    let endpoint = one_shot_json_runtime("{\"ok\":\"pool\"}");
    register_pool_function(&app, "pool-success", &endpoint, 1, "img").await;

    let (_, body) = enqueue(&app, "pool-success", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();
    let _ = drain_once(&app, "pool-success").await;

    let record = execution(&app, &execution_id).await;
    assert_eq!(record["status"], "success");
    assert_eq!(record["output"]["ok"], "pool");
}

#[tokio::test]
async fn completeExecution_withRetry_doesNotCompleteTheFuture() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-flow", "http://127.0.0.1:9/invoke", 3, "img").await;
    let (_, body) = enqueue(&app, "retry-flow", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    let _ = drain_once(&app, "retry-flow").await;
    assert_eq!(execution(&app, &execution_id).await["status"], "queued");
}

#[tokio::test]
async fn completeExecution_afterMaxRetries_completesTheFuture() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-max", "http://127.0.0.1:9/invoke", 3, "img").await;
    let (_, body) = enqueue(&app, "retry-max", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    let _ = drain_once(&app, "retry-max").await;
    let _ = drain_once(&app, "retry-max").await;
    let _ = drain_once(&app, "retry-max").await;
    assert_eq!(execution(&app, &execution_id).await["status"], "error");
}

#[tokio::test]
async fn completeExecution_withSuccess_completesImmediately() {
    let app = control_plane_rust::app::build_app();
    register_local_function(&app, "retry-success").await;

    let (_, body) = enqueue(&app, "retry-success", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();
    let _ = drain_once(&app, "retry-success").await;
    assert_eq!(execution(&app, &execution_id).await["status"], "success");
}

#[tokio::test]
async fn retry_preservesExecutionId() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-id", "http://127.0.0.1:9/invoke", 2, "img").await;
    let (_, body) = enqueue(&app, "retry-id", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    let _ = drain_once(&app, "retry-id").await;
    let first = execution(&app, &execution_id).await;
    assert_eq!(first["executionId"], execution_id);
    assert_eq!(first["status"], "queued");

    let _ = drain_once(&app, "retry-id").await;
    let second = execution(&app, &execution_id).await;
    assert_eq!(second["executionId"], execution_id);
    assert_eq!(second["status"], "error");
}

#[tokio::test]
async fn retry_clearsIdempotencyKey() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-idem", "http://127.0.0.1:9/invoke", 2, "img").await;
    let (_, first) = enqueue(&app, "retry-idem", Some("idem-1")).await;
    let exec_id = first["executionId"].as_str().unwrap().to_string();
    let _ = drain_once(&app, "retry-idem").await;

    let (_, replay) = enqueue(&app, "retry-idem", Some("idem-1")).await;
    assert_eq!(replay["executionId"], exec_id);
}

#[tokio::test]
async fn retryWithQueueFull_completesFutureWithError() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-qfull", "http://127.0.0.1:9/invoke", 1, "img").await;
    let (_, body) = enqueue(&app, "retry-qfull", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();
    let _ = drain_once(&app, "retry-qfull").await;
    assert_eq!(execution(&app, &execution_id).await["status"], "error");
}

#[tokio::test]
async fn retryWithQueueFull_afterSuccessfulRetries_completesFuture() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-qfull-2", "http://127.0.0.1:9/invoke", 2, "img").await;
    let (_, body) = enqueue(&app, "retry-qfull-2", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    let _ = drain_once(&app, "retry-qfull-2").await;
    assert_eq!(execution(&app, &execution_id).await["status"], "queued");
    let _ = drain_once(&app, "retry-qfull-2").await;
    assert_eq!(execution(&app, &execution_id).await["status"], "error");
}
