use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExecutionState {
    Queued,
    Running,
    Success,
    Error,
    Timeout,
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
}

impl ExecutionRecord {
    pub fn new(execution_id: &str, function_name: &str, status: ExecutionState) -> Self {
        Self {
            execution_id: execution_id.to_string(),
            function_name: function_name.to_string(),
            status,
            output: None,
            created_at_millis: 0,
            cleaned_up: false,
        }
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
        self.entries.get(execution_id).map(|entry| entry.record.clone())
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
                stored.record.cleaned_up = true;
            }

            true
        });
    }
}

fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .unwrap_or(0)
}
