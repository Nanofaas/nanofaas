//! `/invoke`: admission, the handler's lifetime, and the terminal callback. Admission order,
//! codes and messages follow `sdks/go/nanofaas/http_invoke.go` so every runtime answers alike.

use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Instant;

use axum::body::Body;
use axum::extract::{Request, State};
use axum::http::header::{CONTENT_LENGTH, CONTENT_TYPE, RETRY_AFTER};
use axum::http::{HeaderMap, HeaderName, HeaderValue, Method, StatusCode};
use axum::response::{IntoResponse, Response};
use bytes::Bytes;
use http_body_util::{BodyExt, LengthLimitError, Limited};
use serde_json::json;
use serde_json::value::RawValue;
use tokio::sync::oneshot;
use tokio::task::{JoinError, JoinHandle};
use tracing::Instrument;

use crate::bounded::{EncodeError, to_vec_bounded};
use crate::callback::Identity;
use crate::context::Context;
use crate::dispatcher::{
    CallbackReservation, MINIMUM_TERMINAL_CALLBACK_PAYLOAD_BYTES, SubmitError,
};
use crate::handler::{BoxError, HandlerFuture, Produced};
use crate::limits::{CountedBody, HandlerReservation};
use crate::runtime::{Shared, json_response};
use crate::types::{
    ENCODING_HEADER, FUNCTION_STATUS_HEADER, InvocationResult, WireRequest, filter_allowed_headers,
    is_status_code_valid,
};

/// Everything an admitted invocation owns. Dropping it releases both reservations.
struct Admitted {
    identity: Identity,
    future: HandlerFuture,
    cancelled: Arc<AtomicBool>,
    handler_reservation: Arc<HandlerReservation>,
    callback_reservation: CallbackReservation,
}

enum Finished {
    Done(Result<Result<Produced, BoxError>, JoinError>),
    TimedOut,
    Cancelled,
}

/// A function-decided status and headers, already validated and filtered.
struct Envelope {
    status: u16,
    headers: Vec<(String, String)>,
    encoding: Option<String>,
}

pub(crate) async fn invoke(State(shared): State<Arc<Shared>>, request: Request) -> Response {
    let admitted = match admit(&shared, request).await {
        Ok(admitted) => admitted,
        Err(response) => return response,
    };
    let (reply, replied) = oneshot::channel();
    // The invocation outlives this request future: if the client disconnects, axum drops us
    // and `run` sees its reply channel close, cancels the handler and still sends the callback.
    tokio::spawn(run(shared, admitted, reply));
    replied.await.unwrap_or_else(|_| {
        runtime_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "HANDLER_ERROR",
            "Handler failed",
        )
    })
}

// The error is the HTTP answer itself, returned once to axum rather than propagated.
#[allow(clippy::result_large_err)]
async fn admit(shared: &Arc<Shared>, request: Request) -> Result<Admitted, Response> {
    if request.method() != Method::POST {
        return Err(StatusCode::METHOD_NOT_ALLOWED.into_response());
    }
    if !shared.limits.snapshot().accepting {
        return Err(stopping());
    }
    let (parts, body) = request.into_parts();
    let (execution_id, trace_id) = shared.settings.resolve_identity(
        header(&parts.headers, "x-execution-id"),
        header(&parts.headers, "x-trace-id"),
    );
    let Some(execution_id) = execution_id else {
        return Err(legacy_error(
            StatusCode::BAD_REQUEST,
            "Execution ID not configured",
        ));
    };
    let identity = Identity {
        execution_id,
        trace_id,
        dispatch_attempt: header(&parts.headers, "x-dispatch-attempt").map(str::to_owned),
    };
    let Some(handler) = shared.resolve_handler().cloned() else {
        return Err(legacy_error(
            StatusCode::INTERNAL_SERVER_ERROR,
            "Handler not configured",
        ));
    };
    // Read-only checks first: occupied handler capacity is reported even when its terminal
    // callback reserve also fills the callback budget.
    if shared.limits.snapshot().active_handlers >= shared.settings.max_concurrent_handlers {
        return Err(handler_saturated());
    }
    if shared.dispatcher.max_callback_payload_bytes < MINIMUM_TERMINAL_CALLBACK_PAYLOAD_BYTES {
        return Err(callback_saturated());
    }
    let Some(callback_reservation) = shared.dispatcher.try_reserve() else {
        return Err(callback_saturated());
    };
    let Some(mut handler_reservation) = shared.limits.try_reserve_handler() else {
        return Err(if shared.limits.snapshot().accepting {
            handler_saturated()
        } else {
            stopping()
        });
    };
    if identity.execution_id.len() > 256
        || !handler_reservation.track(
            Arc::clone(&shared.occupancy),
            identity.execution_id.clone(),
            identity.dispatch_attempt.clone(),
        )
    {
        return Err(runtime_error(
            StatusCode::TOO_MANY_REQUESTS,
            "EXECUTION_ACTIVE",
            "Execution identity is invalid or already active",
        ));
    }
    let handler_reservation = Arc::new(handler_reservation);

    let body = read_body(body, &parts.headers, shared).await?;
    handler_reservation.retain_input(body.len());
    let wire: WireRequest = serde_json::from_slice(&body).map_err(|_| malformed())?;
    let cancelled = Arc::new(AtomicBool::new(false));
    let ctx = Context::new(
        identity.execution_id.clone(),
        identity.trace_id.clone(),
        wire.metadata,
        wire.headers,
        Arc::clone(&cancelled),
        Arc::clone(&handler_reservation),
    );
    let input = wire.input.map_or("null", RawValue::get);
    let future = handler(ctx, input, shared.settings.max_output_bytes).map_err(|_| malformed())?;
    Ok(Admitted {
        identity,
        future,
        cancelled,
        handler_reservation,
        callback_reservation,
    })
}

