use prometheus_client::metrics::{gauge::Gauge, histogram::Histogram};
use serde::Serialize;
use std::collections::{BTreeMap, HashMap};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

#[derive(Clone, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ExecutionStatus {
    pub execution_id: String,
    pub incarnation: String,
    pub dispatch_attempt: Option<String>,
    pub state: &'static str,
    pub occupancy_seconds: Option<f64>,
    pub response_status: &'static str,
    pub handler_started: bool,
}
struct Record {
    started: Instant,
    status: ExecutionStatus,
    released_at: Option<Instant>,
}
#[derive(Default)]
struct History {
    records: HashMap<String, Record>,
    terminal: BTreeMap<(Instant, String), ()>,
}
/// Active records are bounded by handler admission. Only terminal records are evicted.
pub(crate) struct Occupancy {
    pub(crate) incarnation: String,
    history: Mutex<History>,
    maximum: usize,
    retention: Duration,
    active: Gauge,
    duration: Histogram,
}
impl Occupancy {
    pub fn new(
        maximum: usize,
        retention: Duration,
        active: Gauge,
        duration: Histogram,
    ) -> Arc<Self> {
        Arc::new(Self {
            incarnation: format!(
                "{}-{}",
                std::process::id(),
                SystemTime::now()
                    .duration_since(UNIX_EPOCH)
                    .unwrap()
                    .as_nanos()
            ),
            history: Mutex::new(History::default()),
            maximum,
            retention,
            active,
            duration,
        })
    }
    #[cfg(test)]
    pub fn start(&self, id: &str) -> Option<Instant> {
        self.start_attempt(id, None, Instant::now())
    }
    pub fn start_attempt(
        &self,
        id: &str,
        attempt: Option<String>,
        started: Instant,
    ) -> Option<Instant> {
        let mut history = self.history.lock().unwrap();
        self.prune(&mut history);
        if history
            .records
            .get(id)
            .is_some_and(|r| r.released_at.is_none())
        {
            return None;
        }
        if let Some(released) = history.records.get(id).and_then(|r| r.released_at) {
            history.terminal.remove(&(released, id.to_owned()));
        }
        history.records.insert(
            id.to_owned(),
            Record {
                started,
                status: ExecutionStatus {
                    execution_id: id.to_owned(),
                    incarnation: self.incarnation.clone(),
                    dispatch_attempt: attempt,
                    state: "ACTIVE",
                    occupancy_seconds: None,
                    response_status: "cancelled",
                    handler_started: false,
                },
                released_at: None,
            },
        );
        self.active.inc();
        Some(started)
    }
    #[cfg(test)]
    pub fn response(&self, id: &str, status: &'static str) {
        if let Some(record) = self.history.lock().unwrap().records.get_mut(id) {
            record.status.response_status = status;
        }
    }
    pub fn handler_started_at(&self, id: &str, started: Instant) {
        if let Some(record) = self.history.lock().unwrap().records.get_mut(id) {
            if record.started == started && record.released_at.is_none() {
                record.status.handler_started = true;
            }
        }
    }
    pub fn response_at(&self, id: &str, started: Instant, status: &'static str) {
        if let Some(record) = self.history.lock().unwrap().records.get_mut(id) {
            if record.started == started {
                record.status.response_status = status;
            }
        }
    }
    pub fn release(&self, id: &str, started: Instant) {
        let mut history = self.history.lock().unwrap();
        if let Some(record) = history.records.get_mut(id) {
            if record.started != started || record.released_at.is_some() {
                return;
            }
            let seconds = started.elapsed().as_secs_f64();
            record.status.state = "RELEASED";
            record.status.occupancy_seconds = Some(seconds);
            let released = Instant::now();
            record.released_at = Some(released);
            history.terminal.insert((released, id.to_owned()), ());
            self.active.dec();
            self.duration.observe(seconds);
        }
        self.prune(&mut history);
    }
    pub fn get(&self, id: &str) -> Option<ExecutionStatus> {
        let mut history = self.history.lock().unwrap();
        self.prune(&mut history);
        history.records.get(id).map(|r| r.status.clone())
    }
    fn prune(&self, history: &mut History) {
        while let Some(((released, _), _)) = history.terminal.first_key_value() {
            if history.terminal.len() <= self.maximum && released.elapsed() < self.retention {
                break;
            }
            let ((_, id), _) = history.terminal.pop_first().unwrap();
            history.records.remove(&id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
    #[test]
    fn retained_history_does_not_make_execution_status_queries_linear() {
        let tracker = Occupancy::new(
            10_000,
            Duration::from_secs(600),
            Default::default(),
            Histogram::new([1.0]),
        );
        let started = tracker.start("probe").unwrap();
        tracker.release("probe", started);
        fn median_lookup(tracker: &Occupancy) -> Duration {
            let mut samples = Vec::new();
            for _ in 0..25 {
                let start = Instant::now();
                assert!(std::hint::black_box(tracker.get("probe")).is_some());
                samples.push(start.elapsed());
            }
            samples.sort();
            samples[12]
        }
        let baseline = median_lookup(&tracker);
        {
            let mut history = tracker.history.lock().unwrap();
            let sample = history.records.get("probe").unwrap().status.clone();
            let released = Instant::now();
            for i in 0..9_999 {
                let id = format!("retained-{i}");
                let mut status = sample.clone();
                status.execution_id = id.clone();
                history.terminal.insert((released, id.clone()), ());
                history.records.insert(
                    id,
                    Record {
                        started,
                        status,
                        released_at: Some(released),
                    },
                );
            }
        }
        let retained = median_lookup(&tracker);
        assert!(
            retained <= baseline * 16 + Duration::from_micros(50),
            "status lookup grew with retained history: {baseline:?} -> {retained:?}"
        );
    }
    #[test]
    fn release_is_positive_proof_and_unknown_is_not() {
        let tracker = Occupancy::new(
            1,
            Duration::from_secs(600),
            Default::default(),
            prometheus_client::metrics::histogram::Histogram::new([1.0]),
        );
        let started = tracker.start("first").unwrap();
        assert_eq!(tracker.get("first").unwrap().state, "ACTIVE");
        tracker.response("first", "timeout");
        tracker.release("first", started);
        let proof = tracker.get("first").unwrap();
        assert_eq!(proof.state, "RELEASED");
        assert_eq!(proof.response_status, "timeout");
        assert!(proof.occupancy_seconds.unwrap() >= 0.0);
        assert!(tracker.get("missing").is_none());
    }
    #[test]
    fn terminal_retention_never_evicts_active_and_duplicate_admission_is_rejected() {
        let tracker = Occupancy::new(
            1,
            Duration::from_secs(600),
            Default::default(),
            prometheus_client::metrics::histogram::Histogram::new([1.0]),
        );
        tracker.start("active").unwrap();
        assert!(tracker.start("active").is_none());
        let first = tracker.start("first").unwrap();
        tracker.release("first", first);
        let second = tracker.start("second").unwrap();
        tracker.release("second", second);
        assert!(tracker.get("first").is_none());
        assert_eq!(tracker.get("active").unwrap().state, "ACTIVE");
    }
    #[test]
    fn expired_records_are_unknown_and_old_response_cannot_change_new_attempt() {
        let tracker = Occupancy::new(
            10,
            Duration::from_secs(600),
            Default::default(),
            prometheus_client::metrics::histogram::Histogram::new([1.0]),
        );
        let old = tracker.start("reused").unwrap();
        tracker.release("reused", old);
        let current = tracker.start("reused").unwrap();
        tracker.response_at("reused", old, "error");
        assert_eq!(tracker.get("reused").unwrap().response_status, "cancelled");
        tracker.response_at("reused", current, "success");
        tracker.release("reused", current);
        {
            let mut history = tracker.history.lock().unwrap();
            let released = history.records.get("reused").unwrap().released_at.unwrap();
            history.terminal.remove(&(released, "reused".into()));
            let expired = Instant::now() - Duration::from_secs(601);
            history.records.get_mut("reused").unwrap().released_at = Some(expired);
            history.terminal.insert((expired, "reused".into()), ());
        }
        assert!(tracker.get("reused").is_none());
    }
}
