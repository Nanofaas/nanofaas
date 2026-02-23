#![allow(non_snake_case)]

use control_plane_rust::e2e_support::{assert_metric_sum_at_least, metric_sum, pool_function_spec};
use serde_json::Value;
use std::collections::HashMap;

#[test]
fn poolFunctionSpec_containsExpectedDefaults() {
    let spec = pool_function_spec(
        "echo",
        "nanofaas/function-runtime:test",
        "http://function-runtime:8080/invoke",
        5000,
        2,
        20,
        3,
    );

    assert_eq!(spec.get("name"), Some(&Value::String("echo".to_string())));
    assert_eq!(
        spec.get("image"),
        Some(&Value::String("nanofaas/function-runtime:test".to_string()))
    );
    assert_eq!(
        spec.get("endpointUrl"),
        Some(&Value::String(
            "http://function-runtime:8080/invoke".to_string()
        ))
    );
    assert_eq!(
        spec.get("executionMode"),
        Some(&Value::String("POOL".to_string()))
    );
    assert_eq!(spec.get("timeoutMs"), Some(&Value::from(5000)));
    assert_eq!(spec.get("concurrency"), Some(&Value::from(2)));
    assert_eq!(spec.get("queueSize"), Some(&Value::from(20)));
    assert_eq!(spec.get("maxRetries"), Some(&Value::from(3)));
}

#[test]
fn metricSum_filtersByMetricAndLabels() {
    let metrics = r#"
# HELP function_cold_start_total Total cold starts
# TYPE function_cold_start_total counter
function_cold_start_total{function="echo"} 1.0
function_cold_start_total{function="other"} 2.0
function_warm_start_total{function="echo"} 3.0
function_cold_start_total{function="echo",result="ok"} 4.0
"#;

    let labels = HashMap::from([("function".to_string(), "echo".to_string())]);
    let sum = metric_sum(metrics, "function_cold_start_total", &labels);
    assert_eq!(5.0, sum);
}

#[test]
fn assertMetricSumAtLeast_failsWhenMetricMissing() {
    let metrics = r#"
# TYPE function_warm_start_total counter
function_warm_start_total{function="echo"} 1.0
"#;
    let labels = HashMap::from([("function".to_string(), "echo".to_string())]);

    let err = assert_metric_sum_at_least(metrics, "function_cold_start_total", &labels, 1.0)
        .expect_err("should fail");
    assert!(err.contains("expected metric function_cold_start_total"));
}

#[test]
fn assertMetricSumAtLeast_failsWhenBelowThreshold() {
    let metrics = r#"
function_cold_start_total{function="echo"} 0.5
"#;
    let labels = HashMap::from([("function".to_string(), "echo".to_string())]);

    let err = assert_metric_sum_at_least(metrics, "function_cold_start_total", &labels, 1.0)
        .expect_err("should fail");
    assert!(err.contains("sum >= 1.0"));
}
