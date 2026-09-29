use std::time::Duration;

const MAXIMUM_HANDLER_TIMEOUT: Duration = Duration::from_secs(3600);
const MAXIMUM_CONCURRENT_HANDLERS: usize = 4096;
const MAXIMUM_PAYLOAD_BYTES: usize = 64 * 1024 * 1024;
const MAXIMUM_PENDING_CALLBACKS: usize = 65_536;
const MAXIMUM_PENDING_CALLBACK_BYTES: usize = 1024 * 1024 * 1024;
const MAXIMUM_IO_TIMEOUT: Duration = Duration::from_secs(300);
const MAXIMUM_CALLBACK_ATTEMPTS: usize = 10;

/// Runtime identity and limits. `from_env` reads the same variables, defaults and maxima as the
/// Go SDK (`sdks/go/README.md`); zero, unparsable or above-maximum values fall back to defaults.
/// Durations are read from the environment as milliseconds.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RuntimeSettings {
    pub port: u16,
    pub execution_id: Option<String>,
    pub trace_id: Option<String>,
    pub callback_url: Option<String>,
    pub function_handler: Option<String>,
    pub handler_timeout: Duration,
    pub max_concurrent_handlers: usize,
    pub max_input_bytes: usize,
    pub max_output_bytes: usize,
    pub max_pending_callbacks: usize,
    pub max_pending_callback_bytes: usize,
    pub max_callback_payload_bytes: usize,
    pub body_read_timeout: Duration,
    pub callback_attempt_timeout: Duration,
    pub callback_max_attempts: usize,
    pub shutdown_timeout: Duration,
}

impl Default for RuntimeSettings {
    fn default() -> Self {
        Self {
            port: 8080,
            execution_id: None,
            trace_id: None,
            callback_url: None,
            function_handler: None,
            handler_timeout: Duration::from_secs(30),
            max_concurrent_handlers: 32,
            max_input_bytes: 1024 * 1024,
            max_output_bytes: 1024 * 1024,
            max_pending_callbacks: 128,
            max_pending_callback_bytes: 16 * 1024 * 1024,
            max_callback_payload_bytes: 2 * 1024 * 1024,
            body_read_timeout: Duration::from_secs(5),
            callback_attempt_timeout: Duration::from_secs(5),
            callback_max_attempts: 3,
            shutdown_timeout: Duration::from_secs(5),
        }
    }
}

impl RuntimeSettings {
    pub fn from_env() -> Self {
        Self::from_lookup(|name| std::env::var(name).ok())
    }

    fn from_lookup(lookup: impl Fn(&str) -> Option<String>) -> Self {
        let text = |name: &str| {
            lookup(name)
                .map(|value| value.trim().to_owned())
                .filter(|value| !value.is_empty())
        };
        let count = |name: &str| text(name).and_then(|v| v.parse().ok()).unwrap_or(0);
        let millis = |name: &str| {
            Duration::from_millis(text(name).and_then(|v| v.parse().ok()).unwrap_or(0))
        };
        Self {
            port: text("PORT").and_then(|v| v.parse().ok()).unwrap_or(0),
            execution_id: text("EXECUTION_ID"),
            trace_id: text("TRACE_ID"),
            callback_url: text("CALLBACK_URL"),
            function_handler: text("FUNCTION_HANDLER"),
            handler_timeout: millis("NANOFAAS_HANDLER_TIMEOUT"),
            max_concurrent_handlers: count("NANOFAAS_MAX_CONCURRENT_HANDLERS"),
            max_input_bytes: count("NANOFAAS_MAX_INPUT_BYTES"),
            max_output_bytes: count("NANOFAAS_MAX_OUTPUT_BYTES"),
            max_pending_callbacks: count("NANOFAAS_MAX_PENDING_CALLBACKS"),
            max_pending_callback_bytes: count("NANOFAAS_MAX_PENDING_CALLBACK_BYTES"),
            max_callback_payload_bytes: count("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES"),
            body_read_timeout: millis("NANOFAAS_BODY_READ_TIMEOUT"),
            callback_attempt_timeout: millis("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT"),
            callback_max_attempts: count("NANOFAAS_CALLBACK_MAX_ATTEMPTS"),
            shutdown_timeout: millis("NANOFAAS_SHUTDOWN_TIMEOUT"),
        }
        .normalized()
    }

