//! Shared console logging, installed only when the application explicitly opts in.
use serde_json::{Map, Value, json};
use std::collections::BTreeMap;
use std::fmt;
use tracing::field::{Field, Visit};
use tracing::{Event, Subscriber};
use tracing_subscriber::fmt::format::{JsonFields, Writer};
use tracing_subscriber::fmt::time::{FormatTime, SystemTime};
use tracing_subscriber::fmt::{FmtContext, FormatEvent, FormatFields, FormattedFields};
use tracing_subscriber::registry::LookupSpan;

/// Reusable with a host-owned subscriber; application attributes cannot overwrite the envelope.
pub struct LogFormatter {
    service: String,
    json: bool,
}
impl LogFormatter {
    pub fn new(service: impl Into<String>, json: bool) -> Self {
        let service = service.into();
        Self {
            service: if service.is_empty() {
                "function-runtime".into()
            } else {
                service
            },
            json,
        }
    }
}

#[derive(Default)]
struct Fields(BTreeMap<String, Value>);
impl Visit for Fields {
    fn record_debug(&mut self, field: &Field, value: &dyn fmt::Debug) {
        self.0
            .insert(field.name().into(), Value::String(format!("{value:?}")));
    }
    fn record_str(&mut self, field: &Field, value: &str) {
        self.0
            .insert(field.name().into(), Value::String(value.into()));
    }
    fn record_i64(&mut self, field: &Field, value: i64) {
        self.0.insert(field.name().into(), json!(value));
    }
    fn record_u64(&mut self, field: &Field, value: u64) {
        self.0.insert(field.name().into(), json!(value));
    }
    fn record_bool(&mut self, field: &Field, value: bool) {
        self.0.insert(field.name().into(), json!(value));
    }
    fn record_f64(&mut self, field: &Field, value: f64) {
        self.0.insert(field.name().into(), json!(value));
    }
}

impl<S, N> FormatEvent<S, N> for LogFormatter
where
    S: Subscriber + for<'a> LookupSpan<'a>,
    N: for<'a> FormatFields<'a> + 'static,
{
    fn format_event(
        &self,
        ctx: &FmtContext<'_, S, N>,
        mut writer: Writer<'_>,
        event: &Event<'_>,
    ) -> fmt::Result {
        let mut fields = Fields::default();
        event.record(&mut fields);
        let message = fields
            .0
            .remove("message")
            .unwrap_or_else(|| Value::String(event.metadata().name().into()));
        if !self.json {
            return writeln!(
                writer,
                "{} {} {}",
                event.metadata().level(),
                event.metadata().target(),
                message.as_str().unwrap_or("")
            );
        }
        let mut timestamp = String::new();
        SystemTime.format_time(&mut Writer::new(&mut timestamp))?;
        let mut record = Map::new();
        record.insert("schemaVersion".into(), json!(1));
        record.insert("timestamp".into(), json!(timestamp));
        record.insert("level".into(), json!(event.metadata().level().to_string()));
        record.insert("service".into(), json!(self.service));
        record.insert("logger".into(), json!(event.metadata().target()));
        record.insert("message".into(), message);
        // Legacy runtime identity fields remain supported; an active invocation span is authoritative.
        for (source, target) in [("execution_id", "executionId"), ("trace_id", "traceId")] {
            if let Some(Value::String(value)) = fields.0.remove(source) {
                if !value.is_empty() {
                    record.insert(target.into(), json!(value));
                }
            }
        }
        if let Some(scope) = ctx.event_scope() {
            for span in scope.from_root() {
                let extensions = span.extensions();
                if let Some(encoded) = extensions.get::<FormattedFields<JsonFields>>() {
                    if let Ok(values) = serde_json::from_str::<Map<String, Value>>(encoded.as_str())
                    {
                        for (source, target) in [
                            ("execution_id", "executionId"),
                            ("trace_id", "traceId"),
                            ("function_name", "functionName"),
                        ] {
                            if let Some(Value::String(value)) = values.get(source) {
                                if !value.is_empty() {
                                    record.insert(target.into(), json!(value));
                                }
                            }
                        }
                    }
                }
            }
        }
        if !fields.0.is_empty() {
            record.insert("attributes".into(), json!(fields.0));
        }
        let encoded = serde_json::to_string(&record).map_err(|_| fmt::Error)?;
        writeln!(writer, "{encoded}")
    }
}

/// Explicit process setup. Returns an error if the host already installed a subscriber.
pub fn init_logging() -> Result<(), crate::BoxError> {
    let service =
        std::env::var("NANOFAAS_LOG_SERVICE").unwrap_or_else(|_| "function-runtime".into());
    let json = std::env::var("NANOFAAS_LOG_FORMAT").as_deref() == Ok("json");
    tracing_subscriber::fmt()
        .fmt_fields(JsonFields::default())
        .event_format(LogFormatter::new(service, json))
        .try_init()
}
