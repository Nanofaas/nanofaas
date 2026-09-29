//! The crate as a function author sees it: only the public API, over a real TCP connection.

use std::time::Duration;

use nanofaas::{BoxError, Context, Error, HandlerResponse, Runtime, RuntimeSettings};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tokio::task::JoinHandle;

#[derive(Deserialize)]
struct In {
    text: String,
}

#[derive(Serialize)]
struct Out {
    words: usize,
}

async fn word_count(_: Context, input: In) -> Result<Out, BoxError> {
    Ok(Out {
        words: input.text.split_whitespace().count(),
    })
}

struct Served {
    base: String,
    stop: oneshot::Sender<()>,
    served: JoinHandle<Result<(), Error>>,
}

async fn serve(runtime: Runtime) -> Served {
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
    Served { base, stop, served }
}

async fn invoke(base: &str, execution_id: &str, body: &'static str) -> reqwest::Response {
    reqwest::Client::new()
        .post(format!("{base}/invoke"))
        .header("x-execution-id", execution_id)
        .body(body)
        .send()
        .await
        .unwrap()
}

#[tokio::test]
async fn serves_a_typed_handler_over_http_and_stops_cleanly() {
    let runtime = Runtime::with_settings(RuntimeSettings::default()).register("words", word_count);
    let served = serve(runtime).await;

    let response = invoke(
        &served.base,
        "exec-1",
        r#"{"input":{"text":"one two three"}}"#,
    )
    .await;
    assert_eq!(response.status(), 200);
    assert_eq!(response.headers()["x-cold-start"], "true");
    let body: Value = serde_json::from_slice(&response.bytes().await.unwrap()).unwrap();
    assert_eq!(body, json!({"words": 3}));

    let health = reqwest::get(format!("{}/health", served.base))
        .await
        .unwrap();
    assert_eq!(health.status(), 200);

    served.stop.send(()).unwrap();
    served.served.await.unwrap().expect("a clean stop");
}

#[tokio::test]
async fn a_handler_can_return_an_envelope() {
    let runtime = Runtime::with_settings(RuntimeSettings::default()).register(
        "created",
        |_: Context, _: Value| async {
            Ok::<_, BoxError>(
                HandlerResponse::new(json!({"id": 7}), 201).header("Location", "/things/7"),
            )
        },
    );
    let served = serve(runtime).await;

    let response = invoke(&served.base, "exec-2", r#"{"input":null}"#).await;
    assert_eq!(response.status(), 201);
    assert_eq!(response.headers()["location"], "/things/7");
    assert_eq!(response.headers()["x-nanofaas-function-status"], "true");

    served.stop.send(()).unwrap();
    served.served.await.unwrap().expect("a clean stop");
}

/// A local run has no control plane: the invocation still answers, and the undeliverable
/// callback shows up as a drop instead of leaking.
#[tokio::test]
async fn without_a_callback_url_invocations_answer_and_count_a_drop() {
    let runtime = Runtime::with_settings(RuntimeSettings::default()).register("words", word_count);
    let served = serve(runtime).await;

    let response = invoke(&served.base, "exec-3", r#"{"input":{"text":"a"}}"#).await;
    assert_eq!(response.status(), 200);

    let counted = async {
        loop {
            let metrics = reqwest::get(format!("{}/metrics", served.base))
                .await
                .unwrap()
                .text()
                .await
                .unwrap();
            if metrics.contains("nanofaas_runtime_callback_drops_total 1") {
                return;
            }
            tokio::time::sleep(Duration::from_millis(10)).await;
        }
    };
    tokio::time::timeout(Duration::from_secs(2), counted)
        .await
        .expect("the dropped callback is counted");

    served.stop.send(()).unwrap();
    served.served.await.unwrap().expect("a clean stop");
}