    /// Replaces zero or above-maximum values with defaults, then caps the single-callback payload
    /// at the aggregate callback budget, as the Go SDK does for programmatic settings too.
    pub(crate) fn normalized(self) -> Self {
        let d = Self::default();
        let port = if self.port == 0 { d.port } else { self.port };
        let max_pending_callback_bytes = bounded(
            self.max_pending_callback_bytes,
            d.max_pending_callback_bytes,
            MAXIMUM_PENDING_CALLBACK_BYTES,
        );
        let max_callback_payload_bytes = bounded(
            self.max_callback_payload_bytes,
            d.max_callback_payload_bytes,
            MAXIMUM_PAYLOAD_BYTES,
        )
        .min(max_pending_callback_bytes);
        Self {
            port,
            handler_timeout: bounded(
                self.handler_timeout,
                d.handler_timeout,
                MAXIMUM_HANDLER_TIMEOUT,
            ),
            max_concurrent_handlers: bounded(
                self.max_concurrent_handlers,
                d.max_concurrent_handlers,
                MAXIMUM_CONCURRENT_HANDLERS,
            ),
            max_input_bytes: bounded(
                self.max_input_bytes,
                d.max_input_bytes,
                MAXIMUM_PAYLOAD_BYTES,
            ),
            max_output_bytes: bounded(
                self.max_output_bytes,
                d.max_output_bytes,
                MAXIMUM_PAYLOAD_BYTES,
            ),
            max_pending_callbacks: bounded(
                self.max_pending_callbacks,
                d.max_pending_callbacks,
                MAXIMUM_PENDING_CALLBACKS,
            ),
            max_pending_callback_bytes,
            max_callback_payload_bytes,
            body_read_timeout: bounded(
                self.body_read_timeout,
                d.body_read_timeout,
                MAXIMUM_IO_TIMEOUT,
            ),
            callback_attempt_timeout: bounded(
                self.callback_attempt_timeout,
                d.callback_attempt_timeout,
                MAXIMUM_IO_TIMEOUT,
            ),
            callback_max_attempts: bounded(
                self.callback_max_attempts,
                d.callback_max_attempts,
                MAXIMUM_CALLBACK_ATTEMPTS,
            ),
            shutdown_timeout: bounded(
                self.shutdown_timeout,
                d.shutdown_timeout,
                MAXIMUM_IO_TIMEOUT,
            ),
            ..self
        }
    }

    /// Execution and trace ids for one request: a non-blank header wins over the environment.
    pub(crate) fn resolve_identity(
        &self,
        header_execution_id: Option<&str>,
        header_trace_id: Option<&str>,
    ) -> (Option<String>, Option<String>) {
        let pick = |header: Option<&str>, fallback: &Option<String>| {
            header
                .map(str::trim)
                .filter(|value| !value.is_empty())
                .map(str::to_owned)
                .or_else(|| fallback.clone())
        };
        (
            pick(header_execution_id, &self.execution_id),
            pick(header_trace_id, &self.trace_id),
        )
    }
}

