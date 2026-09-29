use std::collections::HashMap;
use std::fmt;
use std::future::{Future, IntoFuture};
use std::net::Ipv4Addr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, OnceLock};

use axum::Router;
use axum::extract::{DefaultBodyLimit, State};
use axum::http::StatusCode;
use axum::http::header::CONTENT_TYPE;
use axum::response::{IntoResponse, Response};
use axum::routing::any;
use serde::Serialize;
use serde::de::DeserializeOwned;
use tokio::net::TcpListener;
use tokio::sync::{oneshot, watch};
use tokio::time::Instant;

use crate::callback::CallbackClient;
use crate::context::Context;
use crate::dispatcher::Dispatcher;
use crate::handler::{self, BoxError, ErasedHandler};
use crate::limits::Limits;
use crate::metrics::{self, Metrics};
use crate::settings::RuntimeSettings;

#[derive(Debug)]
pub enum Error {
    /// The listener could not bind `PORT`.
    Bind(std::io::Error),
    /// The HTTP server failed while serving.
    Serve(std::io::Error),
    /// `serve` was called while running, or while an earlier run still owns callbacks.
    NotDrained,
    /// Handler work was still running when `NANOFAAS_SHUTDOWN_TIMEOUT` expired.
    ShutdownTimedOut,
}

impl fmt::Display for Error {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Bind(error) => write!(f, "cannot bind the runtime port: {error}"),
            Self::Serve(error) => write!(f, "runtime server failed: {error}"),
            Self::NotDrained => f.write_str("runtime is running or still owns callbacks"),
            Self::ShutdownTimedOut => {
                f.write_str("handlers were still running at the shutdown deadline")
            }
        }
    }
}

impl std::error::Error for Error {}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum RunState {
    Idle,
    Running,
    Stopping,
}

/// Marks the first invocation and how long the process waited for it.
pub(crate) struct ColdStart {
    started: Instant,
    first_invocation_done: AtomicBool,
    first_arrival: OnceLock<Instant>,
}

impl ColdStart {
    fn new() -> Self {
        Self {
            started: Instant::now(),
            first_invocation_done: AtomicBool::new(false),
            first_arrival: OnceLock::new(),
        }
    }

    /// True for exactly one caller: the first invocation.
    pub fn first_invocation(&self) -> bool {
        !self.first_invocation_done.swap(true, Ordering::AcqRel)
    }

    pub fn mark_arrival(&self) {
        let _ = self.first_arrival.set(Instant::now());
    }

    pub fn init_duration_ms(&self) -> u128 {
        self.first_arrival.get().map_or(0, |arrival| {
            arrival.duration_since(self.started).as_millis()
        })
    }
}

pub(crate) struct Shared {
    pub settings: RuntimeSettings,
    pub handlers: HashMap<String, ErasedHandler>,
    pub limits: Arc<Limits>,
    pub dispatcher: Arc<Dispatcher>,
    pub metrics: Metrics,
    pub cold_start: ColdStart,
    pub state: watch::Sender<RunState>,
}

impl Shared {
    /// `FUNCTION_HANDLER` when set; otherwise the only registered handler.
    pub fn resolve_handler(&self) -> Option<&ErasedHandler> {
        match &self.settings.function_handler {
            Some(name) => self.handlers.get(name),
            None if self.handlers.len() == 1 => self.handlers.values().next(),
            None => None,
        }
    }
}

/// The warm function runtime: serves `/invoke`, `/health` and `/metrics`.
pub struct Runtime {
    shared: Arc<Shared>,
}

impl Runtime {
    pub fn from_env() -> Self {
        Self::with_settings(RuntimeSettings::from_env())
    }

    pub fn with_settings(settings: RuntimeSettings) -> Self {
        let settings = settings.normalized();
        let metrics = Metrics::new();
        let client = CallbackClient::new(
            settings.callback_url.clone(),
            settings.callback_attempt_timeout,
            settings.callback_max_attempts,
        );
        let dispatcher = Dispatcher::new(&settings, client, metrics.callback_drops.clone());
        Self {
            shared: Arc::new(Shared {
                limits: Limits::new(settings.max_concurrent_handlers),
                settings,
                handlers: HashMap::new(),
                dispatcher,
                metrics,
                cold_start: ColdStart::new(),
                state: watch::channel(RunState::Idle).0,
            }),
        }
    }

