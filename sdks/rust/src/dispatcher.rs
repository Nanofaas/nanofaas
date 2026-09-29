use std::sync::{Arc, Mutex, MutexGuard};

use bytes::Bytes;
use prometheus_client::metrics::counter::Counter;
use tokio::sync::{Notify, mpsc, watch};
use tokio::task::JoinSet;
use tokio::time::Instant;

use crate::bounded::{EncodeError, to_vec_bounded};
use crate::callback::{Callback, CallbackClient, Identity};
use crate::settings::RuntimeSettings;
use crate::types::InvocationResult;

/// Room for every canonical runtime failure callback. A smaller payload cap could not keep the
/// promise that every admitted handler ends with a terminal callback.
pub(crate) const MINIMUM_TERMINAL_CALLBACK_PAYLOAD_BYTES: usize = 256;
const WORKERS: usize = 2;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct CallbackSnapshot {
    pub pending_callbacks: usize,
    pub pending_callback_bytes: usize,
    pub serialized_callback_bytes: usize,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum SubmitError {
    TooLarge,
    Serialization,
    Closed,
}

/// A refused submission hands its reservation back, so the caller can still send a failure.
pub(crate) struct Rejected {
    pub error: SubmitError,
    pub reservation: CallbackReservation,
}

#[derive(Debug, PartialEq, Eq)]
pub(crate) struct NotDrained;

struct Job {
    callback: Callback,
    _reservation: CallbackReservation,
}

struct Running {
    jobs: mpsc::Sender<Job>,
    cancel: watch::Sender<bool>,
    workers: JoinSet<()>,
}

struct DispatchState {
    counts: CallbackSnapshot,
    running: Option<Running>,
}

/// Bounded callback delivery. Capacity is reserved (one count plus the maximum payload) before a
/// handler starts, so accepted work always has room for its terminal callback.
pub(crate) struct Dispatcher {
    pub(crate) client: Arc<CallbackClient>,
    max_pending_callbacks: usize,
    max_pending_callback_bytes: usize,
    pub(crate) max_callback_payload_bytes: usize,
    drops: Counter,
    state: Mutex<DispatchState>,
    changed: Notify,
}

impl Dispatcher {
    pub fn new(settings: &RuntimeSettings, client: CallbackClient, drops: Counter) -> Arc<Self> {
        Arc::new(Self {
            client: Arc::new(client),
            max_pending_callbacks: settings.max_pending_callbacks,
            max_pending_callback_bytes: settings.max_pending_callback_bytes,
            max_callback_payload_bytes: settings.max_callback_payload_bytes,
            drops,
            state: Mutex::new(DispatchState {
                counts: CallbackSnapshot::default(),
                running: None,
            }),
            changed: Notify::new(),
        })
    }

    #[cfg(test)]
    pub fn snapshot(&self) -> CallbackSnapshot {
        self.lock().counts
    }

    /// Starts the delivery workers. Refused while running, or while an earlier run still owns
    /// callbacks.
    pub fn open(&self) -> Result<(), NotDrained> {
        let mut state = self.lock();
        if state.running.is_some() || state.counts != CallbackSnapshot::default() {
            return Err(NotDrained);
        }
        let (jobs, receiver) = mpsc::channel(self.max_pending_callbacks);
        let receiver = Arc::new(tokio::sync::Mutex::new(receiver));
        let (cancel, cancelled) = watch::channel(false);
        let mut workers = JoinSet::new();
        for _ in 0..WORKERS {
            workers.spawn(work(
                Arc::clone(&self.client),
                Arc::clone(&receiver),
                cancelled.clone(),
                self.drops.clone(),
            ));
        }
        state.running = Some(Running {
            jobs,
            cancel,
            workers,
        });
        Ok(())
    }

    pub fn try_reserve(self: &Arc<Self>) -> Option<CallbackReservation> {
        let mut state = self.lock();
        let counts = state.counts;
        let bytes_left = self.max_pending_callback_bytes - counts.pending_callback_bytes;
        if state.running.is_none()
            || counts.pending_callbacks >= self.max_pending_callbacks
            || self.max_callback_payload_bytes > bytes_left
        {
            return None;
        }
        state.counts.pending_callbacks += 1;
        state.counts.pending_callback_bytes += self.max_callback_payload_bytes;
        drop(state);
        self.changed.notify_waiters();
        Some(CallbackReservation {
            dispatcher: Arc::clone(self),
            held_bytes: self.max_callback_payload_bytes,
            serialized_bytes: 0,
        })
    }

    /// Serializes `result` within the payload cap and queues it with its reservation.
    pub fn submit(
        &self,
        mut reservation: CallbackReservation,
        identity: &Identity,
        result: &InvocationResult<'_>,
    ) -> Result<(), Rejected> {
        let body = match to_vec_bounded(result, self.max_callback_payload_bytes) {
            Ok(body) => body,
            Err(EncodeError::TooLarge) => return Err(rejected(SubmitError::TooLarge, reservation)),
            Err(EncodeError::Serialization) => {
                return Err(rejected(SubmitError::Serialization, reservation));
            }
        };
        let state = self.lock();
        let Some(running) = state.running.as_ref() else {
            drop(state);
            return Err(rejected(SubmitError::Closed, reservation));
        };
        let jobs = running.jobs.clone();
        drop(state);
        reservation.hold_serialized(body.len());
        let job = Job {
            callback: Callback {
                identity: identity.clone(),
                body: Bytes::from(body),
            },
            _reservation: reservation,
        };
        jobs.try_send(job).map_err(|error| {
            let mut reservation = error.into_inner()._reservation;
            reservation.release_serialized();
            rejected(SubmitError::Closed, reservation)
        })
    }

    /// Stops intake and lets the workers drain until `deadline`; then cancels whatever is still
    /// in flight or queued (each counted as a drop) and waits for the workers to return.
    pub async fn close(&self, deadline: Instant) {
        let Some(Running {
            jobs,
            cancel,
            mut workers,
        }) = self.lock().running.take()
        else {
            return;
        };
        drop(jobs);
        if tokio::time::timeout_at(deadline, join_all(&mut workers))
            .await
            .is_err()
        {
            cancel.send_replace(true);
            join_all(&mut workers).await;
        }
    }

    /// Resolves once no callback count, byte or serialized body is owned.
    #[cfg(test)]
    pub async fn wait_until_idle(&self) {
        loop {
            let notified = self.changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if self.snapshot() == CallbackSnapshot::default() {
                return;
            }
            notified.await;
        }
    }

    fn update(&self, change: impl FnOnce(&mut CallbackSnapshot)) {
        change(&mut self.lock().counts);
        self.changed.notify_waiters();
    }

    fn lock(&self) -> MutexGuard<'_, DispatchState> {
        self.state
            .lock()
            .expect("dispatcher lock is never held across a panic")
    }
}

