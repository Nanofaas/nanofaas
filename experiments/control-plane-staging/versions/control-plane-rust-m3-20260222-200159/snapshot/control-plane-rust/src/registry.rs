use crate::model::{
    ConcurrencyControlMode, ExecutionMode, FunctionSpec as AppFunctionSpec, RuntimeMode,
    ScalingStrategy,
};
use std::collections::HashMap;
use std::error::Error;
use std::fmt::{Display, Formatter};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FunctionNotFoundError {
    message: Option<String>,
}

impl FunctionNotFoundError {
    pub fn new() -> Self {
        Self { message: None }
    }

    pub fn with_function_name(function_name: &str) -> Self {
        Self {
            message: Some(format!("Function not found: {function_name}")),
        }
    }

    pub fn message(&self) -> Option<&str> {
        self.message.as_deref()
    }
}

impl Display for FunctionNotFoundError {
    fn fmt(&self, f: &mut Formatter<'_>) -> std::fmt::Result {
        match &self.message {
            Some(message) => write!(f, "{message}"),
            None => write!(f, ""),
        }
    }
}

impl Error for FunctionNotFoundError {}

pub trait ImageValidator {
    fn validate(&self, spec: Option<&AppFunctionSpec>);
}

#[derive(Debug)]
pub struct NoOpImageValidator;

impl ImageValidator for NoOpImageValidator {
    fn validate(&self, _spec: Option<&AppFunctionSpec>) {}
}

static NO_OP_IMAGE_VALIDATOR: NoOpImageValidator = NoOpImageValidator;

pub fn no_op_image_validator() -> &'static NoOpImageValidator {
    &NO_OP_IMAGE_VALIDATOR
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FunctionDefaults {
    pub timeout_ms: i32,
    pub concurrency: i32,
    pub queue_size: i32,
    pub max_retries: i32,
}

