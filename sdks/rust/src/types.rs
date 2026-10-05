use std::collections::{BTreeMap, HashMap};

use serde::{Deserialize, Serialize};
use serde_json::Value;
use serde_json::value::RawValue;

/// Marker telling the control plane that the handler chose the status and headers. These names
/// are the frozen wire contract shared with platform/common and every other SDK.
pub(crate) const FUNCTION_STATUS_HEADER: &str = "x-nanofaas-function-status";
pub(crate) const ENCODING_HEADER: &str = "x-nanofaas-encoding";

/// Mirrors ResponseHeaderPolicy.ALLOWED_RESPONSE_HEADERS in platform/common. Keep the two in sync.
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

/// Optional envelope a handler returns instead of a plain value, to choose the HTTP status,
/// response headers and the encoding marker. Detection is nominal: a handler returning any other
/// type gets an implicit 200 with no extra headers.
#[derive(Clone, Debug, PartialEq, Serialize)]
pub struct HandlerResponse {
    output: Value,
    status_code: u16,
    headers: Vec<(String, String)>,
    encoding: Option<String>,
}

impl HandlerResponse {
    pub fn new(output: Value, status_code: u16) -> Self {
        Self {
            output,
            status_code,
            headers: Vec::new(),
            encoding: None,
        }
    }

    /// Adds a response header. Only the allow-listed names reach the caller; for a name repeated
    /// case-insensitively, the first one wins.
    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }

    /// Marks how `output` is encoded, e.g. `"base64"` for binary bodies.
    pub fn encoding(mut self, encoding: impl Into<String>) -> Self {
        self.encoding = Some(encoding.into());
        self
    }

    pub fn output(&self) -> &Value {
        &self.output
    }

    pub fn status_code(&self) -> u16 {
        self.status_code
    }

    /// The headers as added, before the allow-list filter the runtime applies.
    pub fn headers(&self) -> &[(String, String)] {
        &self.headers
    }

    /// The value set with [`HandlerResponse::encoding`], if any.
    pub fn encoding_marker(&self) -> Option<&str> {
        self.encoding.as_deref()
    }

    pub(crate) fn into_parts(self) -> (Value, u16, Vec<(String, String)>, Option<String>) {
        (self.output, self.status_code, self.headers, self.encoding)
    }
}

pub(crate) fn is_status_code_valid(status_code: u16) -> bool {
    (200..=599).contains(&status_code)
}

/// Keeps allow-listed headers only, at most one per case-insensitive name (the first seen), in
/// their original casing.
pub(crate) fn filter_allowed_headers(raw: Vec<(String, String)>) -> Vec<(String, String)> {
    let mut filtered: Vec<(String, String)> = Vec::new();
    for (name, value) in raw {
        let lower = name.to_ascii_lowercase();
        let allowed = ALLOWED_RESPONSE_HEADERS.contains(&lower.as_str());
        let duplicate = filtered
            .iter()
            .any(|(kept, _)| kept.eq_ignore_ascii_case(&name));
        if allowed && !duplicate {
            filtered.push((name, value));
        }
    }
    filtered
}

/// The `/invoke` request body. `input` stays raw so it is deserialized once, straight into the
/// handler's own input type.
#[derive(Deserialize)]
pub(crate) struct WireRequest<'a> {
    #[serde(borrow, default)]
    pub input: Option<&'a RawValue>,
    #[serde(default, deserialize_with = "nullable_string_map")]
    pub metadata: HashMap<String, String>,
    #[serde(default, deserialize_with = "nullable_string_map")]
    pub headers: HashMap<String, String>,
}

// Java InvocationRequest represents omitted optional maps as JSON null.
fn nullable_string_map<'de, D>(deserializer: D) -> Result<HashMap<String, String>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    Option::<HashMap<String, String>>::deserialize(deserializer).map(Option::unwrap_or_default)
}

#[derive(Clone, Debug, PartialEq, Serialize)]
pub(crate) struct ErrorInfo {
    pub code: &'static str,
    pub message: String,
}

/// The callback body. Keys are camelCase to match platform/common's InvocationResult: a mismatch
/// is silent, the field just vanishes on the control plane. `output` and `error` are always
/// present; the envelope fields only when set.
#[derive(Debug, Serialize)]
pub(crate) struct InvocationResult<'a> {
    pub success: bool,
    pub output: Option<&'a RawValue>,
    pub error: Option<ErrorInfo>,
    #[serde(rename = "statusCode", skip_serializing_if = "Option::is_none")]
    pub status_code: Option<u16>,
    #[serde(skip_serializing_if = "BTreeMap::is_empty")]
    pub headers: BTreeMap<&'a str, &'a str>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub encoding: Option<&'a str>,
}