fn rejected(error: SubmitError, reservation: CallbackReservation) -> Rejected {
    Rejected { error, reservation }
}

async fn join_all(workers: &mut JoinSet<()>) {
    while workers.join_next().await.is_some() {}
}

async fn work(
    client: Arc<CallbackClient>,
    jobs: Arc<tokio::sync::Mutex<mpsc::Receiver<Job>>>,
    mut cancel: watch::Receiver<bool>,
    drops: Counter,
) {
    loop {
        let next = jobs.lock().await.recv().await;
        let Some(job) = next else { return };
        if !client.deliver(&job.callback, &mut cancel).await {
            drops.inc();
            tracing::warn!(
                execution_id = %job.callback.identity.execution_id,
                "callback delivery exhausted"
            );
        }
    }
}

/// One pending callback: a count plus its byte share (the maximum payload until serialized,
/// then the serialized size) and the serialized body. All of it returns on drop.
pub(crate) struct CallbackReservation {
    dispatcher: Arc<Dispatcher>,
    held_bytes: usize,
    serialized_bytes: usize,
}

impl CallbackReservation {
    /// Admission reserved the maximum payload because the output was unknown. Once encoded, the
    /// callback keeps only its own size; it never grows.
    fn hold_serialized(&mut self, len: usize) {
        let freed = self.held_bytes.saturating_sub(len);
        self.dispatcher.update(|counts| {
            counts.serialized_callback_bytes += len;
            counts.pending_callback_bytes -= freed;
        });
        self.held_bytes -= freed;
        self.serialized_bytes = len;
    }