fn bounded<T: PartialOrd + Default>(value: T, fallback: T, maximum: T) -> T {
    if value <= T::default() || value > maximum {
        fallback
    } else {
        value
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    fn from(vars: &[(&str, &str)]) -> RuntimeSettings {
        let vars: HashMap<String, String> = vars
            .iter()
            .map(|(name, value)| (name.to_string(), value.to_string()))
            .collect();
        RuntimeSettings::from_lookup(|name| vars.get(name).cloned())
    }

    #[test]
    fn empty_environment_yields_go_defaults() {
        let settings = from(&[]);
        assert_eq!(settings, RuntimeSettings::default());
        assert_eq!(settings.port, 8080);
        assert_eq!(settings.handler_timeout, Duration::from_secs(30));
        assert_eq!(settings.max_pending_callbacks, 128);
        assert_eq!(settings.max_callback_payload_bytes, 2 * 1024 * 1024);
    }

    #[test]
    fn reads_explicit_values() {
        let settings = from(&[
            ("PORT", "9000"),
            ("EXECUTION_ID", " exec-1 "),
            ("TRACE_ID", "trace-1"),
            ("CALLBACK_URL", "http://cp:8080/v1/internal/executions"),
            ("FUNCTION_HANDLER", "echo"),
            ("NANOFAAS_HANDLER_TIMEOUT", "1500"),
            ("NANOFAAS_MAX_CONCURRENT_HANDLERS", "4"),
            ("NANOFAAS_MAX_INPUT_BYTES", "2048"),
            ("NANOFAAS_MAX_OUTPUT_BYTES", "4096"),
            ("NANOFAAS_MAX_PENDING_CALLBACKS", "8"),
            ("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", "65536"),
            ("NANOFAAS_MAX_CALLBACK_PAYLOAD_BYTES", "1024"),
            ("NANOFAAS_BODY_READ_TIMEOUT", "250"),
            ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "300"),
            ("NANOFAAS_CALLBACK_MAX_ATTEMPTS", "5"),
            ("NANOFAAS_SHUTDOWN_TIMEOUT", "700"),
        ]);
        assert_eq!(settings.port, 9000);
        assert_eq!(settings.execution_id.as_deref(), Some("exec-1"));
        assert_eq!(settings.trace_id.as_deref(), Some("trace-1"));
        assert_eq!(
            settings.callback_url.as_deref(),
            Some("http://cp:8080/v1/internal/executions")
        );
        assert_eq!(settings.function_handler.as_deref(), Some("echo"));
        assert_eq!(settings.handler_timeout, Duration::from_millis(1500));
        assert_eq!(settings.max_concurrent_handlers, 4);
        assert_eq!(settings.max_input_bytes, 2048);
        assert_eq!(settings.max_output_bytes, 4096);
        assert_eq!(settings.max_pending_callbacks, 8);
        assert_eq!(settings.max_pending_callback_bytes, 65536);
        assert_eq!(settings.max_callback_payload_bytes, 1024);
        assert_eq!(settings.body_read_timeout, Duration::from_millis(250));
        assert_eq!(
            settings.callback_attempt_timeout,
            Duration::from_millis(300)
        );
        assert_eq!(settings.callback_max_attempts, 5);
        assert_eq!(settings.shutdown_timeout, Duration::from_millis(700));
    }

    #[test]
    fn rejects_zero_unparsable_and_above_maximum_values() {
        let settings = from(&[
            ("PORT", "not-a-port"),
            ("NANOFAAS_HANDLER_TIMEOUT", "0"),
            ("NANOFAAS_MAX_CONCURRENT_HANDLERS", "4097"),
            ("NANOFAAS_MAX_INPUT_BYTES", "-1"),
            ("NANOFAAS_MAX_OUTPUT_BYTES", "67108865"),
            ("NANOFAAS_MAX_PENDING_CALLBACKS", "65537"),
            ("NANOFAAS_MAX_PENDING_CALLBACK_BYTES", "1073741825"),
            ("NANOFAAS_BODY_READ_TIMEOUT", "300001"),
            ("NANOFAAS_CALLBACK_ATTEMPT_TIMEOUT", "5s"),
            ("NANOFAAS_CALLBACK_MAX_ATTEMPTS", "11"),
            ("NANOFAAS_SHUTDOWN_TIMEOUT", ""),
        ]);
        assert_eq!(settings, RuntimeSettings::default());
    }

    #[test]
    fn caps_callback_payload_at_pending_callback_bytes() {
        let settings = RuntimeSettings {
            max_pending_callback_bytes: 1024,
            max_callback_payload_bytes: 4096,
            ..RuntimeSettings::default()
        }
        .normalized();
        assert_eq!(settings.max_callback_payload_bytes, 1024);
    }

    #[test]
    fn resolve_identity_prefers_trimmed_headers_over_environment() {
        let settings = RuntimeSettings {
            execution_id: Some("env-exec".into()),
            trace_id: Some("env-trace".into()),
            ..RuntimeSettings::default()
        };
        assert_eq!(
            settings.resolve_identity(Some(" hdr-exec "), Some("hdr-trace")),
            (Some("hdr-exec".into()), Some("hdr-trace".into()))
        );
        assert_eq!(
            settings.resolve_identity(Some("   "), None),
            (Some("env-exec".into()), Some("env-trace".into()))
        );
        assert_eq!(
            RuntimeSettings::default().resolve_identity(None, None),
            (None, None)
        );
    }
}
