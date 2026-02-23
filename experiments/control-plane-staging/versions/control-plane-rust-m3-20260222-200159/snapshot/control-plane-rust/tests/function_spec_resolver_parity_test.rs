#![allow(non_snake_case)]

use control_plane_rust::model::{
    ConcurrencyControlMode, ExecutionMode, RuntimeMode, ScalingStrategy,
};
use control_plane_rust::registry::{
    FunctionDefaults, FunctionSpecResolver, ResolverConcurrencyControlConfig, ResolverFunctionSpec,
    ResolverScalingConfig,
};
use std::collections::HashMap;

#[test]
fn resolve_fillsDefaultsForNullFields() {
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resolver = FunctionSpecResolver::new(defaults);

    let spec = ResolverFunctionSpec::new("fn", "img:latest");
    let resolved = resolver.resolve(spec);

    assert_eq!("fn", resolved.name);
    assert_eq!(vec![] as Vec<String>, resolved.command.expect("command"));
    assert_eq!(HashMap::<String, String>::new(), resolved.env.expect("env"));
    assert_eq!(Some(30_000), resolved.timeout_ms);
    assert_eq!(Some(4), resolved.concurrency);
    assert_eq!(Some(100), resolved.queue_size);
    assert_eq!(Some(3), resolved.max_retries);
    assert_eq!(Some(ExecutionMode::Deployment), resolved.execution_mode);
    assert_eq!(Some(RuntimeMode::Http), resolved.runtime_mode);
}

#[test]
fn resolve_preservesExplicitValues() {
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resolver = FunctionSpecResolver::new(defaults);
    let mut env = HashMap::new();
    env.insert("K".to_string(), "V".to_string());

    let mut spec = ResolverFunctionSpec::new("fn", "img:latest");
    spec.command = Some(vec!["java".to_string()]);
    spec.env = Some(env.clone());
    spec.timeout_ms = Some(5_000);
    spec.concurrency = Some(2);
    spec.queue_size = Some(50);
    spec.max_retries = Some(1);
    spec.execution_mode = Some(ExecutionMode::Pool);
    spec.runtime_mode = Some(RuntimeMode::Stdio);

    let resolved = resolver.resolve(spec);

    assert_eq!(Some(vec!["java".to_string()]), resolved.command);
    assert_eq!(Some(env), resolved.env);
    assert_eq!(Some(5_000), resolved.timeout_ms);
    assert_eq!(Some(2), resolved.concurrency);
    assert_eq!(Some(50), resolved.queue_size);
    assert_eq!(Some(1), resolved.max_retries);
    assert_eq!(Some(ExecutionMode::Pool), resolved.execution_mode);
    assert_eq!(Some(RuntimeMode::Stdio), resolved.runtime_mode);
}

#[test]
fn resolve_deploymentMode_defaultScaling() {
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resolver = FunctionSpecResolver::new(defaults);

    let mut spec = ResolverFunctionSpec::new("fn", "img:latest");
    spec.execution_mode = Some(ExecutionMode::Deployment);
    let resolved = resolver.resolve(spec);

    let scaling = resolved.scaling_config.expect("scaling");
    assert_eq!(Some(ScalingStrategy::Internal), scaling.strategy);
    assert_eq!(Some(1), scaling.min_replicas);
    assert_eq!(Some(10), scaling.max_replicas);
    let metrics = scaling.metrics.expect("metrics");
    assert_eq!(1, metrics.len());
    assert_eq!("queue_depth", metrics[0].metric_type);
    let cc = scaling.concurrency_control.expect("cc");
    assert_eq!(Some(ConcurrencyControlMode::Fixed), cc.mode);
}

