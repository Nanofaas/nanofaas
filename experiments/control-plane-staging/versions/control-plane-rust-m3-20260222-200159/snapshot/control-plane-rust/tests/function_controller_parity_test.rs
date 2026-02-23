#![allow(non_snake_case)]

use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::{json, Value};
use tower::util::ServiceExt;

async fn register(app: &axum::Router, payload: Value) -> axum::http::Response<Body> {
    let req = Request::builder()
        .method("POST")
        .uri("/v1/functions")
        .header("content-type", "application/json")
        .body(Body::from(payload.to_string()))
        .unwrap();
    app.clone().oneshot(req).await.unwrap()
}

#[tokio::test]
async fn list_returnsRegisteredFunctions() {
    let app = control_plane_rust::app::build_app();

    assert_eq!(
        register(
            &app,
            json!({"name":"echo","image":"img1","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"})
        )
        .await
        .status(),
        StatusCode::CREATED
    );
    assert_eq!(
        register(
            &app,
            json!({"name":"sum","image":"img2","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"})
        )
        .await
        .status(),
        StatusCode::CREATED
    );

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/v1/functions")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::OK);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let arr: Vec<Value> = serde_json::from_slice(&body).unwrap();
    let names: Vec<String> = arr
        .iter()
        .map(|v| v["name"].as_str().unwrap().to_string())
        .collect();
    assert!(names.contains(&"echo".to_string()));
    assert!(names.contains(&"sum".to_string()));
}

#[tokio::test]
async fn register_conflict_returns409() {
    let app = control_plane_rust::app::build_app();
    assert_eq!(
        register(
            &app,
            json!({"name":"echo","image":"img1","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"})
        )
        .await
        .status(),
        StatusCode::CREATED
    );

    let conflict = register(
        &app,
        json!({"name":"echo","image":"img1","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"}),
    )
    .await;
    assert_eq!(conflict.status(), StatusCode::CONFLICT);
}

#[tokio::test]
async fn get_missingFunction_returns404() {
    let app = control_plane_rust::app::build_app();
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("GET")
                .uri("/v1/functions/missing")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn delete_missingFunction_returns404() {
    let app = control_plane_rust::app::build_app();
    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri("/v1/functions/missing")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn delete_existingFunction_returns204() {
    let app = control_plane_rust::app::build_app();
    assert_eq!(
        register(
            &app,
            json!({"name":"echo","image":"img1","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"})
        )
        .await
        .status(),
        StatusCode::CREATED
    );

    let res = app
        .clone()
        .oneshot(
            Request::builder()
                .method("DELETE")
                .uri("/v1/functions/echo")
                .body(Body::empty())
                .unwrap(),
        )
        .await
        .unwrap();
    assert_eq!(res.status(), StatusCode::NO_CONTENT);
}

#[tokio::test]
async fn setReplicas_illegalState_returns503WithMessage() {
    let app = control_plane_rust::app::build_app();
    assert_eq!(register(&app, json!({"name":"echo","image":"scaler-unavailable","executionMode":"DEPLOYMENT","runtimeMode":"HTTP"})).await.status(), StatusCode::CREATED);

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
    assert_eq!(res.status(), StatusCode::SERVICE_UNAVAILABLE);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    assert_eq!(
        String::from_utf8(body.to_vec()).unwrap(),
        "Scaler unavailable"
    );
}

#[tokio::test]
async fn register_withConcurrencyControl_returnsResolvedSpec() {
    let app = control_plane_rust::app::build_app();

    let payload = json!({
      "name": "echo",
      "image": "ghcr.io/example/echo:v2",
      "executionMode": "DEPLOYMENT",
      "runtimeMode": "HTTP",
      "concurrency": 12,
      "queueSize": 10,
      "maxRetries": 3,
      "scalingConfig": {
        "strategy": "INTERNAL",
        "concurrencyControl": {
          "mode": "ADAPTIVE_PER_POD",
          "targetInFlightPerPod": 3,
          "minTargetInFlightPerPod": 1,
          "maxTargetInFlightPerPod": 6
        }
      }
    });

    let res = register(&app, payload).await;
    assert_eq!(res.status(), StatusCode::CREATED);
    let body = axum::body::to_bytes(res.into_body(), usize::MAX)
        .await
        .unwrap();
    let json: Value = serde_json::from_slice(&body).unwrap();
    assert_eq!(json["name"], "echo");
    assert_eq!(
        json["scalingConfig"]["concurrencyControl"]["mode"],
        "ADAPTIVE_PER_POD"
    );
    assert_eq!(
        json["scalingConfig"]["concurrencyControl"]["targetInFlightPerPod"],
        3
    );
    assert_eq!(
        json["scalingConfig"]["concurrencyControl"]["minTargetInFlightPerPod"],
        1
    );
    assert_eq!(
        json["scalingConfig"]["concurrencyControl"]["maxTargetInFlightPerPod"],
        6
    );
}
