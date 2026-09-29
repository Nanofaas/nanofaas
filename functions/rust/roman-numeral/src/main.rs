use nanofaas::{BoxError, Context, HandlerResponse, Runtime};
use serde_json::{Value, json};

const ROMAN_TABLE: [(i64, &str); 13] = [
    (1000, "M"),
    (900, "CM"),
    (500, "D"),
    (400, "CD"),
    (100, "C"),
    (90, "XC"),
    (50, "L"),
    (40, "XL"),
    (10, "X"),
    (9, "IX"),
    (5, "V"),
    (4, "IV"),
    (1, "I"),
];

#[tokio::main]
async fn main() {
    let stopped = Runtime::from_env()
        .register("roman-numeral", handle)
        .start()
        .await;
    if let Err(error) = stopped {
        eprintln!("{error}");
        // Exit now: returning from main would wait for stuck spawn_blocking work.
        std::process::exit(1);
    }
}

async fn handle(_ctx: Context, input: Value) -> Result<HandlerResponse, BoxError> {
    Ok(respond(&input))
}

fn respond(input: &Value) -> HandlerResponse {
    let Some(input) = input.as_object() else {
        return error("Input must be a JSON object");
    };
    let Some(raw) = input.get("number") else {
        return error("missing required field: number");
    };
    // Like the Go handler, a fractional number is truncated rather than rejected.
    let Some(number) = raw.as_f64().map(|number| number as i64) else {
        return error("field 'number' must be an integer");
    };
    if !(1..=3999).contains(&number) {
        return error(&format!("number must be between 1 and 3999, got: {number}"));
    }
    HandlerResponse::new(json!({"roman": to_roman(number)}), 200)
}

fn to_roman(mut number: i64) -> String {
    let mut roman = String::new();
    for (value, symbol) in ROMAN_TABLE {
        while number >= value {
            roman.push_str(symbol);
            number -= value;
        }
    }
    roman
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

    /// The correctness corpus shared with every other language's roman-numeral.
    fn shared_cases() -> Vec<Case> {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../test-data/roman-numeral/correctness.json"
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

    #[test]
    fn converts_the_boundaries_and_subtractive_forms() {
        for (number, roman) in [
            (1, "I"),
            (4, "IV"),
            (9, "IX"),
            (40, "XL"),
            (90, "XC"),
            (400, "CD"),
            (900, "CM"),
            (3999, "MMMCMXCIX"),
        ] {
            assert_eq!(to_roman(number), roman);
        }
    }

    #[test]
    fn rejects_zero_and_a_null_number() {
        assert_eq!(
            respond(&json!({"number": 0})).output(),
            &json!({"error": "number must be between 1 and 3999, got: 0"})
        );
        assert_eq!(respond(&json!({"number": null})).status_code(), 422);
    }
}
