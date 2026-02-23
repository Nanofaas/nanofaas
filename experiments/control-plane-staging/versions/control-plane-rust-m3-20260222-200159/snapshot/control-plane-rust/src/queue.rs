use serde_json::Value;
use std::collections::{HashMap, VecDeque};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueueOverflowError {
    pub function_name: String,
}

#[derive(Debug, Clone)]
pub struct InvocationTask {
    pub execution_id: String,
    pub payload: Value,
    pub attempt: u32,
}

#[derive(Debug, Clone)]
pub struct QueueManager {
    capacity_per_function: usize,
    queues: HashMap<String, VecDeque<InvocationTask>>,
}

impl QueueManager {
    pub fn new(capacity_per_function: usize) -> Self {
        Self {
            capacity_per_function,
            queues: HashMap::new(),
        }
    }

    pub fn enqueue(
        &mut self,
        function_name: &str,
        task: InvocationTask,
    ) -> Result<(), QueueOverflowError> {
        let queue = self.queues.entry(function_name.to_string()).or_default();
        if queue.len() >= self.capacity_per_function {
            return Err(QueueOverflowError {
                function_name: function_name.to_string(),
            });
        }
        queue.push_back(task);
        Ok(())
    }

    pub fn take_next(&mut self, function_name: &str) -> Option<InvocationTask> {
        let queue = self.queues.get_mut(function_name)?;
        queue.pop_front()
    }
}
