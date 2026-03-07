use crate::queue::InvocationTask;
use crate::runtime_config::RuntimeConfigSnapshot;
use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

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

#[derive(Debug, Default)]
struct SyncQueueState {
    in_flight: usize,
    in_flight_per_function: HashMap<String, usize>,
    global_events: VecDeque<Instant>,
    per_function_events: HashMap<String, VecDeque<Instant>>,
}

#[derive(Debug)]
pub struct SyncAdmissionQueue {
    runtime_config: Arc<Mutex<RuntimeConfigSnapshot>>,
    max_concurrency: usize,
    max_depth: usize,
    state: Mutex<SyncQueueState>,
}

impl SyncAdmissionQueue {
    pub fn new(
        runtime_config: Arc<Mutex<RuntimeConfigSnapshot>>,
        max_concurrency: usize,
        max_depth: usize,
    ) -> Self {
        Self {
            runtime_config,
            max_concurrency: max_concurrency.max(1),
            max_depth: max_depth.max(1),
            state: Mutex::new(SyncQueueState::default()),
        }
    }

    fn reject_for_depth(depth: usize) -> Result<(), SyncQueueRejection> {
        Err(SyncQueueRejection {
            reason: SyncQueueRejectReason::Depth,
            est_wait_ms: None,
            queue_depth: Some(depth as u64),
        })
    }

    fn prune(events: &mut VecDeque<Instant>, now: Instant, window: Duration) {
        while let Some(first) = events.front().copied() {
            if now.duration_since(first) <= window {
                break;
            }
            let _ = events.pop_front();
        }
    }

    fn estimate_wait_ms(&self, function_name: &str, depth: usize, now: Instant) -> Option<u64> {
        if depth == 0 {
            return Some(0);
        }

        let settings = self
            .runtime_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        Self::prune(
            &mut state.global_events,
            now,
            settings.sync_queue_max_queue_wait,
        );
        if let Some(events) = state.per_function_events.get_mut(function_name) {
            Self::prune(events, now, settings.sync_queue_max_queue_wait);
        }

        if let Some(events) = state.per_function_events.get(function_name) {
            if events.len() >= 3 {
                let seconds = settings.sync_queue_max_queue_wait.as_secs_f64().max(1.0);
                let throughput = events.len() as f64 / seconds;
                if throughput > 0.0 {
                    return Some(((depth as f64 / throughput) * 1000.0).ceil() as u64);
                }
            }
        }

        let seconds = settings.sync_queue_max_queue_wait.as_secs_f64().max(1.0);
        let throughput = state.global_events.len() as f64 / seconds;
        if throughput > 0.0 {
            Some(((depth as f64 / throughput) * 1000.0).ceil() as u64)
        } else {
            Some(
                settings
                    .sync_queue_max_estimated_wait
                    .as_millis()
                    .max((settings.sync_queue_retry_after_seconds.max(1) as u128) * 1000)
                    as u64,
            )
        }
    }
}

impl SyncQueueGateway for SyncAdmissionQueue {
    fn enqueue_or_throw(&self, _task: Option<&InvocationTask>) {
        // Not used — sync queue uses try_admit/release instead.
    }

    fn enabled(&self) -> bool {
        self.runtime_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .sync_queue_enabled
    }

    fn retry_after_seconds(&self) -> i32 {
        self.runtime_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .sync_queue_retry_after_seconds
    }

    fn try_admit(&self, function_name: &str) -> Result<(), SyncQueueRejection> {
        let now = Instant::now();
        let settings = self
            .runtime_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        if !settings.sync_queue_enabled {
            return Ok(());
        }
        let depth = {
            let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.in_flight
        };

        if depth >= self.max_depth {
            return Self::reject_for_depth(depth);
        }

        if depth < self.max_concurrency {
            let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
            state.in_flight += 1;
            *state
                .in_flight_per_function
                .entry(function_name.to_string())
                .or_insert(0) += 1;
            return Ok(());
        }

        let est_wait_ms = self.estimate_wait_ms(function_name, depth.max(1), now);
        if settings.sync_queue_admission_enabled
            && est_wait_ms.unwrap_or(u64::MAX)
                > settings.sync_queue_max_estimated_wait.as_millis() as u64
        {
            return Err(SyncQueueRejection {
                reason: SyncQueueRejectReason::EstWait,
                est_wait_ms,
                queue_depth: Some(depth as u64),
            });
        }

        Err(SyncQueueRejection {
            reason: SyncQueueRejectReason::EstWait,
            est_wait_ms,
            queue_depth: Some(depth as u64),
        })
    }

    fn release(&self, function_name: &str) {
        let now = Instant::now();
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if state.in_flight > 0 {
            state.in_flight -= 1;
        }
        if let Some(in_flight) = state.in_flight_per_function.get_mut(function_name) {
            if *in_flight > 0 {
                *in_flight -= 1;
            }
            if *in_flight == 0 {
                state.in_flight_per_function.remove(function_name);
            }
        }

        state.global_events.push_back(now);
        let queue_window = self
            .runtime_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .sync_queue_max_queue_wait;
        Self::prune(&mut state.global_events, now, queue_window);
        let events = state
            .per_function_events
            .entry(function_name.to_string())
            .or_default();
        events.push_back(now);
        Self::prune(events, now, queue_window);
    }
}
