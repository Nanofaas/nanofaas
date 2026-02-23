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
                "name": "fn-a",
                "image": "localhost:5000/nanofaas/fn-a:test",
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
async fn drain_once_transitions_queued_execution_to_success() {
    let app = control_plane_rust::app::build_app();
    create_function(&app).await;

    let enqueue = Request::builder()
        .method("POST")
        .uri("/v1/functions/fn-a:enqueue")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input": {"n": 1}}).to_string()))
        .unwrap();
    let enqueue_res = app.clone().oneshot(enqueue).await.unwrap();
    assert_eq!(enqueue_res.status(), StatusCode::ACCEPTED);
    let enqueue_body = axum::body::to_bytes(enqueue_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_json: Value = serde_json::from_slice(&enqueue_body).unwrap();
    let execution_id = enqueue_json["executionId"].as_str().unwrap().to_string();

    let drain = Request::builder()
        .method("POST")
        .uri("/v1/internal/functions/fn-a:drain-once")
        .body(Body::empty())
        .unwrap();
    let drain_res = app.clone().oneshot(drain).await.unwrap();
    assert_eq!(drain_res.status(), StatusCode::OK);

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
    assert_eq!(payload["status"], "SUCCESS");
}

#[tokio::test]
async fn internal_complete_overrides_execution_status_and_output() {
    let app = control_plane_rust::app::build_app();
    create_function(&app).await;

    let enqueue = Request::builder()
        .method("POST")
        .uri("/v1/functions/fn-a:enqueue")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input": {"n": 7}}).to_string()))
        .unwrap();
    let enqueue_res = app.clone().oneshot(enqueue).await.unwrap();
    assert_eq!(enqueue_res.status(), StatusCode::ACCEPTED);
    let enqueue_body = axum::body::to_bytes(enqueue_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let enqueue_json: Value = serde_json::from_slice(&enqueue_body).unwrap();
    let execution_id = enqueue_json["executionId"].as_str().unwrap().to_string();

    let complete = Request::builder()
        .method("POST")
        .uri(format!("/v1/internal/executions/{execution_id}:complete"))
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "status": "ERROR",
                "output": { "message": "forced" }
            })
            .to_string(),
        ))
        .unwrap();
    let complete_res = app.clone().oneshot(complete).await.unwrap();
    assert_eq!(complete_res.status(), StatusCode::NO_CONTENT);

    let get_exec = Request::builder()
        .method("GET")
        .uri(format!("/v1/executions/{execution_id}"))
        .body(Body::empty())
        .unwrap();
    let get_exec_res = app.clone().oneshot(get_exec).await.unwrap();
    let body = axum::body::to_bytes(get_exec_res.into_body(), usize::MAX)
        .await
        .unwrap();
    let payload: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(payload["status"], "ERROR");
    assert_eq!(payload["output"]["message"], "forced");
}
