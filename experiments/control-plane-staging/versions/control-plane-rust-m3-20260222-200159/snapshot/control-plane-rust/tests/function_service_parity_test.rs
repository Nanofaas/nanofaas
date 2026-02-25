#![allow(non_snake_case)]

use control_plane_rust::model::ExecutionMode;
use control_plane_rust::registry::{
    FunctionDefaults, FunctionRegistrationListener, FunctionRegistry, FunctionService,
    KubernetesResourceManager, NoOpResolverImageValidator, ResolverFunctionSpec,
    ResolverImageValidator,
};
use std::sync::{Arc, Mutex};

#[derive(Default)]
struct TestResourceManager {
    provision_calls: Mutex<Vec<String>>,
    deprovision_calls: Mutex<Vec<String>>,
    set_replicas_calls: Mutex<Vec<(String, i32)>>,
    provision_endpoint: String,
}

impl TestResourceManager {
    fn with_endpoint(endpoint: &str) -> Self {
        Self {
            provision_calls: Mutex::new(vec![]),
            deprovision_calls: Mutex::new(vec![]),
            set_replicas_calls: Mutex::new(vec![]),
            provision_endpoint: endpoint.to_string(),
        }
    }
}

impl KubernetesResourceManager for TestResourceManager {
    fn provision(&self, spec: &ResolverFunctionSpec) -> String {
        self.provision_calls
            .lock()
            .expect("provision calls")
            .push(spec.name.clone());
        self.provision_endpoint.clone()
    }

    fn deprovision(&self, name: &str) {
        self.deprovision_calls
            .lock()
            .expect("deprovision calls")
            .push(name.to_string());
    }

    fn set_replicas(&self, name: &str, replicas: i32) {
        self.set_replicas_calls
            .lock()
            .expect("set replicas calls")
            .push((name.to_string(), replicas));
    }
}

#[derive(Default)]
struct TestListener {
    on_register_calls: Mutex<Vec<String>>,
    on_remove_calls: Mutex<Vec<String>>,
}

impl FunctionRegistrationListener for TestListener {
    fn on_register(&self, spec: &ResolverFunctionSpec) {
        self.on_register_calls
            .lock()
            .expect("on register")
            .push(spec.name.clone());
    }

    fn on_remove(&self, name: &str) {
        self.on_remove_calls
            .lock()
            .expect("on remove")
            .push(name.to_string());
    }
}

#[derive(Default)]
struct RecordingImageValidator {
    calls: Mutex<Vec<String>>,
}

impl ResolverImageValidator for RecordingImageValidator {
    fn validate(&self, spec: &ResolverFunctionSpec) {
        self.calls
            .lock()
            .expect("validator calls")
            .push(spec.name.clone());
    }
}

fn spec(name: &str, image: &str, mode: ExecutionMode) -> ResolverFunctionSpec {
    let mut spec = ResolverFunctionSpec::new(name, image);
    spec.execution_mode = Some(mode);
    spec
}

#[test]
fn register_deploymentMode_provisionsAndSetsUrl() {
    let registry = FunctionRegistry::new();
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resource_manager = Arc::new(TestResourceManager::with_endpoint("http://fn-svc:8080"));
    let listener = Arc::new(TestListener::default());
    let validator = Arc::new(RecordingImageValidator::default());
    let service = FunctionService::new(
        registry,
        defaults,
        Some(resource_manager.clone()),
        validator.clone(),
        vec![listener.clone()],
    );

    let result = service.register(spec("fn", "img:latest", ExecutionMode::Deployment));

    assert!(result.is_some());
    assert_eq!(
        Some("http://fn-svc:8080".to_string()),
        result.expect("result").endpoint_url
    );
    assert_eq!(1, validator.calls.lock().expect("calls").len());
    assert_eq!(
        1,
        resource_manager
            .provision_calls
            .lock()
            .expect("calls")
            .len()
    );
    assert_eq!(1, listener.on_register_calls.lock().expect("calls").len());
}

#[test]
fn register_duplicate_returnsEmptyAndDoesNotDeprovision() {
    let registry = FunctionRegistry::new();
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resource_manager = Arc::new(TestResourceManager::with_endpoint("http://fn-svc:8080"));
    let validator = Arc::new(RecordingImageValidator::default());
    let listener = Arc::new(TestListener::default());
    let service = FunctionService::new(
        registry,
        defaults,
        Some(resource_manager.clone()),
        validator,
        vec![listener.clone()],
    );

    service.register(spec("fn", "img:latest", ExecutionMode::Deployment));
    let dup = service.register(spec("fn", "img:latest", ExecutionMode::Deployment));

    assert!(dup.is_none());
    assert_eq!(
        1,
        resource_manager
            .provision_calls
            .lock()
            .expect("calls")
            .len()
    );
    assert_eq!(
        0,
        resource_manager
            .deprovision_calls
            .lock()
            .expect("calls")
            .len()
    );
    assert_eq!(1, listener.on_register_calls.lock().expect("calls").len());
}

