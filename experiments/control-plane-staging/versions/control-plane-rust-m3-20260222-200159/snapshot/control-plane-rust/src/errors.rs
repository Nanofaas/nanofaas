use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde_json::json;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImageValidationKind {
    NotFound,
    AuthRequired,
}

pub fn validation_error_response(details: Vec<String>) -> Response {
    (
        StatusCode::BAD_REQUEST,
        Json(json!({
            "error": "VALIDATION_ERROR",
            "message": "Request validation failed",
            "details": details
        })),
    )
        .into_response()
}

pub fn constraint_violation_response(violations: Vec<(String, String)>) -> Response {
    let details = violations
        .into_iter()
        .map(|(path, message)| {
            let name = path
                .rsplit_once('.')
                .map(|(_, tail)| tail.to_string())
                .unwrap_or(path);
            format!("{name}: {message}")
        })
        .collect::<Vec<_>>();
    validation_error_response(details)
}

pub fn bad_request_response(reason: Option<&str>) -> Response {
    (
        StatusCode::BAD_REQUEST,
        Json(json!({
            "error": "BAD_REQUEST",
            "message": reason.unwrap_or("Invalid request")
        })),
    )
        .into_response()
}

pub fn response_status_error_response(status: StatusCode, reason: Option<&str>) -> Response {
    (
        status,
        Json(json!({
            "error": status.to_string(),
            "message": reason.unwrap_or("Request error")
        })),
    )
        .into_response()
}

pub fn generic_internal_error_response() -> Response {
    (
        StatusCode::INTERNAL_SERVER_ERROR,
        Json(json!({
            "error": "INTERNAL_ERROR",
            "message": "An unexpected error occurred"
        })),
    )
        .into_response()
}

pub fn image_validation_error_response(kind: ImageValidationKind, image: &str) -> Response {
    match kind {
        ImageValidationKind::NotFound => (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({
                "error": "IMAGE_NOT_FOUND",
                "message": format!("Image not found: {image}")
            })),
        )
            .into_response(),
        ImageValidationKind::AuthRequired => (
            StatusCode::FAILED_DEPENDENCY,
            Json(json!({
                "error": "IMAGE_PULL_AUTH_REQUIRED",
                "message": format!("Registry authentication required for image: {image}")
            })),
        )
            .into_response(),
    }
}
