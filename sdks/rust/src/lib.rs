//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod context;
mod dispatcher;
mod handler;
mod limits;
mod metrics;
mod settings;
mod types;

#[cfg(test)]
mod test_support;

pub use context::Context;
pub use handler::BoxError;
pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
