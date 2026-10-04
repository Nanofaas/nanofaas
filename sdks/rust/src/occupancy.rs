use prometheus_client::metrics::{gauge::Gauge, histogram::Histogram};
use serde::Serialize;
use std::collections::HashMap;
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
}
struct Record {
    started: Instant,
    status: ExecutionStatus,
    released_at: Option<Instant>,
}
/// Active records are bounded by handler admission. Only terminal records are evicted.
pub(crate) struct Occupancy {
    pub(crate) incarnation: String,
    records: Mutex<HashMap<String, Record>>,
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
            records: Mutex::new(HashMap::new()),
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
        let mut records = self.records.lock().unwrap();
        self.prune(&mut records);
        if records.get(id).is_some_and(|r| r.released_at.is_none()) {
            return None;
        }
        records.insert(
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
                },
                released_at: None,
            },
        );
        self.active.inc();
        Some(started)
    }
    #[cfg(test)]
    pub fn response(&self, id: &str, status: &'static str) {
        if let Some(record) = self.records.lock().unwrap().get_mut(id) {
            record.status.response_status = status;
        }
    }
    pub fn response_at(&self, id: &str, started: Instant, status: &'static str) {
        if let Some(record) = self.records.lock().unwrap().get_mut(id) {
            if record.started == started {
                record.status.response_status = status;
            }
        }
    }
    pub fn release(&self, id: &str, started: Instant) {
        let mut records = self.records.lock().unwrap();
        if let Some(record) = records.get_mut(id) {
            if record.started != started || record.released_at.is_some() {
                return;
            }
            let seconds = started.elapsed().as_secs_f64();
            record.status.state = "RELEASED";
            record.status.occupancy_seconds = Some(seconds);
            record.released_at = Some(Instant::now());
            self.active.dec();
            self.duration.observe(seconds);
        }
        self.prune(&mut records);
    }
    pub fn get(&self, id: &str) -> Option<ExecutionStatus> {
        let mut records = self.records.lock().unwrap();
        self.prune(&mut records);
        records.get(id).map(|r| r.status.clone())
    }
    fn prune(&self, records: &mut HashMap<String, Record>) {
        records.retain(|_, r| {
            !r.released_at
                .is_some_and(|at| at.elapsed() >= self.retention)
        });
        let mut terminal: Vec<_> = records
            .iter()
            .filter_map(|(id, r)| r.released_at.map(|at| (at, id.clone())))
            .collect();
        terminal.sort();
        let excess = terminal.len().saturating_sub(self.maximum);
        for (_, id) in terminal.into_iter().take(excess) {
            records.remove(&id);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;
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
        tracker
            .records
            .lock()
            .unwrap()
            .get_mut("reused")
            .unwrap()
            .released_at = Some(Instant::now() - Duration::from_secs(601));
        assert!(tracker.get("reused").is_none());
    }
}
