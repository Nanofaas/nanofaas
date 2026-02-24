#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
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

async fn drain_once(app: &axum::Router, function_name: &str) -> StatusCode {
    let drain = Request::builder()
        .method("POST")
        .uri(format!("/v1/internal/functions/{function_name}:drain-once"))
        .body(Body::empty())
        .unwrap();
    app.clone().oneshot(drain).await.unwrap().status()
}

async fn execution_status(app: &axum::Router, execution_id: &str) -> String {
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
    let payload: Value = serde_json::from_slice(&body).unwrap();
    payload["status"].as_str().unwrap_or_default().to_string()
}

#[tokio::test]
async fn completeExecution_withRetry_doesNotCompleteTheFuture() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-flow", "http://127.0.0.1:9/invoke", 3, "img").await;

    let (status, body) = enqueue(&app, "retry-flow", None).await;
    assert_eq!(StatusCode::ACCEPTED, status);
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    assert_eq!(StatusCode::OK, drain_once(&app, "retry-flow").await);
    assert_eq!("queued", execution_status(&app, &execution_id).await);
}

#[tokio::test]
async fn completeExecution_afterMaxRetries_completesTheFuture() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "retry-max", "http://127.0.0.1:9/invoke", 3, "img").await;

    let (_, body) = enqueue(&app, "retry-max", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    assert_eq!(StatusCode::OK, drain_once(&app, "retry-max").await);
    assert_eq!(StatusCode::OK, drain_once(&app, "retry-max").await);
    assert_eq!(StatusCode::OK, drain_once(&app, "retry-max").await);
    assert_eq!("error", execution_status(&app, &execution_id).await);
}

#[tokio::test]
async fn completeExecution_withSuccess_completesImmediately() {
    let app = control_plane_rust::app::build_app();
    register_local_function(&app, "retry-success").await;

    let (_, body) = enqueue(&app, "retry-success", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    assert_eq!(StatusCode::OK, drain_once(&app, "retry-success").await);
    assert_eq!("success", execution_status(&app, &execution_id).await);
}

#[tokio::test]
async fn retry_preservesExecutionId() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(
        &app,
        "retry-preserve-id",
        "http://127.0.0.1:9/invoke",
        2,
        "img",
    )
    .await;

    let (_, body) = enqueue(&app, "retry-preserve-id", None).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    assert_eq!(StatusCode::OK, drain_once(&app, "retry-preserve-id").await);
    assert_eq!("queued", execution_status(&app, &execution_id).await);

    assert_eq!(StatusCode::OK, drain_once(&app, "retry-preserve-id").await);
    assert_eq!("error", execution_status(&app, &execution_id).await);
}

#[tokio::test]
async fn retry_clearsIdempotencyKey() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(
        &app,
        "retry-clears-idem",
        "http://127.0.0.1:9/invoke",
        2,
        "img",
    )
    .await;

    let (_, body) = enqueue(&app, "retry-clears-idem", Some("idem-1")).await;
    let execution_id = body["executionId"].as_str().unwrap().to_string();

    assert_eq!(StatusCode::OK, drain_once(&app, "retry-clears-idem").await);
    let (_, replay_body) = enqueue(&app, "retry-clears-idem", Some("idem-1")).await;
    assert_eq!(execution_id, replay_body["executionId"]);
}

#[tokio::test]
async fn invokeAsync_whenEnqueuerDisabled_throwsAsyncQueueUnavailableException() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(
        &app,
        "retry-async-disabled",
        "http://127.0.0.1:8080/invoke",
        1,
        "async-unavailable",
    )
    .await;

    let (status, _) = enqueue(&app, "retry-async-disabled", None).await;
    assert_eq!(StatusCode::NOT_IMPLEMENTED, status);
}

#[tokio::test]
async fn invokeAsync_whenQueueUnavailable_doesNotLeakExecutionOrIdempotencyEntry() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(
        &app,
        "retry-no-leak-disabled",
        "http://127.0.0.1:8080/invoke",
        1,
        "async-unavailable",
    )
    .await;
    register_local_function(&app, "retry-no-leak-ok").await;

    let (first_status, first_body) =
        enqueue(&app, "retry-no-leak-disabled", Some("idem-123")).await;
    assert_eq!(StatusCode::NOT_IMPLEMENTED, first_status);
    assert!(first_body.get("executionId").is_none());

    let (ok_status, ok_body) = enqueue(&app, "retry-no-leak-ok", Some("idem-123")).await;
    assert_eq!(StatusCode::ACCEPTED, ok_status);
    let queued_id = ok_body["executionId"].as_str().unwrap().to_string();

    let (_, replay_body) = enqueue(&app, "retry-no-leak-ok", Some("idem-123")).await;
    assert_eq!(queued_id, replay_body["executionId"]);
}

#[tokio::test]
async fn invokeSync_waitsForRetryToComplete() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(
        &app,
        "retry-sync-wait",
        "http://127.0.0.1:9/invoke",
        2,
        "img",
    )
    .await;

    let invoke = Request::builder()
        .method("POST")
        .uri("/v1/functions/retry-sync-wait:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let invoke_res = app.clone().oneshot(invoke).await.unwrap();
    assert_eq!(invoke_res.status(), StatusCode::OK);
    let invoke_body = axum::body::to_bytes(invoke_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let invoke_json: Value = serde_json::from_slice(&invoke_body).unwrap();
    assert_eq!("error", invoke_json["status"]);
}
