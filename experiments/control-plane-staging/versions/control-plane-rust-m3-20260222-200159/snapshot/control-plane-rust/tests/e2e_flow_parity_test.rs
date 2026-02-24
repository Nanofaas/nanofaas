#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tokio::time::{sleep, Duration};
use tower::util::ServiceExt;

async fn register_pool_function(app: &axum::Router, function_name: &str) {
    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": function_name,
                "image": "nanofaas/function-runtime:test",
                "executionMode": "POOL",
                "runtimeMode": "HTTP"
            })
            .to_string(),
        ))
        .unwrap();
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);
}

#[tokio::test]
async fn e2eRegisterInvokeAndPoll() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "e2e-echo").await;

    let invoke = Request::builder()
        .method("POST")
        .uri("/v1/functions/e2e-echo:invoke")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input": {"message": "hi"}}).to_string()))
        .unwrap();
    let invoke_res = app.clone().oneshot(invoke).await.unwrap();
    assert_eq!(invoke_res.status(), StatusCode::OK);
    let invoke_body = axum::body::to_bytes(invoke_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let invoke_json: Value = serde_json::from_slice(&invoke_body).unwrap();
    assert_eq!(invoke_json["status"], "success");
    assert_eq!(invoke_json["output"]["message"], "hi");

    let enqueue = Request::builder()
        .method("POST")
        .uri("/v1/functions/e2e-echo:enqueue")
        .header("content-type", "application/json")
        .header("Idempotency-Key", "abc")
        .body(Body::from(json!({"input": "payload"}).to_string()))
        .unwrap();
    let enqueue_res = app.clone().oneshot(enqueue).await.unwrap();
    assert_eq!(enqueue_res.status(), StatusCode::ACCEPTED);
    let enqueue_body = axum::body::to_bytes(enqueue_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_json: Value = serde_json::from_slice(&enqueue_body).unwrap();
    let execution_id = enqueue_json["executionId"].as_str().unwrap().to_string();

    let enqueue_again = Request::builder()
        .method("POST")
        .uri("/v1/functions/e2e-echo:enqueue")
        .header("content-type", "application/json")
        .header("Idempotency-Key", "abc")
        .body(Body::from(json!({"input": "payload"}).to_string()))
        .unwrap();
    let enqueue_again_res = app.clone().oneshot(enqueue_again).await.unwrap();
    assert_eq!(enqueue_again_res.status(), StatusCode::ACCEPTED);
    let enqueue_again_body = axum::body::to_bytes(enqueue_again_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_again_json: Value = serde_json::from_slice(&enqueue_again_body).unwrap();
    assert_eq!(enqueue_again_json["executionId"], execution_id);

    let drain = Request::builder()
        .method("POST")
        .uri("/v1/internal/functions/e2e-echo:drain-once")
        .body(Body::empty())
        .unwrap();
    let drain_res = app.clone().oneshot(drain).await.unwrap();
    assert_eq!(drain_res.status(), StatusCode::OK);

    let mut final_status = String::new();
    for _ in 0..10 {
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
        final_status = payload["status"].as_str().unwrap_or_default().to_string();
        if final_status == "success" {
            break;
        }
        sleep(Duration::from_millis(10)).await;
    }
    assert_eq!(final_status, "success");
}

#[tokio::test]
async fn e2ePrometheusMetricsExposed() {
    let app = control_plane_rust::app::build_app();
    register_pool_function(&app, "metrics-e2e").await;

    let enqueue = Request::builder()
        .method("POST")
        .uri("/v1/functions/metrics-e2e:enqueue")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input": "payload"}).to_string()))
        .unwrap();
    let enqueue_res = app.clone().oneshot(enqueue).await.unwrap();
    assert_eq!(enqueue_res.status(), StatusCode::ACCEPTED);

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
    assert!(text.contains("function_enqueue_total{function=\"metrics-e2e\"}"));
}
