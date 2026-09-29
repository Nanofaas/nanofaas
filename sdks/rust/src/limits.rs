use std::convert::Infallible;
use std::pin::Pin;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use std::task::Poll;

use bytes::Bytes;
use http_body::{Body, Frame, SizeHint};
use tokio::sync::Notify;

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct LimitSnapshot {
    pub active_handlers: usize,
    pub input_bytes: usize,
    pub output_bytes: usize,
    pub accepting: bool,
}

/// Handler admission and the input/output bytes the runtime retains. Every count is owned by a
/// guard that returns it on drop, so release happens on success, error, panic and abort alike.
pub(crate) struct Limits {
    max_handlers: usize,
    state: Mutex<LimitSnapshot>,
    changed: Notify,
}

impl Limits {
    /// Admission starts closed; `serve` opens it.
    pub fn new(max_handlers: usize) -> Arc<Self> {
        Arc::new(Self {
            max_handlers,
            state: Mutex::new(LimitSnapshot::default()),
            changed: Notify::new(),
        })
    }

    pub fn snapshot(&self) -> LimitSnapshot {
        *self.lock()
    }

    pub fn set_accepting(&self, accepting: bool) {
        self.update(|state| state.accepting = accepting);
    }

    pub fn try_reserve_handler(self: &Arc<Self>) -> Option<HandlerReservation> {
        let mut state = self.lock();
        if !state.accepting || state.active_handlers >= self.max_handlers {
            return None;
        }
        state.active_handlers += 1;
        drop(state);
        self.changed.notify_waiters();
        Some(HandlerReservation {
            limits: Arc::clone(self),
            input_bytes: AtomicUsize::new(0),
        })
    }

    pub fn retain_output(self: &Arc<Self>, bytes: usize) -> OutputGuard {
        self.update(|state| state.output_bytes += bytes);
        OutputGuard {
            limits: Arc::clone(self),
            bytes,
        }
    }

    /// Resolves once no handler work is physically running.
    pub async fn wait_until_idle(&self) {
        loop {
            let notified = self.changed.notified();
            tokio::pin!(notified);
            notified.as_mut().enable();
            if self.snapshot().active_handlers == 0 {
                return;
            }
            notified.await;
        }
    }

    fn update(&self, change: impl FnOnce(&mut LimitSnapshot)) {
        change(&mut self.lock());
        self.changed.notify_waiters();
    }

    fn lock(&self) -> MutexGuard<'_, LimitSnapshot> {
        self.state
            .lock()
            .expect("limits lock is never held across a panic")
    }
}

/// One handler slot plus the input bytes it retains; both return when the last owner drops it.
pub(crate) struct HandlerReservation {
    limits: Arc<Limits>,
    input_bytes: AtomicUsize,
}

impl HandlerReservation {
    pub fn retain_input(&self, bytes: usize) {
        self.input_bytes.fetch_add(bytes, Ordering::Relaxed);
        self.limits.update(|state| state.input_bytes += bytes);
    }
}

impl Drop for HandlerReservation {
    fn drop(&mut self) {
        let bytes = *self.input_bytes.get_mut();
        self.limits.update(|state| {
            state.active_handlers -= 1;
            state.input_bytes -= bytes;
        });
    }
}

pub(crate) struct OutputGuard {
    limits: Arc<Limits>,
    bytes: usize,
}

impl Drop for OutputGuard {
    fn drop(&mut self) {
        let bytes = self.bytes;
        self.limits.update(|state| state.output_bytes -= bytes);
    }
}

/// A response body that keeps its bytes counted until the server drops it after writing.
pub(crate) struct CountedBody {
    data: Option<Bytes>,
    _guard: OutputGuard,
}

impl CountedBody {
    pub fn new(data: Bytes, guard: OutputGuard) -> Self {
        Self {
            data: Some(data),
            _guard: guard,
        }
    }
}

impl Body for CountedBody {
    type Data = Bytes;
    type Error = Infallible;

    fn poll_frame(
        self: Pin<&mut Self>,
        _cx: &mut std::task::Context<'_>,
    ) -> Poll<Option<Result<Frame<Bytes>, Infallible>>> {
        Poll::Ready(self.get_mut().data.take().map(|data| Ok(Frame::data(data))))
    }

    fn is_end_stream(&self) -> bool {
        self.data.is_none()
    }

    fn size_hint(&self) -> SizeHint {
        SizeHint::with_exact(self.data.as_ref().map_or(0, |data| data.len() as u64))
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use http_body_util::BodyExt;

    fn open(max_handlers: usize) -> Arc<Limits> {
        let limits = Limits::new(max_handlers);
        limits.set_accepting(true);
        limits
    }

    #[test]
    fn reserves_up_to_the_handler_cap_and_releases_on_drop() {
        let limits = open(2);
        let first = limits.try_reserve_handler().unwrap();
        let _second = limits.try_reserve_handler().unwrap();
        assert!(limits.try_reserve_handler().is_none());
        drop(first);
        assert_eq!(limits.snapshot().active_handlers, 1);
        assert!(limits.try_reserve_handler().is_some());
    }

    #[test]
    fn refuses_admission_while_not_accepting() {
        let limits = Limits::new(1);
        assert!(limits.try_reserve_handler().is_none());
        limits.set_accepting(true);
        assert!(limits.try_reserve_handler().is_some());
    }

    #[test]
    fn input_bytes_stay_counted_until_the_reservation_drops() {
        let limits = open(1);
        let reservation = Arc::new(limits.try_reserve_handler().unwrap());
        reservation.retain_input(128);
        let clone = Arc::clone(&reservation);
        drop(reservation);
        assert_eq!(limits.snapshot().input_bytes, 128);
        drop(clone);
        assert_eq!(
            limits.snapshot(),
            LimitSnapshot {
                accepting: true,
                ..Default::default()
            }
        );
    }

    #[tokio::test]
    async fn wait_until_idle_resolves_when_the_last_handler_releases() {
        let limits = open(1);
        let reservation = limits.try_reserve_handler().unwrap();
        let waiter = tokio::spawn({
            let limits = Arc::clone(&limits);
            async move { limits.wait_until_idle().await }
        });
        tokio::task::yield_now().await;
        assert!(!waiter.is_finished());
        drop(reservation);
        waiter.await.unwrap();
    }

    #[tokio::test]
    async fn counted_body_keeps_output_bytes_until_dropped() {
        let limits = open(1);
        let body = CountedBody::new(Bytes::from_static(b"hello"), limits.retain_output(5));
        assert_eq!(body.size_hint().exact(), Some(5));
        assert_eq!(limits.snapshot().output_bytes, 5);
        let collected = body.collect().await.unwrap().to_bytes();
        assert_eq!(&collected[..], b"hello");
        assert_eq!(limits.snapshot().output_bytes, 0);
    }
}
