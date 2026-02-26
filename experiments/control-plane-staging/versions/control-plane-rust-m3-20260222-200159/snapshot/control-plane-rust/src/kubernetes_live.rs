use crate::kubernetes::{
    Deployment, HorizontalPodAutoscaler, KubernetesDeploymentBuilder, KubernetesProperties, Service,
};
use crate::model::FunctionSpec;
use reqwest::header::{HeaderMap, HeaderValue, AUTHORIZATION, CONTENT_TYPE};
use reqwest::{Certificate, Client, StatusCode};
use serde_json::{json, Value};
use std::fs;

const SA_TOKEN_PATH: &str = "/var/run/secrets/kubernetes.io/serviceaccount/token";
const SA_CA_PATH: &str = "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt";
const SA_NAMESPACE_PATH: &str = "/var/run/secrets/kubernetes.io/serviceaccount/namespace";

#[derive(Debug, Clone)]
pub struct InClusterKubernetesManager {
    client: Client,
    namespace: String,
    api_server_base: String,
    builder: KubernetesDeploymentBuilder,
}

impl InClusterKubernetesManager {
    pub fn from_env() -> Result<Option<Self>, String> {
        if std::env::var("NANOFAAS_K8S_PROVISIONING_ENABLED")
            .ok()
            .map(|value| value.eq_ignore_ascii_case("false"))
            .unwrap_or(false)
        {
            return Ok(None);
        }

        let host = match std::env::var("KUBERNETES_SERVICE_HOST") {
            Ok(value) if !value.trim().is_empty() => value.trim().to_string(),
            _ => return Ok(None),
        };

        let port = std::env::var("KUBERNETES_SERVICE_PORT_HTTPS")
            .or_else(|_| std::env::var("KUBERNETES_SERVICE_PORT"))
            .unwrap_or_else(|_| "443".to_string());

        let token_raw = fs::read_to_string(SA_TOKEN_PATH)
            .map_err(|err| format!("unable to read service account token: {err}"))?;
        let token = token_raw.trim().to_string();
        if token.is_empty() {
            return Err("service account token is empty".to_string());
        }

        let ca_pem = fs::read(SA_CA_PATH)
            .map_err(|err| format!("unable to read service account CA cert: {err}"))?;
        let ca_cert = Certificate::from_pem(&ca_pem)
            .map_err(|err| format!("invalid CA certificate: {err}"))?;

        let mut default_headers = HeaderMap::new();
        let authorization = format!("Bearer {token}");
        let auth_header = HeaderValue::from_str(&authorization)
            .map_err(|err| format!("invalid bearer token header: {err}"))?;
        default_headers.insert(AUTHORIZATION, auth_header);

        let client = Client::builder()
            .add_root_certificate(ca_cert)
            .default_headers(default_headers)
            .build()
            .map_err(|err| format!("unable to build Kubernetes API client: {err}"))?;

        let namespace = resolve_namespace();
        let callback_url = std::env::var("NANOFAAS_CALLBACK_URL").ok();
        let properties = KubernetesProperties::new(Some(namespace.clone()), callback_url);
        let builder = KubernetesDeploymentBuilder::new(properties);

        Ok(Some(Self {
            client,
            namespace,
            api_server_base: format!("https://{host}:{port}"),
            builder,
        }))
    }

    pub async fn provision(&self, spec: &FunctionSpec) -> Result<String, String> {
        let deployment = self.builder.build_deployment(spec);
        let service = self.builder.build_service(spec);
        let hpa = self.builder.build_hpa(spec);

        self.delete_deployment(&deployment.metadata.name).await?;
        self.create_deployment(&deployment).await?;

        self.delete_service(&service.metadata.name).await?;
        self.create_service(&service).await?;

        if let Some(hpa) = hpa {
            self.delete_hpa(&hpa.metadata.name).await?;
            self.create_hpa(&hpa).await?;
        }

        Ok(format!(
            "http://{}.{}.svc.cluster.local:8080/invoke",
            KubernetesDeploymentBuilder::service_name(&spec.name),
            self.namespace
        ))
    }

    pub async fn deprovision(&self, function_name: &str) -> Result<(), String> {
        let deployment_name = KubernetesDeploymentBuilder::deployment_name(function_name);
        let service_name = KubernetesDeploymentBuilder::service_name(function_name);
        self.delete_hpa(&deployment_name).await?;
        self.delete_service(&service_name).await?;
        self.delete_deployment(&deployment_name).await
    }

