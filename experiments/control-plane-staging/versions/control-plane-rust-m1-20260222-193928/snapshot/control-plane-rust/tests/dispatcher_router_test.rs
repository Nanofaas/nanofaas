use control_plane_rust::dispatch::{DispatcherRouter, LocalDispatcher, PoolDispatcher};
use control_plane_rust::model::{ExecutionMode, FunctionSpec, RuntimeMode};
use serde_json::json;

#[test]
fn dispatcher_router_uses_local_dispatcher_for_local_mode() {
    let router = DispatcherRouter::new(Box::new(LocalDispatcher), Box::new(PoolDispatcher));
    let spec = FunctionSpec {
        name: "fn-local".to_string(),
        image: "local".to_string(),
        execution_mode: ExecutionMode::Local,
        runtime_mode: RuntimeMode::Http,
    };

    let result = router.dispatch(&spec, &json!({"x": 1}));
    assert_eq!(result.dispatcher, "local");
    assert_eq!(result.status, "SUCCESS");
}

#[test]
fn dispatcher_router_uses_pool_dispatcher_for_deployment_mode() {
    let router = DispatcherRouter::new(Box::new(LocalDispatcher), Box::new(PoolDispatcher));
    let spec = FunctionSpec {
        name: "fn-deploy".to_string(),
        image: "image".to_string(),
        execution_mode: ExecutionMode::Deployment,
        runtime_mode: RuntimeMode::Http,
    };

    let result = router.dispatch(&spec, &json!({"x": 2}));
    assert_eq!(result.dispatcher, "pool");
    assert_eq!(result.status, "SUCCESS");
}
