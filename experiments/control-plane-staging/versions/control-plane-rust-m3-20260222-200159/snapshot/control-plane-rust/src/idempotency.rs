use std::collections::HashMap;
use std::time::Duration;

#[derive(Debug, Clone)]
struct StoredKey {
    execution_id: String,
    stored_at_millis: u64,
}

#[derive(Debug, Clone)]
pub struct IdempotencyStore {
    ttl: Duration,
    keys: HashMap<String, StoredKey>,
}

impl IdempotencyStore {
    pub fn new_with_ttl(ttl: Duration) -> Self {
        Self {
            ttl,
            keys: HashMap::new(),
        }
    }

    pub fn get_execution_id(
        &mut self,
        function_name: &str,
        key: &str,
        now_millis: u64,
    ) -> Option<String> {
        let composed = compose(function_name, key);
        let stored = self.keys.get(&composed)?.clone();
        if is_expired(stored.stored_at_millis, self.ttl, now_millis) {
            self.keys.remove(&composed);
            return None;
        }
        Some(stored.execution_id)
    }

    pub fn put_with_timestamp(
        &mut self,
        function_name: &str,
        key: &str,
        execution_id: &str,
        now_millis: u64,
    ) {
        self.keys.insert(
            compose(function_name, key),
            StoredKey {
                execution_id: execution_id.to_string(),
                stored_at_millis: now_millis,
            },
        );
    }

    pub fn put_if_absent(
        &mut self,
        function_name: &str,
        key: &str,
        execution_id: &str,
        now_millis: u64,
    ) -> Option<String> {
        let composed = compose(function_name, key);
        match self.keys.get(&composed).cloned() {
            None => {
                self.put_with_timestamp(function_name, key, execution_id, now_millis);
                None
            }
            Some(stored) => {
                if is_expired(stored.stored_at_millis, self.ttl, now_millis) {
                    self.put_with_timestamp(function_name, key, execution_id, now_millis);
                    None
                } else {
                    Some(stored.execution_id)
                }
            }
        }
    }

    pub fn size(&self) -> usize {
        self.keys.len()
    }
}

fn compose(function_name: &str, key: &str) -> String {
    format!("{function_name}:{key}")
}

fn is_expired(stored_at_millis: u64, ttl: Duration, now_millis: u64) -> bool {
    stored_at_millis.saturating_add(ttl.as_millis() as u64) < now_millis
}
