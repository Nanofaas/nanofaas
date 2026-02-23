#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

async fn register_failing_pool_function(app: &axum::Router, function_name: &str, max_retries: u32) {
    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": function_name,
                "image": "nanofaas/function-runtime:test",
                "executionMode": "POOL",
                "runtimeMode": "HTTP",
                "endpointUrl": "http://127.0.0.1:9/invoke",
                "maxRetries": max_retries
            })
            .to_string(),
        ))
        .unwrap();
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);
}

async fn enqueue(app: &axum::Router, function_name: &str) -> String {
    let enqueue = Request::builder()
        .method("POST")
        .uri(format!("/v1/functions/{function_name}:enqueue"))
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let enqueue_res = app.clone().oneshot(enqueue).await.unwrap();
    assert_eq!(enqueue_res.status(), StatusCode::ACCEPTED);
    let enqueue_body = axum::body::to_bytes(enqueue_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_json: Value = serde_json::from_slice(&enqueue_body).unwrap();
    enqueue_json["executionId"].as_str().unwrap().to_string()
}

async fn drain_once(app: &axum::Router, function_name: &str) {
    let drain = Request::builder()
        .method("POST")
        .uri(format!("/v1/internal/functions/{function_name}:drain-once"))
        .body(Body::empty())
        .unwrap();
    let drain_res = app.clone().oneshot(drain).await.unwrap();
    assert_eq!(drain_res.status(), StatusCode::OK);
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
async fn retryWithQueueFull_completesFutureWithError() {
    let app = control_plane_rust::app::build_app();
    register_failing_pool_function(&app, "retry-err", 1).await;

    let execution_id = enqueue(&app, "retry-err").await;
    drain_once(&app, "retry-err").await;

    let status = execution_status(&app, &execution_id).await;
    assert_eq!("ERROR", status);
}

#[tokio::test]
async fn retryWithQueueFull_afterSuccessfulRetries_completesFuture() {
    let app = control_plane_rust::app::build_app();
    register_failing_pool_function(&app, "retry-after-success", 2).await;

    let execution_id = enqueue(&app, "retry-after-success").await;

    drain_once(&app, "retry-after-success").await;
    let status_after_first = execution_status(&app, &execution_id).await;
    assert_eq!("QUEUED", status_after_first);

    drain_once(&app, "retry-after-success").await;
    let final_status = execution_status(&app, &execution_id).await;
    assert_eq!("ERROR", final_status);
}
