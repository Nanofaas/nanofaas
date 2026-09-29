//! nanofaas function SDK for Rust: a warm HTTP runtime serving `/invoke`, `/health` and
//! `/metrics` under the admission, byte-limit and callback rules of
//! `sdks/runtime-contract/README.md`.

mod bounded;
mod callback;
mod limits;
mod settings;
mod types;

#[cfg(test)]
mod test_support;

pub use settings::RuntimeSettings;
pub use types::HandlerResponse;
