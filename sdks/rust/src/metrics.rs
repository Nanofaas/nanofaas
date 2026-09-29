use prometheus_client::encoding::text::encode;
use prometheus_client::metrics::counter::Counter;
use prometheus_client::metrics::family::Family;
use prometheus_client::metrics::histogram::Histogram;
use prometheus_client::registry::Registry;

/// Prometheus' default buckets, which the Go SDK's handler histogram uses.
const DEFAULT_BUCKETS: [f64; 11] = [
    0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0, 10.0,
];

pub(crate) const CONTENT_TYPE: &str = "application/openmetrics-text; version=1.0.0; charset=utf-8";

/// The Go SDK's runtime metrics, under the same names.
pub(crate) struct Metrics {
    registry: Registry,
    invocations: Family<Vec<(String, String)>, Counter>,
    handler_duration: Histogram,
    pub(crate) callback_drops: Counter,
    cold_starts: Counter,
}

impl Metrics {
    pub fn new() -> Self {
        let mut registry = Registry::default();
        let invocations = Family::<Vec<(String, String)>, Counter>::default();
        registry.register(
            "nanofaas_runtime_invocations",
            "Total runtime invocations by status.",
            invocations.clone(),
        );
        let handler_duration = Histogram::new(DEFAULT_BUCKETS);
        registry.register(
            "nanofaas_runtime_handler_duration_seconds",
            "Handler execution duration.",
            handler_duration.clone(),
        );
        let callback_drops = Counter::default();
        registry.register(
            "nanofaas_runtime_callback_drops",
            "Callbacks rejected or exhausted before successful delivery.",
            callback_drops.clone(),
        );
        let cold_starts = Counter::default();
        registry.register(
            "nanofaas_runtime_cold_starts",
            "Total cold starts observed by the runtime.",
            cold_starts.clone(),
        );
        Self {
            registry,
            invocations,
            handler_duration,
            callback_drops,
            cold_starts,
        }
    }

    /// `status` is `success`, `error` or `timeout`.
    pub fn invocation(&self, status: &str) {
        self.invocations
            .get_or_create(&vec![("status".to_owned(), status.to_owned())])
            .inc();
    }

    pub fn handler_duration(&self, seconds: f64) {
        self.handler_duration.observe(seconds);
    }

    pub fn callback_drop(&self) {
        self.callback_drops.inc();
    }

    pub fn cold_start(&self) {
        self.cold_starts.inc();
    }

    pub fn render(&self) -> String {
        let mut text = String::new();
        encode(&mut text, &self.registry).expect("writing to a String cannot fail");
        text
    }
}
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn renders_the_go_metric_names() {
        let metrics = Metrics::new();
        metrics.invocation("success");
        metrics.invocation("timeout");
        metrics.handler_duration(0.02);
        metrics.callback_drop();
        metrics.cold_start();
        let text = metrics.render();
        assert!(
            text.contains(r#"nanofaas_runtime_invocations_total{status="success"} 1"#),
            "{text}"
        );
        assert!(
            text.contains(r#"nanofaas_runtime_invocations_total{status="timeout"} 1"#),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_handler_duration_seconds_count 1"),
            "{text}"
        );
        assert!(
            text.contains(r#"nanofaas_runtime_handler_duration_seconds_bucket{le="0.025"} 1"#),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_callback_drops_total 1"),
            "{text}"
        );
        assert!(
            text.contains("nanofaas_runtime_cold_starts_total 1"),
            "{text}"
        );
    }
}
