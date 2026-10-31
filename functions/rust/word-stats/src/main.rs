use std::collections::HashMap;

use nanofaas::{BoxError, Context, HandlerResponse, Runtime};
use serde_json::{Value, json};

const DEFAULT_TOP_N: usize = 10;

#[tokio::main]
async fn main() {
    let stopped = Runtime::from_env()
        .register("word-stats", handle)
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

/// Accepts `{"text": ..., "topN": ...}` or a plain string. Errors are ordinary 200 outputs, as
/// in every other language's word-stats.
fn respond(input: &Value) -> HandlerResponse {
    let text = match input {
        Value::String(text) => text.as_str(),
        Value::Object(fields) => fields
            .get("text")
            .and_then(Value::as_str)
            .unwrap_or_default(),
        _ => "",
    };
    if text.is_empty() {
        return plain(json!({"error": "Field 'text' is required and must be non-empty"}));
    }
    let top_n = input
        .get("topN")
        .and_then(Value::as_f64)
        .filter(|n| n.is_finite())
        .map_or(DEFAULT_TOP_N, |n| n.max(0.0).floor() as usize);
    plain(analyze(text, top_n))
}

// The shared word-stats-runtime-v1 contract; see docs/word-stats-contract.md.
fn analyze(text: &str, top_n: usize) -> Value {
    use unicode_general_category::{GeneralCategory::*, get_general_category};
    let mut frequencies: HashMap<String, usize> = HashMap::new();
    let mut word = String::new();
    let (mut count, mut total_length) = (0usize, 0usize);
    for char in text.chars() {
        if matches!(
            get_general_category(char),
            UppercaseLetter
                | LowercaseLetter
                | TitlecaseLetter
                | ModifierLetter
                | OtherLetter
                | DecimalNumber
                | LetterNumber
                | OtherNumber
        ) {
            word.push(char.to_lowercase().next().unwrap());
            total_length += 1;
        } else if !word.is_empty() {
            *frequencies.entry(std::mem::take(&mut word)).or_default() += 1;
            count += 1;
        }
    }
    if !word.is_empty() {
        *frequencies.entry(word).or_default() += 1;
        count += 1;
    }
    if count == 0 {
        return json!({"error": "No words found in input"});
    }
    let mut ordered: Vec<_> = frequencies.iter().collect();
    ordered.sort_by(|a, b| b.1.cmp(a.1).then(a.0.cmp(b.0)));
    let top_words: Vec<_> = ordered
        .iter()
        .take(top_n)
        .map(|(word, n)| json!({"word":word,"count":n}))
        .collect();
    let cents = (200 * total_length + count) / (2 * count);
    json!({"wordCount":count,"uniqueWords":frequencies.len(),
           "topWords":top_words,"averageWordLength":cents as f64/100.0})
}

fn plain(output: Value) -> HandlerResponse {
    HandlerResponse::new(output, 200)
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

    /// The correctness corpus shared with every other language's word-stats.
    fn shared_cases() -> Vec<Case> {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../test-data/word-stats/runtime-contract.json"
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
    fn counts_words_and_unique_words() {
        let output = respond(&json!({"text": "the quick brown fox jumps over the lazy dog"}));
        assert_eq!(output.output()["wordCount"], 9);
        assert_eq!(output.output()["uniqueWords"], 8);
        assert_eq!(
            output.output()["topWords"][0],
            json!({"word": "the", "count": 2})
        );
    }

    #[test]
    fn strips_punctuation_but_keeps_unicode_letters_and_digits() {
        let output = respond(&json!({"text": "Caffè, caffè! 42 volte?"}));
        assert_eq!(output.output()["uniqueWords"], 3);
        assert_eq!(
            output.output()["topWords"][0],
            json!({"word": "caffè", "count": 2})
        );
    }

    #[test]
    fn limits_top_words_to_top_n_and_defaults_to_ten() {
        let text: Vec<String> = (0..12).map(|i| format!("w{i:02}")).collect();
        let text = text.join(" ");
        assert_eq!(
            respond(&json!({"text": text})).output()["topWords"]
                .as_array()
                .unwrap()
                .len(),
            10
        );
        assert_eq!(
            respond(&json!({"text": text, "topN": 2})).output()["topWords"]
                .as_array()
                .unwrap()
                .len(),
            2
        );
    }

    #[test]
    fn reports_punctuation_only_input() {
        assert_eq!(
            respond(&json!({"text": "?!"})).output(),
            &json!({"error": "No words found in input"})
        );
    }
}