    pub async fn set_replicas(&self, function_name: &str, replicas: i32) -> Result<(), String> {
        let deployment_name = KubernetesDeploymentBuilder::deployment_name(function_name);
        let url = format!(
            "{}/apis/apps/v1/namespaces/{}/deployments/{}/scale",
            self.api_server_base, self.namespace, deployment_name
        );
        let payload = json!({
            "spec": {
                "replicas": replicas
            }
        });
        let response = self
            .client
            .patch(url)
            .header(CONTENT_TYPE, "application/merge-patch+json")
            .json(&payload)
            .send()
            .await
            .map_err(|err| format!("set replicas request failed for {deployment_name}: {err}"))?;
        self.ensure_status(
            response,
            &[StatusCode::OK, StatusCode::ACCEPTED],
            &format!("set replicas for {deployment_name}"),
        )
        .await
    }

    pub async fn get_ready_replicas(&self, function_name: &str) -> Result<i32, String> {
        let deployment_name = KubernetesDeploymentBuilder::deployment_name(function_name);
        let url = format!(
            "{}/apis/apps/v1/namespaces/{}/deployments/{}",
            self.api_server_base, self.namespace, deployment_name
        );
        let response = self
            .client
            .get(url)
            .send()
            .await
            .map_err(|err| format!("get deployment failed for {deployment_name}: {err}"))?;

        if response.status() == StatusCode::NOT_FOUND {
            return Ok(0);
        }
        if response.status() != StatusCode::OK {
            let status = response.status().as_u16();
            let body = response
                .text()
                .await
                .unwrap_or_else(|_| "<unreadable body>".to_string());
            return Err(format!(
                "get deployment failed for {deployment_name} with status {status}: {body}"
            ));
        }

        let body: Value = response
            .json()
            .await
            .map_err(|err| format!("invalid deployment response for {deployment_name}: {err}"))?;
        let ready = body
            .get("status")
            .and_then(|status| status.get("readyReplicas"))
            .and_then(Value::as_i64)
            .unwrap_or(0);
        Ok(ready as i32)
    }

    async fn create_deployment(&self, deployment: &Deployment) -> Result<(), String> {
        let url = format!(
            "{}/apis/apps/v1/namespaces/{}/deployments",
            self.api_server_base, self.namespace
        );
        let payload = deployment_to_json(deployment, &self.namespace);
        self.create_resource(url, payload, "deployment").await
    }

    async fn create_service(&self, service: &Service) -> Result<(), String> {
        let url = format!(
            "{}/api/v1/namespaces/{}/services",
            self.api_server_base, self.namespace
        );
        let payload = service_to_json(service, &self.namespace);
        self.create_resource(url, payload, "service").await
    }

    async fn create_hpa(&self, hpa: &HorizontalPodAutoscaler) -> Result<(), String> {
        let url = format!(
            "{}/apis/autoscaling/v2/namespaces/{}/horizontalpodautoscalers",
            self.api_server_base, self.namespace
        );
        let payload = hpa_to_json(hpa, &self.namespace);
        self.create_resource(url, payload, "hpa").await
    }

    async fn create_resource(&self, url: String, payload: Value, kind: &str) -> Result<(), String> {
        let response = self
            .client
            .post(url)
            .json(&payload)
            .send()
            .await
            .map_err(|err| format!("create {kind} request failed: {err}"))?;
        if response.status() == StatusCode::CONFLICT {
            return Ok(());
        }
        self.ensure_status(
            response,
            &[StatusCode::OK, StatusCode::CREATED, StatusCode::ACCEPTED],
            &format!("create {kind}"),
        )
        .await
    }

    async fn delete_deployment(&self, deployment_name: &str) -> Result<(), String> {
        let url = format!(
            "{}/apis/apps/v1/namespaces/{}/deployments/{}",
            self.api_server_base, self.namespace, deployment_name
        );
        self.delete_if_exists(url, "deployment").await
    }

    async fn delete_service(&self, service_name: &str) -> Result<(), String> {
        let url = format!(
            "{}/api/v1/namespaces/{}/services/{}",
            self.api_server_base, self.namespace, service_name
        );
        self.delete_if_exists(url, "service").await
    }

    async fn delete_hpa(&self, hpa_name: &str) -> Result<(), String> {
        let url = format!(
            "{}/apis/autoscaling/v2/namespaces/{}/horizontalpodautoscalers/{}",
            self.api_server_base, self.namespace, hpa_name
        );
        self.delete_if_exists(url, "hpa").await
    }

    async fn delete_if_exists(&self, url: String, kind: &str) -> Result<(), String> {
        let response = self
            .client
            .delete(url)
            .send()
            .await
            .map_err(|err| format!("delete {kind} request failed: {err}"))?;
        if response.status() == StatusCode::NOT_FOUND {
            return Ok(());
        }
        self.ensure_status(
            response,
            &[StatusCode::OK, StatusCode::ACCEPTED, StatusCode::NO_CONTENT],
            &format!("delete {kind}"),
        )
        .await
    }

