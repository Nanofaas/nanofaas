#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

async fn register(app: &axum::Router, name: &str, image: &str) {
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

async fn invoke(
    app: &axum::Router,
    function_name: &str,
    payload: Value,
) -> axum::http::Response<Body> {
    app.clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri(format!("/v1/functions/{function_name}:invoke"))
                .header("content-type", "application/json")
                .body(Body::from(json!({"input": payload}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap()
}

#[tokio::test]
async fn issue006_rateLimitAndRouting() {
    let app = control_plane_rust::app::build_app();
    register(&app, "echo-rate-limited", "rate-limited").await;
    register(&app, "echo-ok", "local").await;

    let limited = invoke(&app, "echo-rate-limited", json!("one")).await;
    assert_eq!(limited.status(), StatusCode::TOO_MANY_REQUESTS);

    let ok = invoke(&app, "echo-ok", json!("two")).await;
    assert_eq!(ok.status(), StatusCode::OK);
}

#[tokio::test]
async fn issue007_idempotencyReturnsSameExecutionId() {
    let app = control_plane_rust::app::build_app();
    register(&app, "echo", "local").await;

    let req1 = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo:invoke")
        .header("content-type", "application/json")
        .header("Idempotency-Key", "abc")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let res1 = app.clone().oneshot(req1).await.unwrap();
    assert_eq!(res1.status(), StatusCode::OK);
    let body1 = axum::body::to_bytes(res1.into_body(), usize::MAX)
        .await
        .unwrap();
    let json1: Value = serde_json::from_slice(&body1).unwrap();

    let req2 = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo:invoke")
        .header("content-type", "application/json")
        .header("Idempotency-Key", "abc")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let res2 = app.clone().oneshot(req2).await.unwrap();
    assert_eq!(res2.status(), StatusCode::OK);
    let body2 = axum::body::to_bytes(res2.into_body(), usize::MAX)
        .await
        .unwrap();
    let json2: Value = serde_json::from_slice(&body2).unwrap();

    assert_eq!(json1["executionId"], json2["executionId"]);
}

#[tokio::test]
async fn issue009_schedulerCompletesLocalInvocation() {
    let app = control_plane_rust::app::build_app();
    register(&app, "echo", "local").await;

    let invoke_res = invoke(&app, "echo", json!("payload")).await;
    assert_eq!(invoke_res.status(), StatusCode::OK);
    let invoke_body = axum::body::to_bytes(invoke_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let invoke_json: Value = serde_json::from_slice(&invoke_body).unwrap();
    let execution_id = invoke_json["executionId"].as_str().unwrap();

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
    let get_json: Value = serde_json::from_slice(&get_body).unwrap();
    assert_eq!(get_json["status"], "success");
}

#[tokio::test]
async fn issue013_syncWaitReturnsOutput() {
    let app = control_plane_rust::app::build_app();
    register(&app, "echo", "local").await;

    let res = invoke(&app, "echo", json!("payload")).await;
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["status"], "success");
    assert_eq!(json["output"], "payload");
}

#[tokio::test]
async fn issue017_prometheusMetricsExposed() {
    let app = control_plane_rust::app::build_app();
    register(&app, "echo", "local").await;

    let res = invoke(&app, "echo", json!("payload")).await;
    assert_eq!(res.status(), StatusCode::OK);

    let scrape = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/actuator/prometheus")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(scrape.status(), StatusCode::OK);
    let body = axum::body::to_bytes(scrape.into_body(), usize::MAX)
        .await
        .unwrap();
    let text = String::from_utf8(body.to_vec()).unwrap();
    assert!(text.contains("function_dispatch_total{function=\"echo\"}"));
}

#[tokio::test]
async fn issue018_healthEndpointIsUp() {
    let app = control_plane_rust::app::build_app();
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/actuator/health")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["status"], "UP");
}
