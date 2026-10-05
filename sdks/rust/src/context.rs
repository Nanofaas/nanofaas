use std::collections::HashMap;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};

use crate::limits::HandlerReservation;

/// Per-invocation identity and helpers, handed to every handler call.
#[derive(Clone)]
pub struct Context {
    inner: Arc<Inner>,
}

struct Inner {
    execution_id: String,
    trace_id: Option<String>,
    metadata: HashMap<String, String>,
    headers: HashMap<String, String>,
    cancelled: Arc<AtomicBool>,
    reservation: Arc<HandlerReservation>,
}

impl Context {
    pub(crate) fn new(
        execution_id: String,
        trace_id: Option<String>,
        metadata: HashMap<String, String>,
        headers: HashMap<String, String>,
        cancelled: Arc<AtomicBool>,
        reservation: Arc<HandlerReservation>,
    ) -> Self {
        Self {
            inner: Arc::new(Inner {
                execution_id,
                trace_id,
                metadata,
                headers,
                cancelled,
                reservation,
            }),
        }
    }

    pub fn execution_id(&self) -> &str {
        &self.inner.execution_id
    }

    pub(crate) fn mark_handler_started(&self) {
        self.inner.reservation.mark_handler_started();
    }

    pub fn trace_id(&self) -> Option<&str> {
        self.inner.trace_id.as_deref()
    }

    /// The `metadata` object of the invocation request.
    pub fn metadata(&self) -> &HashMap<String, String> {
        &self.inner.metadata
    }

    /// The `headers` object of the invocation request.
    pub fn headers(&self) -> &HashMap<String, String> {
        &self.inner.headers
    }

    /// True once the runtime gave up on this invocation, on timeout or client disconnect. An
    /// async handler is dropped at that point; blocking work started with `spawn_blocking` should
    /// poll this and return early.
    pub fn is_cancelled(&self) -> bool {
        self.inner.cancelled.load(Ordering::Acquire)
    }

    /// Runs blocking work on tokio's blocking pool. The closure keeps this invocation's handler
    /// slot and input bytes reserved until it returns, even after the runtime stopped waiting.
    pub fn spawn_blocking<F, R>(&self, work: F) -> tokio::task::JoinHandle<R>
    where
        F: FnOnce() -> R + Send + 'static,
        R: Send + 'static,
    {
        let reservation = Arc::clone(&self.inner.reservation);
        tokio::task::spawn_blocking(move || {
            let _reservation = reservation;
            work()
        })
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::limits::Limits;

    fn context(limits: &Arc<Limits>, cancelled: Arc<AtomicBool>) -> Context {
        let reservation = Arc::new(limits.try_reserve_handler().unwrap());
        Context::new(
            "exec-1".into(),
            Some("trace-1".into()),
            HashMap::from([("k".to_string(), "v".to_string())]),
            HashMap::from([("h".to_string(), "w".to_string())]),
            cancelled,
            reservation,
        )
    }

    #[test]
    fn exposes_identity_metadata_and_headers() {
        let limits = Limits::new(1);
        limits.set_accepting(true);
        let ctx = context(&limits, Arc::new(AtomicBool::new(false)));
        assert_eq!(ctx.execution_id(), "exec-1");
        assert_eq!(ctx.trace_id(), Some("trace-1"));
        assert_eq!(ctx.metadata()["k"], "v");
        assert_eq!(ctx.headers()["h"], "w");
        assert!(!ctx.is_cancelled());
    }

    #[tokio::test]
    async fn spawn_blocking_keeps_the_handler_slot_until_the_closure_returns() {
        let limits = Limits::new(1);
        limits.set_accepting(true);
        let cancelled = Arc::new(AtomicBool::new(false));
        let ctx = context(&limits, Arc::clone(&cancelled));
        let (release, released) = std::sync::mpsc::channel::<()>();
        let probe = ctx.clone();
        let work = ctx.spawn_blocking(move || {
            released.recv().unwrap();
            probe.is_cancelled()
        });
        drop(ctx);
        assert_eq!(limits.snapshot().active_handlers, 1);
        cancelled.store(true, Ordering::Release);
        release.send(()).unwrap();
        assert!(work.await.unwrap(), "the closure saw the cancellation");
        assert_eq!(limits.snapshot().active_handlers, 0);
    }
}
