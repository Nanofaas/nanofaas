#![allow(non_snake_case)]

use control_plane_rust::kubernetes::{KubernetesDeploymentBuilder, KubernetesProperties};
use control_plane_rust::model::{
    ExecutionMode, FunctionSpec, ResourceSpec, RuntimeMode, ScalingConfig, ScalingMetric,
    ScalingStrategy,
};
use serde_json::to_value;
use std::collections::HashMap;

fn builder() -> KubernetesDeploymentBuilder {
    KubernetesDeploymentBuilder::new(KubernetesProperties::new(
        Some("default".to_string()),
        Some("http://control-plane:8080/v1/internal/executions".to_string()),
    ))
}

fn spec(scaling: Option<ScalingConfig>) -> FunctionSpec {
    FunctionSpec {
        name: "echo".to_string(),
        image: Some("nanofaas/function-runtime:0.5.0".to_string()),
        execution_mode: ExecutionMode::Deployment,
        runtime_mode: RuntimeMode::Http,
        concurrency: Some(4),
        queue_size: Some(100),
        max_retries: Some(3),
        scaling_config: scaling.map(|cfg| to_value(cfg).expect("serialize scaling config")),
        commands: Some(vec![]),
        env: Some(HashMap::from([("MY_VAR".to_string(), "hello".to_string())])),
        resources: Some(ResourceSpec {
            cpu: "250m".to_string(),
            memory: "128Mi".to_string(),
        }),
        timeout_millis: Some(30_000),
        url: None,
        image_pull_secrets: None,
    }
}

#[test]
fn buildDeployment_correctMetadata() {
    let deployment = builder().build_deployment(&spec(Some(ScalingConfig {
        strategy: ScalingStrategy::Internal,
        min_replicas: 2,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "queue_depth".to_string(),
            target: "5".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    })));

    assert_eq!("fn-echo", deployment.metadata.name);
    assert_eq!(
        Some("nanofaas"),
        deployment.metadata.labels.get("app").map(|v| v.as_str())
    );
    assert_eq!(
        Some("echo"),
        deployment
            .metadata
            .labels
            .get("function")
            .map(|v| v.as_str())
    );
}

#[test]
fn buildDeployment_usesMinReplicasFromScalingConfig() {
    let deployment = builder().build_deployment(&spec(Some(ScalingConfig {
        strategy: ScalingStrategy::Internal,
        min_replicas: 3,
        max_replicas: 10,
        metrics: Some(vec![]),
        concurrency_control: None,
    })));
    assert_eq!(3, deployment.spec.replicas);
}

#[test]
fn buildDeployment_defaultsTo1ReplicaWhenNoScalingConfig() {
    let deployment = builder().build_deployment(&spec(None));
    assert_eq!(1, deployment.spec.replicas);
}

#[test]
fn buildDeployment_containsRequiredEnvVars() {
    let deployment = builder().build_deployment(&spec(None));
    let env = &deployment.spec.template.spec.containers[0].env;

    assert!(env
        .iter()
        .any(|e| e.name == "FUNCTION_NAME" && e.value == "echo"));
    assert!(env.iter().any(|e| e.name == "WARM" && e.value == "true"));
    assert!(env
        .iter()
        .any(|e| e.name == "TIMEOUT_MS" && e.value == "30000"));
    assert!(env
        .iter()
        .any(|e| e.name == "EXECUTION_MODE" && e.value == "HTTP"));
}

#[test]
fn buildDeployment_filtersReservedEnvVars() {
    let mut env = HashMap::new();
    env.insert("FUNCTION_NAME".to_string(), "hacked".to_string());
    env.insert("MY_VAR".to_string(), "ok".to_string());
    let spec_with_reserved = FunctionSpec {
        name: "echo".to_string(),
        image: Some("nanofaas/function-runtime:0.5.0".to_string()),
        execution_mode: ExecutionMode::Deployment,
        runtime_mode: RuntimeMode::Http,
        concurrency: Some(4),
        queue_size: Some(100),
        max_retries: Some(3),
        scaling_config: None,
        commands: Some(vec![]),
        env: Some(env),
        resources: None,
        timeout_millis: Some(30_000),
        url: None,
        image_pull_secrets: None,
    };

    let deployment = builder().build_deployment(&spec_with_reserved);
    let container_env = &deployment.spec.template.spec.containers[0].env;
    let fn_name_values: Vec<&str> = container_env
        .iter()
        .filter(|e| e.name == "FUNCTION_NAME")
        .map(|e| e.value.as_str())
        .collect();
    assert_eq!(vec!["echo"], fn_name_values);
    assert!(container_env
        .iter()
        .any(|e| e.name == "MY_VAR" && e.value == "ok"));
}

#[test]
fn buildDeployment_hasReadinessProbe() {
    let deployment = builder().build_deployment(&spec(None));
    let probe = &deployment.spec.template.spec.containers[0].readiness_probe;
    assert_eq!("/health", probe.path);
    assert_eq!(8080, probe.port);
}

#[test]
fn buildDeployment_addsPrometheusScrapeAnnotations() {
    let deployment = builder().build_deployment(&spec(None));
    let ann = &deployment.spec.template.metadata.annotations;
    assert_eq!(
        Some("true"),
        ann.get("prometheus.io/scrape").map(|v| v.as_str())
    );
    assert_eq!(
        Some("/metrics"),
        ann.get("prometheus.io/path").map(|v| v.as_str())
    );
    assert_eq!(
        Some("8080"),
        ann.get("prometheus.io/port").map(|v| v.as_str())
    );
}

