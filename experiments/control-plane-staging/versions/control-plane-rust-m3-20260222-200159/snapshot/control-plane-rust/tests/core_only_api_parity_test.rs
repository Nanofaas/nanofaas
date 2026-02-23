#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use control_plane_rust::service::InvocationEnqueuer;
use serde_json::json;
use tower::util::ServiceExt;

#[tokio::test]
async fn coreProfileInjectsDisabledNoOpInvocationEnqueuer() {
    let enqueuer = control_plane_rust::service::no_op_invocation_enqueuer();
    assert!(!enqueuer.enabled());
    let panicked = std::panic::catch_unwind(|| {
        let _ = enqueuer.enqueue(None);
    });
    assert!(panicked.is_err());
}

#[tokio::test]
async fn asyncEnqueueReturns501WhenAsyncQueueModuleIsNotLoaded() {
    let app = control_plane_rust::app::build_app();

    let create = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(
            json!({
                "name": "echo",
                "image": "async-unavailable",
                "executionMode": "DEPLOYMENT",
                "runtimeMode": "HTTP"
            })
            .to_string(),
        ))
        .unwrap();
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);

    let enqueue = Request::builder()
        .method("POST")
        .uri("/v1/functions/echo:enqueue")
        .header("content-type", "application/json")
        .body(Body::from(json!({"input":"payload"}).to_string()))
        .unwrap();
    let enqueue_res = app.clone().oneshot(enqueue).await.unwrap();
    assert_eq!(enqueue_res.status(), StatusCode::NOT_IMPLEMENTED);
}
