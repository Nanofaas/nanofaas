use control_plane_rust::app::build_app;
use std::net::SocketAddr;

#[tokio::main]
async fn main() {
    let bind_addr = std::env::var("CONTROL_PLANE_BIND")
        .or_else(|_| std::env::var("BIND_ADDR"))
        .unwrap_or_else(|_| "0.0.0.0:8080".to_string());
    let addr: SocketAddr = bind_addr
        .parse()
        .unwrap_or_else(|_| "0.0.0.0:8080".parse().expect("default bind addr"));

    let listener = tokio::net::TcpListener::bind(addr)
        .await
        .expect("bind control-plane listener");

    axum::serve(listener, build_app())
        .await
        .expect("serve control-plane");
}
