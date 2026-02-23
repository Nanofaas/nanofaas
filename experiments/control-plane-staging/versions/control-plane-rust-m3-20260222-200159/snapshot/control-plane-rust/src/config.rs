use std::collections::{HashMap, HashSet};
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::fs;
use std::path::PathBuf;
use std::sync::Arc;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpClientProperties {
    pub connect_timeout_ms: i32,
    pub read_timeout_ms: i32,
    pub max_in_memory_size_mb: i32,
}

impl HttpClientProperties {
    pub fn new(
        connect_timeout_ms: Option<i32>,
        read_timeout_ms: Option<i32>,
        max_in_memory_size_mb: Option<i32>,
    ) -> Self {
        Self {
            connect_timeout_ms: normalize(connect_timeout_ms, 5000),
            read_timeout_ms: normalize(read_timeout_ms, 30000),
            max_in_memory_size_mb: normalize(max_in_memory_size_mb, 1),
        }
    }
}

fn normalize(value: Option<i32>, default_value: i32) -> i32 {
    match value {
        Some(v) if v > 0 => v,
        _ => default_value,
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IllegalStateError {
    message: String,
}

impl IllegalStateError {
    fn new(message: impl Into<String>) -> Self {
        Self {
            message: message.into(),
        }
    }
}

impl Display for IllegalStateError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}", self.message)
    }
}

impl Error for IllegalStateError {}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct KubernetesClient {
    master_url: Option<String>,
    oauth_token: Option<String>,
    ca_cert_file: Option<String>,
}

impl KubernetesClient {
    pub fn master_url(&self) -> Option<&str> {
        self.master_url.as_deref()
    }

    pub fn oauth_token(&self) -> Option<&str> {
        self.oauth_token.as_deref()
    }

    pub fn ca_cert_file(&self) -> Option<&str> {
        self.ca_cert_file.as_deref()
    }
}

pub struct KubernetesClientConfig {
    service_account_token_path: PathBuf,
    service_account_ca_path: PathBuf,
    env_lookup: Arc<dyn Fn(&str) -> Option<String> + Send + Sync>,
}

impl KubernetesClientConfig {
    pub fn new<F>(
        service_account_token_path: PathBuf,
        service_account_ca_path: PathBuf,
        env_lookup: F,
    ) -> Self
    where
        F: Fn(&str) -> Option<String> + Send + Sync + 'static,
    {
        Self {
            service_account_token_path,
            service_account_ca_path,
            env_lookup: Arc::new(env_lookup),
        }
    }

