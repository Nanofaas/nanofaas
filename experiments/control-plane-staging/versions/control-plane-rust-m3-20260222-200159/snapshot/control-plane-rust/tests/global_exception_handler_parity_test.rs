#![allow(non_snake_case)]

use axum::http::StatusCode;
use serde_json::Value;

async fn body_json(response: axum::response::Response) -> Value {
    let body = axum::body::to_bytes(response.into_body(), usize::MAX)
        .await
        .unwrap();
    serde_json::from_slice(&body).unwrap()
}

#[tokio::test]
async fn handleValidationErrors_returnsBadRequest() {
    let response = control_plane_rust::errors::validation_error_response(vec![
        "name: must not be blank".into(),
    ]);
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let json = body_json(response).await;
    assert_eq!(json["error"], "VALIDATION_ERROR");
    assert!(json["details"][0].as_str().unwrap().contains("name"));
}

#[tokio::test]
async fn handleWebExchangeBindException_returnsBadRequest() {
    let response = control_plane_rust::errors::validation_error_response(vec![
        "image: must not be blank".into(),
    ]);
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let json = body_json(response).await;
    assert_eq!(json["error"], "VALIDATION_ERROR");
}

#[tokio::test]
async fn handleConstraintViolation_returnsBadRequest() {
    let response = control_plane_rust::errors::constraint_violation_response(vec![(
        "invokeSync.name".into(),
        "must not be blank".into(),
    )]);
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let json = body_json(response).await;
    let details = json["details"].as_array().cloned().unwrap_or_default();
    assert!(details
        .iter()
        .any(|d| d.as_str().unwrap_or("").contains("name: must not be blank")));
}

#[tokio::test]
async fn handleServerWebInputException_returnsBadRequest() {
    let response = control_plane_rust::errors::bad_request_response(Some("Bad body"));
    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
    let json = body_json(response).await;
    assert_eq!(json["error"], "BAD_REQUEST");
}

#[tokio::test]
async fn handleResponseStatusException_returnsCorrectStatus() {
    let response = control_plane_rust::errors::response_status_error_response(
        StatusCode::NOT_FOUND,
        Some("Not found"),
    );
    assert_eq!(response.status(), StatusCode::NOT_FOUND);
    let json = body_json(response).await;
    assert!(json["message"].as_str().unwrap().contains("Not found"));
}

#[tokio::test]
async fn handleGenericException_returns500() {
    let response = control_plane_rust::errors::generic_internal_error_response();
    assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
    let json = body_json(response).await;
    assert_eq!(json["error"], "INTERNAL_ERROR");
}

#[tokio::test]
async fn handleImageValidationNotFound_returns422() {
    let response = control_plane_rust::errors::image_validation_error_response(
        control_plane_rust::errors::ImageValidationKind::NotFound,
        "ghcr.io/example/missing:v1",
    );
    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
    let json = body_json(response).await;
    assert_eq!(json["error"], "IMAGE_NOT_FOUND");
}

#[tokio::test]
async fn handleImageValidationAuth_returns424() {
    let response = control_plane_rust::errors::image_validation_error_response(
        control_plane_rust::errors::ImageValidationKind::AuthRequired,
        "ghcr.io/example/private:v1",
    );
    assert_eq!(response.status(), StatusCode::FAILED_DEPENDENCY);
    let json = body_json(response).await;
    assert_eq!(json["error"], "IMAGE_PULL_AUTH_REQUIRED");
}
