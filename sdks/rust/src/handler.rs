use std::any::Any;
use std::future::Future;
use std::pin::Pin;
use std::sync::Arc;

use serde::Serialize;
use serde::de::DeserializeOwned;

use crate::bounded::{EncodeError, to_vec_bounded};
use crate::context::Context;
use crate::types::HandlerResponse;

pub type BoxError = Box<dyn std::error::Error + Send + Sync>;

/// A finished handler's output, reduced to what the runtime needs.
#[derive(Debug)]
pub(crate) enum Produced {
    Json(Vec<u8>),
    Envelope(HandlerResponse),
    Unencodable(EncodeError),
}

pub(crate) type HandlerFuture = Pin<Box<dyn Future<Output = Result<Produced, BoxError>> + Send>>;

/// A registered handler with its input and output types erased. Deserializing the input happens
/// in the call, before any future exists, so malformed input never starts the handler.
pub(crate) type ErasedHandler =
    Arc<dyn Fn(Context, &str, usize) -> Result<HandlerFuture, serde_json::Error> + Send + Sync>;

pub(crate) fn erase<I, O, F, Fut>(handler: F) -> ErasedHandler
where
    I: DeserializeOwned + Send + 'static,
    O: Serialize + Send + 'static,
    F: Fn(Context, I) -> Fut + Send + Sync + 'static,
    Fut: Future<Output = Result<O, BoxError>> + Send + 'static,
{
    Arc::new(move |ctx, input_json, max_output_bytes| {
        let input: I = serde_json::from_str(input_json)?;
        let output = handler(ctx, input);
        Ok(Box::pin(async move {
            Ok(produce(output.await?, max_output_bytes))
        }))
    })
}

/// Detects the envelope by type, like the Go SDK's type switch; encodes anything else within
/// the output limit.
fn produce<O: Serialize + 'static>(output: O, max_output_bytes: usize) -> Produced {
    let mut slot = Some(output);
    if let Some(envelope) = (&mut slot as &mut dyn Any).downcast_mut::<Option<HandlerResponse>>() {
        return Produced::Envelope(envelope.take().expect("slot was just filled"));
    }
    let output = slot.expect("only the envelope branch empties the slot");
    match to_vec_bounded(&output, max_output_bytes) {
        Ok(json) => Produced::Json(json),
        Err(error) => Produced::Unencodable(error),
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn plain_values_are_encoded_within_the_limit() {
        match produce(json!({"a": 1}), 64) {
            Produced::Json(json) => assert_eq!(json, br#"{"a":1}"#),
            other => panic!("unexpected {other:?}"),
        }
        assert!(matches!(
            produce("x".repeat(100), 10),
            Produced::Unencodable(EncodeError::TooLarge)
        ));
    }

    #[test]
    fn the_envelope_is_detected_by_type() {
        let envelope = HandlerResponse::new(json!("aGk="), 201).encoding("base64");
        match produce(envelope.clone(), 1) {
            Produced::Envelope(found) => assert_eq!(found, envelope),
            other => panic!("unexpected {other:?}"),
        }
    }
}
