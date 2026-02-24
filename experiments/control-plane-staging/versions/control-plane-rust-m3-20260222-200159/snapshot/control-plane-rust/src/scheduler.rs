use crate::dispatch::DispatcherRouter;
use crate::execution::{ErrorInfo, ExecutionStore};
use crate::metrics::Metrics;
use crate::model::FunctionSpec;
use crate::queue::QueueManager;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tokio::task::JoinHandle;
use std::time::{SystemTime, UNIX_EPOCH};

fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}

pub struct Scheduler {
    router: DispatcherRouter,
}

impl Scheduler {
    pub fn new(router: DispatcherRouter) -> Self {
        Self { router }
    }

    /// Dequeues and dispatches one task for `function_name`.
    ///
    /// Locks are released before the async dispatch so Tokio worker threads
    /// are never blocked while holding a `std::sync::Mutex` guard (BUG-3).
    pub async fn tick_once(
        &self,
        function_name: &str,
        functions: &HashMap<String, FunctionSpec>,
        queue: &Arc<Mutex<QueueManager>>,
        store: &Arc<Mutex<ExecutionStore>>,
        metrics: &Metrics,
    ) -> Result<Option<JoinHandle<()>>, String> {
        // 1. Acquire a dispatch slot and take next task — release queue lock immediately.
        let task = {
            let mut q = queue.lock().unwrap_or_else(|e| e.into_inner());
            if !q.try_acquire_slot(function_name) {
                None
            } else {
                let next = q.take_next(function_name);
                if next.is_none() {
                    q.release_slot(function_name);
                }
                next
            }
        };
        let Some(task) = task else {
            return Ok(None);
        };

        let function = match functions.get(function_name) {
            Some(spec) => spec.clone(),
            None => {
                queue
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .release_slot(function_name);
                return Err(format!("function not found: {function_name}"));
            }
        };

        // 2. Mark running before dispatch — visible to status-polling clients.
        let started_at = now_millis();
        {
            let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(mut r) = s.get(&task.execution_id) {
                r.mark_running_at(started_at);
                r.mark_dispatched_at(started_at);
                s.put_now(r);
            }
        }
        metrics.dispatch(function_name);

        // 3. Dispatch asynchronously to avoid blocking the scheduler loop.
        // This mirrors the Java implementation, where dispatch returns immediately
        // and completion happens via callback/future.
        let router = self.router.clone();
        let queue = Arc::clone(queue);
        let store = Arc::clone(store);
        let metrics = metrics.clone();
        let function_name = function_name.to_string();
        let handle = tokio::spawn(async move {
            let dispatch = router
                .dispatch(&function, &task.payload, &task.execution_id, None, None)
                .await;
            finalize_dispatch(
                &function_name,
                &function,
                task,
                dispatch,
                started_at,
                queue,
                store,
                metrics,
            );
        });
        Ok(Some(handle))
    }
}

fn finalize_dispatch(
    function_name: &str,
    function: &FunctionSpec,
    task: crate::queue::InvocationTask,
    dispatch: crate::dispatch::DispatchResult,
    started_at: u64,
    queue: Arc<Mutex<QueueManager>>,
    store: Arc<Mutex<ExecutionStore>>,
    metrics: Metrics,
) {
    let finished_at = now_millis();
    queue
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release_slot(function_name);

    if dispatch.status == "SUCCESS" {
        let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(mut record) = s.get(&task.execution_id) {
            let queue_wait_ms = started_at.saturating_sub(record.created_at_millis);
            let e2e_latency_ms = finished_at.saturating_sub(record.created_at_millis);
            let latency_ms = finished_at.saturating_sub(started_at);
            record.mark_success_at(
                dispatch.output.unwrap_or(serde_json::Value::Null),
                finished_at,
            );
            if dispatch.cold_start {
                record.mark_cold_start(dispatch.init_duration_ms.unwrap_or(0));
                metrics.cold_start(function_name);
                metrics
                    .init_duration(function_name)
                    .record_ms(dispatch.init_duration_ms.unwrap_or(0));
            } else {
                metrics.warm_start(function_name);
            }
            s.put_now(record);
            metrics.success(function_name);
            metrics.latency(function_name).record_ms(latency_ms);
            metrics.queue_wait(function_name).record_ms(queue_wait_ms);
            metrics.e2e_latency(function_name).record_ms(e2e_latency_ms);
        }
        return;
    }

    let max_retries = function.max_retries.unwrap_or(1).max(1) as u32;
    if task.attempt < max_retries {
        let retry_task = crate::queue::InvocationTask {
            execution_id: task.execution_id.clone(),
            payload: task.payload,
            attempt: task.attempt + 1,
        };
        let queue_capacity = function.queue_size.unwrap_or(100).max(1) as usize;
        let concurrency = function.concurrency.unwrap_or(1).max(1) as usize;
        if queue
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .enqueue_with_capacity_and_concurrency(
                function_name,
                retry_task.clone(),
                queue_capacity,
                concurrency,
            )
            .is_ok()
        {
            let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(mut r) = s.get(&task.execution_id) {
                let execution_task = crate::execution::InvocationTask::new(
                    &retry_task.execution_id,
                    function_name,
                    retry_task.attempt,
                );
                r.reset_for_retry(execution_task);
                s.put_now(r);
            }
            metrics.retry(function_name);
            return;
        }
    }

    let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(mut record) = s.get(&task.execution_id) {
        let queue_wait_ms = started_at.saturating_sub(record.created_at_millis);
        let e2e_latency_ms = finished_at.saturating_sub(record.created_at_millis);
        let latency_ms = finished_at.saturating_sub(started_at);
        record.mark_error_at(
            ErrorInfo::new("DISPATCH_ERROR", "dispatch failed"),
            finished_at,
        );
        if dispatch.cold_start {
            record.mark_cold_start(dispatch.init_duration_ms.unwrap_or(0));
            metrics.cold_start(function_name);
            metrics
                .init_duration(function_name)
                .record_ms(dispatch.init_duration_ms.unwrap_or(0));
        } else {
            metrics.warm_start(function_name);
        }
        s.put_now(record);
        metrics.latency(function_name).record_ms(latency_ms);
        metrics.queue_wait(function_name).record_ms(queue_wait_ms);
        metrics.e2e_latency(function_name).record_ms(e2e_latency_ms);
    }
    metrics.error(function_name);
}
