#![allow(non_snake_case)]

use control_plane_rust::dispatch::KubernetesMetricsTranslator;
use control_plane_rust::model::{
    ExecutionMode, FunctionSpec, RuntimeMode, ScalingConfig, ScalingMetric, ScalingStrategy,
};

fn spec() -> FunctionSpec {
    FunctionSpec {
        name: "echo".to_string(),
        image: Some("nanofaas/function-runtime:0.5.0".to_string()),
        execution_mode: ExecutionMode::Deployment,
        runtime_mode: RuntimeMode::Http,
        concurrency: Some(4),
        queue_size: Some(100),
        max_retries: Some(3),
        scaling_config: None,
        commands: Some(vec![]),
        env: Some(std::collections::HashMap::new()),
        resources: None,
        timeout_millis: Some(30_000),
        url: None,
        image_pull_secrets: None,
        runtime_command: None,
    }
}

#[test]
fn toMetricSpecs_cpuTranslation() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "cpu".to_string(),
            target: "80".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());

    assert_eq!(1, specs.len());
    assert_eq!("Resource", specs[0].kind);
    let resource = specs[0].resource.as_ref().expect("resource");
    assert_eq!("cpu", resource.name);
    assert_eq!("Utilization", resource.target.target_type);
    assert_eq!(Some(80), resource.target.average_utilization);
}

#[test]
fn toMetricSpecs_memoryTranslation() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "memory".to_string(),
            target: "70".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());

    assert_eq!(1, specs.len());
    assert_eq!("Resource", specs[0].kind);
    let resource = specs[0].resource.as_ref().expect("resource");
    assert_eq!("memory", resource.name);
    assert_eq!(Some(70), resource.target.average_utilization);
}

#[test]
fn toMetricSpecs_externalQueueDepthTranslation() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "queue_depth".to_string(),
            target: "5".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());

    assert_eq!(1, specs.len());
    assert_eq!("External", specs[0].kind);
    let external = specs[0].external.as_ref().expect("external");
    assert_eq!("nanofaas_queue_depth", external.metric.name);
    assert_eq!(
        Some("echo"),
        external
            .metric
            .selector
            .get("function")
            .map(|value| value.as_str())
    );
    assert_eq!("Value", external.target.target_type);
    assert_eq!(Some("5"), external.target.value.as_deref());
}

#[test]
fn toMetricSpecs_externalRpsTranslation() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "rps".to_string(),
            target: "100".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());

    assert_eq!(1, specs.len());
    assert_eq!("External", specs[0].kind);
    assert_eq!(
        "nanofaas_rps",
        specs[0]
            .external
            .as_ref()
            .expect("external")
            .metric
            .name
            .as_str()
    );
}

#[test]
fn toMetricSpecs_prometheusTranslation() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "prometheus".to_string(),
            target: "10".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());

    assert_eq!(1, specs.len());
    assert_eq!("External", specs[0].kind);
    assert_eq!(
        "nanofaas_custom_echo",
        specs[0]
            .external
            .as_ref()
            .expect("external")
            .metric
            .name
            .as_str()
    );
}

#[test]
fn toMetricSpecs_unknownTypeIsSkipped() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "unknown_type".to_string(),
            target: "5".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());
    assert!(specs.is_empty());
}

#[test]
fn toMetricSpecs_nullMetricsReturnsEmpty() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: None,
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());
    assert!(specs.is_empty());
}

#[test]
fn toMetricSpecs_multipleMetrics() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![
            ScalingMetric {
                metric_type: "cpu".to_string(),
                target: "80".to_string(),
                name: None,
            },
            ScalingMetric {
                metric_type: "queue_depth".to_string(),
                target: "5".to_string(),
                name: None,
            },
        ]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());
    assert_eq!(2, specs.len());
    assert_eq!("Resource", specs[0].kind);
    assert_eq!("External", specs[1].kind);
}

#[test]
fn toMetricSpecs_invalidTargetDefaultsTo50() {
    let translator = KubernetesMetricsTranslator;
    let config = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "cpu".to_string(),
            target: "not-a-number".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let specs = translator.to_metric_specs(&config, &spec());
    assert_eq!(1, specs.len());
    assert_eq!(
        Some(50),
        specs[0]
            .resource
            .as_ref()
            .expect("resource")
            .target
            .average_utilization
    );
}
