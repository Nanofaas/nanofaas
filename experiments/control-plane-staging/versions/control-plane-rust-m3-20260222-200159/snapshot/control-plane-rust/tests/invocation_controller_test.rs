use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

async fn create_function(app: &axum::Router) {
    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": "word-stats-java",
                "image": "localhost:5000/nanofaas/java-word-stats:test",
                "executionMode": "DEPLOYMENT",
                "runtimeMode": "HTTP"
            })
            .to_string(),
        ))
        .unwrap();
    let res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);
}

#[tokio::test]
async fn invoke_and_enqueue_and_execution_lookup() {
    let app = control_plane_rust::app::build_app();
    create_function(&app).await;

    let invoke = Request::builder()
        .method("POST")
        .uri("/v1/functions/word-stats-java:invoke")
        .header("content-type", "application/json")
        .header("Idempotency-Key", "idem-1")
        .body(Body::from(json!({"input": {"text": "hello"}}).to_string()))
        .unwrap();
    let invoke_res = app.clone().oneshot(invoke).await.unwrap();
    assert_eq!(invoke_res.status(), StatusCode::OK);
    let invoke_body = axum::body::to_bytes(invoke_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let invoke_json: Value = serde_json::from_slice(&invoke_body).unwrap();
    let first_id = invoke_json["executionId"].as_str().unwrap().to_string();
    assert_eq!(invoke_json["status"], "success");

    let invoke_again = Request::builder()
        .method("POST")
        .uri("/v1/functions/word-stats-java:invoke")
        .header("content-type", "application/json")
        .header("Idempotency-Key", "idem-1")
        .body(Body::from(json!({"input": {"text": "hello"}}).to_string()))
        .unwrap();
    let invoke_again_res = app.clone().oneshot(invoke_again).await.unwrap();
    assert_eq!(invoke_again_res.status(), StatusCode::OK);
    let invoke_again_body = axum::body::to_bytes(invoke_again_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let invoke_again_json: Value = serde_json::from_slice(&invoke_again_body).unwrap();
    assert_eq!(invoke_again_json["executionId"].as_str().unwrap(), first_id);

    let enqueue = Request::builder()
        .method("POST")
        .uri("/v1/functions/word-stats-java:enqueue")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input": {"text": "queued"}}).to_string()))
        .unwrap();
    let enqueue_res = app.clone().oneshot(enqueue).await.unwrap();
    assert_eq!(enqueue_res.status(), StatusCode::ACCEPTED);
    let enqueue_body = axum::body::to_bytes(enqueue_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_json: Value = serde_json::from_slice(&enqueue_body).unwrap();
    let enq_id = enqueue_json["executionId"].as_str().unwrap();

    let get_exec = Request::builder()
        .method("GET")
        .uri(format!("/v1/executions/{enq_id}"))
        .body(Body::empty())
        .unwrap();
    let get_exec_res = app.clone().oneshot(get_exec).await.unwrap();
    assert_eq!(get_exec_res.status(), StatusCode::OK);
}
