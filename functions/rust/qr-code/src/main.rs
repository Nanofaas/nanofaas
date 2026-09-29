use base64::Engine;
use nanofaas::{BoxError, Context, HandlerResponse, Runtime};
use qrcode::{Color, EcLevel, QrCode};
use serde_json::{Value, json};

const DEFAULT_SIZE: usize = 256;
const MAX_TEXT_BYTES: usize = 1024;
/// White modules around the code, as the QR specification and Go's go-qrcode draw them.
const QUIET_ZONE: usize = 4;

#[tokio::main]
async fn main() {
    let stopped = Runtime::from_env()
        .register("qr-code", handle)
        .start()
        .await;
    if let Err(error) = stopped {
        eprintln!("{error}");
        // Exit now: returning from main would wait for stuck spawn_blocking work.
        std::process::exit(1);
    }
}

async fn handle(ctx: Context, input: Value) -> Result<HandlerResponse, BoxError> {
    // Encoding a large code is CPU work: keep it off the async workers.
    Ok(ctx.spawn_blocking(move || respond(&input)).await?)
}

/// `{"text": ..., "size": 128..=1024}` to a base64 PNG of `size` x `size` pixels (larger when
/// the code needs more modules than that), at error-correction level M like Go's go-qrcode.
fn respond(input: &Value) -> HandlerResponse {
    let Some(fields) = input.as_object() else {
        return error("Input must be a JSON object");
    };
    let Some(text) = fields.get("text") else {
        return error("missing required field: text");
    };
    let Some(text) = text.as_str().filter(|text| !text.is_empty()) else {
        return error("field 'text' must be a non-empty string");
    };
    if text.len() > MAX_TEXT_BYTES {
        return error("field 'text' must be at most 1024 UTF-8 bytes");
    }
    let size = match fields.get("size") {
        None => DEFAULT_SIZE,
        Some(size) => match size.as_f64() {
            Some(size) if size.fract() == 0.0 && (128.0..=1024.0).contains(&size) => size as usize,
            _ => return error("field 'size' must be an integer between 128 and 1024"),
        },
    };
    let png = render(text, size);
    HandlerResponse::new(
        Value::String(base64::engine::general_purpose::STANDARD.encode(png)),
        200,
    )
    .header("Content-Type", "image/png")
    .encoding("base64")
}

fn render(text: &str, size: usize) -> Vec<u8> {
    let code = QrCode::with_error_correction_level(text, EcLevel::M)
        .expect("1024 bytes always fit a version-40 code at level M");
    let width = code.width();
    let modules = width + 2 * QUIET_ZONE;
    let size = size.max(modules);
    let scale = size / modules;
    let offset = (size - modules * scale) / 2;

    let mut pixels = vec![u8::MAX; size * size];
    for (index, color) in code.to_colors().into_iter().enumerate() {
        if color != Color::Dark {
            continue;
        }
        let left = offset + (index % width + QUIET_ZONE) * scale;
        let top = offset + (index / width + QUIET_ZONE) * scale;
        for row in top..top + scale {
            pixels[row * size + left..row * size + left + scale].fill(0);
        }
    }

    let mut png = Vec::new();
    let mut encoder = png::Encoder::new(&mut png, size as u32, size as u32);
    encoder.set_color(png::ColorType::Grayscale);
    encoder.set_depth(png::BitDepth::Eight);
    let mut writer = encoder
        .write_header()
        .expect("writing to a Vec cannot fail");
    writer
        .write_image_data(&pixels)
        .expect("the buffer holds exactly size x size pixels");
    drop(writer);
    png
}

fn error(message: &str) -> HandlerResponse {
    HandlerResponse::new(json!({"error": message}), 422)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde::Deserialize;

    #[derive(Deserialize)]
    struct Fixture {
        cases: Vec<Case>,
    }

    #[derive(Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct Case {
        name: String,
        input: Value,
        expected: Option<Value>,
        expected_status_code: Option<u16>,
    }

    /// The correctness corpus shared with every other language's qr-code.
    fn shared_cases() -> Vec<Case> {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../test-data/qr-code/correctness.json"
        );
        let fixture: Fixture =
            serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        fixture.cases
    }

    #[test]
    fn satisfies_the_shared_contract() {
        for case in shared_cases() {
            let response = respond(&case.input);
            let status = case.expected_status_code.unwrap_or(200);
            assert_eq!(response.status_code(), status, "{}", case.name);
            if let Some(expected) = &case.expected {
                assert_eq!(response.output(), expected, "{}", case.name);
            }
        }
    }

    /// Decodes a successful response and returns the PNG's width and height.
    fn png_dimensions(response: &HandlerResponse) -> (u32, u32) {
        use base64::Engine;
        assert_eq!(response.status_code(), 200);
        assert_eq!(
            response.headers(),
            [("Content-Type".to_string(), "image/png".to_string())]
        );
        assert_eq!(response.encoding_marker(), Some("base64"));
        let encoded = response.output().as_str().expect("a base64 string");
        let png = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .unwrap();
        assert_eq!(&png[..8], b"\x89PNG\r\n\x1a\n");
        assert_eq!(&png[12..16], b"IHDR");
        let dimension = |at: usize| u32::from_be_bytes(png[at..at + 4].try_into().unwrap());
        (dimension(16), dimension(20))
    }

    #[test]
    fn successful_shared_cases_are_png_images_of_the_requested_size() {
        for case in shared_cases()
            .iter()
            .filter(|case| case.expected_status_code == Some(200))
        {
            let size = case
                .input
                .get("size")
                .and_then(Value::as_u64)
                .unwrap_or(256) as u32;
            assert_eq!(
                png_dimensions(&respond(&case.input)),
                (size, size),
                "{}",
                case.name
            );
        }
    }

    #[test]
    fn a_code_larger_than_the_requested_size_keeps_every_module() {
        let text = "x".repeat(1024);
        let (width, height) = png_dimensions(&respond(&json!({"text": text, "size": 128})));
        assert!(width > 128 && width == height);
    }

    #[test]
    fn rejects_fractional_sizes_and_non_object_input() {
        let message = json!({"error": "field 'size' must be an integer between 128 and 1024"});
        assert_eq!(
            respond(&json!({"text": "a", "size": 200.5})).output(),
            &message
        );
        assert_eq!(
            respond(&json!(["a"])).output(),
            &json!({"error": "Input must be a JSON object"})
        );
    }
}
