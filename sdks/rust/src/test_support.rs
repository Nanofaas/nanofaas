//! Shared test fixtures: a fake control-plane callback endpoint.

use std::sync::{Arc, Mutex};
use std::time::Duration;

use axum::Router;
use axum::body::Bytes;
use axum::extract::{Request, State};
use axum::http::{HeaderMap, StatusCode};
use axum::routing::any;
use http_body_util::BodyExt;
use tokio::net::TcpListener;
use tokio::sync::{Notify, watch};

/// How the fake answers one callback attempt.
#[derive(Clone, Copy, Debug)]
pub(crate) enum Reply {
    Status(u16),
    /// Records the attempt, then waits for `release_held` before answering.
    HoldThen(u16),
}

#[derive(Clone, Debug)]
pub(crate) struct RecordedCallback {
    pub method: String,
    pub path: String,
    pub headers: HeaderMap,
    pub body: serde_json::Value,
    pub status: u16,
}

type Responder = dyn Fn(&RecordedCallback) -> Reply + Send + Sync;

struct FakeState {
    received: Mutex<Vec<RecordedCallback>>,
    changed: Notify,
    respond: Box<Responder>,
    release: watch::Sender<bool>,
}

pub(crate) struct FakeCallbackServer {
    pub url: String,
    state: Arc<FakeState>,
}

impl FakeCallbackServer {
    /// Answers every attempt with 204.
    pub async fn accepting() -> Self {
        Self::start(|_| Reply::Status(204)).await
    }

    pub async fn start(
        respond: impl Fn(&RecordedCallback) -> Reply + Send + Sync + 'static,
    ) -> Self {
        let state = Arc::new(FakeState {
            received: Mutex::new(Vec::new()),
            changed: Notify::new(),
            respond: Box::new(respond),
            release: watch::channel(false).0,
        });
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let url = format!("http://{}", listener.local_addr().unwrap());
        let app = Router::new()
            .fallback(any(receive))
            .with_state(Arc::clone(&state));
        tokio::spawn(async move { axum::serve(listener, app).await.unwrap() });
        Self { url, state }
    }

    pub fn received(&self) -> Vec<RecordedCallback> {
        self.state.received.lock().unwrap().clone()
    }

    pub fn release_held(&self) {
        self.state.release.send_replace(true);
    }

    /// Waits until at least `count` recorded attempts satisfy `matches`, or panics after 5 s.
    pub async fn wait_for(
        &self,
        count: usize,
        matches: impl Fn(&RecordedCallback) -> bool,
    ) -> Vec<RecordedCallback> {
        let wait = async {
            loop {
                let notified = self.state.changed.notified();
                tokio::pin!(notified);
                notified.as_mut().enable();
                let found: Vec<_> = self.received().into_iter().filter(|r| matches(r)).collect();
                if found.len() >= count {
                    return found;
                }
                notified.await;
            }
        };
        tokio::time::timeout(Duration::from_secs(5), wait)
            .await
            .expect("expected callbacks never arrived")
    }
}

async fn receive(State(state): State<Arc<FakeState>>, request: Request) -> StatusCode {
    let (parts, body) = request.into_parts();
    let body: Bytes = body.collect().await.unwrap().to_bytes();
    let mut recorded = RecordedCallback {
        method: parts.method.to_string(),
        path: parts.uri.path().to_string(),
        headers: parts.headers,
        body: serde_json::from_slice(&body).unwrap_or(serde_json::Value::Null),
        status: 0,
    };
    let reply = (state.respond)(&recorded);
    let status = match reply {
        Reply::Status(status) | Reply::HoldThen(status) => status,
    };
    recorded.status = status;
    state.received.lock().unwrap().push(recorded);
    state.changed.notify_waiters();
    if let Reply::HoldThen(_) = reply {
        let mut release = state.release.subscribe();
        let _ = release.wait_for(|released| *released).await;
    }
    StatusCode::from_u16(status).unwrap()
}
/// A runtime serving on an ephemeral port (so admission is open) with a fake callback endpoint.
/// Requests go straight to the router, as the Go tests call `Handler().ServeHTTP`.
pub(crate) struct TestRuntime {
    pub runtime: Arc<crate::Runtime>,
    pub callbacks: FakeCallbackServer,
    stop: Option<tokio::sync::oneshot::Sender<()>>,
    served: Option<tokio::task::JoinHandle<Result<(), crate::Error>>>,
}