    fn release_serialized(&mut self) {
        let bytes = std::mem::take(&mut self.serialized_bytes);
        self.dispatcher
            .update(|counts| counts.serialized_callback_bytes -= bytes);
    }
}

impl Drop for CallbackReservation {
    fn drop(&mut self) {
        let (held, serialized) = (self.held_bytes, self.serialized_bytes);
        self.dispatcher.update(|counts| {
            counts.pending_callbacks -= 1;
            counts.pending_callback_bytes -= held;
            counts.serialized_callback_bytes -= serialized;
        });
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{FakeCallbackServer, Reply};
    use serde_json::json;
    use serde_json::value::RawValue;
    use std::time::Duration;

    fn dispatcher(
        url: &str,
        pending: usize,
        pending_bytes: usize,
        payload: usize,
    ) -> Arc<Dispatcher> {
        let settings = RuntimeSettings {
            max_pending_callbacks: pending,
            max_pending_callback_bytes: pending_bytes,
            max_callback_payload_bytes: payload,
            ..RuntimeSettings::default()
        };
        let mut client = CallbackClient::new(Some(url.into()), Duration::from_secs(1), 2);
        client.retry_delays = vec![Duration::ZERO; 2];
        let dispatcher = Dispatcher::new(&settings, client, Counter::default());
        dispatcher.open().unwrap();
        dispatcher
    }

    fn identity(execution_id: &str) -> Identity {
        Identity {
            execution_id: execution_id.into(),
            trace_id: None,
            dispatch_attempt: Some("1".into()),
        }
    }

    fn ok_result() -> InvocationResult<'static> {
        let output: &'static RawValue = serde_json::from_str(r#"{"result":"ok"}"#).unwrap();
        InvocationResult::success(output)
    }

    fn soon() -> Instant {
        Instant::now() + Duration::from_secs(2)
    }

