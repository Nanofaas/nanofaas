use crate::app::AppState;
use crate::runtime_config::{
    parse_request as parse_runtime_config_request,
    validation_errors as runtime_config_validation_errors, RuntimeConfigPatchResponse,
};
use axum::extract::State;
use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use chrono::Utc;
use serde_json::{json, Value};
use uuid::Uuid;

pub(crate) async fn get_runtime_config(State(state): State<AppState>) -> Response {
    if !state.runtime_config_admin_enabled {
        return StatusCode::NOT_FOUND.into_response();
    }
    let snapshot = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    Json(snapshot.to_response()).into_response()
}

pub(crate) async fn validate_runtime_config(
    State(state): State<AppState>,
    Json(request): Json<Value>,
) -> Response {
    if !state.runtime_config_admin_enabled {
        return StatusCode::NOT_FOUND.into_response();
    }
    let parsed = match parse_runtime_config_request(request) {
        Ok(parsed) => parsed,
        Err(err) => {
            return (StatusCode::BAD_REQUEST, Json(json!({ "error": err }))).into_response();
        }
    };
    let current = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let effective = match current.apply_patch(&parsed.patch) {
        Ok(effective) => effective,
        Err(errors) => {
            return (
                StatusCode::UNPROCESSABLE_ENTITY,
                Json(json!({ "errors": errors })),
            )
                .into_response();
        }
    };
    let errors = runtime_config_validation_errors(&effective);
    if errors.is_empty() {
        return Json(json!({ "valid": true })).into_response();
    }
    (
        StatusCode::UNPROCESSABLE_ENTITY,
        Json(json!({ "errors": errors })),
    )
        .into_response()
}

pub(crate) async fn patch_runtime_config(
    State(state): State<AppState>,
    Json(request): Json<Value>,
) -> Response {
    if !state.runtime_config_admin_enabled {
        return StatusCode::NOT_FOUND.into_response();
    }
    let parsed = match parse_runtime_config_request(request) {
        Ok(parsed) => parsed,
        Err(err) => {
            return (StatusCode::BAD_REQUEST, Json(json!({ "error": err }))).into_response();
        }
    };
    let Some(expected_revision) = parsed.expected_revision else {
        return (
            StatusCode::BAD_REQUEST,
            Json(json!({ "error": "expectedRevision is required" })),
        )
            .into_response();
    };

    let current = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let next = match current.apply_patch(&parsed.patch) {
        Ok(next) => next,
        Err(errors) => {
            return (
                StatusCode::UNPROCESSABLE_ENTITY,
                Json(json!({ "errors": errors })),
            )
                .into_response();
        }
    };
    let errors = runtime_config_validation_errors(&next);
    if !errors.is_empty() {
        return (
            StatusCode::UNPROCESSABLE_ENTITY,
            Json(json!({ "errors": errors })),
        )
            .into_response();
    }

    let mut runtime_config = state
        .runtime_config_state
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if runtime_config.revision != expected_revision {
        return (
            StatusCode::CONFLICT,
            Json(json!({
                "error": format!(
                    "Revision mismatch: expected {}, actual {}",
                    expected_revision, runtime_config.revision
                ),
                "currentRevision": runtime_config.revision
            })),
        )
            .into_response();
    }

    *runtime_config = next.clone();
    let revision = runtime_config.revision;
    drop(runtime_config);

    state
        .rate_limiter
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .set_capacity_per_second(next.rate_max_per_second);

    let effective_config = next.to_response();
    Json(RuntimeConfigPatchResponse {
        revision,
        effective_config,
        applied_at: Utc::now().to_rfc3339(),
        change_id: Uuid::new_v4().to_string(),
        warnings: vec![],
    })
    .into_response()
}
