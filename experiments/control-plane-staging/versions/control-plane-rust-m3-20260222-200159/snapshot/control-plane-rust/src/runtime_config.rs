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
    pub fn apply_patch(&self, patch: &RuntimeConfigPatch) -> Result<Self, Vec<String>> {
        let mut errors = Vec::new();
        let rate_max_per_second = match patch.rate_max_per_second {
            Some(value) if value <= 0 => {
                errors.push("rateMaxPerSecond must be greater than 0".to_string());
                self.rate_max_per_second
            }
            Some(value) => usize::try_from(value).unwrap_or(self.rate_max_per_second),
            None => self.rate_max_per_second,
        };

        let next = Self {
            revision: self.revision + 1,
            rate_max_per_second,
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
        };
        errors.extend(validation_errors(&next));
        if errors.is_empty() {
            Ok(next)
        } else {
            Err(errors)
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
    pub rate_max_per_second: Option<i64>,
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
            rate_max_per_second: optional_i64(object, "rateMaxPerSecond")?,
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

fn optional_i64(object: &serde_json::Map<String, Value>, key: &str) -> Result<Option<i64>, String> {
    match object.get(key) {
        None => Ok(None),
        Some(Value::Number(value)) => value
            .as_i64()
            .map(Some)
            .ok_or_else(|| format!("{key} must be an integer")),
        Some(_) => Err(format!("{key} must be an integer")),
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
    let mut total_nanos: u128 = 0;
    let mut remainder = &value[2..];
    let mut consumed_unit = false;
    while !remainder.is_empty() {
        let Some(unit_index) = remainder.find(|ch: char| ch.is_ascii_alphabetic()) else {
            return Err(());
        };
        let (number, rest) = remainder.split_at(unit_index);
        if number.is_empty() {
            return Err(());
        }
        let unit = rest.chars().next().ok_or(())?;
        remainder = &rest[unit.len_utf8()..];
        consumed_unit = true;

        match unit {
            'H' => {
                if number.contains('.') {
                    return Err(());
                }
                let amount = number.parse::<u128>().map_err(|_| ())?;
                total_nanos = total_nanos
                    .saturating_add(amount.saturating_mul(3_600_000_000_000));
            }
            'M' => {
                if number.contains('.') {
                    return Err(());
                }
                let amount = number.parse::<u128>().map_err(|_| ())?;
                total_nanos = total_nanos
                    .saturating_add(amount.saturating_mul(60_000_000_000));
            }
            'S' => {
                total_nanos = total_nanos.saturating_add(parse_seconds_component(number)?);
            }
            _ => return Err(()),
        }
    }
    if !consumed_unit {
        return Err(());
    }
    let secs = u64::try_from(total_nanos / 1_000_000_000).map_err(|_| ())?;
    let nanos = u32::try_from(total_nanos % 1_000_000_000).map_err(|_| ())?;
    Ok(Duration::new(secs, nanos))
}

fn format_duration_iso(duration: Duration) -> String {
    let total_seconds = duration.as_secs();
    let hours = total_seconds / 3600;
    let minutes = (total_seconds % 3600) / 60;
    let seconds = total_seconds % 60;
    let nanos = duration.subsec_nanos();

    let mut encoded = String::from("PT");
    if hours > 0 {
        encoded.push_str(&format!("{hours}H"));
    }
    if minutes > 0 {
        encoded.push_str(&format!("{minutes}M"));
    }
    if nanos > 0 {
        let fractional = format!("{:09}", nanos);
        let fractional = fractional.trim_end_matches('0');
        encoded.push_str(&format!("{seconds}.{fractional}S"));
    } else if seconds > 0 || encoded == "PT" {
        encoded.push_str(&format!("{seconds}S"));
    }
    encoded
}

fn parse_seconds_component(value: &str) -> Result<u128, ()> {
    if let Some((whole, fraction)) = value.split_once('.') {
        if whole.is_empty()
            || fraction.is_empty()
            || !whole.chars().all(|ch| ch.is_ascii_digit())
            || !fraction.chars().all(|ch| ch.is_ascii_digit())
            || fraction.len() > 9
        {
            return Err(());
        }
        let whole_seconds = whole.parse::<u128>().map_err(|_| ())?;
        let mut nanos = fraction.to_string();
        while nanos.len() < 9 {
            nanos.push('0');
        }
        let nanos = nanos.parse::<u128>().map_err(|_| ())?;
        Ok(whole_seconds.saturating_mul(1_000_000_000).saturating_add(nanos))
    } else {
        value
            .parse::<u128>()
            .map(|seconds| seconds.saturating_mul(1_000_000_000))
            .map_err(|_| ())
    }
}