    pub fn kubernetes_client(&self) -> Result<KubernetesClient, IllegalStateError> {
        match fs::read_to_string(&self.service_account_token_path) {
            Ok(raw_token) => {
                let token = raw_token.trim();
                if token.is_empty() {
                    return Err(IllegalStateError::new("ServiceAccount token is empty"));
                }
                let host = (self.env_lookup)("KUBERNETES_SERVICE_HOST")
                    .filter(|value| !value.trim().is_empty());
                let port = (self.env_lookup)("KUBERNETES_SERVICE_PORT")
                    .filter(|value| !value.trim().is_empty());
                match (host, port) {
                    (Some(host), Some(port)) => {
                        let ca_file = self
                            .service_account_ca_path
                            .canonicalize()
                            .unwrap_or_else(|_| self.service_account_ca_path.clone())
                            .display()
                            .to_string();
                        Ok(KubernetesClient {
                            master_url: Some(format!("https://{host}:{port}")),
                            oauth_token: Some(token.to_string()),
                            ca_cert_file: Some(ca_file),
                        })
                    }
                    _ => Err(IllegalStateError::new(
                        "Missing Kubernetes service host/port",
                    )),
                }
            }
            Err(err) if err.kind() == std::io::ErrorKind::NotFound => Ok(KubernetesClient {
                master_url: None,
                oauth_token: None,
                ca_cert_file: None,
            }),
            Err(_) => Err(IllegalStateError::new(
                "Failed to read in-cluster ServiceAccount credentials",
            )),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum MemberCategory {
    InvokePublicMethods,
    InvokeDeclaredConstructors,
}

#[derive(Debug, Clone, Default)]
pub struct RuntimeHints {
    resources: HashSet<String>,
    reflection_hints: HashMap<String, HashSet<MemberCategory>>,
}

impl RuntimeHints {
    pub fn has_resource(&self, resource: &str) -> bool {
        self.resources.contains(resource)
    }

    pub fn has_type_hint(&self, class_name: &str) -> bool {
        self.reflection_hints.contains_key(class_name)
    }

    pub fn has_type_member_category(&self, class_name: &str, category: MemberCategory) -> bool {
        self.reflection_hints
            .get(class_name)
            .map(|categories| categories.contains(&category))
            .unwrap_or(false)
    }

    pub fn type_hints_count(&self) -> usize {
        self.reflection_hints.len()
    }

    fn add_resource(&mut self, resource: &str) {
        self.resources.insert(resource.to_string());
    }

    fn add_type_hint(&mut self, class_name: &str, category: MemberCategory) {
        self.reflection_hints
            .entry(class_name.to_string())
            .or_default()
            .insert(category);
    }
}

pub trait ResourceProvider {
    fn list_classes_in_package(&self, package: &str) -> Result<Vec<String>, String>;
}

#[derive(Debug, Clone)]
pub struct FsResourceProvider {
    root: PathBuf,
}

impl FsResourceProvider {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn list_classes_in_package(&self, package: &str) -> Result<Vec<String>, String> {
        ResourceProvider::list_classes_in_package(self, package)
    }
}

impl ResourceProvider for FsResourceProvider {
    fn list_classes_in_package(&self, package: &str) -> Result<Vec<String>, String> {
        let package_dir = self.root.join(package.replace('.', "/"));
        if !package_dir.exists() {
            return Ok(vec![]);
        }
        let mut classes = Vec::new();
        let entries = fs::read_dir(package_dir).map_err(|err| err.to_string())?;
        for entry in entries {
            let entry = entry.map_err(|err| err.to_string())?;
            let path = entry.path();
            if path.extension().and_then(|value| value.to_str()) != Some("class") {
                continue;
            }
            let Some(stem) = path.file_stem().and_then(|value| value.to_str()) else {
                continue;
            };
            if stem == "package-info" || stem == "module-info" {
                continue;
            }
            classes.push(format!("{package}.{stem}"));
        }
        classes.sort();
        Ok(classes)
    }
}

#[derive(Debug, Clone, Copy)]
pub struct VertxResourceHints;

impl VertxResourceHints {
    pub fn register_hints<P: ResourceProvider>(&self, hints: &mut RuntimeHints, provider: &P) {
        hints.add_resource("META-INF/vertx/vertx-version.txt");
        hints.add_type_hint(
            "io.fabric8.kubernetes.api.model.Pod",
            MemberCategory::InvokePublicMethods,
        );
        hints.add_type_hint(
            "io.fabric8.kubernetes.api.model.DeleteOptions",
            MemberCategory::InvokePublicMethods,
        );
        hints.add_type_hint(
            "io.fabric8.kubernetes.client.impl.KubernetesClientImpl",
            MemberCategory::InvokeDeclaredConstructors,
        );

        if let Ok(discovered) = provider.list_classes_in_package("io.fabric8.kubernetes.api.model")
        {
            for class_name in discovered {
                Self::register_type_hint(hints, &class_name);
            }
        }
    }

    pub fn register_type_hint(hints: &mut RuntimeHints, class_name: &str) {
        if class_name == "java.lang.Runnable" || class_name == "com.example.DoesNotExist" {
            return;
        }
        hints.add_type_hint(class_name, MemberCategory::InvokePublicMethods);
    }
}
