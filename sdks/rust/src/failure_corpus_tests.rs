//! Runtime execution of the shared failure contracts, with real callback I/O.
use crate::test_support::{
    FakeCallbackServer, PendingBody, Reply, TestRuntime, body_json, invoke_request,
};
use crate::{BoxError, Context, HandlerResponse, RuntimeSettings};
use axum::body::Body;
use serde_json::{Value, json};
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::time::Duration;

#[tokio::test]
async fn executes_shared_failure_lifecycles() {
    crate::corpus_tests::run_shared_validator(
        &std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../runtime-contract/failure-wire-corpus.json"),
        false,
    );
    let corpus: Value = serde_json::from_str(include_str!(
        "../../runtime-contract/failure-wire-corpus.json"
    ))
    .unwrap();
    let config = &corpus["config"];
    let deadline = Duration::from_millis(config["deadlineMs"].as_u64().unwrap());
    for (name, expected) in corpus["contractDefinitions"].as_object().unwrap() {
        let held = name == "callback-io-timeout";
        let status = expected["callbackStatus"].as_u64().unwrap_or(204) as u16;
        let callbacks = FakeCallbackServer::start(move |_| {
            if held {
                Reply::HoldThen(status)
            } else {
                Reply::Status(status)
            }
        })
        .await;
        let started = Arc::new(AtomicBool::new(false));
        let signal = started.clone();
        let success_output = corpus["successOutput"].clone();
        let serialization = name == "envelope-serialization-failure";
        let settings = RuntimeSettings {
            body_read_timeout: Duration::from_millis(config["bodyReadTimeoutMs"].as_u64().unwrap()),
            callback_attempt_timeout: Duration::from_millis(
                config["callbackAttemptTimeoutMs"].as_u64().unwrap(),
            ),
            callback_max_attempts: config["callbackMaxAttempts"].as_u64().unwrap() as usize,
            ..RuntimeSettings::default()
        };
        let runtime = TestRuntime::start_with(settings, callbacks, move |rt| {
            if serialization {
                rt.register("failure", move |_: Context, _: Value| {
                    let signal = signal.clone();
                    let success_output = success_output.clone();
                    async move {
                        signal.store(true, Ordering::SeqCst);
                        // Public Rust envelopes only hold JSON Value; invalid status tests
                        // the real envelope rejection without a production fault hook.
                        Ok::<_, BoxError>(HandlerResponse::new(success_output, 99))
                    }
                })
            } else {
                rt.register("failure", move |_: Context, _: Value| {
                    let signal = signal.clone();
                    let success_output = success_output.clone();
                    async move {
                        signal.store(true, Ordering::SeqCst);
                        Ok::<_, BoxError>(success_output)
                    }
                })
            }
        })
        .await;
        let input = if name == "ingress-io-timeout" {
            Body::new(PendingBody)
        } else {
            Body::from(r#"{"input":null}"#)
        };
        let mut request = invoke_request(input);
        request.headers_mut().insert(
            "x-execution-id",
            config["executionId"].as_str().unwrap().parse().unwrap(),
        );
        request.headers_mut().insert(
            "x-trace-id",
            config["traceId"].as_str().unwrap().parse().unwrap(),
        );
        request.headers_mut().insert(
            "x-dispatch-attempt",
            config["dispatchAttempt"].to_string().parse().unwrap(),
        );
        let response = tokio::time::timeout(deadline, runtime.send(request))
            .await
            .unwrap();
        assert_eq!(
            response.status().as_u16() as u64,
            expected["httpStatus"].as_u64().unwrap(),
            "{name}"
        );
        let body = body_json(response).await;
        assert_eq!(
            started.load(Ordering::SeqCst),
            expected["handlerStarted"].as_bool().unwrap()
        );
        if !expected["errorCode"].is_null() {
            assert_eq!(body["error"]["code"], expected["errorCode"]);
        }
        let shared = runtime.runtime.shared();
        tokio::time::timeout(deadline, async {
            shared.limits.wait_until_idle().await;
            shared.dispatcher.wait_until_idle().await;
        })
        .await
        .expect("physical resources did not drain");
        let limits = shared.limits.snapshot();
        assert_eq!(
            limits.input_bytes as u64,
            corpus["finalCounters"]["inputBytes"].as_u64().unwrap()
        );
        assert_eq!(
            limits.output_bytes as u64,
            corpus["finalCounters"]["outputBytes"].as_u64().unwrap()
        );
        assert_eq!(
            shared.dispatcher.snapshot(),
            crate::dispatcher::CallbackSnapshot::default()
        );
        let calls = runtime.callbacks.received();
        assert_eq!(
            calls.len() as u64,
            expected["callbackAttempts"].as_u64().unwrap()
        );
        for call in calls {
            assert!(call.path.contains(config["executionId"].as_str().unwrap()));
            assert_eq!(
                call.headers["x-trace-id"],
                config["traceId"].as_str().unwrap()
            );
            assert_eq!(
                call.headers["x-dispatch-attempt"],
                config["dispatchAttempt"].to_string()
            );
            if expected["errorCode"].is_null() {
                assert_eq!(
                    call.body,
                    json!({"success":true,"output":corpus["successOutput"],"error":null})
                );
            } else {
                assert_eq!(
                    call.body,
                    json!({"success":false,"output":null,"error":body["error"]})
                );
            }
        }
        runtime.callbacks.release_held();
    }
}
