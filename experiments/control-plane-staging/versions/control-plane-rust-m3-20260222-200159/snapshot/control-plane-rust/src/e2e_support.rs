use serde_json::Value;
use std::collections::HashMap;
use std::fs;
use std::path::{Path, PathBuf};

pub fn pool_function_spec(
    name: &str,
    image: &str,
    endpoint_url: &str,
    timeout_ms: i32,
    concurrency: i32,
    queue_size: i32,
    max_retries: i32,
) -> HashMap<String, Value> {
    HashMap::from([
        ("name".to_string(), Value::String(name.to_string())),
        ("image".to_string(), Value::String(image.to_string())),
        ("timeoutMs".to_string(), Value::from(timeout_ms)),
        ("concurrency".to_string(), Value::from(concurrency)),
        ("queueSize".to_string(), Value::from(queue_size)),
        ("maxRetries".to_string(), Value::from(max_retries)),
        (
            "executionMode".to_string(),
            Value::String("POOL".to_string()),
        ),
        (
            "endpointUrl".to_string(),
            Value::String(endpoint_url.to_string()),
        ),
    ])
}

pub fn metric_sum(metrics: &str, metric: &str, label_filter: &HashMap<String, String>) -> f64 {
    aggregate_metric(metrics, metric, label_filter).0
}

pub fn assert_metric_sum_at_least(
    metrics: &str,
    metric: &str,
    label_filter: &HashMap<String, String>,
    min: f64,
) -> Result<(), String> {
    let (sum, matches) = aggregate_metric(metrics, metric, label_filter);
    if matches == 0 {
        return Err(format!(
            "expected metric {metric} with labels {label_filter:?} to be present"
        ));
    }
    if sum < min {
        return Err(format!("expected {metric} sum >= {:.1} but was {sum}", min));
    }
    Ok(())
}

fn aggregate_metric(
    metrics: &str,
    metric: &str,
    label_filter: &HashMap<String, String>,
) -> (f64, usize) {
    let mut sum = 0.0;
    let mut matches = 0usize;
    for raw_line in metrics.lines() {
        let line = raw_line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }

        let mut segments = line.split_whitespace();
        let Some(metric_token) = segments.next() else {
            continue;
        };
        let Some(value_token) = segments.next() else {
            continue;
        };

        let (metric_name, labels) = split_metric_token(metric_token);
        if metric_name != metric {
            continue;
        }
        if !labels_match(&labels, label_filter) {
            continue;
        }
        let Ok(value) = value_token.parse::<f64>() else {
            continue;
        };
        sum += value;
        matches += 1;
    }
    (sum, matches)
}

fn split_metric_token(metric_token: &str) -> (String, HashMap<String, String>) {
    if let Some(open_idx) = metric_token.find('{') {
        if let Some(close_idx) = metric_token.rfind('}') {
            let name = metric_token[..open_idx].to_string();
            let raw_labels = &metric_token[open_idx + 1..close_idx];
            return (name, parse_labels(raw_labels));
        }
    }
    (metric_token.to_string(), HashMap::new())
}

fn parse_labels(raw_labels: &str) -> HashMap<String, String> {
    let mut labels = HashMap::new();
    if raw_labels.trim().is_empty() {
        return labels;
    }

    for token in raw_labels.split(',') {
        let pair = token.trim();
        if pair.is_empty() {
            continue;
        }
        let mut kv = pair.splitn(2, '=');
        let Some(key) = kv.next() else {
            continue;
        };
        let Some(value_raw) = kv.next() else {
            continue;
        };
        let value = value_raw
            .trim()
            .strip_prefix('"')
            .and_then(|v| v.strip_suffix('"'))
            .unwrap_or(value_raw.trim())
            .replace("\\\"", "\"");
        labels.insert(key.trim().to_string(), value);
    }
    labels
}

fn labels_match(labels: &HashMap<String, String>, expected: &HashMap<String, String>) -> bool {
    expected
        .iter()
        .all(|(key, value)| labels.get(key) == Some(value))
}

pub fn resolve_boot_jar(
    directory: &Path,
    artifact_prefix: &str,
    project_version: Option<&str>,
) -> Result<PathBuf, String> {
    let mut candidates = Vec::new();
    let entries = fs::read_dir(directory).map_err(|err| err.to_string())?;
    for entry in entries {
        let entry = entry.map_err(|err| err.to_string())?;
        let path = entry.path();
        let Some(file_name) = path.file_name().and_then(|value| value.to_str()) else {
            continue;
        };
        if !file_name.starts_with(&format!("{artifact_prefix}-")) {
            continue;
        }
        if !file_name.ends_with(".jar") || file_name.ends_with("-plain.jar") {
            continue;
        }
        candidates.push(path);
    }

    if candidates.is_empty() {
        return Err(format!(
            "no boot jar found for prefix '{artifact_prefix}' in {}",
            directory.display()
        ));
    }

    if let Some(version) = project_version {
        let preferred = format!("{artifact_prefix}-{version}.jar");
        if let Some(path) = candidates.iter().find(|path| {
            path.file_name()
                .and_then(|value| value.to_str())
                .map(|value| value == preferred)
                .unwrap_or(false)
        }) {
            return Ok(path.clone());
        }
    }

    candidates
        .into_iter()
        .max_by(|a, b| {
            let an = a.file_name().and_then(|v| v.to_str()).unwrap_or_default();
            let bn = b.file_name().and_then(|v| v.to_str()).unwrap_or_default();
            an.cmp(bn)
        })
        .ok_or_else(|| "no suitable boot jar found".to_string())
}
