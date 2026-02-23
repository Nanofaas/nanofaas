#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

async fn register(app: &axum::Router, payload: Value) {
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/functions")
                .header("content-type", "application/json")
                .body(Body::from(payload.to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);
}

#[tokio::test]
async fn setReplicas_returns200WithCorrectBody() {
    let app = control_plane_rust::app::build_app();
    register(
        &app,
        json!({"name":"echo","image":"img","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"}),
    )
    .await;

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/v1/functions/echo/replicas")
                .header("content-type", "application/json")
                .body(Body::from(json!({"replicas":5}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["function"], "echo");
    assert_eq!(json["replicas"], 5);
}

#[tokio::test]
async fn setReplicas_returns404WhenFunctionNotFound() {
    let app = control_plane_rust::app::build_app();

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/v1/functions/nonexistent/replicas")
                .header("content-type", "application/json")
                .body(Body::from(json!({"replicas":3}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn setReplicas_returns400WhenNotDeploymentMode() {
    let app = control_plane_rust::app::build_app();
    register(
        &app,
        json!({"name":"echo","image":"img","executionMode":"LOCAL","runtimeMode":"HTTP"}),
    )
    .await;

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/v1/functions/echo/replicas")
                .header("content-type", "application/json")
                .body(Body::from(json!({"replicas":3}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn setReplicas_allowsZeroReplicas() {
    let app = control_plane_rust::app::build_app();
    register(
        &app,
        json!({"name":"echo","image":"img","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"}),
    )
    .await;

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PUT")
                .uri("/v1/functions/echo/replicas")
                .header("content-type", "application/json")
                .body(Body::from(json!({"replicas":0}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["replicas"], 0);
}
