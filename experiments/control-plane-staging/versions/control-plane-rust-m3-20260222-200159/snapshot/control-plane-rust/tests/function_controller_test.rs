use axum::body::Body;
use axum::http::{Request, StatusCode};
use serde_json::json;
use tower::util::ServiceExt;

#[tokio::test]
async fn function_controller_crud_roundtrip() {
    let app = control_plane_rust::app::build_app();

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
    let create_res = app.clone().oneshot(create).await.unwrap();
    assert_eq!(create_res.status(), StatusCode::CREATED);

    let list = Request::builder()
        .method("GET")
        .uri("/v1/functions")
        .body(Body::empty())
        .unwrap();
    let list_res = app.clone().oneshot(list).await.unwrap();
    assert_eq!(list_res.status(), StatusCode::OK);

    let get = Request::builder()
        .method("GET")
        .uri("/v1/functions/word-stats-java")
        .body(Body::empty())
        .unwrap();
    let get_res = app.clone().oneshot(get).await.unwrap();
    assert_eq!(get_res.status(), StatusCode::OK);

    let del = Request::builder()
        .method("DELETE")
        .uri("/v1/functions/word-stats-java")
        .body(Body::empty())
        .unwrap();
    let del_res = app.clone().oneshot(del).await.unwrap();
    assert_eq!(del_res.status(), StatusCode::NO_CONTENT);
}