#[test]
fn resolve_deploymentMode_partialScalingConfig() {
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resolver = FunctionSpecResolver::new(defaults);

    let mut spec = ResolverFunctionSpec::new("fn", "img:latest");
    spec.execution_mode = Some(ExecutionMode::Deployment);
    spec.scaling_config = Some(ResolverScalingConfig {
        strategy: None,
        min_replicas: None,
        max_replicas: Some(5),
        metrics: None,
        concurrency_control: None,
    });

    let resolved = resolver.resolve(spec);
    let scaling = resolved.scaling_config.expect("scaling");
    assert_eq!(Some(ScalingStrategy::Internal), scaling.strategy);
    assert_eq!(Some(1), scaling.min_replicas);
    assert_eq!(Some(5), scaling.max_replicas);
}

#[test]
fn resolve_nonDeploymentMode_scalingPassedThrough() {
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resolver = FunctionSpecResolver::new(defaults);

    let config = ResolverScalingConfig {
        strategy: Some(ScalingStrategy::Hpa),
        min_replicas: Some(2),
        max_replicas: Some(20),
        metrics: Some(vec![]),
        concurrency_control: None,
    };
    let mut spec = ResolverFunctionSpec::new("fn", "img:latest");
    spec.execution_mode = Some(ExecutionMode::Local);
    spec.scaling_config = Some(config.clone());

    let resolved = resolver.resolve(spec);
    assert_eq!(Some(config), resolved.scaling_config);
}

#[test]
fn resolve_staticPerPod_defaultsMissingTargetTo2() {
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resolver = FunctionSpecResolver::new(defaults);

    let mut spec = ResolverFunctionSpec::new("fn", "img:latest");
    spec.execution_mode = Some(ExecutionMode::Deployment);
    spec.scaling_config = Some(ResolverScalingConfig {
        strategy: Some(ScalingStrategy::Internal),
        min_replicas: Some(1),
        max_replicas: Some(5),
        metrics: Some(vec![]),
        concurrency_control: Some(ResolverConcurrencyControlConfig {
            mode: Some(ConcurrencyControlMode::StaticPerPod),
            target_in_flight_per_pod: None,
            min_target_in_flight_per_pod: None,
            max_target_in_flight_per_pod: None,
            upscale_cooldown_ms: None,
            downscale_cooldown_ms: None,
            high_load_threshold: None,
            low_load_threshold: None,
        }),
    });

    let resolved = resolver.resolve(spec);
    let cc = resolved
        .scaling_config
        .expect("scaling")
        .concurrency_control
        .expect("cc");
    assert_eq!(Some(ConcurrencyControlMode::StaticPerPod), cc.mode);
    assert_eq!(Some(2), cc.target_in_flight_per_pod);
}

#[test]
fn resolve_staticPerPod_clampsInvalidMinMaxAndTarget() {
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resolver = FunctionSpecResolver::new(defaults);

    let mut spec = ResolverFunctionSpec::new("fn", "img:latest");
    spec.execution_mode = Some(ExecutionMode::Deployment);
    spec.scaling_config = Some(ResolverScalingConfig {
        strategy: Some(ScalingStrategy::Internal),
        min_replicas: Some(1),
        max_replicas: Some(5),
        metrics: Some(vec![]),
        concurrency_control: Some(ResolverConcurrencyControlConfig {
            mode: Some(ConcurrencyControlMode::StaticPerPod),
            target_in_flight_per_pod: Some(50),
            min_target_in_flight_per_pod: Some(10),
            max_target_in_flight_per_pod: Some(3),
            upscale_cooldown_ms: None,
            downscale_cooldown_ms: None,
            high_load_threshold: None,
            low_load_threshold: None,
        }),
    });

    let resolved = resolver.resolve(spec);
    let cc = resolved
        .scaling_config
        .expect("scaling")
        .concurrency_control
        .expect("cc");

    assert_eq!(Some(3), cc.min_target_in_flight_per_pod);
    assert_eq!(Some(3), cc.max_target_in_flight_per_pod);
    assert_eq!(Some(3), cc.target_in_flight_per_pod);
}
