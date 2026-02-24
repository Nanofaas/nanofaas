use serde_json::Value;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Mutex;

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

#[derive(Debug)]
pub struct FunctionQueueState {
    function_name: String,
    queue: Mutex<VecDeque<InvocationTask>>,
    queue_capacity: AtomicUsize,
    configured_concurrency: AtomicUsize,
    effective_concurrency: AtomicUsize,
    in_flight: AtomicUsize,
}

impl FunctionQueueState {
    pub fn new(function_name: String, queue_size: usize, concurrency: usize) -> Self {
        let normalized_queue = queue_size.max(1);
        let normalized_concurrency = concurrency.max(1);
        Self {
            function_name,
            queue: Mutex::new(VecDeque::new()),
            queue_capacity: AtomicUsize::new(normalized_queue),
            configured_concurrency: AtomicUsize::new(normalized_concurrency),
            effective_concurrency: AtomicUsize::new(normalized_concurrency),
            in_flight: AtomicUsize::new(0),
        }
    }

    pub fn function_name(&self) -> &str {
        &self.function_name
    }

    pub fn queue_size(&self) -> usize {
        self.queue_capacity.load(Ordering::SeqCst)
    }

    pub fn queued(&self) -> usize {
        self.queue.lock().unwrap_or_else(|e| e.into_inner()).len()
    }

    pub fn offer(&self, task: InvocationTask) -> bool {
        let mut queue = self.queue.lock().unwrap_or_else(|e| e.into_inner());
        if queue.len() >= self.queue_capacity.load(Ordering::SeqCst) {
            return false;
        }
        queue.push_back(task);
        true
    }

    pub fn poll(&self) -> Option<InvocationTask> {
        self.queue
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .pop_front()
    }

    pub fn try_acquire_slot(&self) -> bool {
        loop {
            let current = self.in_flight.load(Ordering::SeqCst);
            let limit = self.effective_concurrency.load(Ordering::SeqCst);
            if current >= limit {
                return false;
            }
            if self
                .in_flight
                .compare_exchange(current, current + 1, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                return true;
            }
        }
    }

    pub fn release_slot(&self) {
        loop {
            let current = self.in_flight.load(Ordering::SeqCst);
            if current == 0 {
                return;
            }
            if self
                .in_flight
                .compare_exchange(current, current - 1, Ordering::SeqCst, Ordering::SeqCst)
                .is_ok()
            {
                return;
            }
        }
    }

    pub fn in_flight(&self) -> usize {
        self.in_flight.load(Ordering::SeqCst)
    }

    pub fn configured_concurrency(&self) -> usize {
        self.configured_concurrency.load(Ordering::SeqCst)
    }

    pub fn effective_concurrency(&self) -> usize {
        self.effective_concurrency.load(Ordering::SeqCst)
    }

    pub fn update_queue_size(&self, queue_size: usize) {
        self.queue_capacity
            .store(queue_size.max(1), Ordering::SeqCst);
    }

    pub fn concurrency(&self, concurrency: usize) {
        let normalized = concurrency.max(1);
        let previous_configured = self
            .configured_concurrency
            .swap(normalized, Ordering::SeqCst);
        let current_effective = self.effective_concurrency.load(Ordering::SeqCst);
        let next_effective = if current_effective == previous_configured {
            normalized
        } else {
            current_effective.min(normalized).max(1)
        };
        self.effective_concurrency
            .store(next_effective, Ordering::SeqCst);
    }

    pub fn set_effective_concurrency(&self, effective: usize) {
        let configured = self.configured_concurrency.load(Ordering::SeqCst).max(1);
        let clamped = effective.max(1).min(configured);
        self.effective_concurrency.store(clamped, Ordering::SeqCst);
    }
}

#[derive(Debug, Clone)]
pub struct QueueManager {
    capacity_per_function: usize,
    queues: HashMap<String, std::sync::Arc<FunctionQueueState>>,
    signaled_functions: HashSet<String>,
}

impl QueueManager {
    pub fn new(capacity_per_function: usize) -> Self {
        Self {
            capacity_per_function,
            queues: HashMap::new(),
            signaled_functions: HashSet::new(),
        }
    }

    pub fn enqueue(
        &mut self,
        function_name: &str,
        task: InvocationTask,
    ) -> Result<(), QueueOverflowError> {
        self.enqueue_with_capacity_and_concurrency(
            function_name,
            task,
            self.capacity_per_function,
            1,
        )
    }

    pub fn enqueue_with_capacity(
        &mut self,
        function_name: &str,
        task: InvocationTask,
        capacity: usize,
    ) -> Result<(), QueueOverflowError> {
        self.enqueue_with_capacity_and_concurrency(function_name, task, capacity, 1)
    }

    pub fn enqueue_with_capacity_and_concurrency(
        &mut self,
        function_name: &str,
        task: InvocationTask,
        capacity: usize,
        concurrency: usize,
    ) -> Result<(), QueueOverflowError> {
        let state = self.get_or_create(function_name, capacity.max(1), concurrency.max(1));
        if !state.offer(task) {
            return Err(QueueOverflowError {
                function_name: function_name.to_string(),
            });
        }
        self.signal_work(function_name);
        Ok(())
    }

    pub fn take_next(&mut self, function_name: &str) -> Option<InvocationTask> {
        self.queues.get(function_name)?.poll()
    }

    pub fn try_acquire_slot(&self, function_name: &str) -> bool {
        self.queues
            .get(function_name)
            .map(|state| state.try_acquire_slot())
            .unwrap_or(false)
    }

    pub fn release_slot(&mut self, function_name: &str) {
        if let Some(state) = self.queues.get(function_name) {
            state.release_slot();
            if state.queued() > 0 {
                self.signal_work(function_name);
            }
        }
    }

    pub fn set_effective_concurrency(&self, function_name: &str, effective: usize) {
        if let Some(state) = self.queues.get(function_name) {
            state.set_effective_concurrency(effective);
        }
    }

    pub fn in_flight(&self, function_name: &str) -> usize {
        self.queues
            .get(function_name)
            .map(|state| state.in_flight())
            .unwrap_or(0)
    }

    pub fn configure_function(
        &mut self,
        function_name: &str,
        queue_size: usize,
        concurrency: usize,
    ) {
        let _ = self.get_or_create(function_name, queue_size.max(1), concurrency.max(1));
    }

    pub fn remove_function(&mut self, function_name: &str) {
        self.queues.remove(function_name);
        self.signaled_functions.remove(function_name);
    }

    pub fn queued_functions(&self) -> Vec<String> {
        self.queues
            .iter()
            .filter_map(|(name, state)| {
                if state.queued() == 0 {
                    None
                } else {
                    Some(name.clone())
                }
            })
            .collect()
    }

    pub fn signal_work(&mut self, function_name: &str) {
        self.signaled_functions.insert(function_name.to_string());
    }

    pub fn take_signaled_functions(&mut self) -> Vec<String> {
        self.signaled_functions.drain().collect()
    }

    fn get_or_create(
        &mut self,
        function_name: &str,
        queue_size: usize,
        concurrency: usize,
    ) -> std::sync::Arc<FunctionQueueState> {
        if let Some(existing) = self.queues.get(function_name) {
            existing.update_queue_size(queue_size);
            existing.concurrency(concurrency);
            return std::sync::Arc::clone(existing);
        }

        let state = std::sync::Arc::new(FunctionQueueState::new(
            function_name.to_string(),
            queue_size,
            concurrency,
        ));
        self.queues
            .insert(function_name.to_string(), std::sync::Arc::clone(&state));
        state
    }
}
