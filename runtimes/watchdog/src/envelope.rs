//! The wire-level response envelope, as it reaches the watchdog.
//!
//! Unlike the typed SDKs — which recognise an envelope through their own type systems — a child
//! process writing JSON to stdout has no type to check. The `__nanofaas_envelope__` marker key is
//! what disambiguates "these are envelope fields" from "the script's real output happens to contain
//! a statusCode key". This marker applies to STDIO and FILE mode only: on the HTTP proxy path the
//! fronted runtime is an SDK that already emits the marker headers, and the watchdog forwards them.

use std::collections::BTreeMap;

pub const MARKER_HEADER: &str = "X-NanoFaaS-Function-Status";
pub const ENCODING_HEADER: &str = "X-NanoFaaS-Encoding";
pub const ENVELOPE_KEY: &str = "__nanofaas_envelope__";

/// Mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/common. Keep in sync.
const ALLOWED_RESPONSE_HEADERS: [&str; 8] = [
    "content-type",
    "location",
    "cache-control",
    "etag",
    "content-disposition",
    "content-language",
    "retry-after",
    "vary",
];

#[derive(Debug, Clone)]
pub struct Envelope {
    pub output: serde_json::Value,
    pub status_code: u16,
    pub headers: BTreeMap<String, String>,
    pub encoding: Option<String>,
}

pub fn is_status_valid(status: u16) -> bool {
    (200..=599).contains(&status)
}

/// Filters handler-supplied response headers down to the allow-list.
///
/// At most one entry survives per header name, compared case-insensitively. BTreeMap iteration is
/// ordered, so the first occurrence in sort order wins deterministically and keeps its casing.
pub fn filter_allowed_headers(raw: &serde_json::Value) -> BTreeMap<String, String> {
    let mut filtered = BTreeMap::new();
    let mut seen: Vec<String> = Vec::new();
    let Some(object) = raw.as_object() else {
        return filtered;
    };
    for (key, value) in object {
        let lower = key.to_lowercase();
        if !ALLOWED_RESPONSE_HEADERS.contains(&lower.as_str()) || seen.contains(&lower) {
            continue;
        }
        let Some(text) = value.as_str() else {
            continue;
        };
        seen.push(lower);
        filtered.insert(key.clone(), text.to_string());
    }
    filtered
}

/// Recognises an envelope in a child process's parsed JSON output.
///
/// Returns `None` when there is no marker — the caller uses the value verbatim, which is the
/// pre-envelope behavior and must stay byte-identical. Returns `Some(Err)` when the marker is
/// present but the envelope is unusable, which the caller reports as a platform error.
pub fn detect(value: &serde_json::Value) -> Option<Result<Envelope, String>> {
    let object = value.as_object()?;
    if object.get(ENVELOPE_KEY)?.as_bool() != Some(true) {
        return None;
    }

    let status_code = match object.get("statusCode") {
        None | Some(serde_json::Value::Null) => 200,
        Some(raw) => match raw.as_u64() {
            Some(candidate) if candidate <= u16::MAX as u64 => candidate as u16,
            _ => return Some(Err(format!("Envelope statusCode is not an integer: {raw}"))),
        },
    };
    if !is_status_valid(status_code) {
        return Some(Err(format!(
            "Envelope statusCode {status_code} is outside [200,599]"
        )));
    }

    let headers = object
        .get("headers")
        .map(filter_allowed_headers)
        .unwrap_or_default();

    let encoding = object
        .get("encoding")
        .and_then(|raw| raw.as_str())
        .map(str::to_string);

    Some(Ok(Envelope {
        output: object
            .get("output")
            .cloned()
            .unwrap_or(serde_json::Value::Null),
        status_code,
        headers,
        encoding,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn detect_returns_none_without_the_marker_key() {
        let value = json!({"roman": "XLII", "statusCode": 999});
        assert!(
            detect(&value).is_none(),
            "a plain output that merely contains a statusCode key must NOT be treated as an envelope"
        );
    }

    #[test]
    fn detect_returns_none_for_non_objects() {
        assert!(detect(&json!("plain string")).is_none());
        assert!(detect(&json!([1, 2, 3])).is_none());
        assert!(detect(&json!(null)).is_none());
    }

    #[test]
    fn detect_unpacks_a_well_formed_envelope() {
        let value = json!({
            "__nanofaas_envelope__": true,
            "output": {"error": "not found"},
            "statusCode": 404,
            "headers": {"Location": "/x", "X-Custom": "dropped"},
            "encoding": "base64"
        });

        let envelope = detect(&value)
            .expect("marker present")
            .expect("well formed");

        assert_eq!(envelope.status_code, 404);
        assert_eq!(envelope.output, json!({"error": "not found"}));
        assert_eq!(envelope.encoding.as_deref(), Some("base64"));
        assert_eq!(
            envelope.headers.get("Location").map(String::as_str),
            Some("/x")
        );
        assert!(
            !envelope.headers.contains_key("X-Custom"),
            "disallowed header must be dropped"
        );
    }

    #[test]
    fn detect_defaults_a_missing_status_to_200() {
        let value = json!({"__nanofaas_envelope__": true, "output": "ok"});
        let envelope = detect(&value).unwrap().unwrap();
        assert_eq!(envelope.status_code, 200);
        assert!(envelope.encoding.is_none());
        assert!(envelope.headers.is_empty());
    }

    #[test]
    fn detect_rejects_an_out_of_range_status() {
        let value = json!({"__nanofaas_envelope__": true, "output": "ok", "statusCode": 999});
        let err = detect(&value)
            .expect("marker present")
            .expect_err("999 is not in [200,599]");
        assert!(
            err.contains("999"),
            "the error must name the offending status, got: {err}"
        );
    }

    #[test]
    fn detect_ignores_a_falsy_marker() {
        let value = json!({"__nanofaas_envelope__": false, "output": "ok", "statusCode": 404});
        assert!(
            detect(&value).is_none(),
            "only an explicit true marker opts in; anything else is plain output"
        );
    }

    #[test]
    fn filter_dedupes_case_insensitively_keeping_the_first_occurrence() {
        // BTreeMap iteration is ordered, so "Content-Type" sorts before "content-type"
        // and first-occurrence-wins is deterministic here (unlike Go's HashMap).
        let raw = json!({"Content-Type": "application/pdf", "content-type": "text/plain"});
        let filtered = filter_allowed_headers(&raw);
        assert_eq!(
            filtered.len(),
            1,
            "colliding casings must collapse to one entry"
        );
        assert_eq!(
            filtered.get("Content-Type").map(String::as_str),
            Some("application/pdf")
        );
    }

    #[test]
    fn filter_drops_control_headers() {
        let raw = json!({
            "X-NanoFaaS-Function-Status": "spoofed",
            "X-NanoFaaS-Encoding": "spoofed",
            "X-Execution-Id": "spoofed",
            "ETag": "\"abc\""
        });
        let filtered = filter_allowed_headers(&raw);
        assert_eq!(filtered.len(), 1);
        assert!(filtered.contains_key("ETag"));
    }

    #[test]
    fn is_status_valid_range_boundaries() {
        assert!(is_status_valid(200));
        assert!(is_status_valid(599));
        assert!(!is_status_valid(199));
        assert!(!is_status_valid(600));
    }
}
