#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

#[tokio::test]
async fn register_withBlankName_returns400() {
    let app = control_plane_rust::app::build_app();
    let res = app.clone().oneshot(Request::builder().method("POST").uri("/v1/functions").header("content-type", "application/json").body(Body::from(json!({"name":"","image":"myimage","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"}).to_string())).unwrap()).await.unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"], "VALIDATION_ERROR");
    assert!(json["details"].is_array());
}

#[tokio::test]
async fn register_withNullImage_returns400() {
    let app = control_plane_rust::app::build_app();
    let res = app.clone().oneshot(Request::builder().method("POST").uri("/v1/functions").header("content-type", "application/json").body(Body::from(json!({"name":"myfunc","image":null,"executionMode":"DEPLOYMENT","runtimeMode":"HTTP"}).to_string())).unwrap()).await.unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"], "VALIDATION_ERROR");
}

#[tokio::test]
async fn register_withZeroConcurrency_returns400() {
    let app = control_plane_rust::app::build_app();
    let res = app.clone().oneshot(Request::builder().method("POST").uri("/v1/functions").header("content-type", "application/json").body(Body::from(json!({"name":"myfunc","image":"myimage","executionMode":"DEPLOYMENT","runtimeMode":"HTTP","concurrency":0}).to_string())).unwrap()).await.unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"], "VALIDATION_ERROR");
    let details = json["details"].as_array().cloned().unwrap_or_default();
    assert!(details
        .iter()
        .any(|d| d.as_str().unwrap_or("").contains("concurrency")));
}

#[tokio::test]
async fn register_withValidSpec_returns201() {
    let app = control_plane_rust::app::build_app();
    let res = app.clone().oneshot(Request::builder().method("POST").uri("/v1/functions").header("content-type", "application/json").body(Body::from(json!({"name":"myfunc","image":"myimage","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"}).to_string())).unwrap()).await.unwrap();
    assert_eq!(res.status(), StatusCode::CREATED);
}

#[tokio::test]
async fn invoke_withNullInput_returns400() {
    let app = control_plane_rust::app::build_app();
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/functions/myfunc:invoke")
                .header("content-type", "application/json")
                .body(Body::from(json!({"input":null}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"], "VALIDATION_ERROR");
    let details = json["details"].as_array().cloned().unwrap_or_default();
    assert!(details
        .iter()
        .any(|d| d.as_str().unwrap_or("").contains("input")));
}

#[tokio::test]
async fn invoke_withValidRequest_callsService() {
    let app = control_plane_rust::app::build_app();
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/functions/myfunc:invoke")
                .header("content-type", "application/json")
                .body(Body::from(json!({"input":"payload"}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn validationError_hasCorrectFormat() {
    let app = control_plane_rust::app::build_app();
    let res = app.clone().oneshot(Request::builder().method("POST").uri("/v1/functions").header("content-type", "application/json").body(Body::from(json!({"name":"","image":"","executionMode":"DEPLOYMENT","runtimeMode":"HTTP","concurrency":-1}).to_string())).unwrap()).await.unwrap();
    assert_eq!(res.status(), StatusCode::BAD_REQUEST);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["error"], "VALIDATION_ERROR");
    assert_eq!(json["message"], "Request validation failed");
    assert!(json["details"].is_array());
    assert!(!json["details"].as_array().unwrap_or(&Vec::new()).is_empty());
}
