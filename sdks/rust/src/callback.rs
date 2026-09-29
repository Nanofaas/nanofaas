use std::time::Duration;

use bytes::Bytes;
use tokio::sync::watch;

const BASE_RETRY_DELAYS_MS: [u64; 3] = [100, 500, 2000];

/// Who a callback reports on. `dispatch_attempt` is echoed unchanged on every delivery attempt:
/// only the control plane redispatches.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct Identity {
    pub execution_id: String,
    pub trace_id: Option<String>,
    pub dispatch_attempt: Option<String>,
}

pub(crate) struct Callback {
    pub identity: Identity,
    pub body: Bytes,
}

/// Delay after each failed attempt: 100, 500, 2000 ms, the last repeated up to `max_attempts`.
pub(crate) fn retry_delays(max_attempts: usize) -> Vec<Duration> {
    (0..max_attempts)
        .map(|attempt| Duration::from_millis(BASE_RETRY_DELAYS_MS[attempt.min(2)]))
        .collect()
}

/// `{base}/{executionId}:complete`; a base already ending in `/<id>:complete` is cut back to its
/// parent first.
pub(crate) fn callback_url(base: &str, execution_id: &str) -> String {
    let mut base = base.trim().trim_end_matches('/');
    if let Some(suffix) = base.rfind(":complete") {
        if let Some(slash) = base[..suffix].rfind('/') {
            base = &base[..slash];
        }
    }
    format!("{base}/{execution_id}:complete")
}

#[derive(Debug, PartialEq, Eq)]
enum AttemptOutcome {
    Delivered,
    Permanent,
    Retry,
}

/// 2xx is delivered; a 4xx other than 408 and 429 will never succeed; everything else retries.
fn classify(status: u16) -> AttemptOutcome {
    match status {
        200..=299 => AttemptOutcome::Delivered,
        408 | 429 => AttemptOutcome::Retry,
        400..=499 => AttemptOutcome::Permanent,
        _ => AttemptOutcome::Retry,
    }
}

pub(crate) struct CallbackClient {
    base_url: Option<String>,
    http: reqwest::Client,
    attempt_timeout: Duration,
    pub(crate) retry_delays: Vec<Duration>,
}

impl CallbackClient {
    pub fn new(base_url: Option<String>, attempt_timeout: Duration, max_attempts: usize) -> Self {
        Self {
            base_url,
            http: reqwest::Client::new(),
            attempt_timeout,
            retry_delays: retry_delays(max_attempts),
        }
    }

    /// Delivers one serialized callback and reports whether a 2xx arrived. Gives up early on a
    /// permanent 4xx, and as soon as `cancel` turns true.
    pub async fn deliver(&self, callback: &Callback, cancel: &mut watch::Receiver<bool>) -> bool {
        let Some(base) = self.base_url.as_deref() else {
            return false;
        };
        if callback.identity.execution_id.trim().is_empty() {
            return false;
        }
        let url = callback_url(base, &callback.identity.execution_id);
        for (attempt, delay) in self.retry_delays.iter().enumerate() {
            let outcome = tokio::select! {
                outcome = self.send_once(&url, callback) => outcome,
                () = cancelled(cancel) => return false,
            };
            match outcome {
                AttemptOutcome::Delivered => return true,
                AttemptOutcome::Permanent => return false,
                AttemptOutcome::Retry => {}
            }
            if attempt + 1 == self.retry_delays.len() {
                break;
            }
            tokio::select! {
                () = tokio::time::sleep(*delay) => {}
                () = cancelled(cancel) => return false,
            }
        }
        false
    }

    async fn send_once(&self, url: &str, callback: &Callback) -> AttemptOutcome {
        let mut request = self
            .http
            .post(url)
            .timeout(self.attempt_timeout)
            .header("content-type", "application/json")
            .body(callback.body.clone());
        let identity = &callback.identity;
        if let Some(trace_id) = identity
            .trace_id
            .as_deref()
            .filter(|id| !id.trim().is_empty())
        {
            request = request.header("x-trace-id", trace_id);
        }
        if let Some(attempt) = identity
            .dispatch_attempt
            .as_deref()
            .filter(|a| !a.trim().is_empty())
        {
            request = request.header("x-dispatch-attempt", attempt);
        }
        match request.send().await {
            Ok(response) => classify(response.status().as_u16()),
            Err(_) => AttemptOutcome::Retry,
        }
    }
}

