#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

async fn register_function(app: &axum::Router, name: &str, image: &str) {
    let req = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": name,
                "image": image,
                "executionMode": "DEPLOYMENT",
                "runtimeMode": "HTTP"
            })
            .to_string(),
        ))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);
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

async fn invoke_async(app: &axum::Router, function_name: &str) -> axum::http::Response<Body> {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/v1/functions/{function_name}:enqueue"))
                .header("content-type", "application/json")
                .header("Idempotency-Key", "idem-1")
                .header("X-Trace-Id", "trace-1")
                .body(Body::from(json!({"input":"payload"}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}

#[tokio::test]
async fn invokeSync_success_returnsExecutionHeaderAndBody() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "img-ok").await;

    let res = invoke_sync(&app, "echo").await;
    assert_eq!(res.status(), StatusCode::OK);
    let header_exec = res
        .headers()
        .get("X-Execution-Id")
        .and_then(|v| v.to_str().ok())
        .map(|v| v.to_string());
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(header_exec.as_deref(), payload["executionId"].as_str());
    assert_eq!(payload["status"], "success");
}

#[tokio::test]
async fn invokeSync_syncQueueRejectedFromMono_mapsTo429WithHeaders() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "sync-reject-est-wait").await;

    let res = invoke_sync(&app, "echo").await;
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
async fn invokeSync_syncQueueRejectedThrownSynchronously_mapsTo429WithHeaders() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "sync-reject-depth").await;

    let res = invoke_sync(&app, "echo").await;
    assert_eq!(res.status(), StatusCode::TOO_MANY_REQUESTS);
    assert_eq!(
        res.headers()
            .get("Retry-After")
            .and_then(|v| v.to_str().ok()),
        Some("3")
    );
    assert_eq!(
        res.headers()
            .get("X-Queue-Reject-Reason")
            .and_then(|v| v.to_str().ok()),
        Some("depth")
    );
}

#[tokio::test]
async fn invokeSync_rateLimited_returns429() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "rate-limited").await;

    let res = invoke_sync(&app, "echo").await;
    assert_eq!(res.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn invokeSync_queueFull_returns429() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "queue-full").await;

    let res = invoke_sync(&app, "echo").await;
    assert_eq!(res.status(), StatusCode::TOO_MANY_REQUESTS);
}

#[tokio::test]
async fn invokeAsync_success_returns202AndDelegatesHeaders() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "img-ok").await;

    let res = invoke_async(&app, "echo").await;
    assert_eq!(res.status(), StatusCode::ACCEPTED);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    assert!(payload["executionId"].as_str().is_some());
    assert_eq!(payload["status"], "queued");
}

#[tokio::test]
async fn invokeAsync_functionNotFound_returns404() {
    let app = control_plane_rust::app::build_app();
    let res = invoke_async(&app, "missing").await;
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn invokeAsync_whenAsyncQueueUnavailable_returns501() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "async-unavailable").await;

    let res = invoke_async(&app, "echo").await;
    assert_eq!(res.status(), StatusCode::NOT_IMPLEMENTED);
}

#[tokio::test]
async fn getExecution_notFound_returns404() {
    let app = control_plane_rust::app::build_app();
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/v1/executions/exec-missing")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn getExecution_found_returns200() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "img-ok").await;

    let enqueue = invoke_async(&app, "echo").await;
    assert_eq!(enqueue.status(), StatusCode::ACCEPTED);
    let enqueue_body = axum::body::to_bytes(enqueue.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_json: Value = serde_json::from_slice(&enqueue_body).unwrap();
    let execution_id = enqueue_json["executionId"].as_str().unwrap();

    let get_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/v1/executions/{execution_id}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(get_res.status(), StatusCode::OK);
    let get_body = axum::body::to_bytes(get_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&get_body).unwrap();
    assert_eq!(payload["executionId"], execution_id);
    assert_eq!(payload["status"], "queued");
}

#[tokio::test]
async fn completeExecution_returns204AndCallsService() {
    let app = control_plane_rust::app::build_app();
    register_function(&app, "echo", "img-ok").await;

    let enqueue = invoke_async(&app, "echo").await;
    let enqueue_body = axum::body::to_bytes(enqueue.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_json: Value = serde_json::from_slice(&enqueue_body).unwrap();
    let execution_id = enqueue_json["executionId"].as_str().unwrap().to_string();

    let complete_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/v1/internal/executions/{execution_id}:complete"))
                .header("content-type", "application/json")
                .body(Body::from(
                    json!({
                        "status": "success",
                        "output": "ok"
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(complete_res.status(), StatusCode::NO_CONTENT);

    let get_res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri(format!("/v1/executions/{execution_id}"))
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    let get_body = axum::body::to_bytes(get_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&get_body).unwrap();
    assert_eq!(payload["status"], "success");
    assert_eq!(payload["output"], "ok");
}
