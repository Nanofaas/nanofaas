use control_plane_rust::app::build_app_pair;
use std::net::SocketAddr;

#[tokio::main]
async fn main() {
    let api_bind = std::env::var("CONTROL_PLANE_BIND")
        .or_else(|_| std::env::var("BIND_ADDR"))
        .unwrap_or_else(|_| "0.0.0.0:8080".to_string());
    let mgmt_bind = std::env::var("MANAGEMENT_BIND")
        .unwrap_or_else(|_| "0.0.0.0:8081".to_string());

    let api_addr: SocketAddr = api_bind
        .parse()
        .unwrap_or_else(|_| "0.0.0.0:8080".parse().expect("default api addr"));
    let mgmt_addr: SocketAddr = mgmt_bind
        .parse()
        .unwrap_or_else(|_| "0.0.0.0:8081".parse().expect("default management addr"));

    let (api_app, mgmt_app) = build_app_pair();

    let api_listener = tokio::net::TcpListener::bind(api_addr)
        .await
        .expect("bind api listener");
    let mgmt_listener = tokio::net::TcpListener::bind(mgmt_addr)
        .await
        .expect("bind management listener");

    tokio::join!(
        async { axum::serve(api_listener, api_app).await.expect("api server error") },
        async { axum::serve(mgmt_listener, mgmt_app).await.expect("management server error") },
    );
}
