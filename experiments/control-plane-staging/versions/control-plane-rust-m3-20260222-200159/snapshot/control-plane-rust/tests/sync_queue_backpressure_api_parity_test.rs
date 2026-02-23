#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::json;
use tower::util::ServiceExt;

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
