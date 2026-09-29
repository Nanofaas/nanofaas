use std::io;

use serde::Serialize;

/// Why a value could not be encoded within its byte limit.
#[derive(Debug, PartialEq, Eq)]
pub(crate) enum EncodeError {
    TooLarge,
    Serialization,
}

/// Serializes `value` to JSON, failing as soon as the encoding would exceed `limit` bytes: the
/// buffer never holds more than `limit` bytes, whatever the value's size.
pub(crate) fn to_vec_bounded<T: Serialize + ?Sized>(
    value: &T,
    limit: usize,
) -> Result<Vec<u8>, EncodeError> {
    let mut writer = LimitedWriter {
        buffer: Vec::new(),
        limit,
    };
    match serde_json::to_writer(&mut writer, value) {
        Ok(()) => Ok(writer.buffer),
        Err(error) if error.is_io() => Err(EncodeError::TooLarge),
        Err(_) => Err(EncodeError::Serialization),
    }
}

struct LimitedWriter {
    buffer: Vec<u8>,
    limit: usize,
}

impl io::Write for LimitedWriter {
    fn write(&mut self, data: &[u8]) -> io::Result<usize> {
        if data.len() > self.limit - self.buffer.len() {
            return Err(io::Error::other("encoded value exceeds its byte limit"));
        }
        self.buffer.extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn encodes_a_value_that_fits_exactly() {
        let encoded = to_vec_bounded(&"abc", 5).unwrap();
        assert_eq!(encoded, br#""abc""#);
    }

    #[test]
    fn rejects_a_value_one_byte_over_the_limit() {
        assert_eq!(to_vec_bounded(&"abc", 4), Err(EncodeError::TooLarge));
    }

    #[test]
    fn rejects_a_large_value_without_buffering_it() {
        let huge = "x".repeat(10 * 1024 * 1024);
        assert_eq!(to_vec_bounded(&huge, 1024), Err(EncodeError::TooLarge));
    }

    #[test]
    fn reports_values_json_cannot_represent_as_serialization_errors() {
        let mut map = HashMap::new();
        map.insert((1, 2), 3);
        assert_eq!(to_vec_bounded(&map, 1024), Err(EncodeError::Serialization));
    }
}