    /// Registers a handler. `I` is deserialized from the request's `input`; `O` is serialized as
    /// the response and callback output, unless it is a [`crate::HandlerResponse`] envelope.
    ///
    /// # Panics
    ///
    /// If called after the runtime started serving.
    pub fn register<I, O, F, Fut>(mut self, name: impl Into<String>, handler: F) -> Self
    where
        I: DeserializeOwned + Send + 'static,
        O: Serialize + Send + 'static,
        F: Fn(Context, I) -> Fut + Send + Sync + 'static,
        Fut: Future<Output = Result<O, BoxError>> + Send + 'static,
    {
        Arc::get_mut(&mut self.shared)
            .expect("register every handler before serving")
            .handlers
            .insert(name.into(), handler::erase(handler));
        self
    }

    /// Binds `0.0.0.0:PORT` and serves until SIGTERM or Ctrl-C, then stops within
    /// `NANOFAAS_SHUTDOWN_TIMEOUT`.
    pub async fn start(self) -> Result<(), Error> {
        let port = self.shared.settings.port;
        let listener = TcpListener::bind((Ipv4Addr::UNSPECIFIED, port))
            .await
            .map_err(Error::Bind)?;
        self.serve(listener, shutdown_signal()).await
    }

    /// Serves on `listener` until `shutdown` resolves. Stopping closes admission (new invocations
    /// get 503), then waits for the server, running handlers and queued callbacks, all within one
    /// `shutdown_timeout`. The same runtime may serve again once a run has fully drained.
    pub async fn serve(
        &self,
        listener: TcpListener,
        shutdown: impl Future<Output = ()> + Send,
    ) -> Result<(), Error> {
        let shared = &self.shared;
        shared.dispatcher.open().map_err(|_| Error::NotDrained)?;
        shared.limits.set_accepting(true);
        shared.state.send_replace(RunState::Running);

        let (stop_server, server_stopped) = oneshot::channel::<()>();
        let server = axum::serve(listener, self.router()).with_graceful_shutdown(async move {
            let _ = server_stopped.await;
        });
        let mut server = tokio::spawn(server.into_future());
        let early_exit = tokio::select! {
            joined = &mut server => Some(joined),
            () = shutdown => None,
        };

        shared.limits.set_accepting(false);
        shared.state.send_replace(RunState::Stopping);
        let deadline = Instant::now() + shared.settings.shutdown_timeout;
        let _ = stop_server.send(());
        let joined = match early_exit {
            Some(joined) => joined,
            None => match tokio::time::timeout_at(deadline, &mut server).await {
                Ok(joined) => joined,
                Err(_) => {
                    server.abort();
                    Ok(Ok(()))
                }
            },
        };
        let handlers_done = tokio::time::timeout_at(deadline, shared.limits.wait_until_idle())
            .await
            .is_ok();
        shared.dispatcher.close(deadline).await;
        shared.state.send_replace(RunState::Idle);

        joined
            .unwrap_or_else(|panic| Err(std::io::Error::other(panic)))
            .map_err(Error::Serve)?;
        if handlers_done {
            Ok(())
        } else {
            Err(Error::ShutdownTimedOut)
        }
    }

    pub(crate) fn router(&self) -> Router {
        Router::new()
            .route("/health", any(health))
            .route("/metrics", any(render_metrics))
            .layer(DefaultBodyLimit::disable())
            .with_state(Arc::clone(&self.shared))
    }

    #[cfg(test)]
    pub(crate) fn shared(&self) -> &Arc<Shared> {
        &self.shared
    }

    #[cfg(test)]
    pub(crate) fn state(&self) -> watch::Receiver<RunState> {
        self.shared.state.subscribe()
    }

    /// Tests keep the attempt count but drop the 100/500/2000 ms backoff between attempts.
    #[cfg(test)]
    pub(crate) fn without_callback_backoff(mut self) -> Self {
        let shared = Arc::get_mut(&mut self.shared).expect("configure before serving");
        let dispatcher = Arc::get_mut(&mut shared.dispatcher).expect("configure before serving");
        let client = Arc::get_mut(&mut dispatcher.client).expect("configure before serving");
        client.retry_delays.fill(std::time::Duration::ZERO);
        self
    }
}

