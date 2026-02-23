#![allow(non_snake_case)]

use control_plane_rust::kubernetes::{
    InMemoryKubernetesClient, KubernetesProperties, KubernetesResourceManager,
};
use control_plane_rust::model::{
    ExecutionMode, FunctionSpec, RuntimeMode, ScalingConfig, ScalingMetric, ScalingStrategy,
};
use serde_json::to_value;

fn manager() -> (InMemoryKubernetesClient, KubernetesResourceManager) {
    let client = InMemoryKubernetesClient::default();
    let manager = KubernetesResourceManager::new(
        client.clone(),
        KubernetesProperties::new(Some("default".to_string()), None),
    );
    (client, manager)
}

fn spec(scaling: ScalingConfig) -> FunctionSpec {
    FunctionSpec {
        name: "echo".to_string(),
        image: Some("nanofaas/function-runtime:0.5.0".to_string()),
        execution_mode: ExecutionMode::Deployment,
        runtime_mode: RuntimeMode::Http,
        concurrency: Some(4),
        queue_size: Some(100),
        max_retries: Some(3),
        scaling_config: Some(to_value(scaling).expect("serialize scaling config")),
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
fn provision_createsDeploymentAndService() {
    let (client, manager) = manager();
    let scaling = ScalingConfig {
        strategy: ScalingStrategy::Internal,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "queue_depth".to_string(),
            target: "5".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };

    let url = manager.provision(&spec(scaling));
    assert!(url.contains("fn-echo"));
    assert!(url.ends_with("/invoke"));

    let deployment = client
        .get_deployment("default", "fn-echo")
        .expect("deployment");
    assert_eq!(1, deployment.spec.replicas);
    let service = client.get_service("default", "fn-echo").expect("service");
    assert_eq!("ClusterIP", service.spec.service_type);
}

#[test]
fn provision_createsHpaForHpaStrategy() {
    let (client, manager) = manager();
    let scaling = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 5,
        metrics: Some(vec![ScalingMetric {
            metric_type: "cpu".to_string(),
            target: "80".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };
    manager.provision(&spec(scaling));

    let hpa = client.get_hpa("default", "fn-echo").expect("hpa");
    assert_eq!(1, hpa.spec.min_replicas);
    assert_eq!(5, hpa.spec.max_replicas);
}

#[test]
fn provision_doesNotCreateHpaForInternalStrategy() {
    let (client, manager) = manager();
    let scaling = ScalingConfig {
        strategy: ScalingStrategy::Internal,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "queue_depth".to_string(),
            target: "5".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };
    manager.provision(&spec(scaling));
    assert!(client.get_hpa("default", "fn-echo").is_none());
}

#[test]
fn provision_doesNotCreateHpaForNoneStrategy() {
    let (client, manager) = manager();
    let scaling = ScalingConfig {
        strategy: ScalingStrategy::None,
        min_replicas: 2,
        max_replicas: 10,
        metrics: Some(vec![]),
        concurrency_control: None,
    };
    manager.provision(&spec(scaling));

    assert!(client.get_hpa("default", "fn-echo").is_none());
    assert!(client.get_deployment("default", "fn-echo").is_some());
}

#[test]
fn deprovision_deletesAllResources() {
    let (client, manager) = manager();
    let scaling = ScalingConfig {
        strategy: ScalingStrategy::Hpa,
        min_replicas: 1,
        max_replicas: 5,
        metrics: Some(vec![ScalingMetric {
            metric_type: "cpu".to_string(),
            target: "80".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };
    manager.provision(&spec(scaling));
    assert!(client.get_deployment("default", "fn-echo").is_some());
    assert!(client.get_service("default", "fn-echo").is_some());

    manager.deprovision("echo");
    assert!(client.get_deployment("default", "fn-echo").is_none());
    assert!(client.get_service("default", "fn-echo").is_none());
    assert!(client.get_hpa("default", "fn-echo").is_none());
}

#[test]
fn getReadyReplicas_returnsZeroWhenDeploymentNotFound() {
    let (_, manager) = manager();
    assert_eq!(0, manager.get_ready_replicas("nonexistent"));
}

#[test]
fn provision_isIdempotent() {
    let (client, manager) = manager();
    let scaling = ScalingConfig {
        strategy: ScalingStrategy::Internal,
        min_replicas: 1,
        max_replicas: 10,
        metrics: Some(vec![ScalingMetric {
            metric_type: "queue_depth".to_string(),
            target: "5".to_string(),
            name: None,
        }]),
        concurrency_control: None,
    };
    let spec = spec(scaling);

    let url1 = manager.provision(&spec);
    let url2 = manager.provision(&spec);

    assert_eq!(url1, url2);
    assert_eq!(1, client.list_deployments("default").len());
}
