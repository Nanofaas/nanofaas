use crate::dispatch::DispatchResult;
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tokio::sync::oneshot;
use tokio::sync::Mutex as TokioMutex;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExecutionState {
    Queued,
    Running,
    Success,
    Error,
    Timeout,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ErrorInfo {
    pub code: String,
    pub message: String,
}

impl ErrorInfo {
    pub fn new(code: &str, message: &str) -> Self {
        Self {
            code: code.to_string(),
            message: message.to_string(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct InvocationTask {
    #[serde(rename = "executionId")]
    pub execution_id: String,
    #[serde(rename = "functionName")]
    pub function_name: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub payload: Option<Value>,
    pub attempt: u32,
}

impl InvocationTask {
    pub fn new(execution_id: &str, function_name: &str, attempt: u32) -> Self {
        Self {
            execution_id: execution_id.to_string(),
            function_name: function_name.to_string(),
            payload: None,
            attempt,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ExecutionRecord {
    #[serde(rename = "executionId")]
    pub execution_id: String,
    #[serde(rename = "functionName")]
    pub function_name: String,
    pub status: ExecutionState,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<Value>,
    #[serde(rename = "createdAtMillis")]
    pub created_at_millis: u64,
    #[serde(skip_serializing)]
    pub cleaned_up: bool,

    #[serde(skip_serializing)]
    task: InvocationTask,
    #[serde(skip_serializing)]
    started_at_millis: Option<u64>,
    #[serde(skip_serializing)]
    finished_at_millis: Option<u64>,
    #[serde(skip_serializing)]
    dispatched_at_millis: Option<u64>,
    #[serde(skip_serializing)]
    last_error: Option<ErrorInfo>,
    #[serde(skip_serializing)]
    cold_start: bool,
    #[serde(skip_serializing)]
    init_duration_ms: Option<u64>,
    #[serde(skip)]
    pub completion_tx: Option<Arc<TokioMutex<Option<oneshot::Sender<DispatchResult>>>>>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ExecutionSnapshot {
    pub execution_id: String,
    pub task: InvocationTask,
    pub state: ExecutionState,
    pub started_at_millis: Option<u64>,
    pub finished_at_millis: Option<u64>,
    pub dispatched_at_millis: Option<u64>,
    pub output: Option<Value>,
    pub last_error: Option<ErrorInfo>,
    pub cold_start: bool,
    pub init_duration_ms: Option<u64>,
}

impl ExecutionRecord {
    pub fn new(execution_id: &str, function_name: &str, status: ExecutionState) -> Self {
        let mut task = InvocationTask::new(execution_id, function_name, 1);
        task.payload = None;
        Self {
            execution_id: execution_id.to_string(),
            function_name: function_name.to_string(),
            status,
            output: None,
            created_at_millis: 0,
            cleaned_up: false,
            task,
            started_at_millis: None,
            finished_at_millis: None,
            dispatched_at_millis: None,
            last_error: None,
            cold_start: false,
            init_duration_ms: None,
            completion_tx: None,
        }
    }

    pub fn new_with_completion(
        execution_id: &str,
        function_name: &str,
    ) -> (Self, oneshot::Receiver<DispatchResult>) {
        let (tx, rx) = oneshot::channel();
        let mut record = Self::new(execution_id, function_name, ExecutionState::Queued);
        record.completion_tx = Some(Arc::new(TokioMutex::new(Some(tx))));
        (record, rx)
    }

    pub async fn complete(&self, result: DispatchResult) {
        if let Some(tx_mutex) = &self.completion_tx {
            let mut guard = tx_mutex.lock().await;
            if let Some(tx) = guard.take() {
                let _ = tx.send(result);
            }
        }
    }

    pub fn new_with_task(execution_id: &str, task: InvocationTask) -> Self {
        let function_name = task.function_name.clone();
        Self {
            execution_id: execution_id.to_string(),
            function_name,
            status: ExecutionState::Queued,
            output: None,
            created_at_millis: 0,
            cleaned_up: false,
            task,
            started_at_millis: None,
            finished_at_millis: None,
            dispatched_at_millis: None,
            last_error: None,
            cold_start: false,
            init_duration_ms: None,
            completion_tx: None,
        }
    }

    pub fn snapshot(&self) -> ExecutionSnapshot {
        ExecutionSnapshot {
            execution_id: self.execution_id.clone(),
            task: self.task.clone(),
            state: self.status.clone(),
            started_at_millis: self.started_at_millis,
            finished_at_millis: self.finished_at_millis,
            dispatched_at_millis: self.dispatched_at_millis,
            output: self.output.clone(),
            last_error: self.last_error.clone(),
            cold_start: self.cold_start,
            init_duration_ms: self.init_duration_ms,
        }
    }

    pub fn mark_running_at(&mut self, at_millis: u64) {
        self.status = ExecutionState::Running;
        self.started_at_millis = Some(at_millis);
    }

    pub fn mark_success_at(&mut self, output: Value, at_millis: u64) {
        self.status = ExecutionState::Success;
        self.finished_at_millis = Some(at_millis);
        self.output = Some(output);
        self.last_error = None;
    }

    pub fn mark_error_at(&mut self, error: ErrorInfo, at_millis: u64) {
        self.status = ExecutionState::Error;
        self.finished_at_millis = Some(at_millis);
        self.last_error = Some(error);
        self.output = None;
    }

    pub fn mark_timeout_at(&mut self, at_millis: u64) {
        self.status = ExecutionState::Timeout;
        self.finished_at_millis = Some(at_millis);
    }

    pub fn mark_dispatched_at(&mut self, at_millis: u64) {
        self.dispatched_at_millis = Some(at_millis);
    }

    pub fn mark_cold_start(&mut self, init_duration_ms: u64) {
        self.cold_start = true;
        self.init_duration_ms = Some(init_duration_ms);
    }

    pub fn reset_for_retry(&mut self, retry_task: InvocationTask) {
        self.task = retry_task;
        self.function_name = self.task.function_name.clone();
        self.status = ExecutionState::Queued;
        self.started_at_millis = None;
        self.finished_at_millis = None;
        self.dispatched_at_millis = None;
        self.last_error = None;
        self.output = None;
        self.cold_start = false;
        self.init_duration_ms = None;
    }

    pub fn cleanup(&mut self) {
        self.output = None;
        self.task.payload = None;
    }

    pub fn task(&self) -> &InvocationTask {
        &self.task
    }

    pub fn state(&self) -> ExecutionState {
        self.status.clone()
    }

    pub fn started_at_millis(&self) -> Option<u64> {
        self.started_at_millis
    }

    pub fn finished_at_millis(&self) -> Option<u64> {
        self.finished_at_millis
    }

    pub fn output(&self) -> Option<Value> {
        self.output.clone()
    }

    pub fn last_error(&self) -> Option<ErrorInfo> {
        self.last_error.clone()
    }

    pub fn update_task(&mut self, new_task: InvocationTask) {
        self.task = new_task;
    }

    pub fn set_state(&mut self, state: ExecutionState) {
        self.status = state;
    }

    pub fn set_started_at_millis(&mut self, value: Option<u64>) {
        self.started_at_millis = value;
    }

    pub fn set_finished_at_millis(&mut self, value: Option<u64>) {
        self.finished_at_millis = value;
    }

    pub fn set_last_error(&mut self, value: Option<ErrorInfo>) {
        self.last_error = value;
    }

    pub fn set_output(&mut self, value: Option<Value>) {
        self.output = value;
    }
}

#[derive(Debug, Clone)]
struct StoredExecution {
    record: ExecutionRecord,
    created_at_millis: u64,
}

#[derive(Debug, Clone)]
pub struct ExecutionStore {
    entries: HashMap<String, StoredExecution>,
    ttl: Duration,
    cleanup_ttl: Duration,
    stale_ttl: Duration,
}

impl ExecutionStore {
    pub fn new_with_durations(ttl: Duration, cleanup_ttl: Duration, stale_ttl: Duration) -> Self {
        Self {
            entries: HashMap::new(),
            ttl,
            cleanup_ttl,
            stale_ttl,
        }
    }

    pub fn put_with_timestamp(&mut self, mut record: ExecutionRecord, now_millis: u64) {
        record.created_at_millis = now_millis;
        self.entries.insert(
            record.execution_id.clone(),
            StoredExecution {
                record,
                created_at_millis: now_millis,
            },
        );
    }

    pub fn put_now(&mut self, record: ExecutionRecord) {
        self.put_with_timestamp(record, now_millis());
    }

    pub fn get(&self, execution_id: &str) -> Option<ExecutionRecord> {
        self.entries
            .get(execution_id)
            .map(|entry| entry.record.clone())
    }

    pub fn remove(&mut self, execution_id: &str) {
        self.entries.remove(execution_id);
    }

    pub fn evict_expired(&mut self, now_millis: u64) {
        let ttl_ms = self.ttl.as_millis() as u64;
        let cleanup_ttl_ms = self.cleanup_ttl.as_millis() as u64;
        let stale_ttl_ms = self.stale_ttl.as_millis() as u64;

        self.entries.retain(|_, stored| {
            let age = now_millis.saturating_sub(stored.created_at_millis);
            if age > stale_ttl_ms {
                return false;
            }

            if age > ttl_ms
                && stored.record.status != ExecutionState::Running
                && stored.record.status != ExecutionState::Queued
            {
                return false;
            }

            if age > cleanup_ttl_ms
                && stored.record.status != ExecutionState::Running
                && stored.record.status != ExecutionState::Queued
            {
                stored.record.cleanup();
                stored.record.cleaned_up = true;
            }

            true
        });
    }
}

pub fn spawn_execution_store_janitor(store: Arc<Mutex<ExecutionStore>>, interval: Duration) {
    let interval = if interval.is_zero() {
        Duration::from_millis(1)
    } else {
        interval
    };
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(interval).await;
            let mut guard = store.lock().unwrap_or_else(|e| e.into_inner());
            guard.evict_expired(now_millis());
        }
    });
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}
