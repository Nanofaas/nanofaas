use axum::body::{to_bytes, Body};
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

async fn response_json(response: axum::response::Response) -> Value {
    let bytes = to_bytes(response.into_body(), usize::MAX).await.unwrap();
    serde_json::from_slice(&bytes).unwrap()
}

#[tokio::test]
async fn runtime_config_endpoint_is_disabled_by_default() {
    let app = control_plane_rust::app::build_app();

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/v1/admin/runtime-config")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn runtime_config_get_returns_rate_and_revision_when_enabled() {
    let app = control_plane_rust::app::build_app_with_runtime_config_admin(true);

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/v1/admin/runtime-config")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response_json(response).await;
    assert_eq!(body["revision"], 0);
    assert_eq!(body["rateMaxPerSecond"], 1_000_000);
}

#[tokio::test]
async fn runtime_config_patch_updates_rate_limit() {
    let app = control_plane_rust::app::build_app_with_runtime_config_admin(true);

    let patch_response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri("/v1/admin/runtime-config")
                .header("content-type", "application/json")
                .body(Body::from(
                    json!({
                        "expectedRevision": 0,
                        "rateMaxPerSecond": 7
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(patch_response.status(), StatusCode::OK);
    let patched = response_json(patch_response).await;
    assert_eq!(patched["revision"], 1);
    assert_eq!(patched["effectiveConfig"]["rateMaxPerSecond"], 7);

    let get_response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/v1/admin/runtime-config")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(get_response.status(), StatusCode::OK);
    let snapshot = response_json(get_response).await;
    assert_eq!(snapshot["revision"], 1);
    assert_eq!(snapshot["rateMaxPerSecond"], 7);
}

#[tokio::test]
async fn runtime_config_patch_requires_expected_revision() {
    let app = control_plane_rust::app::build_app_with_runtime_config_admin(true);

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri("/v1/admin/runtime-config")
                .header("content-type", "application/json")
                .body(Body::from(json!({"rateMaxPerSecond": 5}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::BAD_REQUEST);
}

#[tokio::test]
async fn runtime_config_patch_rejects_invalid_rate() {
    let app = control_plane_rust::app::build_app_with_runtime_config_admin(true);

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri("/v1/admin/runtime-config")
                .header("content-type", "application/json")
                .body(Body::from(
                    json!({
                        "expectedRevision": 0,
                        "rateMaxPerSecond": 0
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::UNPROCESSABLE_ENTITY);
}

#[tokio::test]
async fn runtime_config_patch_returns_conflict_on_revision_mismatch() {
    let app = control_plane_rust::app::build_app_with_runtime_config_admin(true);

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("PATCH")
                .uri("/v1/admin/runtime-config")
                .header("content-type", "application/json")
                .body(Body::from(
                    json!({
                        "expectedRevision": 99,
                        "rateMaxPerSecond": 5
                    })
                    .to_string(),
                ))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::CONFLICT);
}

#[tokio::test]
async fn runtime_config_validate_accepts_valid_patch() {
    let app = control_plane_rust::app::build_app_with_runtime_config_admin(true);

    let response = app
        .clone()
        .oneshot(
            Request::builder()
                .method("POST")
                .uri("/v1/admin/runtime-config/validate")
                .header("content-type", "application/json")
                .body(Body::from(json!({"rateMaxPerSecond": 321}).to_string()))
                .unwrap(),
        )
        .await
        .unwrap();

    assert_eq!(response.status(), StatusCode::OK);
    let body = response_json(response).await;
    assert_eq!(body["valid"], true);
}
