use nanofaas::logging::LogFormatter;
use serde_json::Value;
use std::io::Write;
use std::sync::{Arc, Mutex};
use tracing_subscriber::fmt::format::JsonFields;

#[derive(Clone)]
struct Sink(Arc<Mutex<Vec<u8>>>);
impl Write for Sink {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[test]
fn shared_corpus_and_reserved_fields_use_canonical_json() {
    let corpus: Value =
        serde_json::from_str(include_str!("../../runtime-contract/logging-corpus.json")).unwrap();
    for case in corpus["cases"].as_array().unwrap() {
        let buffer = Arc::new(Mutex::new(Vec::new()));
        let sink = Sink(buffer.clone());
        let subscriber = tracing_subscriber::fmt()
            .fmt_fields(JsonFields::default())
            .event_format(LogFormatter::new("function-runtime", true))
            .with_writer(move || sink.clone())
            .finish();
        let input = &case["input"];
        tracing::subscriber::with_default(subscriber, || {
            let span = tracing::info_span!(
                "invocation",
                execution_id = input["executionId"].as_str().unwrap_or(""),
                trace_id = input["traceId"].as_str().unwrap_or("")
            );
            span.in_scope(|| {
                let message=input["message"].as_str().unwrap();
                let execution_attr=input["attributes"]["executionId"].as_str();
                let level_attr=input["attributes"]["level"].as_str();
                match input["level"].as_str().unwrap() {
                    "WARN" => tracing::warn!(message=%message,executionId=execution_attr,level=level_attr),
                    "ERROR" => tracing::error!(message=%message,executionId=execution_attr,level=level_attr),
                    _ => tracing::info!(message=%message,executionId=execution_attr,level=level_attr),
                }
            });
        });
        let line = String::from_utf8(buffer.lock().unwrap().clone()).unwrap();
        assert_eq!(line.lines().count(), 1);
        let event: Value = serde_json::from_str(&line).unwrap();
        for (key, want) in case["expected"].as_object().unwrap() {
            if ["timestamp", "exception", "logger"].contains(&key.as_str()) {
                continue;
            }
            assert_eq!(&event[key], want, "{}: {key}", case["name"]);
        }
        assert!(event["timestamp"].as_str().unwrap().ends_with('Z'));
        if input.get("executionId").is_none() {
            assert!(event.get("executionId").is_none());
        }
    }
}
