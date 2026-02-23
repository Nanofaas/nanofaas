#![allow(non_snake_case)]

use control_plane_rust::dispatch::{
    PoolHttpClient, PoolHttpError, PoolHttpResponse, PoolInvocationDispatcher, PoolInvocationTask,
};
use control_plane_rust::model::{ExecutionMode, FunctionSpec, RuntimeMode};
use serde_json::json;
use std::collections::HashMap;
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::Arc;

struct MockClient {
    response: PoolHttpResponse,
    calls: Arc<AtomicUsize>,
}

impl PoolHttpClient for MockClient {
    fn invoke(
        &self,
        _endpoint: &str,
        _payload: &serde_json::Value,
        _timeout_ms: u64,
    ) -> Result<PoolHttpResponse, PoolHttpError> {
        self.calls.fetch_add(1, Ordering::SeqCst);
        Ok(self.response.clone())
    }
}

fn pool_spec(endpoint: &str, timeout_millis: u64) -> FunctionSpec {
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
        url: Some(endpoint.to_string()),
        image_pull_secrets: None,
        runtime_command: None,
    }
}

#[test]
fn poolDispatchCallsEndpoint() {
    let calls = Arc::new(AtomicUsize::new(0));
    let client = MockClient {
        response: PoolHttpResponse {
            status_code: 200,
            body: "{\"message\":\"ok\"}".to_string(),
            content_type: "application/json".to_string(),
            headers: HashMap::new(),
        },
        calls: calls.clone(),
    };
    let dispatcher = PoolInvocationDispatcher::new(client);
    let task = PoolInvocationTask {
        execution_id: "exec-pool".to_string(),
        function_name: "pool-fn".to_string(),
        function_spec: pool_spec("http://mock/invoke", 1_000),
        payload: json!("payload"),
    };

    let result = dispatcher.dispatch(&task);

    assert!(result.result.success);
    assert!(result.result.output.is_some());
    assert!(!result.cold_start);
    assert_eq!(1, calls.load(Ordering::SeqCst));
}

#[test]
fn poolDispatchHandlesTextPlain() {
    let client = MockClient {
        response: PoolHttpResponse {
            status_code: 200,
            body: "plain-output".to_string(),
            content_type: "text/plain".to_string(),
            headers: HashMap::new(),
        },
        calls: Arc::new(AtomicUsize::new(0)),
    };
    let dispatcher = PoolInvocationDispatcher::new(client);
    let task = PoolInvocationTask {
        execution_id: "exec-pool".to_string(),
        function_name: "pool-fn".to_string(),
        function_spec: pool_spec("http://mock/invoke", 1_000),
        payload: json!("payload"),
    };

    let result = dispatcher.dispatch(&task);

    assert!(result.result.success);
    assert_eq!(
        Some(serde_json::Value::String("plain-output".to_string())),
        result.result.output
    );
}