// The error is the HTTP answer itself, returned once to axum rather than propagated.
#[allow(clippy::result_large_err)]
async fn read_body(body: Body, headers: &HeaderMap, shared: &Shared) -> Result<Bytes, Response> {
    let max = shared.settings.max_input_bytes;
    let declared = header(headers, CONTENT_LENGTH.as_str()).and_then(|v| v.parse::<usize>().ok());
    if declared.is_some_and(|length| length > max) {
        return Err(input_too_large());
    }
    let read = Limited::new(body, max).collect();
    match tokio::time::timeout(shared.settings.body_read_timeout, read).await {
        Err(_) => Err(runtime_error(
            StatusCode::REQUEST_TIMEOUT,
            "RUNTIME_BODY_READ_TIMEOUT",
            "Runtime request body read timed out",
        )),
        Ok(Err(error)) if error.downcast_ref::<LengthLimitError>().is_some() => {
            Err(input_too_large())
        }
        Ok(Err(_)) => Err(malformed()),
        Ok(Ok(collected)) => Ok(collected.to_bytes()),
    }
}

async fn run(shared: Arc<Shared>, admitted: Admitted, mut reply: oneshot::Sender<Response>) {
    let Admitted {
        identity,
        future,
        cancelled,
        handler_reservation,
        callback_reservation,
    } = admitted;
    let cold_start = shared.cold_start.first_invocation();
    if cold_start {
        shared.metrics.cold_start();
    }
    shared.cold_start.mark_arrival();

    let span = tracing::info_span!(
        "invocation",
        execution_id = %identity.execution_id,
        trace_id = identity.trace_id.as_deref().unwrap_or_default(),
    );
    let started = Instant::now();
    // The task owns the handler slot as well as the context does: a handler that ignores its
    // context must not release the slot before its work is gone.
    let occupancy_started = handler_reservation.started();
    let mut handle: JoinHandle<Result<Produced, BoxError>> = tokio::spawn(
        async move {
            let _reservation = handler_reservation;
            future.await
        }
        .instrument(span),
    );
    let finished = tokio::select! {
        joined = &mut handle => Finished::Done(joined),
        () = tokio::time::sleep(shared.settings.handler_timeout) => Finished::TimedOut,
        () = reply.closed() => Finished::Cancelled,
    };
    shared.occupancy.response_at(
        &identity.execution_id,
        occupancy_started,
        match &finished {
            Finished::Done(Ok(Ok(_))) => "success",
            Finished::Done(_) => "error",
            Finished::TimedOut => "timeout",
            Finished::Cancelled => "cancelled",
        },
    );
    if !matches!(finished, Finished::Done(_)) {
        cancelled.store(true, Ordering::Release);
        handle.abort();
    }
    shared
        .metrics
        .handler_duration(started.elapsed().as_secs_f64());
    if let Some(response) = conclude(
        &shared,
        finished,
        callback_reservation,
        &identity,
        cold_start,
    ) {
        let _ = reply.send(response);
    }
}

/// Sends the terminal callback and builds the response; `None` when the client is gone.
fn conclude(
    shared: &Shared,
    finished: Finished,
    reservation: CallbackReservation,
    identity: &Identity,
    cold_start: bool,
) -> Option<Response> {
    match finished {
        Finished::Done(Ok(Ok(produced))) => {
            Some(succeed(shared, produced, reservation, identity, cold_start))
        }
        Finished::Done(Ok(Err(error))) => {
            tracing::error!(execution_id = %identity.execution_id, %error, "handler failed");
            Some(handler_failed(shared, reservation, identity))
        }
        Finished::Done(Err(panic)) => {
            tracing::error!(execution_id = %identity.execution_id, %panic, "handler panicked");
            Some(handler_failed(shared, reservation, identity))
        }
        Finished::TimedOut => {
            shared.metrics.invocation("timeout");
            let (code, message) = ("HANDLER_TIMEOUT", "Handler exceeded configured timeout");
            submit_failure(shared, reservation, identity, code, message);
            Some(runtime_error(StatusCode::GATEWAY_TIMEOUT, code, message))
        }
        Finished::Cancelled => {
            shared.metrics.invocation("error");
            let (code, message) = ("INVOCATION_CANCELLED", "Invocation cancelled");
            submit_failure(shared, reservation, identity, code, message);
            None
        }
    }
}

/// Why a finished handler's output cannot be answered as a success.
enum OutputRejection {
    Unencodable(EncodeError),
    InvalidStatus(u16),
}

