use std::collections::BTreeMap;

use nanofaas::{BoxError, Context, HandlerResponse, Runtime};
use serde_json::{Map, Value, json};

#[tokio::main]
async fn main() {
    let stopped = Runtime::from_env()
        .register("json-transform", handle)
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

/// Groups `data` records by `groupBy` and aggregates `valueField` with `operation`
/// (count, sum, avg, min or max; count by default).
fn respond(input: &Value) -> HandlerResponse {
    let Some(fields) = input.as_object() else {
        return bad_request("Input must be a JSON object");
    };
    let data = fields.get("data").and_then(Value::as_array);
    let group_by = fields.get("groupBy").and_then(Value::as_str);
    let operation = fields
        .get("operation")
        .and_then(Value::as_str)
        .filter(|operation| !operation.is_empty())
        .unwrap_or("count");
    let value_field = fields
        .get("valueField")
        .and_then(Value::as_str)
        .unwrap_or_default();
    let (Some(data), Some(group_by)) = (data, group_by) else {
        return bad_request("Fields 'data' (array) and 'groupBy' (string) are required");
    };
    if !operation.eq_ignore_ascii_case("count") && value_field.is_empty() {
        return bad_request(&format!(
            "Field 'valueField' is required for operation: {operation}"
        ));
    }

    let mut groups: BTreeMap<String, Vec<&Map<String, Value>>> = BTreeMap::new();
    for item in data.iter().filter_map(Value::as_object) {
        let key = match item.get(group_by) {
            None | Some(Value::Null) => "null".to_owned(),
            Some(value) => go_string(value),
        };
        groups.entry(key).or_default().push(item);
    }
    let groups: Map<String, Value> = groups
        .into_iter()
        .map(|(key, items)| (key, aggregate(&items, operation, value_field)))
        .collect();
    HandlerResponse::new(
        json!({"groupBy": group_by, "operation": operation, "groups": groups}),
        200,
    )
}

fn aggregate(items: &[&Map<String, Value>], operation: &str, field: &str) -> Value {
    let values: Vec<f64> = items
        .iter()
        .filter_map(|item| item.get(field).and_then(Value::as_f64))
        .collect();
    let fold = |initial: f64, step: fn(f64, f64) -> f64| {
        if values.is_empty() {
            0.0
        } else {
            values.iter().copied().fold(initial, step)
        }
    };
    match operation.to_ascii_lowercase().as_str() {
        "count" => json!(items.len()),
        "sum" => json!(fold(0.0, |a, b| a + b)),
        "avg" => json!(fold(0.0, |a, b| a + b) / values.len().max(1) as f64),
        "min" => json!(fold(f64::INFINITY, f64::min)),
        "max" => json!(fold(f64::NEG_INFINITY, f64::max)),
        _ => json!(format!("unknown operation: {operation}")),
    }
}

/// A group key as Go's fmt.Sprint prints the decoded JSON value, so every runtime groups alike.
// ponytail: arrays and objects print as JSON, not Go's "[a b]" / "map[k:v]"; nobody groups by them.
fn go_string(value: &Value) -> String {
    match value {
        Value::String(text) => text.clone(),
        Value::Number(number) => {
            let float = number.as_f64().unwrap_or_default();
            if float.fract() == 0.0 && float.abs() < 1e15 {
                (float as i64).to_string()
            } else {
                float.to_string()
            }
        }
        other => other.to_string(),
    }
}

fn bad_request(message: &str) -> HandlerResponse {
    HandlerResponse::new(json!({"error": message}), 400)
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

    /// The correctness corpus shared with every other language's json-transform.
    fn shared_cases() -> Vec<Case> {
        let path = concat!(
            env!("CARGO_MANIFEST_DIR"),
            "/../../test-data/json-transform/correctness.json"
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

    fn transform(operation: &str) -> Value {
        let data = json!([
            {"team": "a", "score": 3}, {"team": "b", "score": 10},
            {"team": "a", "score": 5}, {"team": "a", "score": "n/a"}, {"score": 1}, "not an object"
        ]);
        respond(&json!({"data": data, "groupBy": "team", "operation": operation, "valueField": "score"}))
            .output()["groups"]
            .clone()
    }

    #[test]
    fn aggregates_numbers_and_skips_non_numeric_values_and_non_objects() {
        assert_eq!(transform("count"), json!({"a": 3, "b": 1, "null": 1}));
        assert_eq!(transform("sum"), json!({"a": 8.0, "b": 10.0, "null": 1.0}));
        assert_eq!(transform("min"), json!({"a": 3.0, "b": 10.0, "null": 1.0}));
        assert_eq!(transform("MAX"), json!({"a": 5.0, "b": 10.0, "null": 1.0}));
        assert_eq!(transform("median")["a"], "unknown operation: median");
    }

    #[test]
    fn counts_by_default_and_formats_numeric_keys_like_go() {
        let output = respond(
            &json!({"data": [{"k": 1.0}, {"k": 1}, {"k": 2.5}, {"k": true}], "groupBy": "k"}),
        );
        assert_eq!(output.output()["operation"], "count");
        assert_eq!(
            output.output()["groups"],
            json!({"1": 2, "2.5": 1, "true": 1})
        );
    }

    #[test]
    fn requires_a_value_field_for_aggregations() {
        let output = respond(&json!({"data": [], "groupBy": "k", "operation": "sum"}));
        assert_eq!(output.status_code(), 400);
        assert_eq!(
            output.output(),
            &json!({"error": "Field 'valueField' is required for operation: sum"})
        );
    }
}
