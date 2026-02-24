use serde::{Deserialize, Serialize};
use serde_json::Value;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ExecutionMode {
    Deployment,
    Local,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum RuntimeMode {
    Http,
    Stdio,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct FunctionSpec {
    pub name: String,
    pub image: String,
    #[serde(rename = "executionMode")]
    pub execution_mode: ExecutionMode,
    #[serde(rename = "runtimeMode")]
    pub runtime_mode: RuntimeMode,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvocationRequest {
    pub input: Value,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InvocationResponse {
    #[serde(rename = "executionId")]
    pub execution_id: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub output: Option<Value>,
}