/// Resolves once `cancel` turns true; never, if its sender is gone.
async fn cancelled(cancel: &mut watch::Receiver<bool>) {
    if cancel.wait_for(|cancelled| *cancelled).await.is_err() {
        std::future::pending::<()>().await;
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use crate::test_support::{FakeCallbackServer, Reply};
    use serde_json::json;
    use std::sync::atomic::{AtomicUsize, Ordering};

    fn callback(execution_id: &str) -> Callback {
        Callback {
            identity: Identity {
                execution_id: execution_id.into(),
                trace_id: Some("trace-1".into()),
                dispatch_attempt: Some("2".into()),
            },
            body: Bytes::from_static(br#"{"success":true}"#),
        }
    }

    fn client(url: &str, attempts: usize) -> CallbackClient {
        let mut client = CallbackClient::new(Some(url.into()), Duration::from_secs(1), attempts);
        client.retry_delays = vec![Duration::ZERO; attempts];
        client
    }

    fn never_cancelled() -> watch::Receiver<bool> {
        watch::channel(false).1
    }

    #[test]
    fn builds_the_execution_complete_url() {
        assert_eq!(
            callback_url("http://cp:8080/v1/internal/executions/", "exec-1"),
            "http://cp:8080/v1/internal/executions/exec-1:complete"
        );
        assert_eq!(
            callback_url("http://cp/v1/internal/executions/old:complete", "exec-2"),
            "http://cp/v1/internal/executions/exec-2:complete"
        );
    }

    #[test]
    fn retry_delays_repeat_the_last_base_delay() {
        assert_eq!(retry_delays(1), vec![Duration::from_millis(100)]);
        let millis: Vec<u128> = retry_delays(5).iter().map(Duration::as_millis).collect();
        assert_eq!(millis, vec![100, 500, 2000, 2000, 2000]);
    }

    #[test]
    fn classifies_statuses() {
        assert_eq!(classify(204), AttemptOutcome::Delivered);
        assert_eq!(classify(400), AttemptOutcome::Permanent);
        assert_eq!(classify(408), AttemptOutcome::Retry);
        assert_eq!(classify(429), AttemptOutcome::Retry);
        assert_eq!(classify(503), AttemptOutcome::Retry);
    }

    #[tokio::test]
    async fn delivers_with_identity_headers() {
        let server = FakeCallbackServer::accepting().await;
        assert!(
            client(&server.url, 3)
                .deliver(&callback("exec-1"), &mut never_cancelled())
                .await
        );
        let received = server.received();
        assert_eq!(received.len(), 1);
        assert_eq!(received[0].method, "POST");
        assert_eq!(received[0].path, "/exec-1:complete");
        assert_eq!(received[0].headers["content-type"], "application/json");
        assert_eq!(received[0].headers["x-trace-id"], "trace-1");
        assert_eq!(received[0].headers["x-dispatch-attempt"], "2");
        assert_eq!(received[0].body, json!({"success": true}));
    }

    #[tokio::test]
    async fn does_not_retry_a_permanent_4xx() {
        let server = FakeCallbackServer::start(|_| Reply::Status(400)).await;
        assert!(
            !client(&server.url, 3)
                .deliver(&callback("e"), &mut never_cancelled())
                .await
        );
        assert_eq!(server.received().len(), 1);
    }

    #[tokio::test]
    async fn retries_429_with_the_same_dispatch_attempt() {
        let calls = AtomicUsize::new(0);
        let server = FakeCallbackServer::start(move |_| {
            Reply::Status(if calls.fetch_add(1, Ordering::SeqCst) < 2 {
                429
            } else {
                204
            })
        })
        .await;
        assert!(
            client(&server.url, 3)
                .deliver(&callback("e"), &mut never_cancelled())
                .await
        );
        let attempts: Vec<_> = server
            .received()
            .iter()
            .map(|r| r.headers["x-dispatch-attempt"].to_str().unwrap().to_owned())
            .collect();
        assert_eq!(attempts, vec!["2", "2", "2"]);
    }

    #[tokio::test]
    async fn stops_after_max_attempts() {
        let server = FakeCallbackServer::start(|_| Reply::Status(503)).await;
        assert!(
            !client(&server.url, 2)
                .deliver(&callback("e"), &mut never_cancelled())
                .await
        );
        assert_eq!(server.received().len(), 2);
    }

    #[tokio::test]
    async fn gives_up_without_a_callback_url() {
        let client = CallbackClient::new(None, Duration::from_secs(1), 3);
        assert!(!client.deliver(&callback("e"), &mut never_cancelled()).await);
    }

    #[tokio::test]
    async fn cancellation_interrupts_an_in_flight_attempt() {
        let server = FakeCallbackServer::start(|_| Reply::HoldThen(204)).await;
        let (cancel, mut cancelled) = watch::channel(false);
        let client = client(&server.url, 3);
        let payload = callback("e");
        let delivery = client.deliver(&payload, &mut cancelled);
        let trigger = async {
            server.wait_for(1, |_| true).await;
            cancel.send_replace(true);
        };
        let (delivered, ()) = tokio::join!(delivery, trigger);
        assert!(!delivered);
    }
}
