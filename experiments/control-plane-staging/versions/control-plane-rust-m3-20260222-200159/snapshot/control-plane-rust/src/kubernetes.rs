use crate::dispatch::{KubernetesMetricsTranslator, MetricSpec};
use crate::model::{FunctionSpec, ScalingConfig, ScalingStrategy};
use std::collections::{HashMap, HashSet};
use std::sync::{Arc, Mutex};

const PROM_SCRAPE: &str = "prometheus.io/scrape";
const PROM_PATH: &str = "prometheus.io/path";
const PROM_PORT: &str = "prometheus.io/port";

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ObjectMeta {
    pub name: String,
    pub labels: HashMap<String, String>,
    pub annotations: HashMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EnvVar {
    pub name: String,
    pub value: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Probe {
    pub path: String,
    pub port: u16,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ResourceRequirements {
    pub requests: HashMap<String, String>,
    pub limits: HashMap<String, String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Container {
    pub name: String,
    pub image: String,
    pub image_pull_policy: String,
    pub command: Option<Vec<String>>,
    pub env: Vec<EnvVar>,
    pub resources: ResourceRequirements,
    pub container_port: u16,
    pub readiness_probe: Probe,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PodSpec {
    pub containers: Vec<Container>,
    pub image_pull_secrets: Vec<String>,
    pub restart_policy: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PodTemplate {
    pub metadata: ObjectMeta,
    pub spec: PodSpec,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct DeploymentStatus {
    pub ready_replicas: Option<i32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeploymentSpec {
    pub replicas: i32,
    pub selector: HashMap<String, String>,
    pub template: PodTemplate,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Deployment {
    pub metadata: ObjectMeta,
    pub spec: DeploymentSpec,
    pub status: DeploymentStatus,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServicePort {
    pub port: u16,
    pub target_port: u16,
    pub protocol: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ServiceSpec {
    pub service_type: String,
    pub selector: HashMap<String, String>,
    pub ports: Vec<ServicePort>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Service {
    pub metadata: ObjectMeta,
    pub spec: ServiceSpec,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ScaleTargetRef {
    pub api_version: String,
    pub kind: String,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HorizontalPodAutoscalerSpec {
    pub scale_target_ref: ScaleTargetRef,
    pub min_replicas: i32,
    pub max_replicas: i32,
    pub metrics: Vec<MetricSpec>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HorizontalPodAutoscaler {
    pub metadata: ObjectMeta,
    pub spec: HorizontalPodAutoscalerSpec,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct KubernetesProperties {
    pub namespace: Option<String>,
    pub callback_url: Option<String>,
}

impl KubernetesProperties {
    pub fn new(namespace: Option<String>, callback_url: Option<String>) -> Self {
        Self {
            namespace,
            callback_url,
        }
    }
}

#[derive(Debug, Clone)]
pub struct KubernetesDeploymentBuilder {
    properties: KubernetesProperties,
    metrics_translator: KubernetesMetricsTranslator,
}

impl KubernetesDeploymentBuilder {
    pub fn new(properties: KubernetesProperties) -> Self {
        Self {
            properties,
            metrics_translator: KubernetesMetricsTranslator,
        }
    }

    pub fn deployment_name(function_name: &str) -> String {
        format!("fn-{function_name}")
    }

    pub fn service_name(function_name: &str) -> String {
        format!("fn-{function_name}")
    }

    pub fn build_deployment(&self, spec: &FunctionSpec) -> Deployment {
        let labels = HashMap::from([
            ("app".to_string(), "nanofaas".to_string()),
            ("function".to_string(), spec.name.clone()),
        ]);
        let annotations = HashMap::from([
            (PROM_SCRAPE.to_string(), "true".to_string()),
            (PROM_PATH.to_string(), "/metrics".to_string()),
            (PROM_PORT.to_string(), "8080".to_string()),
        ]);
        let pod_meta = ObjectMeta {
            name: String::new(),
            labels: labels.clone(),
            annotations,
        };

        let selector = HashMap::from([("function".to_string(), spec.name.clone())]);
        let deployment_name = Self::deployment_name(&spec.name);

        Deployment {
            metadata: ObjectMeta {
                name: deployment_name,
                labels: labels.clone(),
                annotations: HashMap::new(),
            },
            spec: DeploymentSpec {
                replicas: min_replicas(spec).unwrap_or(1),
                selector,
                template: PodTemplate {
                    metadata: pod_meta,
                    spec: PodSpec {
                        containers: vec![Container {
                            name: "function".to_string(),
                            image: spec.image.clone().unwrap_or_default(),
                            image_pull_policy: "IfNotPresent".to_string(),
                            command: empty_to_none(spec.commands.clone()),
                            env: self.build_env_vars(spec),
                            resources: build_resources(spec),
                            container_port: 8080,
                            readiness_probe: Probe {
                                path: "/health".to_string(),
                                port: 8080,
                            },
                        }],
                        image_pull_secrets: build_image_pull_secrets(spec),
                        restart_policy: "Always".to_string(),
                    },
                },
            },
            status: DeploymentStatus::default(),
        }
    }

    pub fn build_service(&self, spec: &FunctionSpec) -> Service {
        Service {
            metadata: ObjectMeta {
                name: Self::service_name(&spec.name),
                labels: HashMap::from([
                    ("app".to_string(), "nanofaas".to_string()),
                    ("function".to_string(), spec.name.clone()),
                ]),
                annotations: HashMap::from([
                    (PROM_SCRAPE.to_string(), "true".to_string()),
                    (PROM_PATH.to_string(), "/metrics".to_string()),
                    (PROM_PORT.to_string(), "8080".to_string()),
                ]),
            },
            spec: ServiceSpec {
                service_type: "ClusterIP".to_string(),
                selector: HashMap::from([("function".to_string(), spec.name.clone())]),
                ports: vec![ServicePort {
                    port: 8080,
                    target_port: 8080,
                    protocol: "TCP".to_string(),
                }],
            },
        }
    }

    pub fn build_hpa(&self, spec: &FunctionSpec) -> Option<HorizontalPodAutoscaler> {
        let scaling = parse_scaling(spec)?;
        if scaling.strategy != ScalingStrategy::Hpa {
            return None;
        }
        Some(HorizontalPodAutoscaler {
            metadata: ObjectMeta {
                name: Self::deployment_name(&spec.name),
                labels: HashMap::from([
                    ("app".to_string(), "nanofaas".to_string()),
                    ("function".to_string(), spec.name.clone()),
                ]),
                annotations: HashMap::new(),
            },
            spec: HorizontalPodAutoscalerSpec {
                scale_target_ref: ScaleTargetRef {
                    api_version: "apps/v1".to_string(),
                    kind: "Deployment".to_string(),
                    name: Self::deployment_name(&spec.name),
                },
                min_replicas: scaling.min_replicas,
                max_replicas: scaling.max_replicas,
                metrics: self.metrics_translator.to_metric_specs(&scaling, spec),
            },
        })
    }

    fn build_env_vars(&self, spec: &FunctionSpec) -> Vec<EnvVar> {
        let mut env = Vec::new();
        let reserved: HashSet<&str> = HashSet::from([
            "FUNCTION_NAME",
            "WARM",
            "TIMEOUT_MS",
            "EXECUTION_MODE",
            "WATCHDOG_CMD",
            "CALLBACK_URL",
        ]);
        env.push(EnvVar {
            name: "FUNCTION_NAME".to_string(),
            value: spec.name.clone(),
        });
        env.push(EnvVar {
            name: "WARM".to_string(),
            value: "true".to_string(),
        });
        env.push(EnvVar {
            name: "TIMEOUT_MS".to_string(),
            value: spec.timeout_millis.unwrap_or(30_000).to_string(),
        });
        env.push(EnvVar {
            name: "EXECUTION_MODE".to_string(),
            value: runtime_mode_name(spec).to_string(),
        });
        if let Some(watchdog_cmd) = spec
            .runtime_command
            .as_deref()
            .map(|s| s.trim())
            .filter(|s| !s.is_empty())
        {
            env.push(EnvVar {
                name: "WATCHDOG_CMD".to_string(),
                value: watchdog_cmd.to_string(),
            });
        }
        if let Some(callback_url) = self
            .properties
            .callback_url
            .as_ref()
            .map(|value| value.trim())
            .filter(|value| !value.is_empty())
        {
            env.push(EnvVar {
                name: "CALLBACK_URL".to_string(),
                value: callback_url.to_string(),
            });
        }
        if let Some(spec_env) = &spec.env {
            for (key, value) in spec_env {
                if reserved.contains(key.as_str()) {
                    continue;
                }
                env.push(EnvVar {
                    name: key.clone(),
                    value: value.clone(),
                });
            }
        }
        env
    }
}

fn runtime_mode_name(spec: &FunctionSpec) -> &'static str {
    match spec.runtime_mode {
        crate::model::RuntimeMode::Http => "HTTP",
        crate::model::RuntimeMode::Stdio => "STDIO",
        crate::model::RuntimeMode::File => "FILE",
    }
}

fn empty_to_none(values: Option<Vec<String>>) -> Option<Vec<String>> {
    values.filter(|items| !items.is_empty())
}

fn build_resources(spec: &FunctionSpec) -> ResourceRequirements {
    let mut resources = ResourceRequirements::default();
    if let Some(resource_spec) = &spec.resources {
        if !resource_spec.cpu.is_empty() {
            resources
                .requests
                .insert("cpu".to_string(), resource_spec.cpu.clone());
            resources
                .limits
                .insert("cpu".to_string(), resource_spec.cpu.clone());
        }
        if !resource_spec.memory.is_empty() {
            resources
                .requests
                .insert("memory".to_string(), resource_spec.memory.clone());
            resources
                .limits
                .insert("memory".to_string(), resource_spec.memory.clone());
        }
    }
    resources
}

fn build_image_pull_secrets(spec: &FunctionSpec) -> Vec<String> {
    spec.image_pull_secrets
        .clone()
        .unwrap_or_default()
        .into_iter()
        .filter_map(|name| {
            let trimmed = name.trim().to_string();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed)
            }
        })
        .collect()
}

fn parse_scaling(spec: &FunctionSpec) -> Option<ScalingConfig> {
    spec.scaling_config
        .clone()
        .and_then(|raw| serde_json::from_value(raw).ok())
}

fn min_replicas(spec: &FunctionSpec) -> Option<i32> {
    parse_scaling(spec).map(|scaling| scaling.min_replicas)
}

#[derive(Debug, Default, Clone)]
pub struct InMemoryKubernetesClient {
    state: Arc<Mutex<InMemoryKubernetesState>>,
}

#[derive(Debug, Default)]
struct InMemoryKubernetesState {
    deployments: HashMap<String, Deployment>,
    services: HashMap<String, Service>,
    hpas: HashMap<String, HorizontalPodAutoscaler>,
}

impl InMemoryKubernetesClient {
    fn key(namespace: &str, name: &str) -> String {
        format!("{namespace}/{name}")
    }

    pub fn delete_deployment(&self, namespace: &str, name: &str) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.deployments.remove(&Self::key(namespace, name));
    }

    pub fn create_deployment(&self, namespace: &str, deployment: Deployment) {
        let key = Self::key(namespace, &deployment.metadata.name);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.deployments.insert(key, deployment);
    }

    pub fn get_deployment(&self, namespace: &str, name: &str) -> Option<Deployment> {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.deployments.get(&Self::key(namespace, name)).cloned()
    }

    pub fn list_deployments(&self, namespace: &str) -> Vec<Deployment> {
        let prefix = format!("{namespace}/");
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state
            .deployments
            .iter()
            .filter_map(|(key, value)| {
                if key.starts_with(&prefix) {
                    Some(value.clone())
                } else {
                    None
                }
            })
            .collect()
    }

    pub fn scale_deployment(&self, namespace: &str, name: &str, replicas: i32) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(deployment) = state.deployments.get_mut(&Self::key(namespace, name)) {
            deployment.spec.replicas = replicas;
        }
    }

    pub fn set_ready_replicas(&self, namespace: &str, name: &str, ready_replicas: i32) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        if let Some(deployment) = state.deployments.get_mut(&Self::key(namespace, name)) {
            deployment.status.ready_replicas = Some(ready_replicas);
        }
    }

    pub fn delete_service(&self, namespace: &str, name: &str) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.services.remove(&Self::key(namespace, name));
    }

    pub fn create_service(&self, namespace: &str, service: Service) {
        let key = Self::key(namespace, &service.metadata.name);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.services.insert(key, service);
    }

    pub fn get_service(&self, namespace: &str, name: &str) -> Option<Service> {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.services.get(&Self::key(namespace, name)).cloned()
    }

    pub fn delete_hpa(&self, namespace: &str, name: &str) {
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.hpas.remove(&Self::key(namespace, name));
    }

    pub fn create_hpa(&self, namespace: &str, hpa: HorizontalPodAutoscaler) {
        let key = Self::key(namespace, &hpa.metadata.name);
        let mut state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.hpas.insert(key, hpa);
    }

    pub fn get_hpa(&self, namespace: &str, name: &str) -> Option<HorizontalPodAutoscaler> {
        let state = self.state.lock().unwrap_or_else(|e| e.into_inner());
        state.hpas.get(&Self::key(namespace, name)).cloned()
    }
}

#[derive(Debug, Clone)]
pub struct KubernetesResourceManager {
    client: InMemoryKubernetesClient,
    builder: KubernetesDeploymentBuilder,
    resolved_namespace: String,
}

impl KubernetesResourceManager {
    pub fn new(client: InMemoryKubernetesClient, properties: KubernetesProperties) -> Self {
        let builder = KubernetesDeploymentBuilder::new(properties.clone());
        let resolved_namespace = resolve_namespace(&properties);
        Self {
            client,
            builder,
            resolved_namespace,
        }
    }

    pub fn provision(&self, spec: &FunctionSpec) -> String {
        let deployment = self.builder.build_deployment(spec);
        let service = self.builder.build_service(spec);

        self.client
            .delete_deployment(&self.resolved_namespace, &deployment.metadata.name);
        self.client
            .create_deployment(&self.resolved_namespace, deployment);

        self.client
            .delete_service(&self.resolved_namespace, &service.metadata.name);
        self.client
            .create_service(&self.resolved_namespace, service);

        if let Some(hpa) = self.builder.build_hpa(spec) {
            self.client
                .delete_hpa(&self.resolved_namespace, &hpa.metadata.name);
            self.client.create_hpa(&self.resolved_namespace, hpa);
        }

        format!(
            "http://{}.{}.svc.cluster.local:8080/invoke",
            KubernetesDeploymentBuilder::service_name(&spec.name),
            self.resolved_namespace
        )
    }

    pub fn deprovision(&self, function_name: &str) {
        let deployment_name = KubernetesDeploymentBuilder::deployment_name(function_name);
        let service_name = KubernetesDeploymentBuilder::service_name(function_name);
        self.client
            .delete_hpa(&self.resolved_namespace, &deployment_name);
        self.client
            .delete_service(&self.resolved_namespace, &service_name);
        self.client
            .delete_deployment(&self.resolved_namespace, &deployment_name);
    }

    pub fn set_replicas(&self, function_name: &str, replicas: i32) {
        let name = KubernetesDeploymentBuilder::deployment_name(function_name);
        self.client
            .scale_deployment(&self.resolved_namespace, &name, replicas);
    }

    pub fn get_ready_replicas(&self, function_name: &str) -> i32 {
        let name = KubernetesDeploymentBuilder::deployment_name(function_name);
        self.client
            .get_deployment(&self.resolved_namespace, &name)
            .and_then(|deployment| deployment.status.ready_replicas)
            .unwrap_or(0)
    }

    pub fn resolved_namespace(&self) -> &str {
        &self.resolved_namespace
    }

    pub fn client(&self) -> &InMemoryKubernetesClient {
        &self.client
    }
}

fn resolve_namespace(properties: &KubernetesProperties) -> String {
    if let Some(namespace) = properties
        .namespace
        .as_ref()
        .map(|value| value.trim())
        .filter(|value| !value.is_empty())
    {
        return namespace.to_string();
    }
    std::env::var("POD_NAMESPACE")
        .ok()
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
        .unwrap_or_else(|| "default".to_string())
}
