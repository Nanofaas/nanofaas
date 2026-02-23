use crate::dispatch::DispatcherRouter;
use crate::execution::{ExecutionState, ExecutionStore};
use crate::model::FunctionSpec;
use crate::queue::QueueManager;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};

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
    ) -> Result<bool, String> {
        // 1. Take next task — release queue lock immediately.
        let task = {
            let mut q = queue.lock().expect("queue lock");
            q.take_next(function_name)
        };
        let Some(task) = task else {
            return Ok(false);
        };

        let function = functions
            .get(function_name)
            .ok_or_else(|| format!("function not found: {function_name}"))?;

        // 2. Dispatch — no lock held across this await point.
        let dispatch = self
            .router
            .dispatch(function, &task.payload, &task.execution_id)
            .await;

        if dispatch.status == "SUCCESS" {
            let mut s = store.lock().expect("store lock");
            let mut record = s
                .get(&task.execution_id)
                .ok_or_else(|| format!("execution not found: {}", task.execution_id))?;
            record.status = ExecutionState::Success;
            record.output = dispatch.output;
            s.put_now(record);
            return Ok(true);
        }

        // 3. Retry logic — locks acquired and released individually.
        let max_retries = function.max_retries.unwrap_or(1).max(1) as u32;
        if task.attempt < max_retries {
            let retry_task = crate::queue::InvocationTask {
                execution_id: task.execution_id.clone(),
                payload: task.payload,
                attempt: task.attempt + 1,
            };
            if queue
                .lock()
                .expect("queue lock")
                .enqueue(function_name, retry_task)
                .is_ok()
            {
                let mut s = store.lock().expect("store lock");
                if let Some(mut r) = s.get(&task.execution_id) {
                    r.status = ExecutionState::Queued;
                    r.output = None;
                    s.put_now(r);
                }
                return Ok(true);
            }
        }

        // 4. Record final error state.
        let mut s = store.lock().expect("store lock");
        if let Some(mut record) = s.get(&task.execution_id) {
            record.status = ExecutionState::Error;
            record.output = dispatch.output;
            s.put_now(record);
        }
        Ok(true)
    }
}
