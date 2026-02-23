#![allow(non_snake_case)]

use control_plane_rust::dispatch::{
    PoolHttpClient, PoolHttpError, PoolHttpResponse, PoolInvocationDispatcher, PoolInvocationTask,
};
use control_plane_rust::model::{ExecutionMode, FunctionSpec, RuntimeMode};
use serde_json::json;
use std::collections::HashMap;

struct MockClient {
    response: PoolHttpResponse,
}

impl PoolHttpClient for MockClient {
    fn invoke(
        &self,
        _endpoint: &str,
        _payload: &serde_json::Value,
        _timeout_ms: u64,
    ) -> Result<PoolHttpResponse, PoolHttpError> {
        Ok(self.response.clone())
    }
}

fn task() -> PoolInvocationTask {
    PoolInvocationTask {
        execution_id: "exec-cs".to_string(),
        function_name: "test-fn".to_string(),
        function_spec: FunctionSpec {
            name: "test-fn".to_string(),
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
            timeout_millis: Some(5_000),
            url: Some("http://mock/invoke".to_string()),
            image_pull_secrets: None,
            runtime_command: None,
        },
        payload: json!("payload"),
    }
}

#[test]
fn dispatch_extractsColdStartHeaders() {
    let mut headers = HashMap::new();
    headers.insert("X-Cold-Start".to_string(), "true".to_string());
    headers.insert("X-Init-Duration-Ms".to_string(), "250".to_string());
    let dispatcher = PoolInvocationDispatcher::new(MockClient {
        response: PoolHttpResponse {
            status_code: 200,
            body: "{\"message\":\"ok\"}".to_string(),
            content_type: "application/json".to_string(),
            headers: headers.clone(),
        },
    });

    let result = dispatcher.dispatch(&task());

    assert!(result.result.success);
    assert!(result.cold_start);
    assert_eq!(Some(250), result.init_duration_ms);
}

#[test]
fn dispatch_warmStart_noColdStartHeaders() {
    let dispatcher = PoolInvocationDispatcher::new(MockClient {
        response: PoolHttpResponse {
            status_code: 200,
            body: "{\"message\":\"ok\"}".to_string(),
            content_type: "application/json".to_string(),
            headers: HashMap::new(),
        },
    });

    let result = dispatcher.dispatch(&task());

    assert!(result.result.success);
    assert!(!result.cold_start);
    assert_eq!(None, result.init_duration_ms);
}
