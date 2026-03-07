mod admin_runtime_config;
pub mod app;
pub mod core_defaults;
pub mod dispatch;
pub mod e2e_support;
pub mod errors;
pub mod execution;
pub mod idempotency;
mod invocation;
pub mod kubernetes;
pub mod kubernetes_live;
pub mod metrics;
pub mod model;
pub mod queue;
pub mod rate_limiter;
pub mod registry;
pub mod runtime_config;
pub mod scheduler;
pub mod service;
pub mod sync;

pub(crate) fn now_millis() -> u64 {
    use std::time::{SystemTime, UNIX_EPOCH};
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
