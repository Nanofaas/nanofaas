use std::collections::HashMap;
use std::time::Duration;

/// Result of an atomic acquire-or-get operation on the idempotency store.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum AcquireResult {
    /// Caller acquired a new pending claim; holds the claim token.
    Claimed(String),
    /// A stable (non-pending) entry already exists for this key.
    Existing(String),
    /// A pending claim exists from a concurrent caller; caller should retry.
    Pending,
    /// No entry exists for this key (used by claimIfMatches when key is gone).
    Missing,
}

#[derive(Debug, Clone)]
struct StoredKey {
    execution_id: String,
    stored_at_millis: u64,
    pending: bool,
}

impl StoredKey {
    fn pending(claim_token: String, stored_at_millis: u64) -> Self {
        Self {
            execution_id: claim_token,
            stored_at_millis,
            pending: true,
        }
    }

    fn published(execution_id: String, stored_at_millis: u64) -> Self {
        Self {
            execution_id,
            stored_at_millis,
            pending: false,
        }
    }
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

    /// Returns the stored execution ID for the given key, or None if absent/expired/pending.
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
        // Pending entries are not yet stable — treat as absent.
        if stored.pending {
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
            StoredKey::published(execution_id.to_string(), now_millis),
        );
    }

    /// Stores `execution_id` if no live entry exists; returns the existing execution ID if one does.
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
                } else if stored.pending {
                    // Treat pending as absent — replace it with the new stable entry.
                    self.put_with_timestamp(function_name, key, execution_id, now_millis);
                    None
                } else {
                    Some(stored.execution_id)
                }
            }
        }
    }

    /// Atomically acquires a new pending claim or returns an existing entry.
    ///
    /// Returns:
    /// - `Claimed(token)` — caller owns the slot; must call `publish_claim` or `abandon_claim`.
    /// - `Existing(id)` — a stable entry already exists; caller should reuse it.
    /// - `Pending` — another caller holds a pending claim; caller should spin/retry.
    pub fn acquire_or_get(&mut self, function_name: &str, key: &str, now_millis: u64) -> AcquireResult {
        let composed = compose(function_name, key);
        match self.keys.get(&composed).cloned() {
            None => {
                let token = pending_token(now_millis);
                self.keys.insert(composed, StoredKey::pending(token.clone(), now_millis));
                AcquireResult::Claimed(token)
            }
            Some(stored) if is_expired(stored.stored_at_millis, self.ttl, now_millis) => {
                let token = pending_token(now_millis);
                self.keys.insert(composed, StoredKey::pending(token.clone(), now_millis));
                AcquireResult::Claimed(token)
            }
            Some(stored) if stored.pending => AcquireResult::Pending,
            Some(stored) => AcquireResult::Existing(stored.execution_id),
        }
    }

    /// Acquires a pending claim only if the current entry matches `expected_execution_id`.
    ///
    /// Used to reclaim a stale idempotency mapping that points to an evicted execution.
    pub fn claim_if_matches(
        &mut self,
        function_name: &str,
        key: &str,
        expected_execution_id: &str,
        now_millis: u64,
    ) -> AcquireResult {
        let composed = compose(function_name, key);
        match self.keys.get(&composed).cloned() {
            None => AcquireResult::Missing,
            Some(stored) if is_expired(stored.stored_at_millis, self.ttl, now_millis) => {
                let token = pending_token(now_millis);
                self.keys.insert(composed, StoredKey::pending(token.clone(), now_millis));
                AcquireResult::Claimed(token)
            }
            Some(stored) if stored.pending => AcquireResult::Pending,
            Some(stored) if stored.execution_id != expected_execution_id => {
                AcquireResult::Existing(stored.execution_id)
            }
            _ => {
                let token = pending_token(now_millis);
                self.keys.insert(composed, StoredKey::pending(token.clone(), now_millis));
                AcquireResult::Claimed(token)
            }
        }
    }

    /// Publishes a stable execution ID for a previously acquired pending claim.
    ///
    /// Panics if the claim token does not match the current pending entry (indicates a bug).
    pub fn publish_claim(
        &mut self,
        function_name: &str,
        key: &str,
        claim_token: &str,
        execution_id: &str,
        now_millis: u64,
    ) {
        let composed = compose(function_name, key);
        let existing = self.keys.get(&composed);
        match existing {
            Some(stored) if stored.pending && stored.execution_id == claim_token => {
                self.keys.insert(composed, StoredKey::published(execution_id.to_string(), now_millis));
            }
            _ => {
                panic!("Missing or mismatched idempotency claim for {composed}");
            }
        }
    }

    /// Abandons a pending claim without publishing, removing the entry so the next caller can retry.
    pub fn abandon_claim(&mut self, function_name: &str, key: &str, claim_token: &str) {
        let composed = compose(function_name, key);
        if let Some(stored) = self.keys.get(&composed) {
            if stored.pending && stored.execution_id == claim_token {
                self.keys.remove(&composed);
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

fn pending_token(now_millis: u64) -> String {
    format!("pending:{now_millis}")
}