    #[tokio::test]
    async fn reserves_count_and_maximum_payload_before_serialization() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 2, 2048, 1024);
        let first = dispatcher.try_reserve().unwrap();
        assert_eq!(
            dispatcher.snapshot(),
            CallbackSnapshot {
                pending_callbacks: 1,
                pending_callback_bytes: 1024,
                serialized_callback_bytes: 0
            }
        );
        let _second = dispatcher.try_reserve().unwrap();
        assert!(dispatcher.try_reserve().is_none(), "count cap reached");
        drop(first);
        assert_eq!(dispatcher.snapshot().pending_callbacks, 1);
    }

    #[tokio::test]
    async fn the_byte_budget_caps_reservations_before_the_count_does() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 10, 1024, 1024);
        let _held = dispatcher.try_reserve().unwrap();
        assert!(dispatcher.try_reserve().is_none());
    }

    #[tokio::test]
    async fn refuses_reservations_while_closed() {
        let settings = RuntimeSettings::default();
        let client = CallbackClient::new(None, Duration::from_secs(1), 1);
        let closed = Dispatcher::new(&settings, client, Counter::default());
        assert!(closed.try_reserve().is_none());
        assert_eq!(closed.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn a_submitted_callback_holds_its_serialized_size_until_delivered() {
        let server = FakeCallbackServer::start(|_| Reply::HoldThen(204)).await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let reservation = dispatcher.try_reserve().unwrap();
        dispatcher
            .submit(reservation, &identity("exec-1"), &ok_result())
            .unwrap_or_else(|_| panic!("submit failed"));
        let size = serde_json::to_vec(&ok_result()).unwrap().len();
        assert_eq!(
            dispatcher.snapshot(),
            CallbackSnapshot {
                pending_callbacks: 1,
                pending_callback_bytes: size,
                serialized_callback_bytes: size
            }
        );
        let received = server.wait_for(1, |_| true).await;
        assert_eq!(received[0].path, "/exec-1:complete");
        assert_eq!(
            received[0].body,
            json!({"success": true, "output": {"result": "ok"}, "error": null})
        );
        server.release_held();
        dispatcher.wait_until_idle().await;
    }

    #[tokio::test]
    async fn an_oversized_callback_is_rejected_without_changing_counters() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 300);
        let reservation = dispatcher.try_reserve().unwrap();
        let big = format!("\"{}\"", "x".repeat(400));
        let output: &RawValue = serde_json::from_str(&big).unwrap();
        let rejected = dispatcher
            .submit(
                reservation,
                &identity("e"),
                &InvocationResult::success(output),
            )
            .err()
            .unwrap();
        assert_eq!(rejected.error, SubmitError::TooLarge);
        assert_eq!(dispatcher.snapshot().pending_callback_bytes, 300);
        drop(rejected);
        assert_eq!(dispatcher.snapshot(), CallbackSnapshot::default());
    }

    #[tokio::test]
    async fn exhausted_delivery_is_counted_and_releases_everything() {
        let server = FakeCallbackServer::start(|_| Reply::Status(503)).await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let reservation = dispatcher.try_reserve().unwrap();
        dispatcher
            .submit(reservation, &identity("e"), &ok_result())
            .unwrap_or_else(|_| panic!("submit failed"));
        dispatcher.wait_until_idle().await;
        assert_eq!(server.received().len(), 2);
        assert_eq!(dispatcher.drops.get(), 1);
    }

    #[tokio::test]
    async fn close_drains_queued_callbacks_before_the_deadline() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 4, 4096, 1024);
        for id in ["a", "b", "c"] {
            let reservation = dispatcher.try_reserve().unwrap();
            dispatcher
                .submit(reservation, &identity(id), &ok_result())
                .unwrap_or_else(|_| panic!("submit failed"));
        }
        dispatcher.close(soon()).await;
        assert_eq!(server.received().len(), 3);
        assert_eq!(dispatcher.snapshot(), CallbackSnapshot::default());
        assert!(dispatcher.try_reserve().is_none(), "closed after close()");
    }

    #[tokio::test]
    async fn close_cancels_in_flight_delivery_at_the_deadline() {
        let server = FakeCallbackServer::start(|_| Reply::HoldThen(204)).await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let reservation = dispatcher.try_reserve().unwrap();
        dispatcher
            .submit(reservation, &identity("e"), &ok_result())
            .unwrap_or_else(|_| panic!("submit failed"));
        server.wait_for(1, |_| true).await;
        let started = Instant::now();
        dispatcher
            .close(Instant::now() + Duration::from_millis(50))
            .await;
        assert!(started.elapsed() < Duration::from_secs(1));
        assert_eq!(dispatcher.snapshot(), CallbackSnapshot::default());
        assert_eq!(dispatcher.drops.get(), 1);
    }

    #[tokio::test]
    async fn open_is_refused_while_an_earlier_run_owns_callbacks() {
        let server = FakeCallbackServer::accepting().await;
        let dispatcher = dispatcher(&server.url, 1, 1024, 1024);
        let held = dispatcher.try_reserve().unwrap();
        dispatcher.close(soon()).await;
        assert_eq!(dispatcher.open(), Err(NotDrained));
        drop(held);
        assert_eq!(dispatcher.open(), Ok(()));
    }
}
