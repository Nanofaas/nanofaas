use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::thread;
use std::time::Duration;
use tower::util::ServiceExt;

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

async fn register_pool_function(app: &axum::Router, name: &str, endpoint_url: &str) {
    let payload = json!({
        "name": name,
        "image": "img-ok",
        "executionMode": "DEPLOYMENT",
        "runtimeMode": "HTTP",
        "endpointUrl": endpoint_url,
        "timeoutMs": 5000,
        "concurrency": 2,
        "queueSize": 20,
        "maxRetries": 1
    });
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
        .body(Body::from(json!({"input":{"message":"hello"}}).to_string()))
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::ACCEPTED);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    payload["executionId"].as_str().unwrap().to_string()
}

async fn execution_payload(app: &axum::Router, execution_id: &str) -> Value {
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
    serde_json::from_slice(&body).unwrap()
}

async fn prometheus_text(app: &axum::Router) -> String {
    let req = Request::builder()
        .method("GET")
        .uri("/actuator/prometheus")
        .body(Body::empty())
        .unwrap();
    let res = app.clone().oneshot(req).await.unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    String::from_utf8(body.to_vec()).unwrap()
}

#[tokio::test]
async fn background_scheduler_drains_async_queue_without_manual_drain_once() {
    let (app, _mgmt) = control_plane_rust::app::build_app_pair_with_background_scheduler();
    let endpoint = one_shot_json_runtime(r#"{"message":"ok"}"#);
    register_pool_function(&app, "bg-echo", &endpoint).await;
    let execution_id = enqueue(&app, "bg-echo").await;

    for _ in 0..40 {
        let payload = execution_payload(&app, &execution_id).await;
        let status = payload["status"].as_str().unwrap_or_default();
        if status == "success" {
            assert_eq!(payload["output"]["message"], "ok");
            return;
        }
        if status == "error" || status == "timeout" {
            panic!("execution reached terminal non-success status: {payload}");
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }

    let payload = execution_payload(&app, &execution_id).await;
    panic!("execution never completed to success: {payload}")
}

#[tokio::test]
async fn background_scheduler_records_dispatch_and_completion_metrics() {
    let (app, _mgmt) = control_plane_rust::app::build_app_pair_with_background_scheduler();
    let endpoint = one_shot_json_runtime(r#"{"message":"ok"}"#);
    register_pool_function(&app, "bg-metrics", &endpoint).await;
    let execution_id = enqueue(&app, "bg-metrics").await;

    for _ in 0..40 {
        let payload = execution_payload(&app, &execution_id).await;
        let status = payload["status"].as_str().unwrap_or_default();
        if status == "success" {
            let metrics = prometheus_text(&app).await;
            assert!(metrics.contains("function_dispatch_total{function=\"bg-metrics\"}"));
            assert!(metrics.contains("function_success_total{function=\"bg-metrics\"}"));
            assert!(metrics.contains("function_latency_ms_count{function=\"bg-metrics\"}"));
            assert!(metrics.contains("function_queue_wait_ms_count{function=\"bg-metrics\"}"));
            assert!(metrics.contains("function_e2e_latency_ms_count{function=\"bg-metrics\"}"));
            return;
        }
        if status == "error" || status == "timeout" {
            panic!("execution reached terminal non-success status: {payload}");
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }

    let payload = execution_payload(&app, &execution_id).await;
    panic!("execution never completed to success: {payload}");
}
