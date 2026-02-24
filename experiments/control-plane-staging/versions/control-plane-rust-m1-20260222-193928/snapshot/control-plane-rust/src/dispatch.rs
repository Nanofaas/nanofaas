use crate::model::{ExecutionMode, FunctionSpec};
use serde_json::{json, Value};
use std::sync::Arc;

#[derive(Debug, Clone)]
pub struct DispatchResult {
    pub status: String,
    pub output: Option<Value>,
    pub dispatcher: String,
}

pub trait Dispatcher: Send + Sync {
    fn dispatch(&self, function: &FunctionSpec, payload: &Value) -> DispatchResult;
}

#[derive(Debug, Default)]
pub struct LocalDispatcher;

impl Dispatcher for LocalDispatcher {
    fn dispatch(&self, function: &FunctionSpec, payload: &Value) -> DispatchResult {
        DispatchResult {
            status: "SUCCESS".to_string(),
            output: Some(json!({
                "dispatcher": "local",
                "function": function.name,
                "echo": payload,
            })),
            dispatcher: "local".to_string(),
        }
    }
}

#[derive(Debug, Default)]
pub struct PoolDispatcher;

impl Dispatcher for PoolDispatcher {
    fn dispatch(&self, function: &FunctionSpec, payload: &Value) -> DispatchResult {
        DispatchResult {
            status: "SUCCESS".to_string(),
            output: Some(json!({
                "dispatcher": "pool",
                "function": function.name,
                "echo": payload,
            })),
            dispatcher: "pool".to_string(),
        }
    }
}

pub struct DispatcherRouter {
    local: Arc<dyn Dispatcher>,
    pool: Arc<dyn Dispatcher>,
}

impl DispatcherRouter {
    pub fn new(local: Box<dyn Dispatcher>, pool: Box<dyn Dispatcher>) -> Self {
        Self {
            local: Arc::from(local),
            pool: Arc::from(pool),
        }
    }

    pub fn dispatch(&self, function: &FunctionSpec, payload: &Value) -> DispatchResult {
        match function.execution_mode {
            ExecutionMode::Local => self.local.dispatch(function, payload),
            ExecutionMode::Deployment => self.pool.dispatch(function, payload),
        }
    }
}

impl Clone for DispatcherRouter {
    fn clone(&self) -> Self {
        Self {
            local: Arc::clone(&self.local),
            pool: Arc::clone(&self.pool),
        }
    }
}