    async fn ensure_status(
        &self,
        response: reqwest::Response,
        accepted: &[StatusCode],
        action: &str,
    ) -> Result<(), String> {
        let status = response.status();
        if accepted.contains(&status) {
            return Ok(());
        }
        let body = response
            .text()
            .await
            .unwrap_or_else(|_| "<unreadable body>".to_string());
        Err(format!(
            "{action} failed with status {}: {}",
            status.as_u16(),
            body
        ))
    }
}

fn resolve_namespace() -> String {
    if let Ok(value) = std::env::var("POD_NAMESPACE") {
        let trimmed = value.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }
    if let Ok(value) = fs::read_to_string(SA_NAMESPACE_PATH) {
        let trimmed = value.trim();
        if !trimmed.is_empty() {
            return trimmed.to_string();
        }
    }
    "default".to_string()
}

fn deployment_to_json(deployment: &Deployment, namespace: &str) -> Value {
    let container = &deployment.spec.template.spec.containers[0];
    let mut container_json = json!({
        "name": container.name,
        "image": container.image,
        "imagePullPolicy": container.image_pull_policy,
        "env": container.env.iter().map(|value| {
            json!({
                "name": value.name,
                "value": value.value
            })
        }).collect::<Vec<_>>(),
        "resources": {
            "requests": container.resources.requests,
            "limits": container.resources.limits
        },
        "ports": [{
            "containerPort": container.container_port,
            "protocol": "TCP"
        }],
        "readinessProbe": {
            "httpGet": {
                "path": container.readiness_probe.path,
                "port": container.readiness_probe.port
            }
        }
    });

    if let Some(command) = &container.command {
        container_json["command"] = json!(command);
    }

    json!({
        "apiVersion": "apps/v1",
        "kind": "Deployment",
        "metadata": {
            "name": deployment.metadata.name,
            "namespace": namespace,
            "labels": deployment.metadata.labels
        },
        "spec": {
            "replicas": deployment.spec.replicas,
            "selector": {
                "matchLabels": deployment.spec.selector
            },
            "template": {
                "metadata": {
                    "labels": deployment.spec.template.metadata.labels,
                    "annotations": deployment.spec.template.metadata.annotations
                },
                "spec": {
                    "containers": [container_json],
                    "imagePullSecrets": deployment.spec.template.spec.image_pull_secrets
                        .iter()
                        .map(|name| json!({ "name": name }))
                        .collect::<Vec<_>>(),
                    "restartPolicy": deployment.spec.template.spec.restart_policy
                }
            }
        }
    })
}

fn service_to_json(service: &Service, namespace: &str) -> Value {
    json!({
        "apiVersion": "v1",
        "kind": "Service",
        "metadata": {
            "name": service.metadata.name,
            "namespace": namespace,
            "labels": service.metadata.labels,
            "annotations": service.metadata.annotations
        },
        "spec": {
            "type": service.spec.service_type,
            "selector": service.spec.selector,
            "ports": service.spec.ports.iter().map(|port| {
                json!({
                    "port": port.port,
                    "targetPort": port.target_port,
                    "protocol": port.protocol
                })
            }).collect::<Vec<_>>()
        }
    })
}

fn hpa_to_json(hpa: &HorizontalPodAutoscaler, namespace: &str) -> Value {
    json!({
        "apiVersion": "autoscaling/v2",
        "kind": "HorizontalPodAutoscaler",
        "metadata": {
            "name": hpa.metadata.name,
            "namespace": namespace,
            "labels": hpa.metadata.labels
        },
        "spec": {
            "scaleTargetRef": {
                "apiVersion": hpa.spec.scale_target_ref.api_version,
                "kind": hpa.spec.scale_target_ref.kind,
                "name": hpa.spec.scale_target_ref.name
            },
            "minReplicas": hpa.spec.min_replicas,
            "maxReplicas": hpa.spec.max_replicas,
            "metrics": hpa.spec.metrics.iter().filter_map(metric_to_json).collect::<Vec<_>>()
        }
    })
}

fn metric_to_json(metric: &crate::dispatch::MetricSpec) -> Option<Value> {
    match metric.kind.as_str() {
        "Resource" => metric.resource.as_ref().map(|resource| {
            json!({
                "type": "Resource",
                "resource": {
                    "name": resource.name,
                    "target": target_to_json(&resource.target)
                }
            })
        }),
        "External" => metric.external.as_ref().map(|external| {
            json!({
                "type": "External",
                "external": {
                    "metric": {
                        "name": external.metric.name,
                        "selector": {
                            "matchLabels": external.metric.selector
                        }
                    },
                    "target": target_to_json(&external.target)
                }
            })
        }),
        _ => None,
    }
}

fn target_to_json(target: &crate::dispatch::MetricTarget) -> Value {
    let mut value = json!({
        "type": target.target_type
    });
    if let Some(average_utilization) = target.average_utilization {
        value["averageUtilization"] = json!(average_utilization);
    }
    if let Some(target_value) = &target.value {
        value["value"] = json!(target_value);
    }
    value
}
