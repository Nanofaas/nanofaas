use serde::Serialize;
use serde_json::Value;
use std::time::Duration;

#[derive(Debug, Clone)]
pub struct RuntimeConfigSnapshot {
    pub revision: u64,
    pub rate_max_per_second: usize,
    pub sync_queue_enabled: bool,
    pub sync_queue_admission_enabled: bool,
    pub sync_queue_max_estimated_wait: Duration,
    pub sync_queue_max_queue_wait: Duration,
    pub sync_queue_retry_after_seconds: i32,
}

impl RuntimeConfigSnapshot {
    pub fn apply_patch(&self, patch: &RuntimeConfigPatch) -> Self {
        Self {
            revision: self.revision + 1,
            rate_max_per_second: patch
                .rate_max_per_second
                .unwrap_or(self.rate_max_per_second),
            sync_queue_enabled: patch.sync_queue_enabled.unwrap_or(self.sync_queue_enabled),
            sync_queue_admission_enabled: patch
                .sync_queue_admission_enabled
                .unwrap_or(self.sync_queue_admission_enabled),
            sync_queue_max_estimated_wait: patch
                .sync_queue_max_estimated_wait
                .unwrap_or(self.sync_queue_max_estimated_wait),
            sync_queue_max_queue_wait: patch
                .sync_queue_max_queue_wait
                .unwrap_or(self.sync_queue_max_queue_wait),
            sync_queue_retry_after_seconds: patch
                .sync_queue_retry_after_seconds
                .unwrap_or(self.sync_queue_retry_after_seconds),
        }
    }

