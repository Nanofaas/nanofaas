use crate::dispatch::{DispatchResult, DispatcherRouter};
use crate::execution::{ErrorInfo, ExecutionStore};
use crate::metrics::Metrics;
use crate::model::FunctionSpec;
use crate::queue::QueueManager;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use tokio::task::JoinHandle;

pub struct Scheduler {
    router: DispatcherRouter,
}

impl Scheduler {
    pub fn new(router: DispatcherRouter) -> Self {
        Self { router }
    }

    pub async fn tick_ready_functions_once(
        &self,
        functions: &HashMap<String, FunctionSpec>,
        queue: &Arc<Mutex<QueueManager>>,
        store: &Arc<Mutex<ExecutionStore>>,
        metrics: &Metrics,
    ) -> Result<Vec<JoinHandle<()>>, String> {
        let queued_functions = {
            let mut guard = queue.lock().unwrap_or_else(|e| e.into_inner());
            let signaled = guard.take_signaled_functions();
            if signaled.is_empty() {
                guard.queued_functions()
            } else {
                let mut queued = signaled;
                for function_name in guard.queued_functions() {
                    if !queued.iter().any(|candidate| candidate == &function_name) {
                        queued.push(function_name);
                    }
                }
                queued
            }
        };

        let mut handles = Vec::new();
        for function_name in queued_functions {
            if let Some(handle) = self
                .tick_once(&function_name, functions, queue, store, metrics)
                .await?
            {
                handles.push(handle);
            }
        }
        Ok(handles)
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
        let started_at = crate::now_millis();
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
    dispatch: DispatchResult,
    started_at: u64,
    queue: Arc<Mutex<QueueManager>>,
    store: Arc<Mutex<ExecutionStore>>,
    metrics: Metrics,
) {
    let finished_at = crate::now_millis();
    queue
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .release_slot(function_name);

    // Fetch all four timer handles in one lock acquisition before branching.
    let timers = metrics.timers(function_name);

    if dispatch.status == "SUCCESS" {
        let completion_tx = {
            let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(mut record) = s.get(&task.execution_id) {
                // Early exit: execution already reached a terminal state (e.g. timed out by
                // client) — don't overwrite state or record metrics for it.
                if record.is_terminal() {
                    return;
                }
                let queue_wait_ms = started_at.saturating_sub(record.created_at_millis);
                let e2e_latency_ms = finished_at.saturating_sub(record.created_at_millis);
                let latency_ms = finished_at.saturating_sub(started_at);
                record.mark_success_at(
                    dispatch.output.clone().unwrap_or(serde_json::Value::Null),
                    finished_at,
                );
                if dispatch.cold_start {
                    record.mark_cold_start(dispatch.init_duration_ms.unwrap_or(0));
                    metrics.cold_start(function_name);
                    timers
                        .init_duration
                        .record_ms(dispatch.init_duration_ms.unwrap_or(0));
                } else {
                    metrics.warm_start(function_name);
                }
                let tx = record.completion_tx.clone();
                s.put_now(record);
                metrics.success(function_name);
                timers.latency.record_ms(latency_ms);
                timers.queue_wait.record_ms(queue_wait_ms);
                timers.e2e_latency.record_ms(e2e_latency_ms);
                tx
            } else {
                None
            }
        };
        if let Some(tx_arc) = completion_tx {
            let mut guard = tx_arc.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(tx) = guard.take() {
                let _ = tx.send(dispatch);
            }
        }
        return;
    }

    let max_retries = function.max_retries.unwrap_or(1).max(1) as u32;
    if task.attempt < max_retries {
        // Check terminal before retrying — don't re-queue an already-timed-out execution.
        let is_terminal = store
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .get(&task.execution_id)
            .map(|r| r.is_terminal())
            .unwrap_or(false);
        if is_terminal {
            return;
        }

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

    let completion_tx = {
        let mut s = store.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(mut record) = s.get(&task.execution_id) {
            // Early exit: execution already terminal (e.g. timed out).
            if record.is_terminal() {
                return;
            }
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
                timers
                    .init_duration
                    .record_ms(dispatch.init_duration_ms.unwrap_or(0));
            } else {
                metrics.warm_start(function_name);
            }
            let tx = record.completion_tx.clone();
            s.put_now(record);
            timers.latency.record_ms(latency_ms);
            timers.queue_wait.record_ms(queue_wait_ms);
            timers.e2e_latency.record_ms(e2e_latency_ms);
            tx
        } else {
            None
        }
    };
    metrics.error(function_name);
    if let Some(tx_arc) = completion_tx {
        let mut guard = tx_arc.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(tx) = guard.take() {
            let _ = tx.send(dispatch);
        }
    }
}
