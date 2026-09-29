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
    if text.trim().is_empty() {
        return plain(json!({"error": "Field 'text' is required and must be non-empty"}));
    }
    let top_n = input
        .get("topN")
        .and_then(Value::as_f64)
        .map_or(DEFAULT_TOP_N, |top_n| top_n.max(0.0) as usize);
    plain(analyze(text, top_n))
}

fn analyze(text: &str, top_n: usize) -> Value {
    // Go's [^\p{L}\p{N}\s] filter; is_alphanumeric also keeps the few marks Unicode counts as
    // alphabetic, which the regex drops.
    let normalized: String = text
        .to_lowercase()
        .chars()
        .filter(|c| c.is_alphanumeric() || c.is_whitespace())
        .collect();
    let words: Vec<&str> = normalized.split_whitespace().collect();
    if words.is_empty() {
        return json!({"error": "No words found in input"});
    }

    let mut frequencies: HashMap<&str, usize> = HashMap::new();
    for word in &words {
        *frequencies.entry(word).or_default() += 1;
    }
    let mut counts: Vec<(&str, usize)> = frequencies.iter().map(|(w, c)| (*w, *c)).collect();
    counts.sort_by(|a, b| b.1.cmp(&a.1).then(a.0.cmp(b.0)));
    let top_words: Vec<Value> = counts
        .iter()
        .take(top_n)
        .map(|(word, count)| json!({"word": word, "count": count}))
        .collect();

    // Byte length, like Go's len(word), so every runtime reports the same average.
    let total_length: usize = words.iter().map(|word| word.len()).sum();
    let average = total_length as f64 / words.len() as f64;
    json!({
        "wordCount": words.len(),
        "uniqueWords": frequencies.len(),
        "topWords": top_words,
        "averageWordLength": (average * 100.0).round() / 100.0,
    })
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
            "/../../test-data/word-stats/correctness.json"
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