fn succeed(
    shared: &Shared,
    produced: Produced,
    reservation: CallbackReservation,
    identity: &Identity,
    cold_start: bool,
) -> Response {
    let (body, envelope) = match encode_output(shared, produced) {
        Ok(encoded) => encoded,
        Err(OutputRejection::Unencodable(error)) => {
            return unencodable(shared, reservation, identity, error);
        }
        Err(OutputRejection::InvalidStatus(status)) => {
            tracing::warn!(execution_id = %identity.execution_id, status, "treating an invalid statusCode as a platform error");
            let message = format!("Handler returned invalid statusCode: {status}");
            return output_failure(
                shared,
                reservation,
                identity,
                "OUTPUT_SERIALIZATION_ERROR",
                message,
            );
        }
    };

    let output: &RawValue = serde_json::from_slice(&body).expect("runtime-encoded JSON is valid");
    let result = match &envelope {
        None => InvocationResult::success(output),
        Some(envelope) => InvocationResult::envelope(
            output,
            envelope.status,
            &envelope.headers,
            envelope.encoding.as_deref(),
        ),
    };
    if let Err(rejected) = shared.dispatcher.submit(reservation, identity, &result) {
        return match rejected.error {
            SubmitError::TooLarge => unencodable(
                shared,
                rejected.reservation,
                identity,
                EncodeError::TooLarge,
            ),
            SubmitError::Serialization => unencodable(
                shared,
                rejected.reservation,
                identity,
                EncodeError::Serialization,
            ),
            SubmitError::Closed => {
                shared.metrics.callback_drop();
                stopping()
            }
        };
    }
    shared.metrics.invocation("success");
    success_response(shared, body, envelope.as_ref(), cold_start)
}

/// The response body, plus the validated and filtered envelope when the handler returned one.
fn encode_output(
    shared: &Shared,
    produced: Produced,
) -> Result<(Vec<u8>, Option<Envelope>), OutputRejection> {
    let envelope = match produced {
        Produced::Json(body) => return Ok((body, None)),
        Produced::Unencodable(error) => return Err(OutputRejection::Unencodable(error)),
        Produced::Envelope(envelope) => envelope,
    };
    let (output, status, headers, encoding) = envelope.into_parts();
    if !is_status_code_valid(status) {
        return Err(OutputRejection::InvalidStatus(status));
    }
    let body = to_vec_bounded(&output, shared.settings.max_output_bytes)
        .map_err(OutputRejection::Unencodable)?;
    // A value HTTP cannot carry is dropped from the response and the callback alike, so the
    // two never disagree.
    let headers = filter_allowed_headers(headers)
        .into_iter()
        .filter(|(_, value)| HeaderValue::from_str(value).is_ok())
        .collect();
    Ok((
        body,
        Some(Envelope {
            status,
            headers,
            encoding,
        }),
    ))
}

fn success_response(
    shared: &Shared,
    body: Vec<u8>,
    envelope: Option<&Envelope>,
    cold_start: bool,
) -> Response {
    let status = envelope.map_or(200, |envelope| envelope.status);
    let length = body.len();
    let body = CountedBody::new(Bytes::from(body), shared.limits.retain_output(length));
    let mut response = Response::new(Body::new(body));
    *response.status_mut() = StatusCode::from_u16(status).expect("validated as 200..=599");
    let headers = response.headers_mut();
    if let Some(envelope) = envelope {
        for (name, value) in &envelope.headers {
            if let (Ok(name), Ok(value)) =
                (HeaderName::try_from(name), HeaderValue::try_from(value))
            {
                headers.insert(name, value);
            }
        }
        headers.insert(FUNCTION_STATUS_HEADER, HeaderValue::from_static("true"));
        if let Some(Ok(encoding)) = envelope.encoding.as_deref().map(HeaderValue::try_from) {
            headers.insert(ENCODING_HEADER, encoding);
        }
    }
    if cold_start {
        headers.insert("x-cold-start", HeaderValue::from_static("true"));
        headers.insert(
            "x-init-duration-ms",
            HeaderValue::from(shared.cold_start.init_duration_ms() as u64),
        );
    }
    headers
        .entry(CONTENT_TYPE)
        .or_insert(HeaderValue::from_static("application/json"));
    response
}

fn unencodable(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
    error: EncodeError,
) -> Response {
    match error {
        EncodeError::TooLarge => output_failure(
            shared,
            reservation,
            identity,
            "RUNTIME_OUTPUT_TOO_LARGE",
            "Runtime output exceeds configured byte limit".into(),
        ),
        EncodeError::Serialization => output_failure(
            shared,
            reservation,
            identity,
            "OUTPUT_SERIALIZATION_ERROR",
            "Handler output could not be serialized".into(),
        ),
    }
}

fn output_failure(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
    code: &'static str,
    message: String,
) -> Response {
    shared.metrics.invocation("error");
    submit_failure(shared, reservation, identity, code, &message);
    runtime_error(StatusCode::INTERNAL_SERVER_ERROR, code, &message)
}

fn handler_failed(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
) -> Response {
    output_failure(
        shared,
        reservation,
        identity,
        "HANDLER_ERROR",
        "Handler failed".into(),
    )
}

fn submit_failure(
    shared: &Shared,
    reservation: CallbackReservation,
    identity: &Identity,
    code: &'static str,
    message: &str,
) {
    let result = InvocationResult::failure(code, message);
    if shared
        .dispatcher
        .submit(reservation, identity, &result)
        .is_err()
    {
        shared.metrics.callback_drop();
    }
}

fn header<'a>(headers: &'a HeaderMap, name: &str) -> Option<&'a str> {
    headers.get(name).and_then(|value| value.to_str().ok())
}

fn runtime_error(status: StatusCode, code: &str, message: &str) -> Response {
    json_response(
        status,
        &json!({"error": {"code": code, "message": message}}),
    )
}

fn retryable(status: StatusCode, code: &str, message: &str) -> Response {
    let mut response = runtime_error(status, code, message);
    response
        .headers_mut()
        .insert(RETRY_AFTER, HeaderValue::from_static("1"));
    response
}