    pub fn to_response(&self) -> RuntimeConfigSnapshotResponse {
        RuntimeConfigSnapshotResponse {
            revision: self.revision,
            rate_max_per_second: self.rate_max_per_second,
            sync_queue_enabled: self.sync_queue_enabled,
            sync_queue_admission_enabled: self.sync_queue_admission_enabled,
            sync_queue_max_estimated_wait: format_duration_iso(self.sync_queue_max_estimated_wait),
            sync_queue_max_queue_wait: format_duration_iso(self.sync_queue_max_queue_wait),
            sync_queue_retry_after_seconds: self.sync_queue_retry_after_seconds,
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct RuntimeConfigPatch {
    pub rate_max_per_second: Option<usize>,
    pub sync_queue_enabled: Option<bool>,
    pub sync_queue_admission_enabled: Option<bool>,
    pub sync_queue_max_estimated_wait: Option<Duration>,
    pub sync_queue_max_queue_wait: Option<Duration>,
    pub sync_queue_retry_after_seconds: Option<i32>,
}

#[derive(Debug, Clone)]
pub struct ParsedRuntimeConfigRequest {
    pub expected_revision: Option<u64>,
    pub patch: RuntimeConfigPatch,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeConfigSnapshotResponse {
    pub revision: u64,
    pub rate_max_per_second: usize,
    pub sync_queue_enabled: bool,
    pub sync_queue_admission_enabled: bool,
    pub sync_queue_max_estimated_wait: String,
    pub sync_queue_max_queue_wait: String,
    pub sync_queue_retry_after_seconds: i32,
}

#[derive(Debug, Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RuntimeConfigPatchResponse {
    pub revision: u64,
    pub effective_config: RuntimeConfigSnapshotResponse,
    pub applied_at: String,
    pub change_id: String,
    pub warnings: Vec<String>,
}

pub fn parse_request(value: Value) -> Result<ParsedRuntimeConfigRequest, String> {
    let object = value
        .as_object()
        .ok_or_else(|| "runtime config payload must be a JSON object".to_string())?;
    Ok(ParsedRuntimeConfigRequest {
        expected_revision: optional_u64(object, "expectedRevision")?,
        patch: RuntimeConfigPatch {
            rate_max_per_second: optional_usize(object, "rateMaxPerSecond")?,
            sync_queue_enabled: optional_bool(object, "syncQueueEnabled")?,
            sync_queue_admission_enabled: optional_bool(object, "syncQueueAdmissionEnabled")?,
            sync_queue_max_estimated_wait: optional_duration(object, "syncQueueMaxEstimatedWait")?,
            sync_queue_max_queue_wait: optional_duration(object, "syncQueueMaxQueueWait")?,
            sync_queue_retry_after_seconds: optional_i32(object, "syncQueueRetryAfterSeconds")?,
        },
    })
}

pub fn validation_errors(snapshot: &RuntimeConfigSnapshot) -> Vec<String> {
    let mut errors = Vec::new();
    if snapshot.rate_max_per_second == 0 {
        errors.push("rateMaxPerSecond must be greater than 0".to_string());
    }
    if snapshot.sync_queue_max_estimated_wait.is_zero() {
        errors.push("syncQueueMaxEstimatedWait must be greater than 0".to_string());
    }
    if snapshot.sync_queue_max_queue_wait.is_zero() {
        errors.push("syncQueueMaxQueueWait must be greater than 0".to_string());
    }
    if snapshot.sync_queue_max_estimated_wait > snapshot.sync_queue_max_queue_wait {
        errors.push("syncQueueMaxEstimatedWait must be <= syncQueueMaxQueueWait".to_string());
    }
    if snapshot.sync_queue_retry_after_seconds < 1 {
        errors.push("syncQueueRetryAfterSeconds must be >= 1".to_string());
    }
    errors
}

fn optional_bool(
    object: &serde_json::Map<String, Value>,
    key: &str,
) -> Result<Option<bool>, String> {
    match object.get(key) {
        None => Ok(None),
        Some(Value::Bool(value)) => Ok(Some(*value)),
        Some(_) => Err(format!("{key} must be a boolean")),
    }
}

fn optional_u64(object: &serde_json::Map<String, Value>, key: &str) -> Result<Option<u64>, String> {
    match object.get(key) {
        None => Ok(None),
        Some(Value::Number(value)) => value
            .as_u64()
            .map(Some)
            .ok_or_else(|| format!("{key} must be an unsigned integer")),
        Some(_) => Err(format!("{key} must be an unsigned integer")),
    }
}

fn optional_i32(object: &serde_json::Map<String, Value>, key: &str) -> Result<Option<i32>, String> {
    match object.get(key) {
        None => Ok(None),
        Some(Value::Number(value)) => {
            let parsed = value
                .as_i64()
                .ok_or_else(|| format!("{key} must be an integer"))?;
            i32::try_from(parsed)
                .map(Some)
                .map_err(|_| format!("{key} is out of range"))
        }
        Some(_) => Err(format!("{key} must be an integer")),
    }
}

fn optional_usize(
    object: &serde_json::Map<String, Value>,
    key: &str,
) -> Result<Option<usize>, String> {
    match object.get(key) {
        None => Ok(None),
        Some(Value::Number(value)) => value
            .as_u64()
            .and_then(|parsed| usize::try_from(parsed).ok())
            .map(Some)
            .ok_or_else(|| format!("{key} must be a positive integer")),
        Some(_) => Err(format!("{key} must be a positive integer")),
    }
}

fn optional_duration(
    object: &serde_json::Map<String, Value>,
    key: &str,
) -> Result<Option<Duration>, String> {
    match object.get(key) {
        None => Ok(None),
        Some(Value::String(value)) => parse_duration_iso(value)
            .map(Some)
            .map_err(|_| format!("{key} must be an ISO-8601 duration like PT5S or PT1M30S")),
        Some(_) => Err(format!(
            "{key} must be an ISO-8601 duration like PT5S or PT1M30S"
        )),
    }
}

fn parse_duration_iso(value: &str) -> Result<Duration, ()> {
    if !value.starts_with("PT") {
        return Err(());
    }
    let mut total_seconds: u64 = 0;
    let mut current = String::new();
    let mut consumed_unit = false;
    for ch in value[2..].chars() {
        if ch.is_ascii_digit() {
            current.push(ch);
            continue;
        }
        let amount = current.parse::<u64>().map_err(|_| ())?;
        current.clear();
        consumed_unit = true;
        match ch {
            'H' => total_seconds = total_seconds.saturating_add(amount.saturating_mul(3600)),
            'M' => total_seconds = total_seconds.saturating_add(amount.saturating_mul(60)),
            'S' => total_seconds = total_seconds.saturating_add(amount),
            _ => return Err(()),
        }
    }
    if !current.is_empty() || !consumed_unit {
        return Err(());
    }
    Ok(Duration::from_secs(total_seconds))
}

fn format_duration_iso(duration: Duration) -> String {
    let total_seconds = duration.as_secs();
    let hours = total_seconds / 3600;
    let minutes = (total_seconds % 3600) / 60;
    let seconds = total_seconds % 60;

    let mut encoded = String::from("PT");
    if hours > 0 {
        encoded.push_str(&format!("{hours}H"));
    }
    if minutes > 0 {
        encoded.push_str(&format!("{minutes}M"));
    }
    if seconds > 0 || encoded == "PT" {
        encoded.push_str(&format!("{seconds}S"));
    }
    encoded
}