impl FunctionDefaults {
    pub fn new(timeout_ms: i32, concurrency: i32, queue_size: i32, max_retries: i32) -> Self {
        Self {
            timeout_ms,
            concurrency,
            queue_size,
            max_retries,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResolverScalingMetric {
    pub metric_type: String,
    pub target: String,
    pub name: Option<String>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ResolverConcurrencyControlConfig {
    pub mode: Option<ConcurrencyControlMode>,
    pub target_in_flight_per_pod: Option<i32>,
    pub min_target_in_flight_per_pod: Option<i32>,
    pub max_target_in_flight_per_pod: Option<i32>,
    pub upscale_cooldown_ms: Option<u64>,
    pub downscale_cooldown_ms: Option<u64>,
    pub high_load_threshold: Option<f64>,
    pub low_load_threshold: Option<f64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ResolverScalingConfig {
    pub strategy: Option<ScalingStrategy>,
    pub min_replicas: Option<i32>,
    pub max_replicas: Option<i32>,
    pub metrics: Option<Vec<ResolverScalingMetric>>,
    pub concurrency_control: Option<ResolverConcurrencyControlConfig>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct ResolverFunctionSpec {
    pub name: String,
    pub image: String,
    pub command: Option<Vec<String>>,
    pub env: Option<HashMap<String, String>>,
    pub timeout_ms: Option<i32>,
    pub concurrency: Option<i32>,
    pub queue_size: Option<i32>,
    pub max_retries: Option<i32>,
    pub endpoint_url: Option<String>,
    pub execution_mode: Option<ExecutionMode>,
    pub runtime_mode: Option<RuntimeMode>,
    pub runtime_command: Option<String>,
    pub scaling_config: Option<ResolverScalingConfig>,
}

impl ResolverFunctionSpec {
    pub fn new(name: &str, image: &str) -> Self {
        Self {
            name: name.to_string(),
            image: image.to_string(),
            command: None,
            env: None,
            timeout_ms: None,
            concurrency: None,
            queue_size: None,
            max_retries: None,
            endpoint_url: None,
            execution_mode: None,
            runtime_mode: None,
            runtime_command: None,
            scaling_config: None,
        }
    }
}

#[derive(Debug, Clone)]
pub struct FunctionSpecResolver {
    defaults: FunctionDefaults,
}

impl FunctionSpecResolver {
    pub fn new(defaults: FunctionDefaults) -> Self {
        Self { defaults }
    }

    pub fn resolve(&self, spec: ResolverFunctionSpec) -> ResolverFunctionSpec {
        let mode = spec
            .execution_mode
            .clone()
            .unwrap_or(ExecutionMode::Deployment);
        let scaling = self.resolve_scaling_config(spec.scaling_config.clone(), mode.clone());
        ResolverFunctionSpec {
            name: spec.name,
            image: spec.image,
            command: Some(spec.command.unwrap_or_default()),
            env: Some(spec.env.unwrap_or_default()),
            timeout_ms: Some(spec.timeout_ms.unwrap_or(self.defaults.timeout_ms)),
            concurrency: Some(spec.concurrency.unwrap_or(self.defaults.concurrency)),
            queue_size: Some(spec.queue_size.unwrap_or(self.defaults.queue_size)),
            max_retries: Some(spec.max_retries.unwrap_or(self.defaults.max_retries)),
            endpoint_url: spec.endpoint_url,
            execution_mode: Some(mode),
            runtime_mode: Some(spec.runtime_mode.unwrap_or(RuntimeMode::Http)),
            runtime_command: spec.runtime_command,
            scaling_config: scaling,
        }
    }

    fn resolve_scaling_config(
        &self,
        config: Option<ResolverScalingConfig>,
        mode: ExecutionMode,
    ) -> Option<ResolverScalingConfig> {
        if mode != ExecutionMode::Deployment {
            return config;
        }

        let Some(config) = config else {
            return Some(ResolverScalingConfig {
                strategy: Some(ScalingStrategy::Internal),
                min_replicas: Some(1),
                max_replicas: Some(10),
                metrics: Some(vec![ResolverScalingMetric {
                    metric_type: "queue_depth".to_string(),
                    target: "5".to_string(),
                    name: None,
                }]),
                concurrency_control: self.normalize_concurrency_control(None),
            });
        };

        let metrics = config.metrics.clone().filter(|values| !values.is_empty());
        Some(ResolverScalingConfig {
            strategy: Some(config.strategy.unwrap_or(ScalingStrategy::Internal)),
            min_replicas: Some(config.min_replicas.unwrap_or(1)),
            max_replicas: Some(config.max_replicas.unwrap_or(10)),
            metrics: Some(metrics.unwrap_or_else(|| {
                vec![ResolverScalingMetric {
                    metric_type: "queue_depth".to_string(),
                    target: "5".to_string(),
                    name: None,
                }]
            })),
            concurrency_control: self.normalize_concurrency_control(config.concurrency_control),
        })
    }

    fn normalize_concurrency_control(
        &self,
        config: Option<ResolverConcurrencyControlConfig>,
    ) -> Option<ResolverConcurrencyControlConfig> {
        const DEFAULT_TARGET_PER_POD: i32 = 2;
        const DEFAULT_MIN_TARGET_PER_POD: i32 = 1;
        const DEFAULT_MAX_TARGET_PER_POD: i32 = 8;
        const DEFAULT_UPSCALE_COOLDOWN_MS: u64 = 30_000;
        const DEFAULT_DOWNSCALE_COOLDOWN_MS: u64 = 60_000;
        const DEFAULT_HIGH_LOAD_THRESHOLD: f64 = 0.85;
        const DEFAULT_LOW_LOAD_THRESHOLD: f64 = 0.35;

        let Some(config) = config else {
            return Some(ResolverConcurrencyControlConfig {
                mode: Some(ConcurrencyControlMode::Fixed),
                target_in_flight_per_pod: None,
                min_target_in_flight_per_pod: None,
                max_target_in_flight_per_pod: None,
                upscale_cooldown_ms: None,
                downscale_cooldown_ms: None,
                high_load_threshold: None,
                low_load_threshold: None,
            });
        };

        let Some(mode) = config.mode.clone() else {
            return Some(ResolverConcurrencyControlConfig {
                mode: Some(ConcurrencyControlMode::Fixed),
                target_in_flight_per_pod: None,
                min_target_in_flight_per_pod: None,
                max_target_in_flight_per_pod: None,
                upscale_cooldown_ms: None,
                downscale_cooldown_ms: None,
                high_load_threshold: None,
                low_load_threshold: None,
            });
        };
        if mode == ConcurrencyControlMode::Fixed {
            return Some(ResolverConcurrencyControlConfig {
                mode: Some(ConcurrencyControlMode::Fixed),
                target_in_flight_per_pod: None,
                min_target_in_flight_per_pod: None,
                max_target_in_flight_per_pod: None,
                upscale_cooldown_ms: None,
                downscale_cooldown_ms: None,
                high_load_threshold: None,
                low_load_threshold: None,
            });
        }

        let mut min = config
            .min_target_in_flight_per_pod
            .unwrap_or(DEFAULT_MIN_TARGET_PER_POD)
            .max(1);
        let max = config
            .max_target_in_flight_per_pod
            .unwrap_or(DEFAULT_MAX_TARGET_PER_POD)
            .max(1);
        if min > max {
            min = max;
        }

        let target = config
            .target_in_flight_per_pod
            .unwrap_or(DEFAULT_TARGET_PER_POD)
            .clamp(min, max);

        Some(ResolverConcurrencyControlConfig {
            mode: Some(mode),
            target_in_flight_per_pod: Some(target),
            min_target_in_flight_per_pod: Some(min),
            max_target_in_flight_per_pod: Some(max),
            upscale_cooldown_ms: Some(
                config
                    .upscale_cooldown_ms
                    .unwrap_or(DEFAULT_UPSCALE_COOLDOWN_MS),
            ),
            downscale_cooldown_ms: Some(
                config
                    .downscale_cooldown_ms
                    .unwrap_or(DEFAULT_DOWNSCALE_COOLDOWN_MS),
            ),
            high_load_threshold: Some(
                config
                    .high_load_threshold
                    .unwrap_or(DEFAULT_HIGH_LOAD_THRESHOLD),
            ),
            low_load_threshold: Some(
                config
                    .low_load_threshold
                    .unwrap_or(DEFAULT_LOW_LOAD_THRESHOLD),
            ),
        })
    }
}

pub trait KubernetesResourceManager: Send + Sync {
    fn provision(&self, spec: &ResolverFunctionSpec) -> String;
    fn deprovision(&self, name: &str);
    fn set_replicas(&self, name: &str, replicas: i32);
}

pub trait FunctionRegistrationListener: Send + Sync {
    fn on_register(&self, spec: &ResolverFunctionSpec);
    fn on_remove(&self, name: &str);
}

pub trait ResolverImageValidator: Send + Sync {
    fn validate(&self, spec: &ResolverFunctionSpec);
}

#[derive(Debug, Default)]
pub struct NoOpResolverImageValidator;

impl ResolverImageValidator for NoOpResolverImageValidator {
    fn validate(&self, _spec: &ResolverFunctionSpec) {}
}

#[derive(Debug, Default)]
pub struct FunctionRegistry {
    functions: Mutex<HashMap<String, ResolverFunctionSpec>>,
}

impl FunctionRegistry {
    pub fn new() -> Self {
        Self {
            functions: Mutex::new(HashMap::new()),
        }
    }

    pub fn get(&self, name: &str) -> Option<ResolverFunctionSpec> {
        self.functions
            .lock()
            .expect("functions lock")
            .get(name)
            .cloned()
    }

    pub fn insert(&self, name: String, spec: ResolverFunctionSpec) {
        self.functions
            .lock()
            .expect("functions lock")
            .insert(name, spec);
    }

    pub fn remove(&self, name: &str) -> Option<ResolverFunctionSpec> {
        self.functions.lock().expect("functions lock").remove(name)
    }

    pub fn list(&self) -> Vec<ResolverFunctionSpec> {
        self.functions
            .lock()
            .expect("functions lock")
            .values()
            .cloned()
            .collect()
    }
}

pub struct FunctionService {
    registry: Arc<FunctionRegistry>,
    resolver: FunctionSpecResolver,
    resource_manager: Option<Arc<dyn KubernetesResourceManager>>,
    image_validator: Arc<dyn ResolverImageValidator>,
    listeners: Vec<Arc<dyn FunctionRegistrationListener>>,
    function_locks: Mutex<HashMap<String, Arc<Mutex<()>>>>,
}

impl FunctionService {
    pub fn new(
        registry: FunctionRegistry,
        defaults: FunctionDefaults,
        resource_manager: Option<Arc<dyn KubernetesResourceManager>>,
        image_validator: Arc<dyn ResolverImageValidator>,
        listeners: Vec<Arc<dyn FunctionRegistrationListener>>,
    ) -> Self {
        Self {
            registry: Arc::new(registry),
            resolver: FunctionSpecResolver::new(defaults),
            resource_manager,
            image_validator,
            listeners,
            function_locks: Mutex::new(HashMap::new()),
        }
    }

    pub fn register(&self, spec: ResolverFunctionSpec) -> Option<ResolverFunctionSpec> {
        let name = spec.name.clone();
        let name_for_lock = name.clone();
        self.with_function_lock(&name_for_lock, move || {
            if self.registry.get(&name).is_some() {
                return None;
            }

            let mut resolved = self.resolver.resolve(spec);
            self.image_validator.validate(&resolved);

            if resolved.execution_mode == Some(ExecutionMode::Deployment) {
                if let Some(resource_manager) = &self.resource_manager {
                    resolved.endpoint_url = Some(resource_manager.provision(&resolved));
                }
            }

            self.registry.insert(name.clone(), resolved.clone());
            for listener in &self.listeners {
                listener.on_register(&resolved);
            }
            Some(resolved)
        })
    }

    pub fn remove(&self, name: &str) -> Option<ResolverFunctionSpec> {
        self.with_function_lock(name, || {
            let removed = self.registry.remove(name)?;
            if removed.execution_mode == Some(ExecutionMode::Deployment) {
                if let Some(resource_manager) = &self.resource_manager {
                    resource_manager.deprovision(name);
                }
            }
            for listener in &self.listeners {
                listener.on_remove(name);
            }
            Some(removed)
        })
    }

    pub fn set_replicas(&self, name: &str, replicas: i32) -> Option<i32> {
        self.with_function_lock(name, || {
            let current = self.registry.get(name)?;
            if current.execution_mode != Some(ExecutionMode::Deployment) {
                panic!("Only DEPLOYMENT mode supports manual replica updates");
            }
            if let Some(resource_manager) = &self.resource_manager {
                resource_manager.set_replicas(name, replicas);
            }
            Some(replicas)
        })
    }

    pub fn list(&self) -> Vec<ResolverFunctionSpec> {
        self.registry.list()
    }

    pub fn get(&self, name: &str) -> Option<ResolverFunctionSpec> {
        self.registry.get(name)
    }

    pub fn function_lock_count(&self) -> usize {
        self.function_locks.lock().expect("function locks").len()
    }

    fn with_function_lock<T>(&self, name: &str, action: impl FnOnce() -> T) -> T {
        let lock = {
            let mut locks = self.function_locks.lock().expect("function locks");
            locks
                .entry(name.to_string())
                .or_insert_with(|| Arc::new(Mutex::new(())))
                .clone()
        };
        let _guard = lock.lock().expect("per-function lock");
        let result = action();
        drop(_guard);

        let mut locks = self.function_locks.lock().expect("function locks");
        if let Some(existing) = locks.get(name) {
            if Arc::ptr_eq(existing, &lock) && Arc::strong_count(existing) == 2 {
                locks.remove(name);
            }
        }
        result
    }
}