#[test]
fn buildService_addsPrometheusScrapeAnnotations() {
    let service = builder().build_service(&spec(None));
    let ann = &service.metadata.annotations;
    assert_eq!(
        Some("true"),
        ann.get("prometheus.io/scrape").map(|v| v.as_str())
    );
    assert_eq!(
        Some("/metrics"),
        ann.get("prometheus.io/path").map(|v| v.as_str())
    );
    assert_eq!(
        Some("8080"),
        ann.get("prometheus.io/port").map(|v| v.as_str())
    );
}

#[test]
fn buildDeployment_setsResources() {
    let deployment = builder().build_deployment(&spec(None));
    let resources = &deployment.spec.template.spec.containers[0].resources;
    assert_eq!(
        Some("250m"),
        resources.requests.get("cpu").map(|v| v.as_str())
    );
    assert_eq!(
        Some("128Mi"),
        resources.requests.get("memory").map(|v| v.as_str())
    );
}

#[test]
fn buildService_correctStructure() {
    let service = builder().build_service(&spec(None));
    assert_eq!("fn-echo", service.metadata.name);
    assert_eq!("ClusterIP", service.spec.service_type);
    assert_eq!(
        Some("echo"),
        service.spec.selector.get("function").map(|v| v.as_str())
    );
    assert_eq!(8080, service.spec.ports[0].port);
}

#[test]
fn buildHpa_returnsNullForNonHpaStrategy() {
    let hpa = builder().build_hpa(&spec(Some(ScalingConfig {
        strategy: ScalingStrategy::Internal,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![]),
        concurrency_control: None,
    })));
    assert!(hpa.is_none());
}

#[test]
fn buildHpa_returnsNullForNoneStrategy() {
    let hpa = builder().build_hpa(&spec(Some(ScalingConfig {
        strategy: ScalingStrategy::None,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![]),
        concurrency_control: None,
    })));
    assert!(hpa.is_none());
}

#[test]
fn buildHpa_createsHpaForHpaStrategy() {
    let hpa = builder()
        .build_hpa(&spec(Some(ScalingConfig {
            strategy: ScalingStrategy::Hpa,
            min_replicas: 1,
            max_replicas: 10,
            metrics: Some(vec![ScalingMetric {
                metric_type: "cpu".to_string(),
                target: "80".to_string(),
                name: None,
            }]),
            concurrency_control: None,
        })))
        .expect("hpa");

    assert_eq!("fn-echo", hpa.metadata.name);
    assert_eq!(1, hpa.spec.min_replicas);
    assert_eq!(10, hpa.spec.max_replicas);
    assert_eq!("Deployment", hpa.spec.scale_target_ref.kind);
    assert_eq!("fn-echo", hpa.spec.scale_target_ref.name);
}

#[test]
fn buildHpa_cpuMetricTranslation() {
    let hpa = builder()
        .build_hpa(&spec(Some(ScalingConfig {
            strategy: ScalingStrategy::Hpa,
            min_replicas: 1,
            max_replicas: 10,
            metrics: Some(vec![ScalingMetric {
                metric_type: "cpu".to_string(),
                target: "80".to_string(),
                name: None,
            }]),
            concurrency_control: None,
        })))
        .expect("hpa");

    assert_eq!(1, hpa.spec.metrics.len());
    assert_eq!("Resource", hpa.spec.metrics[0].kind);
    let resource = hpa.spec.metrics[0].resource.as_ref().expect("resource");
    assert_eq!("cpu", resource.name);
    assert_eq!(Some(80), resource.target.average_utilization);
}

#[test]
fn buildHpa_externalMetricTranslation() {
    let hpa = builder()
        .build_hpa(&spec(Some(ScalingConfig {
            strategy: ScalingStrategy::Hpa,
            min_replicas: 1,
            max_replicas: 10,
            metrics: Some(vec![ScalingMetric {
                metric_type: "queue_depth".to_string(),
                target: "5".to_string(),
                name: None,
            }]),
            concurrency_control: None,
        })))
        .expect("hpa");

    assert_eq!(1, hpa.spec.metrics.len());
    assert_eq!("External", hpa.spec.metrics[0].kind);
    let external = hpa.spec.metrics[0].external.as_ref().expect("external");
    assert_eq!("nanofaas_queue_depth", external.metric.name);
    assert_eq!(
        Some("echo"),
        external.metric.selector.get("function").map(|v| v.as_str())
    );
}

#[test]
fn buildHpa_returnsNullWhenScalingConfigNull() {
    assert!(builder().build_hpa(&spec(None)).is_none());
}

#[test]
fn buildDeployment_containsCallbackUrlEnvVar() {
    let deployment = builder().build_deployment(&spec(None));
    let env = &deployment.spec.template.spec.containers[0].env;
    assert!(env.iter().any(|e| {
        e.name == "CALLBACK_URL" && e.value == "http://control-plane:8080/v1/internal/executions"
    }));
}

#[test]
fn buildDeployment_setsImagePullSecrets() {
    let mut with_secrets = spec(None);
    with_secrets.image_pull_secrets = Some(vec!["regcred".to_string(), "ghcr-creds".to_string()]);

    let deployment = builder().build_deployment(&with_secrets);
    assert_eq!(2, deployment.spec.template.spec.image_pull_secrets.len());
    assert_eq!(
        "regcred",
        deployment.spec.template.spec.image_pull_secrets[0]
    );
    assert_eq!(
        "ghcr-creds",
        deployment.spec.template.spec.image_pull_secrets[1]
    );
}

#[test]
fn deploymentName_format() {
    assert_eq!(
        "fn-myFunc",
        KubernetesDeploymentBuilder::deployment_name("myFunc")
    );
}

#[test]
fn serviceName_format() {
    assert_eq!(
        "fn-myFunc",
        KubernetesDeploymentBuilder::service_name("myFunc")
    );
}