/// The pre-contract `{"error": "<message>"}` shape, kept for the requests the Go SDK answers so.
fn legacy_error(status: StatusCode, message: &str) -> Response {
    json_response(status, &json!({"error": message}))
}

fn stopping() -> Response {
    retryable(
        StatusCode::SERVICE_UNAVAILABLE,
        "RUNTIME_STOPPING",
        "Runtime is stopping",
    )
}

fn handler_saturated() -> Response {
    retryable(
        StatusCode::TOO_MANY_REQUESTS,
        "RUNTIME_HANDLER_SATURATED",
        "Runtime handler capacity exhausted",
    )
}

fn callback_saturated() -> Response {
    retryable(
        StatusCode::TOO_MANY_REQUESTS,
        "RUNTIME_CALLBACK_SATURATED",
        "Runtime callback capacity exhausted",
    )
}

fn input_too_large() -> Response {
    runtime_error(
        StatusCode::PAYLOAD_TOO_LARGE,
        "RUNTIME_INPUT_TOO_LARGE",
        "Runtime input exceeds configured byte limit",
    )
}

fn malformed() -> Response {
    legacy_error(StatusCode::BAD_REQUEST, "Malformed request body")
}
#[cfg(test)]
mod admission_tests {
    use crate::dispatcher::CallbackSnapshot;
    use crate::test_support::{
        PendingBody, TestRuntime, body_json, handler_started, invoke_request,
    };
    use crate::{BoxError, Context, Runtime, RuntimeSettings};
    use axum::body::Body;
    use axum::http::{Request, StatusCode};
    use serde::Deserialize;
    use serde_json::{Value, json};
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::time::Duration;
    use tokio::sync::Notify;
    use tower::ServiceExt;

    async fn echo(_: Context, input: Value) -> Result<Value, BoxError> {
        Ok(input)
    }

    fn with_echo(runtime: Runtime) -> Runtime {
        runtime.register("echo", echo)
    }

    fn settings() -> RuntimeSettings {
        RuntimeSettings {
            handler_timeout: Duration::from_secs(2),
            ..RuntimeSettings::default()
        }
    }

