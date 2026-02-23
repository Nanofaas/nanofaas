use crate::queue::InvocationTask;
use std::sync::atomic::{AtomicUsize, Ordering};

pub trait SyncQueueGateway {
    fn enqueue_or_throw(&self, task: Option<&InvocationTask>);

    fn enabled(&self) -> bool;

    fn retry_after_seconds(&self) -> i32;

    /// Try to admit a sync invocation. Returns Ok(()) if admitted, Err with
    /// estimated wait time if rejected. Caller must call release() after dispatch.
    fn try_admit(&self, _function_name: &str) -> Result<(), SyncQueueRejection> {
        Ok(())
    }

    /// Release an admission slot after dispatch completes.
    fn release(&self, _function_name: &str) {}
}

#[derive(Debug, Clone)]
pub struct SyncQueueRejection {
    pub reason: SyncQueueRejectReason,
    pub est_wait_ms: Option<u64>,
    pub queue_depth: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum SyncQueueRejectReason {
    EstWait,
    Depth,
}

#[derive(Debug)]
pub struct NoOpSyncQueueGateway;

impl SyncQueueGateway for NoOpSyncQueueGateway {
    fn enqueue_or_throw(&self, _task: Option<&InvocationTask>) {
        panic!("Sync queue module not loaded")
    }

    fn enabled(&self) -> bool {
        false
    }

    fn retry_after_seconds(&self) -> i32 {
        2
    }
}

static NO_OP_SYNC_QUEUE_GATEWAY: NoOpSyncQueueGateway = NoOpSyncQueueGateway;

pub fn no_op_sync_queue_gateway() -> &'static NoOpSyncQueueGateway {
    &NO_OP_SYNC_QUEUE_GATEWAY
}

/// Real sync admission queue with atomic in-flight counter per global max concurrency.
pub struct SyncAdmissionQueue {
    in_flight: AtomicUsize,
    max_concurrency: usize,
}

impl std::fmt::Debug for SyncAdmissionQueue {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("SyncAdmissionQueue")
            .field("in_flight", &self.in_flight.load(Ordering::Relaxed))
            .field("max_concurrency", &self.max_concurrency)
            .finish()
    }
}

impl SyncAdmissionQueue {
    pub fn new(max_concurrency: usize) -> Self {
        Self {
            in_flight: AtomicUsize::new(0),
            max_concurrency,
        }
    }
}

impl SyncQueueGateway for SyncAdmissionQueue {
    fn enqueue_or_throw(&self, _task: Option<&InvocationTask>) {
        // Not used — sync queue uses try_admit/release instead
    }

    fn enabled(&self) -> bool {
        true
    }

    fn retry_after_seconds(&self) -> i32 {
        2
    }

    fn try_admit(&self, _function_name: &str) -> Result<(), SyncQueueRejection> {
        let current = self.in_flight.fetch_add(1, Ordering::SeqCst);
        if current >= self.max_concurrency {
            self.in_flight.fetch_sub(1, Ordering::SeqCst);
            return Err(SyncQueueRejection {
                reason: SyncQueueRejectReason::EstWait,
                est_wait_ms: Some((current as u64) * 100),
                queue_depth: Some(current as u64),
            });
        }
        Ok(())
    }

    fn release(&self, _function_name: &str) {
        self.in_flight.fetch_sub(1, Ordering::SeqCst);
    }
}