pub(crate) fn json_response(status: StatusCode, body: &serde_json::Value) -> Response {
    let body = serde_json::to_vec(body).expect("a Value always serializes");
    (status, [(CONTENT_TYPE, "application/json")], body).into_response()
}

async fn health() -> Response {
    json_response(StatusCode::OK, &serde_json::json!({"status": "ok"}))
}

async fn render_metrics(State(shared): State<Arc<Shared>>) -> Response {
    (
        [(CONTENT_TYPE, metrics::CONTENT_TYPE)],
        shared.metrics.render(),
    )
        .into_response()
}

async fn shutdown_signal() {
    let ctrl_c = async {
        if tokio::signal::ctrl_c().await.is_err() {
            std::future::pending::<()>().await;
        }
    };
    #[cfg(unix)]
    let terminate = async {
        use tokio::signal::unix::{SignalKind, signal};
        match signal(SignalKind::terminate()) {
            Ok(mut terminate) => {
                terminate.recv().await;
            }
            Err(_) => std::future::pending::<()>().await,
        }
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        () = ctrl_c => {}
        () = terminate => {}
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{TestRuntime, body_json, get};
    use serde_json::{Value, json};
    use tower::ServiceExt;

    #[tokio::test]
    async fn health_answers_ok_json() {
        let runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        let response = runtime.send(get("/health")).await;
        assert_eq!(response.status(), StatusCode::OK);
        assert_eq!(response.headers()[CONTENT_TYPE], "application/json");
        assert_eq!(body_json(response).await, json!({"status": "ok"}));
    }

    #[tokio::test]
    async fn metrics_expose_the_runtime_counters() {
        let runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        let response = runtime.send(get("/metrics")).await;
        assert_eq!(response.status(), StatusCode::OK);
        let text = crate::test_support::body_text(response).await;
        assert!(
            text.contains("nanofaas_runtime_cold_starts_total 0"),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_callback_drops_total 0"),
            "{text}"
        );
    }

    #[tokio::test]
    async fn stop_closes_admission_and_the_same_runtime_serves_again() {
        let mut runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        runtime.stop().await.unwrap();
        let response = runtime
            .runtime
            .router()
            .oneshot(get("/health"))
            .await
            .unwrap();
        assert_eq!(
            response.status(),
            StatusCode::OK,
            "health stays reachable in-process"
        );
        assert!(!runtime.runtime.shared().limits.snapshot().accepting);
        runtime.restart().await;
        assert!(runtime.runtime.shared().limits.snapshot().accepting);
        runtime.stop().await.unwrap();
    }

    #[tokio::test]
    async fn serve_refuses_to_restart_over_an_undrained_callback_owner() {
        let mut runtime = TestRuntime::start(RuntimeSettings::default(), |rt| rt).await;
        let held = runtime.runtime.shared().dispatcher.try_reserve().unwrap();
        runtime.stop().await.unwrap();
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let refused = runtime.runtime.serve(listener, async {}).await;
        assert!(matches!(refused, Err(Error::NotDrained)));
        drop(held);
        runtime.restart().await;
        runtime.stop().await.unwrap();
    }

    #[tokio::test]
    async fn start_reports_a_bind_failure_without_opening_the_dispatcher() {
        let taken = std::net::TcpListener::bind("0.0.0.0:0").unwrap();
        let port = taken.local_addr().unwrap().port();
        let runtime = Runtime::with_settings(RuntimeSettings {
            port,
            ..RuntimeSettings::default()
        });
        let shared = Arc::clone(runtime.shared());
        assert!(matches!(runtime.start().await, Err(Error::Bind(_))));
        assert!(shared.dispatcher.try_reserve().is_none());
    }

    #[test]
    fn resolves_the_single_handler_or_the_configured_one() {
        let echo = |_: Context, input: Value| async move { Ok::<_, BoxError>(input) };
        let single = Runtime::with_settings(RuntimeSettings::default()).register("a", echo);
        assert!(single.shared.resolve_handler().is_some());

        let two = Runtime::with_settings(RuntimeSettings::default())
            .register("a", echo)
            .register("b", echo);
        assert!(two.shared.resolve_handler().is_none());

        let chosen = Runtime::with_settings(RuntimeSettings {
            function_handler: Some("b".into()),
            ..RuntimeSettings::default()
        })
        .register("a", echo)
        .register("b", echo);
        assert!(chosen.shared.resolve_handler().is_some());
    }
}