#[test]
fn register_localMode_noProvisioning() {
    let registry = FunctionRegistry::new();
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resource_manager = Arc::new(TestResourceManager::with_endpoint("http://fn-svc:8080"));
    let validator = Arc::new(RecordingImageValidator::default());
    let service = FunctionService::new(
        registry,
        defaults,
        Some(resource_manager.clone()),
        validator.clone(),
        vec![],
    );

    let result = service.register(spec("fn", "img:latest", ExecutionMode::Local));

    assert!(result.is_some());
    assert_eq!(1, validator.calls.lock().expect("calls").len());
    assert_eq!(
        0,
        resource_manager
            .provision_calls
            .lock()
            .expect("calls")
            .len()
    );
}

#[test]
fn register_duplicateSkipsImageValidationOnConflict() {
    let registry = FunctionRegistry::new();
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resource_manager = Arc::new(TestResourceManager::with_endpoint("http://fn-svc:8080"));
    let validator = Arc::new(RecordingImageValidator::default());
    let service = FunctionService::new(
        registry,
        defaults,
        Some(resource_manager),
        validator.clone(),
        vec![],
    );

    service.register(spec("fn", "img:latest", ExecutionMode::Deployment));
    service.register(spec("fn", "img:latest", ExecutionMode::Deployment));

    assert_eq!(1, validator.calls.lock().expect("calls").len());
}

#[test]
fn perFunctionLocksAreCleanedUpAfterOperations() {
    let registry = FunctionRegistry::new();
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let service = FunctionService::new(
        registry,
        defaults,
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );

    service.register(spec("fn", "img:latest", ExecutionMode::Local));
    service.remove("fn");
    service.remove("ghost");

    assert_eq!(0, service.function_lock_count());
}

#[test]
fn remove_existing_deprovisions() {
    let registry = FunctionRegistry::new();
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resource_manager = Arc::new(TestResourceManager::with_endpoint("http://fn-svc:8080"));
    let listener = Arc::new(TestListener::default());
    let service = FunctionService::new(
        registry,
        defaults,
        Some(resource_manager.clone()),
        Arc::new(NoOpResolverImageValidator),
        vec![listener.clone()],
    );
    service.register(spec("fn", "img:latest", ExecutionMode::Deployment));

    let removed = service.remove("fn");
    assert!(removed.is_some());
    assert_eq!(
        vec!["fn".to_string()],
        *resource_manager.deprovision_calls.lock().expect("calls")
    );
    assert_eq!(
        vec!["fn".to_string()],
        *listener.on_remove_calls.lock().expect("calls")
    );
}

#[test]
fn remove_nonexistent_returnsEmpty() {
    let service = FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );

    assert!(service.remove("ghost").is_none());
}

#[test]
fn setReplicas_success() {
    let registry = FunctionRegistry::new();
    let defaults = FunctionDefaults::new(30_000, 4, 100, 3);
    let resource_manager = Arc::new(TestResourceManager::with_endpoint("http://fn-svc:8080"));
    let service = FunctionService::new(
        registry,
        defaults,
        Some(resource_manager.clone()),
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );
    service.register(spec("fn", "img:latest", ExecutionMode::Deployment));

    let result = service.set_replicas("fn", 3);
    assert_eq!(Some(3), result);
    assert_eq!(
        vec![("fn".to_string(), 3)],
        *resource_manager.set_replicas_calls.lock().expect("calls")
    );
}

#[test]
fn setReplicas_nonDeployment_returnsNone() {
    let service = FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );
    service.register(spec("fn", "img:latest", ExecutionMode::Local));

    let result = service.set_replicas("fn", 2);
    assert!(result.is_none());
}

#[test]
fn setReplicas_notFound_returnsEmpty() {
    let service = FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );
    assert_eq!(None, service.set_replicas("ghost", 2));
}

#[test]
fn list_returnsAllFunctions() {
    let service = FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );
    service.register(spec("fn", "img:latest", ExecutionMode::Local));
    assert_eq!(1, service.list().len());
}

#[test]
fn get_existing_returnsSpec() {
    let service = FunctionService::new(
        FunctionRegistry::new(),
        FunctionDefaults::new(30_000, 4, 100, 3),
        None,
        Arc::new(NoOpResolverImageValidator),
        vec![],
    );
    service.register(spec("fn", "img:latest", ExecutionMode::Local));
    assert!(service.get("fn").is_some());
}
