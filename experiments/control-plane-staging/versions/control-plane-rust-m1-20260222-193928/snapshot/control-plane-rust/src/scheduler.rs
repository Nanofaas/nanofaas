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

        let dispatch = self.router.dispatch(function, &task.payload);
        let mut record = store
            .get(&task.execution_id)
            .ok_or_else(|| format!("execution not found: {}", task.execution_id))?;

        record.status = if dispatch.status == "SUCCESS" {
            ExecutionState::Success
        } else {
            ExecutionState::Error
        };
        record.output = dispatch.output;
        store.put_now(record);

        Ok(true)
    }
}
