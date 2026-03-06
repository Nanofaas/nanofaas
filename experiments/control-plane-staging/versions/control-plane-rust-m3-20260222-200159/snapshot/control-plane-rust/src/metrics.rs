use std::collections::{HashMap, VecDeque};
use std::sync::{Arc, Mutex};
use std::time::Duration;

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
    dispatch_events: HashMap<String, VecDeque<u64>>,
}

/// Bundle of all per-function timers, fetched once per invocation to avoid repeated map lookups.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FunctionTimers {
    pub latency: TimerHandle,
    pub init_duration: TimerHandle,
    pub queue_wait: TimerHandle,
    pub e2e_latency: TimerHandle,
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
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let timer = inner.timers.entry(self.key.clone()).or_default();
        timer.count += 1;
        timer.sum_ms += duration_ms;
    }

    pub fn count(&self) -> u64 {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
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
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let key = MetricKey {
            name: "function_dispatch_total".to_string(),
            function: function.to_string(),
        };
        *inner.counters.entry(key).or_insert(0.0) += 1.0;
        let now = crate::now_millis();
        let events = inner.dispatch_events.entry(function.to_string()).or_default();
        events.push_back(now);
        prune_dispatch_events(events, now, Duration::from_secs(1));
    }

    pub fn success(&self, function: &str) {
        self.inc_counter("function_success_total", function);
    }

    pub fn enqueue(&self, function: &str) {
        self.inc_counter("function_enqueue_total", function);
    }

    pub fn error(&self, function: &str) {
        self.inc_counter("function_error_total", function);
    }

    pub fn retry(&self, function: &str) {
        self.inc_counter("function_retry_total", function);
    }

    pub fn timeout(&self, function: &str) {
        self.inc_counter("function_timeout_total", function);
    }

    pub fn queue_depth(&self, function: &str) {
        self.inc_counter("function_queue_depth", function);
    }

    pub fn in_flight(&self, function: &str) {
        self.inc_counter("function_inFlight", function);
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

    /// Returns a bundle of all four per-function timers, initialised in a single lock
    /// acquisition to minimise contention on the hot completion path.
    pub fn timers(&self, function: &str) -> FunctionTimers {
        let keys = [
            "function_latency_ms",
            "function_init_duration_ms",
            "function_queue_wait_ms",
            "function_e2e_latency_ms",
        ]
        .map(|name| MetricKey {
            name: name.to_string(),
            function: function.to_string(),
        });
        {
            let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
            for key in &keys {
                inner.timers.entry(key.clone()).or_default();
            }
        }
        let [latency_key, init_key, queue_key, e2e_key] = keys;
        FunctionTimers {
            latency: TimerHandle {
                inner: Arc::clone(&self.inner),
                key: latency_key,
            },
            init_duration: TimerHandle {
                inner: Arc::clone(&self.inner),
                key: init_key,
            },
            queue_wait: TimerHandle {
                inner: Arc::clone(&self.inner),
                key: queue_key,
            },
            e2e_latency: TimerHandle {
                inner: Arc::clone(&self.inner),
                key: e2e_key,
            },
        }
    }

    pub fn counter_value(&self, metric_name: &str, function: &str) -> f64 {
        self.inner
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .counters
            .get(&MetricKey {
                name: metric_name.to_string(),
                function: function.to_string(),
            })
            .copied()
            .unwrap_or(0.0)
    }

    pub fn dispatch_rate_per_second(&self, function: &str, window: Duration) -> f64 {
        let now = crate::now_millis();
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let events = inner.dispatch_events.entry(function.to_string()).or_default();
        prune_dispatch_events(events, now, window);
        if events.is_empty() {
            return 0.0;
        }
        events.len() as f64 / window.as_secs_f64().max(1.0)
    }

    pub fn to_prometheus_text(&self) -> String {
        let inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
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
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
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
            .unwrap_or_else(|e| e.into_inner())
            .timers
            .entry(key.clone())
            .or_default();
        TimerHandle {
            inner: Arc::clone(&self.inner),
            key,
        }
    }
}

fn prune_dispatch_events(events: &mut VecDeque<u64>, now: u64, window: Duration) {
    let cutoff = now.saturating_sub(window.as_millis() as u64);
    while let Some(first) = events.front().copied() {
        if first >= cutoff {
            break;
        }
        let _ = events.pop_front();
    }
}
