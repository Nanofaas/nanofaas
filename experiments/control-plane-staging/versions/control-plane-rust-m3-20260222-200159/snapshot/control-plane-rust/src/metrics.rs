use std::collections::HashMap;
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct MetricKey {
    name: String,
    function: String,
}

#[derive(Debug, Default)]
struct TimerData {
    count: u64,
    sum_ms: u64,
}

#[derive(Debug, Default)]
struct MetricsInner {
    counters: HashMap<MetricKey, f64>,
    timers: HashMap<MetricKey, TimerData>,
}

#[derive(Debug, Clone)]
pub struct TimerHandle {
    inner: Arc<Mutex<MetricsInner>>,
    key: MetricKey,
}

impl PartialEq for TimerHandle {
    fn eq(&self, other: &Self) -> bool {
        self.key == other.key
    }
}

impl Eq for TimerHandle {}

impl TimerHandle {
    pub fn record_ms(&self, duration_ms: u64) {
        let mut inner = self.inner.lock().expect("metrics lock");
        let timer = inner.timers.entry(self.key.clone()).or_default();
        timer.count += 1;
        timer.sum_ms += duration_ms;
    }

    pub fn count(&self) -> u64 {
        self.inner
            .lock()
            .expect("metrics lock")
            .timers
            .get(&self.key)
            .map(|t| t.count)
            .unwrap_or(0)
    }
}

#[derive(Debug, Clone)]
pub struct Metrics {
    inner: Arc<Mutex<MetricsInner>>,
}

impl Default for Metrics {
    fn default() -> Self {
        Self::new()
    }
}

impl Metrics {
    pub fn new() -> Self {
        Self {
            inner: Arc::new(Mutex::new(MetricsInner::default())),
        }
    }

    pub fn cold_start(&self, function: &str) {
        self.inc_counter("function_cold_start_total", function);
    }

    pub fn warm_start(&self, function: &str) {
        self.inc_counter("function_warm_start_total", function);
    }

    pub fn dispatch(&self, function: &str) {
        self.inc_counter("function_dispatch_total", function);
    }

    pub fn success(&self, function: &str) {
        self.inc_counter("function_success_total", function);
    }

    pub fn enqueue(&self, function: &str) {
        self.inc_counter("function_enqueue_total", function);
    }

    pub fn sync_queue_admitted(&self, function: &str) {
        self.inc_counter("sync_queue_admitted_total", function);
    }

    pub fn sync_queue_rejected(&self, function: &str) {
        self.inc_counter("sync_queue_rejected_total", function);
    }

    pub fn sync_queue_depth(&self, function: &str) {
        self.inc_counter("sync_queue_depth", function);
    }

    pub fn sync_queue_wait_seconds(&self, function: &str) -> TimerHandle {
        self.timer("sync_queue_wait_seconds", function)
    }

    pub fn latency(&self, function: &str) -> TimerHandle {
        self.timer("function_latency_ms", function)
    }

    pub fn init_duration(&self, function: &str) -> TimerHandle {
        self.timer("function_init_duration_ms", function)
    }

    pub fn queue_wait(&self, function: &str) -> TimerHandle {
        self.timer("function_queue_wait_ms", function)
    }

    pub fn e2e_latency(&self, function: &str) -> TimerHandle {
        self.timer("function_e2e_latency_ms", function)
    }

    pub fn counter_value(&self, metric_name: &str, function: &str) -> f64 {
        self.inner
            .lock()
            .expect("metrics lock")
            .counters
            .get(&MetricKey {
                name: metric_name.to_string(),
                function: function.to_string(),
            })
            .copied()
            .unwrap_or(0.0)
    }

    pub fn to_prometheus_text(&self) -> String {
        let inner = self.inner.lock().expect("metrics lock");
        let mut lines = Vec::new();

        for (key, value) in &inner.counters {
            lines.push(format!(
                "{}{{function=\"{}\"}} {}",
                key.name, key.function, *value as u64
            ));
        }

        for (key, timer) in &inner.timers {
            lines.push(format!(
                "{}_count{{function=\"{}\"}} {}",
                key.name, key.function, timer.count
            ));
            lines.push(format!(
                "{}_sum{{function=\"{}\"}} {}",
                key.name, key.function, timer.sum_ms
            ));
        }

        lines.sort();
        lines.join("\n")
    }

    fn inc_counter(&self, metric_name: &str, function: &str) {
        let mut inner = self.inner.lock().expect("metrics lock");
        let key = MetricKey {
            name: metric_name.to_string(),
            function: function.to_string(),
        };
        *inner.counters.entry(key).or_insert(0.0) += 1.0;
    }

    fn timer(&self, metric_name: &str, function: &str) -> TimerHandle {
        let key = MetricKey {
            name: metric_name.to_string(),
            function: function.to_string(),
        };
        self.inner
            .lock()
            .expect("metrics lock")
            .timers
            .entry(key.clone())
            .or_default();
        TimerHandle {
            inner: Arc::clone(&self.inner),
            key,
        }
    }
}
