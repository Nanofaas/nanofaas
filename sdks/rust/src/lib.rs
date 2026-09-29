//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod settings;
mod types;

pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
