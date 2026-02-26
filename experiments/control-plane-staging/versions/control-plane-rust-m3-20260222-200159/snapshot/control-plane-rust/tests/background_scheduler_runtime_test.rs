use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use std::io::{Read, Write};
use std::net::TcpListener;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;
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

fn delayed_json_runtime(
    body: &str,
    delay: Duration,
    max_requests: usize,
) -> (String, Arc<AtomicUsize>) {
    let listener = TcpListener::bind("127.0.0.1:0").expect("bind delayed runtime");
    let addr = listener.local_addr().expect("runtime local addr");
    let response_body = body.to_string();
    let in_flight = Arc::new(AtomicUsize::new(0));
    let max_in_flight = Arc::new(AtomicUsize::new(0));
    let in_flight_for_loop = Arc::clone(&in_flight);
    let max_for_loop = Arc::clone(&max_in_flight);
    thread::spawn(move || {
        for _ in 0..max_requests {
            let accepted = listener.accept();
            let Ok((mut socket, _)) = accepted else {
                break;
            };
            let body = response_body.clone();
            let in_flight = Arc::clone(&in_flight_for_loop);
            let max_in_flight = Arc::clone(&max_for_loop);
            thread::spawn(move || {
                let current = in_flight.fetch_add(1, Ordering::SeqCst) + 1;
                loop {
                    let observed = max_in_flight.load(Ordering::SeqCst);
                    if current <= observed {
                        break;
                    }
                    if max_in_flight
                        .compare_exchange(observed, current, Ordering::SeqCst, Ordering::SeqCst)
                        .is_ok()
                    {
                        break;
                    }
                }

                let mut buf = [0u8; 2048];
                let _ = socket.read(&mut buf);
                thread::sleep(delay);
                let reply = format!(
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    body.len(),
                    body
                );
                let _ = socket.write_all(reply.as_bytes());
                let _ = socket.flush();
                in_flight.fetch_sub(1, Ordering::SeqCst);
            });
        }
    });

    (
        format!("http://127.0.0.1:{}/invoke", addr.port()),
        max_in_flight,
    )
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

#[tokio::test]
async fn background_scheduler_dispatches_multiple_tasks_concurrently() {
    let (app, _mgmt) = control_plane_rust::app::build_app_pair_with_background_scheduler();
    let (endpoint, max_in_flight) =
        delayed_json_runtime(r#"{"message":"ok"}"#, Duration::from_millis(600), 24);
    register_pool_function(&app, "bg-concurrency", &endpoint).await;

    let mut execution_ids = Vec::new();
    for _ in 0..8 {
        execution_ids.push(enqueue(&app, "bg-concurrency").await);
    }

    for execution_id in &execution_ids {
        for _ in 0..80 {
            let payload = execution_payload(&app, execution_id).await;
            let status = payload["status"].as_str().unwrap_or_default();
            if status == "success" {
                break;
            }
            if status == "error" || status == "timeout" {
                panic!("execution reached terminal non-success status: {payload}");
            }
            tokio::time::sleep(Duration::from_millis(100)).await;
        }
    }

    assert!(
        max_in_flight.load(Ordering::SeqCst) >= 2,
        "scheduler dispatched requests sequentially (max in-flight = {})",
        max_in_flight.load(Ordering::SeqCst)
    );
}
