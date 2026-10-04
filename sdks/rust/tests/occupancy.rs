use nanofaas::{BoxError, Context, Runtime, RuntimeSettings};
use serde_json::Value;
use std::sync::{Arc, Mutex};
use std::time::Duration;
#[tokio::test(flavor = "multi_thread")]
async fn timeout_does_not_release_blocking_work_and_release_has_one_sample() {
    let (release, released) = std::sync::mpsc::channel();
    let released = Arc::new(Mutex::new(released));
    let runtime = Runtime::with_settings(RuntimeSettings {
        max_concurrent_handlers: 1,
        handler_timeout: Duration::from_millis(50),
        ..Default::default()
    })
    .register("held", move |ctx: Context, _: Value| {
        let released = Arc::clone(&released);
        async move {
            ctx.spawn_blocking(move || {
                released.lock().unwrap().recv().unwrap();
            })
            .await?;
            Ok::<_, BoxError>(Value::Null)
        }
    });
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let (stop, stopped) = tokio::sync::oneshot::channel();
    let server = tokio::spawn(async move {
        runtime
            .serve(listener, async {
                let _ = stopped.await;
            })
            .await
    });
    let client = reqwest::Client::new();
    let response = client
        .post(format!("{base}/invoke"))
        .header("x-execution-id", "held-1")
        .body("{\"input\":null}")
        .send()
        .await
        .unwrap();
    assert_eq!(response.status(), 504);
    let url = format!("{base}/runtime/executions/held-1");
    let read = |url: String| {
        let client = client.clone();
        async move {
            serde_json::from_slice::<Value>(
                &client.get(url).send().await.unwrap().bytes().await.unwrap(),
            )
            .unwrap()
        }
    };
    let active = read(url.clone()).await;
    assert_eq!(active["state"], "ACTIVE");
    assert_eq!(active["responseStatus"], "timeout");
    let metrics = client
        .get(format!("{base}/metrics"))
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    assert!(metrics.contains("nanofaas_runtime_active_handlers 1"));
    release.send(()).unwrap();
    let proof = tokio::time::timeout(Duration::from_secs(2), async {
        loop {
            let proof = read(url.clone()).await;
            if proof["state"] == "RELEASED" {
                break proof;
            }
            tokio::task::yield_now().await;
        }
    })
    .await
    .unwrap();
    assert_eq!(proof["incarnation"], active["incarnation"]);
    assert!(proof["occupancySeconds"].as_f64().unwrap() >= 0.05);
    let metrics = client
        .get(format!("{base}/metrics"))
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    assert!(metrics.contains("nanofaas_runtime_active_handlers 0"));
    assert!(metrics.contains("nanofaas_runtime_replica_occupancy_seconds_count 1"));
    assert_eq!(
        client
            .get(format!("{base}/runtime/executions/unknown"))
            .send()
            .await
            .unwrap()
            .status(),
        404
    );
    stop.send(()).unwrap();
    server.await.unwrap().unwrap();
}

#[tokio::test]
async fn errors_panics_and_context_clones_record_exactly_one_sample() {
    let runtime = Runtime::with_settings(RuntimeSettings::default()).register(
        "failures",
        |ctx: Context, input: Value| async move {
            let clone = ctx.clone();
            match input.as_str() {
                Some("panic") => panic!("intentional handler panic"),
                Some("error") => Err::<Value, BoxError>("intentional error".into()),
                _ => {
                    drop(clone);
                    Ok(Value::Null)
                }
            }
        },
    );
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let base = format!("http://{}", listener.local_addr().unwrap());
    let (stop, stopped) = tokio::sync::oneshot::channel();
    let server = tokio::spawn(async move {
        runtime
            .serve(listener, async {
                let _ = stopped.await;
            })
            .await
    });
    let client = reqwest::Client::new();
    for (id, expected) in [("panic", 500), ("error", 500), ("clone", 200)] {
        let response = client
            .post(format!("{base}/invoke"))
            .header("x-execution-id", id)
            .body(format!("{{\"input\":\"{id}\"}}"))
            .send()
            .await
            .unwrap();
        assert_eq!(response.status(), expected);
        let proof: Value = serde_json::from_slice(
            &client
                .get(format!("{base}/runtime/executions/{id}"))
                .send()
                .await
                .unwrap()
                .bytes()
                .await
                .unwrap(),
        )
        .unwrap();
        assert_eq!(proof["state"], "RELEASED");
        assert_eq!(
            proof["responseStatus"],
            if expected == 200 { "success" } else { "error" }
        );
    }
    let metrics = client
        .get(format!("{base}/metrics"))
        .send()
        .await
        .unwrap()
        .text()
        .await
        .unwrap();
    assert!(metrics.contains("nanofaas_runtime_replica_occupancy_seconds_count 3"));
    stop.send(()).unwrap();
    server.await.unwrap().unwrap();
}
