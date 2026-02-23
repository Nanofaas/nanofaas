use crate::dispatch::DispatcherRouter;
use crate::execution::{ExecutionState, ExecutionStore};
use crate::model::FunctionSpec;
use crate::queue::QueueManager;
use std::collections::HashMap;

pub struct Scheduler {
    router: DispatcherRouter,
}

impl Scheduler {
    pub fn new(router: DispatcherRouter) -> Self {
        Self { router }
    }

    pub fn tick_once(
        &self,
        function_name: &str,
        functions: &HashMap<String, FunctionSpec>,
        queue: &mut QueueManager,
        store: &mut ExecutionStore,
    ) -> Result<bool, String> {
        let task = match queue.take_next(function_name) {
            Some(task) => task,
            None => return Ok(false),
        };

        let function = functions
            .get(function_name)
            .ok_or_else(|| format!("function not found: {function_name}"))?;

        let dispatch = self
            .router
            .dispatch(function, &task.payload, &task.execution_id);
        let mut record = store
            .get(&task.execution_id)
            .ok_or_else(|| format!("execution not found: {}", task.execution_id))?;

        if dispatch.status == "SUCCESS" {
            record.status = ExecutionState::Success;
            record.output = dispatch.output;
            store.put_now(record);
            return Ok(true);
        }

        let max_retries = function.max_retries.unwrap_or(1).max(1) as u32;
        if task.attempt < max_retries {
            let retry_task = crate::queue::InvocationTask {
                execution_id: task.execution_id,
                payload: task.payload,
                attempt: task.attempt + 1,
            };

            if queue.enqueue(function_name, retry_task).is_ok() {
                record.status = ExecutionState::Queued;
                record.output = None;
                store.put_now(record);
                return Ok(true);
            }
        }

        record.status = ExecutionState::Error;
        record.output = dispatch.output;
        store.put_now(record);

        Ok(true)
    }
}
