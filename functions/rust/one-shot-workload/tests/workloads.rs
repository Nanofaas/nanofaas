use one_shot_workload::{
    Arithmetic, Matrix, MemoryScan, Mixed, Sorting, arithmetic, matrix, memory_scan, mixed, sorting,
};
use serde_json::{Value, json};

fn calculate(name: &str, payload: Value) -> Result<u64, nanofaas::BoxError> {
    match name {
        "one-shot-arithmetic" => arithmetic(serde_json::from_value::<Arithmetic>(payload)?),
        "one-shot-sort" => sorting(serde_json::from_value::<Sorting>(payload)?),
        "one-shot-memory" => memory_scan(serde_json::from_value::<MemoryScan>(payload)?),
        "one-shot-matrix" => matrix(serde_json::from_value::<Matrix>(payload)?),
        "one-shot-mixed" => mixed(serde_json::from_value::<Mixed>(payload)?),
        _ => Err("unknown workload".into()),
    }
}

#[test]
fn independent_reference_vectors_and_determinism() {
    let vectors: Vec<Value> = serde_json::from_str(include_str!("vectors.json")).unwrap();
    for row in vectors {
        let name = row["function"].as_str().unwrap();
        let payload = row["input"].clone();
        let expected = row["expected"].as_u64().unwrap();
        assert_eq!(
            calculate(name, payload.clone()).unwrap(),
            expected,
            "{name}"
        );
        assert_eq!(calculate(name, payload).unwrap(), expected, "{name}");
    }
}

#[test]
fn zero_work_and_empty_buffers_keep_the_seed() {
    let seed = u64::MAX;
    for (name, payload) in [
        ("one-shot-arithmetic", json!({"iterations":0,"seed":seed})),
        ("one-shot-sort", json!({"items":0,"passes":0,"seed":seed})),
        (
            "one-shot-memory",
            json!({"working_set_bytes":0,"passes":0,"seed":seed}),
        ),
        (
            "one-shot-matrix",
            json!({"dimension":0,"repetitions":0,"seed":seed}),
        ),
    ] {
        assert_eq!(calculate(name, payload).unwrap(), seed);
    }
}

#[test]
fn invalid_dimensions_products_and_unknown_fields_are_rejected() {
    for (name, payload) in [
        (
            "one-shot-arithmetic",
            json!({"iterations":u64::MAX,"seed":0}),
        ),
        (
            "one-shot-sort",
            json!({"items":u64::MAX,"passes":1,"seed":0}),
        ),
        (
            "one-shot-sort",
            json!({"items":1000000,"passes":1000000,"seed":0}),
        ),
        (
            "one-shot-memory",
            json!({"working_set_bytes":u64::MAX,"passes":1,"seed":0}),
        ),
        (
            "one-shot-memory",
            json!({"working_set_bytes":67108864,"passes":1000000,"seed":0}),
        ),
        (
            "one-shot-matrix",
            json!({"dimension":u64::MAX,"repetitions":1,"seed":0}),
        ),
        (
            "one-shot-matrix",
            json!({"dimension":256,"repetitions":1000000,"seed":0}),
        ),
        (
            "one-shot-arithmetic",
            json!({"iterations":0,"seed":0,"extra":true}),
        ),
        (
            "one-shot-mixed",
            json!({"arithmetic_iterations":0,"sort_items":0,"sort_passes":0,
            "working_set_bytes":0,"memory_passes":0,"matrix_dimension":u64::MAX,
            "matrix_repetitions":1,"seed":0}),
        ),
    ] {
        assert!(calculate(name, payload).is_err(), "{name}");
    }
}
