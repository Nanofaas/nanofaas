#![allow(non_snake_case)]

use control_plane_rust::dispatch::{
    PoolHttpClient, PoolHttpError, PoolHttpResponse, PoolInvocationDispatcher, PoolInvocationTask,
};
use control_plane_rust::model::{ExecutionMode, FunctionSpec, RuntimeMode};
use serde_json::json;
use std::collections::HashMap;

enum MockBehavior {
    Timeout,
    ServerError,
}

struct MockClient {
    behavior: MockBehavior,
}

impl PoolHttpClient for MockClient {
    fn invoke(
        &self,
        _endpoint: &str,
        _payload: &serde_json::Value,
        _timeout_ms: u64,
    ) -> Result<PoolHttpResponse, PoolHttpError> {
        match self.behavior {
            MockBehavior::Timeout => Err(PoolHttpError::Timeout),
            MockBehavior::ServerError => Ok(PoolHttpResponse {
                status_code: 500,
                body: "Internal Server Error".to_string(),
                content_type: "text/plain".to_string(),
                headers: HashMap::new(),
            }),
        }
    }
}

fn pool_spec(endpoint: Option<&str>, timeout_millis: u64) -> FunctionSpec {
    FunctionSpec {
        name: "pool-fn".to_string(),
        image: Some("image".to_string()),
        execution_mode: ExecutionMode::Pool,
        runtime_mode: RuntimeMode::Http,
        concurrency: Some(1),
        queue_size: Some(10),
        max_retries: Some(3),
        scaling_config: None,
        commands: None,
        env: Some(HashMap::new()),
        resources: None,
        timeout_millis: Some(timeout_millis),
        url: endpoint.map(|value| value.to_string()),
        image_pull_secrets: None,
        runtime_command: None,
    }
}

#[test]
fn dispatch_slowServer_returnsPoolTimeout() {
    let dispatcher = PoolInvocationDispatcher::new(MockClient {
        behavior: MockBehavior::Timeout,
    });
    let task = PoolInvocationTask {
        execution_id: "exec-timeout".to_string(),
        function_name: "pool-fn".to_string(),
        function_spec: pool_spec(Some("http://mock/invoke"), 200),
        payload: json!("payload"),
    };

    let result = dispatcher.dispatch(&task);

    assert!(!result.result.success);
    let error = result.result.error.expect("error");
    assert_eq!("POOL_TIMEOUT", error.code);
    assert!(error.message.contains("200ms"));
}

#[test]
fn dispatch_missingEndpoint_returnsPoolEndpointMissing() {
    let dispatcher = PoolInvocationDispatcher::new(MockClient {
        behavior: MockBehavior::ServerError,
    });
    let task = PoolInvocationTask {
        execution_id: "exec-no-ep".to_string(),
        function_name: "pool-fn".to_string(),
        function_spec: pool_spec(None, 1_000),
        payload: json!("payload"),
    };

    let result = dispatcher.dispatch(&task);

    assert!(!result.result.success);
    assert_eq!(
        "POOL_ENDPOINT_MISSING",
        result.result.error.expect("error").code
    );
}

#[test]
fn dispatch_serverError_returnsPoolError() {
    let dispatcher = PoolInvocationDispatcher::new(MockClient {
        behavior: MockBehavior::ServerError,
    });
    let task = PoolInvocationTask {
        execution_id: "exec-err".to_string(),
        function_name: "pool-fn".to_string(),
        function_spec: pool_spec(Some("http://mock/invoke"), 1_000),
        payload: json!("payload"),
    };

    let result = dispatcher.dispatch(&task);

    assert!(!result.result.success);
    assert_eq!("POOL_ERROR", result.result.error.expect("error").code);
}