impl<'a> InvocationResult<'a> {
    pub fn success(output: &'a RawValue) -> Self {
        Self {
            success: true,
            output: Some(output),
            error: None,
            status_code: None,
            headers: BTreeMap::new(),
            encoding: None,
        }
    }

    /// A function-decided result. `success` is always true: the control plane retries on
    /// `!success`, so a response the function chose must never be retried, 5xx included.
    pub fn envelope(
        output: &'a RawValue,
        status_code: u16,
        headers: &'a [(String, String)],
        encoding: Option<&'a str>,
    ) -> Self {
        Self {
            status_code: Some(status_code),
            headers: headers
                .iter()
                .map(|(name, value)| (name.as_str(), value.as_str()))
                .collect(),
            encoding,
            ..Self::success(output)
        }
    }

    pub fn failure(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            success: false,
            output: None,
            error: Some(ErrorInfo {
                code,
                message: message.into(),
            }),
            status_code: None,
            headers: BTreeMap::new(),
            encoding: None,
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn raw(json: &str) -> &RawValue {
        serde_json::from_str(json).unwrap()
    }

    #[test]
    fn plain_success_omits_envelope_fields_but_keeps_null_error() {
        let encoded = serde_json::to_value(InvocationResult::success(raw(r#"{"a":1}"#))).unwrap();
        assert_eq!(
            encoded,
            json!({"success": true, "output": {"a": 1}, "error": null})
        );
    }

    #[test]
    fn failure_keeps_null_output() {
        let encoded =
            serde_json::to_value(InvocationResult::failure("HANDLER_ERROR", "Handler failed"))
                .unwrap();
        assert_eq!(
            encoded,
            json!({"success": false, "output": null,
                   "error": {"code": "HANDLER_ERROR", "message": "Handler failed"}})
        );
    }

    #[test]
    fn envelope_is_always_success_and_uses_camel_case_keys() {
        let headers = vec![("Content-Type".to_string(), "image/png".to_string())];
        let encoded = serde_json::to_value(InvocationResult::envelope(
            raw(r#""aGk=""#),
            503,
            &headers,
            Some("base64"),
        ))
        .unwrap();
        assert_eq!(
            encoded,
            json!({"success": true, "output": "aGk=", "error": null, "statusCode": 503,
                   "headers": {"Content-Type": "image/png"}, "encoding": "base64"})
        );
    }

    #[test]
    fn handler_response_exposes_what_it_was_built_with() {
        let response = HandlerResponse::new(json!({"id": 7}), 201)
            .header("Location", "/things/7")
            .encoding("identity");
        assert_eq!(response.output(), &json!({"id": 7}));
        assert_eq!(response.status_code(), 201);
        assert_eq!(
            response.headers(),
            [("Location".to_string(), "/things/7".to_string())]
        );
        assert_eq!(response.encoding_marker(), Some("identity"));
    }

    #[test]
    fn status_code_validity_is_200_to_599() {
        assert!(!is_status_code_valid(199));
        assert!(is_status_code_valid(200));
        assert!(is_status_code_valid(599));
        assert!(!is_status_code_valid(600));
    }

    #[test]
    fn filter_keeps_allowed_headers_in_original_casing_and_drops_the_rest() {
        let filtered = filter_allowed_headers(vec![
            ("Content-Type".into(), "text/plain".into()),
            ("X-Execution-Id".into(), "spoofed".into()),
            ("X-NanoFaaS-Function-Status".into(), "false".into()),
            ("ETag".into(), "\"v1\"".into()),
        ]);
        assert_eq!(
            filtered,
            vec![
                ("Content-Type".to_string(), "text/plain".to_string()),
                ("ETag".to_string(), "\"v1\"".to_string()),
            ]
        );
    }

    #[test]
    fn filter_keeps_only_the_first_of_case_insensitive_duplicates() {
        let filtered = filter_allowed_headers(vec![
            ("Content-Type".into(), "a".into()),
            ("content-type".into(), "b".into()),
        ]);
        assert_eq!(
            filtered,
            vec![("Content-Type".to_string(), "a".to_string())]
        );
    }

    #[test]
    fn wire_request_defaults_missing_fields() {
        let request: WireRequest = serde_json::from_str(r#"{"input":{"x":1}}"#).unwrap();
        assert_eq!(request.input.unwrap().get(), r#"{"x":1}"#);
        assert!(request.metadata.is_empty() && request.headers.is_empty());
        let empty: WireRequest = serde_json::from_str("{}").unwrap();
        assert!(empty.input.is_none());
    }
}