    fn assert_idle(runtime: &TestRuntime) {
        let shared = runtime.runtime.shared();
        assert_eq!(shared.limits.snapshot().active_handlers, 0);
        assert_eq!(shared.limits.snapshot().input_bytes, 0);
        assert_eq!(shared.dispatcher.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn rejects_non_post_methods() {
        let runtime = TestRuntime::start(settings(), with_echo).await;
        let request = Request::get("/invoke").body(Body::empty()).unwrap();
        assert_eq!(
            runtime.send(request).await.status(),
            StatusCode::METHOD_NOT_ALLOWED
        );
    }

    #[tokio::test]
    async fn answers_stopping_before_the_runtime_serves() {
        let runtime = with_echo(Runtime::with_settings(settings()));
        let response = runtime
            .router()
            .oneshot(invoke_request(r#"{"input":1}"#))
            .await
            .unwrap();
        assert_eq!(response.status(), StatusCode::SERVICE_UNAVAILABLE);
        assert_eq!(response.headers()["retry-after"], "1");
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_STOPPING", "message": "Runtime is stopping"}})
        );
    }

    #[tokio::test]
    async fn requires_an_execution_id() {
        let runtime = TestRuntime::start(settings(), with_echo).await;
        let request = Request::post("/invoke")
            .body(Body::from(r#"{"input":1}"#))
            .unwrap();
        let response = runtime.send(request).await;
        assert_eq!(response.status(), StatusCode::BAD_REQUEST);
        assert_eq!(
            body_json(response).await,
            json!({"error": "Execution ID not configured"})
        );
    }

    #[tokio::test]
    async fn falls_back_to_the_environment_execution_id() {
        let settings = RuntimeSettings {
            execution_id: Some("env-exec".into()),
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let request = Request::post("/invoke")
            .body(Body::from(r#"{"input":1}"#))
            .unwrap();
        assert_eq!(runtime.send(request).await.status(), StatusCode::OK);
        let received = runtime.callbacks.wait_for(1, |_| true).await;
        assert_eq!(received[0].path, "/env-exec:complete");
    }

    #[tokio::test]
    async fn answers_500_when_no_handler_resolves() {
        let runtime =
            TestRuntime::start(settings(), |rt| rt.register("a", echo).register("b", echo)).await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": "Handler not configured"})
        );
    }

    #[tokio::test]
    async fn rejects_a_declared_length_over_the_input_limit() {
        let settings = RuntimeSettings {
            max_input_bytes: 16,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let request = Request::post("/invoke")
            .header("x-execution-id", "exec-1")
            .header("content-length", "17")
            .body(Body::from("x".repeat(17)))
            .unwrap();
        let response = runtime.send(request).await;
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_INPUT_TOO_LARGE",
                             "message": "Runtime input exceeds configured byte limit"}})
        );
        assert_idle(&runtime);
    }

    #[tokio::test]
    async fn rejects_a_streamed_body_over_the_input_limit() {
        let settings = RuntimeSettings {
            max_input_bytes: 16,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let response = runtime
            .send(invoke_request(format!(
                r#"{{"input":"{}"}}"#,
                "x".repeat(32)
            )))
            .await;
        assert_eq!(response.status(), StatusCode::PAYLOAD_TOO_LARGE);
        assert_idle(&runtime);
    }

    #[tokio::test]
    async fn bounds_the_body_read_time() {
        let settings = RuntimeSettings {
            body_read_timeout: Duration::from_millis(50),
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let response = runtime.send(invoke_request(Body::new(PendingBody))).await;
        assert_eq!(response.status(), StatusCode::REQUEST_TIMEOUT);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_BODY_READ_TIMEOUT",
                             "message": "Runtime request body read timed out"}})
        );
        assert_idle(&runtime);
    }

    #[tokio::test]
    async fn rejects_malformed_json_and_input_of_the_wrong_shape() {
        #[derive(Deserialize)]
        struct Named {
            #[allow(dead_code)]
            name: String,
        }
        let calls = Arc::new(AtomicUsize::new(0));
        let counted = Arc::clone(&calls);
        let runtime = TestRuntime::start(settings(), move |rt| {
            rt.register("named", move |_: Context, _: Named| {
                counted.fetch_add(1, Ordering::SeqCst);
                async { Ok::<_, BoxError>(json!("ok")) }
            })
        })
        .await;
        for body in ["{not json", r#"{"input":{"name":42}}"#] {
            let response = runtime.send(invoke_request(body)).await;
            assert_eq!(response.status(), StatusCode::BAD_REQUEST, "{body}");
            assert_eq!(
                body_json(response).await,
                json!({"error": "Malformed request body"})
            );
        }
        assert_eq!(calls.load(Ordering::SeqCst), 0, "the handler never started");
        assert_idle(&runtime);
        assert!(runtime.callbacks.received().is_empty());
    }

    #[tokio::test]
    async fn accepts_a_body_exactly_at_the_input_limit() {
        let body = r#"{"input":"xxxxxxxxxx"}"#;
        let settings = RuntimeSettings {
            max_input_bytes: body.len(),
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let request = Request::post("/invoke")
            .header("x-execution-id", "exec-1")
            .header("content-length", body.len().to_string())
            .body(Body::from(body))
            .unwrap();
        assert_eq!(runtime.send(request).await.status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn reports_handler_saturation_while_a_handler_runs() {
        let settings = RuntimeSettings {
            max_concurrent_handlers: 1,
            ..settings()
        };
        let started = Arc::new(Notify::new());
        let release = Arc::new(Notify::new());
        let (started_signal, release_wait) = (Arc::clone(&started), Arc::clone(&release));
        let runtime = TestRuntime::start(settings, move |rt| {
            rt.register("block", move |_: Context, _: Value| {
                let (started, release) = (Arc::clone(&started_signal), Arc::clone(&release_wait));
                async move {
                    started.notify_one();
                    release.notified().await;
                    Ok::<_, BoxError>(json!("done"))
                }
            })
        })
        .await;
        let router = runtime.runtime.router();
        let first = tokio::spawn(router.oneshot(invoke_request(r#"{"input":1}"#)));
        handler_started(&started).await;
        let response = runtime.send(invoke_request(r#"{"input":2}"#)).await;
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(response.headers()["retry-after"], "1");
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_HANDLER_SATURATED",
                             "message": "Runtime handler capacity exhausted"}})
        );
        release.notify_one();
        assert_eq!(first.await.unwrap().unwrap().status(), StatusCode::OK);
    }

    #[tokio::test]
    async fn reports_callback_saturation_before_the_handler_starts() {
        let runtime = TestRuntime::start(
            RuntimeSettings {
                max_pending_callbacks: 1,
                ..settings()
            },
            with_echo,
        )
        .await;
        let held = runtime.runtime.shared().dispatcher.try_reserve().unwrap();
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(response.headers()["retry-after"], "1");
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "RUNTIME_CALLBACK_SATURATED",
                             "message": "Runtime callback capacity exhausted"}})
        );
        assert_eq!(
            runtime.runtime.shared().limits.snapshot().active_handlers,
            0
        );
        drop(held);
    }

    #[tokio::test]
    async fn a_payload_cap_below_the_terminal_minimum_counts_as_callback_saturation() {
        let settings = RuntimeSettings {
            max_callback_payload_bytes: 255,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, with_echo).await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
        assert_eq!(
            body_json(response).await["error"]["code"],
            "RUNTIME_CALLBACK_SATURATED"
        );
    }
}

#[cfg(test)]
mod execution_tests {
    use crate::dispatcher::CallbackSnapshot;
    use crate::test_support::{
        FakeCallbackServer, Reply, TestRuntime, body_bytes, body_json, body_text, get,
        handler_started, invoke_request,
    };
    use crate::{BoxError, Context, HandlerResponse, Runtime, RuntimeSettings};
    use axum::http::StatusCode;
    use serde::{Deserialize, Serialize};
    use serde_json::{Value, json};
    use std::collections::HashMap;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::time::Duration;
    use tokio::sync::Notify;
    use tower::ServiceExt;

    async fn echo(_: Context, input: Value) -> Result<Value, BoxError> {
        Ok(json!({"echo": input}))
    }

    fn settings() -> RuntimeSettings {
        RuntimeSettings {
            handler_timeout: Duration::from_secs(2),
            ..RuntimeSettings::default()
        }
    }

    async fn assert_drained(runtime: &TestRuntime) {
        let shared = runtime.runtime.shared();
        tokio::time::timeout(Duration::from_secs(2), async {
            shared.limits.wait_until_idle().await;
            shared.dispatcher.wait_until_idle().await;
        })
        .await
        .expect("counters never drained");
        let limits = shared.limits.snapshot();
        assert_eq!((limits.input_bytes, limits.output_bytes), (0, 0));
        assert_eq!(shared.dispatcher.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn returns_the_output_and_reports_success_by_callback() {
        let runtime = TestRuntime::start(settings(), |rt| rt.register("echo", echo)).await;
        let response = runtime.send(invoke_request(r#"{"input":{"n":1}}"#)).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["content-type"], "application/json");
        assert_eq!(response.headers()["x-cold-start"], "true");
        assert!(response.headers().contains_key("x-init-duration-ms"));
        assert_eq!(body_json(response).await, json!({"echo": {"n": 1}}));

        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.method, "POST");
        assert_eq!(callback.path, "/exec-1:complete");
        assert_eq!(callback.headers["x-trace-id"], "trace-1");
        assert_eq!(callback.headers["x-dispatch-attempt"], "1");
        assert_eq!(
            callback.body,
            json!({"success": true, "output": {"echo": {"n": 1}}, "error": null})
        );

        let second = runtime.send(invoke_request(r#"{"input":2}"#)).await;
        assert!(
            !second.headers().contains_key("x-cold-start"),
            "only the first is cold"
        );
        drop(second);
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn deserializes_typed_input_and_serializes_typed_output() {
        #[derive(Deserialize)]
        struct In {
            text: String,
        }
        #[derive(Serialize)]
        struct Out {
            words: usize,
        }
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("count", |_: Context, input: In| async move {
                Ok::<_, BoxError>(Out {
                    words: input.text.split_whitespace().count(),
                })
            })
        })
        .await;
        let response = runtime
            .send(invoke_request(r#"{"input":{"text":"a b c"}}"#))
            .await;
        assert_eq!(body_json(response).await, json!({"words": 3}));
    }

    #[tokio::test]
    async fn nullable_java_envelope_maps_are_empty_handler_context() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("inspect", |ctx: Context, _: Value| async move {
                Ok::<_, BoxError>(json!({"metadata": ctx.metadata(), "headers": ctx.headers()}))
            })
        })
        .await;
        let response = runtime
            .send(invoke_request(
                r#"{"input":{"n":1},"metadata":null,"headers":null}"#,
            ))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(
            body_json(response).await,
            json!({"metadata":{},"headers":{}})
        );
    }

    #[tokio::test]
    async fn the_handler_sees_request_metadata_and_headers() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("inspect", |ctx: Context, _: Value| async move {
                Ok::<_, BoxError>(json!({
                    "executionId": ctx.execution_id(),
                    "traceId": ctx.trace_id(),
                    "metadata": ctx.metadata(),
                    "headers": ctx.headers(),
                }))
            })
        })
        .await;
        let body = r#"{"input":null,"metadata":{"k":"v"},"headers":{"accept":"text/plain"}}"#;
        let response = runtime.send(invoke_request(body)).await;
        assert_eq!(
            body_json(response).await,
            json!({"executionId": "exec-1", "traceId": "trace-1",
                   "metadata": {"k": "v"}, "headers": {"accept": "text/plain"}})
        );
    }

    #[tokio::test]
    async fn a_handler_error_is_a_platform_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("fail", |_: Context, _: Value| async {
                Err::<Value, BoxError>("boom".into())
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "HANDLER_ERROR", "message": "Handler failed"}})
        );
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(
            callback.body,
            json!({"success": false, "output": null,
                   "error": {"code": "HANDLER_ERROR", "message": "Handler failed"}})
        );
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn a_panicking_handler_is_a_platform_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("panic", |_: Context, _: Value| async {
                if true {
                    panic!("handler bug");
                }
                Ok::<Value, BoxError>(Value::Null)
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(body_json(response).await["error"]["code"], "HANDLER_ERROR");
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn a_timed_out_handler_is_dropped_and_reported() {
        let settings = RuntimeSettings {
            handler_timeout: Duration::from_millis(50),
            ..settings()
        };
        let dropped = Arc::new(AtomicBool::new(false));
        let observed = Arc::clone(&dropped);
        let runtime = TestRuntime::start(settings, move |rt| {
            rt.register("hang", move |_: Context, _: Value| {
                let guard = DropFlag(Arc::clone(&observed));
                async move {
                    let _guard = guard;
                    std::future::pending::<()>().await;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::GATEWAY_TIMEOUT);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "HANDLER_TIMEOUT", "message": "Handler exceeded configured timeout"}})
        );
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.body["error"]["code"], "HANDLER_TIMEOUT");
        assert_drained(&runtime).await;
        assert!(
            dropped.load(Ordering::SeqCst),
            "the handler future was dropped"
        );
    }

    struct DropFlag(Arc<AtomicBool>);

    impl Drop for DropFlag {
        fn drop(&mut self) {
            self.0.store(true, Ordering::SeqCst);
        }
    }

    #[tokio::test]
    async fn blocking_work_keeps_its_slot_after_a_timeout_until_it_returns() {
        let settings = RuntimeSettings {
            handler_timeout: Duration::from_millis(50),
            max_concurrent_handlers: 1,
            ..settings()
        };
        let (release, released) = std::sync::mpsc::channel::<()>();
        let released = Arc::new(std::sync::Mutex::new(released));
        let saw_cancel = Arc::new(AtomicBool::new(false));
        let observed = Arc::clone(&saw_cancel);
        let runtime = TestRuntime::start(settings, move |rt| {
            rt.register("blocking", move |ctx: Context, _: Value| {
                let (released, observed) = (Arc::clone(&released), Arc::clone(&observed));
                async move {
                    let probe = ctx.clone();
                    ctx.spawn_blocking(move || {
                        released.lock().unwrap().recv().unwrap();
                        observed.store(probe.is_cancelled(), Ordering::SeqCst);
                    })
                    .await?;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::GATEWAY_TIMEOUT);
        let busy = runtime.send(invoke_request(r#"{"input":2}"#)).await;
        assert_eq!(
            busy.status(),
            StatusCode::TOO_MANY_REQUESTS,
            "the slot is still held"
        );
        release.send(()).unwrap();
        assert_drained(&runtime).await;
        assert!(saw_cancel.load(Ordering::SeqCst));
    }

    #[tokio::test]
    async fn a_disconnected_client_cancels_the_handler_and_still_gets_a_callback() {
        let started = Arc::new(Notify::new());
        let signal = Arc::clone(&started);
        let runtime = TestRuntime::start(settings(), move |rt| {
            rt.register("hang", move |_: Context, _: Value| {
                let signal = Arc::clone(&signal);
                async move {
                    signal.notify_one();
                    std::future::pending::<()>().await;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let request = tokio::spawn(
            runtime
                .runtime
                .router()
                .oneshot(invoke_request(r#"{"input":1}"#)),
        );
        handler_started(&started).await;
        request.abort();
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(
            callback.body,
            json!({"success": false, "output": null,
                   "error": {"code": "INVOCATION_CANCELLED", "message": "Invocation cancelled"}})
        );
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn oversized_output_is_rejected_with_a_callback() {
        let settings = RuntimeSettings {
            max_output_bytes: 16,
            ..settings()
        };
        let runtime = TestRuntime::start(settings, |rt| {
            rt.register("big", |_: Context, _: Value| async {
                Ok::<_, BoxError>("x".repeat(64))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        let expected = json!({"code": "RUNTIME_OUTPUT_TOO_LARGE",
                              "message": "Runtime output exceeds configured byte limit"});
        assert_eq!(body_json(response).await, json!({"error": expected}));
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.body["error"], expected);
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn output_json_cannot_represent_is_a_serialization_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("tuple-keys", |_: Context, _: Value| async {
                Ok::<_, BoxError>(HashMap::from([((1, 2), 3)]))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "OUTPUT_SERIALIZATION_ERROR",
                             "message": "Handler output could not be serialized"}})
        );
    }

    #[tokio::test]
    async fn the_envelope_sets_status_allowed_headers_and_markers() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("png", |_: Context, _: Value| async {
                Ok::<_, BoxError>(
                    HandlerResponse::new(json!("iVBORw0KGgo="), 201)
                        .header("Content-Type", "image/png")
                        .header("Location", "/things/1")
                        .header("X-Execution-Id", "spoofed")
                        .header("X-NanoFaaS-Function-Status", "false")
                        .encoding("base64"),
                )
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::CREATED);
        let headers = response.headers().clone();
        assert_eq!(headers["content-type"], "image/png");
        assert_eq!(headers["location"], "/things/1");
        assert!(!headers.contains_key("x-execution-id"));
        assert_eq!(headers["x-nanofaas-function-status"], "true");
        assert_eq!(headers["x-nanofaas-encoding"], "base64");
        assert_eq!(body_text(response).await, r#""iVBORw0KGgo=""#);
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(
            callback.body,
            json!({"success": true, "output": "iVBORw0KGgo=", "error": null, "statusCode": 201,
                   "headers": {"Content-Type": "image/png", "Location": "/things/1"},
                   "encoding": "base64"})
        );
    }

    #[tokio::test]
    async fn an_envelope_with_an_invalid_status_is_a_platform_error() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("bad", |_: Context, _: Value| async {
                Ok::<_, BoxError>(HandlerResponse::new(json!("x"), 99))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::INTERNAL_SERVER_ERROR);
        assert_eq!(
            body_json(response).await,
            json!({"error": {"code": "OUTPUT_SERIALIZATION_ERROR",
                             "message": "Handler returned invalid statusCode: 99"}})
        );
    }

    /// The frozen wire contract shared with platform/common, sdks/python, sdks/go and
    /// sdks/javascript. Changing one side silently breaks cross-language dispatch.
    #[tokio::test]
    async fn wire_contract_header_names() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("x", |_: Context, _: Value| async {
                Ok::<_, BoxError>(HandlerResponse::new(json!("x"), 201).encoding("base64"))
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.headers()["X-NanoFaaS-Function-Status"], "true");
        assert_eq!(response.headers()["X-NanoFaaS-Encoding"], "base64");
    }

    #[tokio::test]
    async fn a_missing_input_reaches_the_handler_as_null() {
        let runtime = TestRuntime::start(settings(), |rt| rt.register("echo", echo)).await;
        let response = runtime
            .send(invoke_request(r#"{"metadata":{"k":"v"}}"#))
            .await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(body_json(response).await, json!({"echo": null}));
    }

    #[tokio::test]
    async fn envelope_header_values_http_cannot_carry_are_dropped_everywhere() {
        let runtime = TestRuntime::start(settings(), |rt| {
            rt.register("inject", |_: Context, _: Value| async {
                Ok::<_, BoxError>(
                    HandlerResponse::new(json!("x"), 200)
                        .header("Content-Type", "text/plain\r\nX-Injected: 1")
                        .header("Location", "/ok"),
                )
            })
        })
        .await;
        let response = runtime.send(invoke_request(r#"{"input":1}"#)).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()["content-type"], "application/json");
        assert_eq!(response.headers()["location"], "/ok");
        assert!(!response.headers().contains_key("x-injected"));
        let callback = runtime.callbacks.wait_for(1, |_| true).await.remove(0);
        assert_eq!(callback.body["headers"], json!({"Location": "/ok"}));
    }

    #[tokio::test]
    async fn a_burst_above_the_handler_cap_admits_exactly_the_cap_and_drains() {
        let settings = RuntimeSettings {
            max_concurrent_handlers: 4,
            ..settings()
        };
        let (release, released) = tokio::sync::watch::channel(false);
        let runtime = TestRuntime::start(settings, move |rt| {
            rt.register("held", move |_: Context, input: Value| {
                let mut released = released.clone();
                async move {
                    let _ = released.wait_for(|open| *open).await;
                    Ok::<_, BoxError>(input)
                }
            })
        })
        .await;
        let router = runtime.runtime.router();
        let mut burst = tokio::task::JoinSet::new();
        for i in 0..32 {
            burst.spawn(router.clone().oneshot({
                let mut request = invoke_request(format!(r#"{{"input":{i}}}"#));
                request.headers_mut().insert(
                    "x-execution-id",
                    axum::http::HeaderValue::from_str(&format!("burst-{i}")).unwrap(),
                );
                request
            }));
        }
        // The four admitted handlers hold their slots until released, so every response that
        // arrives first is a rejection.
        let rejections = async {
            for _ in 0..28 {
                let response = burst.join_next().await.unwrap().unwrap().unwrap();
                assert_eq!(response.status(), StatusCode::TOO_MANY_REQUESTS);
            }
        };
        tokio::time::timeout(Duration::from_secs(5), rejections)
            .await
            .expect("more than four requests were admitted");
        release.send_replace(true);
        let mut succeeded = 0;
        while let Some(response) = burst.join_next().await {
            assert_eq!(response.unwrap().unwrap().status(), StatusCode::OK);
            succeeded += 1;
        }
        assert_eq!(succeeded, 4);
        assert_drained(&runtime).await;
    }

    #[tokio::test]
    async fn output_bytes_stay_counted_until_the_body_is_written() {
        let runtime = TestRuntime::start(settings(), |rt| rt.register("echo", echo)).await;
        let response = runtime.send(invoke_request(r#"{"input":"abc"}"#)).await;
        let expected = br#"{"echo":"abc"}"#;
        assert_eq!(
            runtime.runtime.shared().limits.snapshot().output_bytes,
            expected.len()
        );
        assert_eq!(&body_bytes(response).await[..], expected);
        assert_eq!(runtime.runtime.shared().limits.snapshot().output_bytes, 0);
    }

    #[tokio::test]
    async fn exhausted_callbacks_are_counted_in_metrics() {
        let callbacks = FakeCallbackServer::start(|_| Reply::Status(503)).await;
        let settings = RuntimeSettings {
            callback_max_attempts: 2,
            ..settings()
        };
        let runtime =
            TestRuntime::start_with(settings, callbacks, |rt| rt.register("echo", echo)).await;
        assert_eq!(
            runtime
                .send(invoke_request(r#"{"input":1}"#))
                .await
                .status(),
            StatusCode::OK
        );
        assert_drained(&runtime).await;
        assert_eq!(runtime.callbacks.received().len(), 2);
        let metrics = body_text(runtime.send(get("/metrics")).await).await;
        assert!(
            metrics.contains("nanofaas_runtime_callback_drops_total 1"),
            "{metrics}"
        );
        assert!(metrics.contains(r#"nanofaas_runtime_invocations_total{status="success"} 1"#));
    }

    #[tokio::test]
    async fn stop_reports_handlers_still_running_at_the_deadline() {
        let settings = RuntimeSettings {
            shutdown_timeout: Duration::from_millis(100),
            ..settings()
        };
        let started = Arc::new(Notify::new());
        let signal = Arc::clone(&started);
        let (release, released) = std::sync::mpsc::channel::<()>();
        let released = Arc::new(std::sync::Mutex::new(released));
        let mut runtime = TestRuntime::start(settings, move |rt| {
            rt.register("stuck", move |ctx: Context, _: Value| {
                let (signal, released) = (Arc::clone(&signal), Arc::clone(&released));
                async move {
                    ctx.spawn_blocking(move || {
                        signal.notify_one();
                        released.lock().unwrap().recv().unwrap();
                    })
                    .await?;
                    Ok::<Value, BoxError>(Value::Null)
                }
            })
        })
        .await;
        let _request = tokio::spawn(
            runtime
                .runtime
                .router()
                .oneshot(invoke_request(r#"{"input":1}"#)),
        );
        handler_started(&started).await;
        let stopped = tokio::time::Instant::now();
        assert!(matches!(
            runtime.stop().await,
            Err(crate::Error::ShutdownTimedOut)
        ));
        assert!(
            stopped.elapsed() < Duration::from_secs(1),
            "stop is bounded"
        );
        release.send(()).unwrap();
    }

    #[test]
    #[should_panic(expected = "register every handler before serving")]
    fn registering_after_serving_panics() {
        let runtime = Runtime::with_settings(settings());
        let _router = runtime.router();
        let _ = runtime.register("late", echo);
    }
}
