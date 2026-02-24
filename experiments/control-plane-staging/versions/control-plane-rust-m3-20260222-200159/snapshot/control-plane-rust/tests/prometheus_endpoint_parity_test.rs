#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::json;
use tower::util::ServiceExt;

#[tokio::test]
async fn actuatorPrometheus_exposesFunctionCountersAndLatencyTimer() {
    let app = control_plane_rust::app::build_app();

    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": "echo",
                "image": "local",
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

    assert!(text.contains("function_dispatch_total{function=\"echo\"}"));
    assert!(text.contains("function_latency_ms_count{function=\"echo\"}"));
    assert!(text.contains("function_queue_wait_ms_count{function=\"echo\"}"));
    assert!(text.contains("function_e2e_latency_ms_count{function=\"echo\"}"));
}
