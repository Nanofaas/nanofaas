//! A `scratch` container has no CA certificates. The runtime must still start and serve: plain
//! HTTP callbacks, the in-cluster case, do not need them. Kept in its own test binary because it
//! points the process-wide certificate environment at an empty store.

use nanofaas::{BoxError, Context, Runtime, RuntimeSettings};
use serde_json::Value;
use tokio::net::TcpListener;
use tokio::sync::oneshot;

#[tokio::test]
async fn the_runtime_serves_without_system_ca_certificates() {
    let empty = std::env::temp_dir().join(format!("nanofaas-no-certs-{}", std::process::id()));
    std::fs::create_dir_all(&empty).unwrap();
    let empty_file = empty.join("none.pem");
    std::fs::write(&empty_file, "").unwrap();
    // SAFETY: this binary holds this single test, which sets the variables before starting any
    // thread that could read the environment.
    unsafe {
        std::env::set_var("SSL_CERT_FILE", &empty_file);
        std::env::set_var("SSL_CERT_DIR", &empty);
    }

    let runtime = Runtime::with_settings(RuntimeSettings::default())
        .register("echo", |_: Context, input: Value| async move {
            Ok::<_, BoxError>(input)
        });
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let (stop, stopped) = oneshot::channel::<()>();
    let served = tokio::spawn(async move {
        runtime
            .serve(listener, async move {
                let _ = stopped.await;
            })
            .await
    });

    let response = reqwest::Client::builder()
        .tls_certs_only([])
        .build()
        .unwrap()
        .post(format!("{base}/invoke"))
        .header("x-execution-id", "exec-1")
        .body(r#"{"input":"hi"}"#)
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 200);

    stop.send(()).unwrap();
    served.await.unwrap().expect("a clean stop");
}