impl TestRuntime {
    pub async fn start(
        settings: crate::RuntimeSettings,
        register: impl FnOnce(crate::Runtime) -> crate::Runtime,
    ) -> Self {
        Self::start_with(settings, FakeCallbackServer::accepting().await, register).await
    }

    pub async fn start_with(
        settings: crate::RuntimeSettings,
        callbacks: FakeCallbackServer,
        register: impl FnOnce(crate::Runtime) -> crate::Runtime,
    ) -> Self {
        let settings = crate::RuntimeSettings {
            callback_url: Some(callbacks.url.clone()),
            ..settings
        };
        let runtime = register(crate::Runtime::with_settings(settings)).without_callback_backoff();
        let mut started = Self {
            runtime: Arc::new(runtime),
            callbacks,
            stop: None,
            served: None,
        };
        started.restart().await;
        started
    }

    /// Serves again; the previous run must have been stopped.
    pub async fn restart(&mut self) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (stop, stopped) = tokio::sync::oneshot::channel::<()>();
        let runtime = Arc::clone(&self.runtime);
        self.served = Some(tokio::spawn(async move {
            runtime
                .serve(listener, async move {
                    let _ = stopped.await;
                })
                .await
        }));
        self.stop = Some(stop);
        self.runtime
            .state()
            .wait_for(|state| *state == crate::runtime::RunState::Running)
            .await
            .unwrap();
    }

    pub async fn stop(&mut self) -> Result<(), crate::Error> {
        let _ = self.stop.take().expect("serving").send(());
        self.served.take().expect("serving").await.unwrap()
    }

    pub async fn send(
        &self,
        request: axum::http::Request<axum::body::Body>,
    ) -> axum::response::Response {
        use tower::ServiceExt;
        self.runtime.router().oneshot(request).await.unwrap()
    }
}

pub(crate) fn get(path: &str) -> axum::http::Request<axum::body::Body> {
    axum::http::Request::get(path)
        .body(axum::body::Body::empty())
        .unwrap()
}

/// `POST /invoke` with execution id `exec-1`, trace `trace-1`, dispatch attempt `1`.
pub(crate) fn invoke_request(
    body: impl Into<axum::body::Body>,
) -> axum::http::Request<axum::body::Body> {
    axum::http::Request::post("/invoke")
        .header("x-execution-id", "exec-1")
        .header("x-trace-id", "trace-1")
        .header("x-dispatch-attempt", "1")
        .body(body.into())
        .unwrap()
}

pub(crate) async fn body_bytes(response: axum::response::Response) -> Bytes {
    response.into_body().collect().await.unwrap().to_bytes()
}

pub(crate) async fn body_text(response: axum::response::Response) -> String {
    String::from_utf8(body_bytes(response).await.to_vec()).unwrap()
}

pub(crate) async fn body_json(response: axum::response::Response) -> serde_json::Value {
    serde_json::from_slice(&body_bytes(response).await).unwrap()
}

/// A request body that never yields a byte.
pub(crate) struct PendingBody;

impl http_body::Body for PendingBody {
    type Data = Bytes;
    type Error = std::convert::Infallible;

    fn poll_frame(
        self: std::pin::Pin<&mut Self>,
        _cx: &mut std::task::Context<'_>,
    ) -> std::task::Poll<Option<Result<http_body::Frame<Bytes>, Self::Error>>> {
        std::task::Poll::Pending
    }
}

/// Waits for a handler to signal that it started, failing instead of hanging if it never does.
pub(crate) async fn handler_started(signal: &Notify) {
    tokio::time::timeout(Duration::from_secs(5), signal.notified())
        .await
        .expect("the handler never started");
}
