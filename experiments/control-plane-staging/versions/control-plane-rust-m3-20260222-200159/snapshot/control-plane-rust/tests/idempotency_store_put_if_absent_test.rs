#![allow(non_snake_case)]

use control_plane_rust::idempotency::{AcquireResult, IdempotencyStore};
use std::time::Duration;

#[test]
fn put_if_absent_matches_java_semantics() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(100));
    assert_eq!(store.put_if_absent("fn", "k1", "exec-1", 0), None);
    assert_eq!(
        store.put_if_absent("fn", "k1", "exec-2", 10),
        Some("exec-1".to_string())
    );
    assert_eq!(store.put_if_absent("fn", "k1", "exec-3", 150), None);
    assert_eq!(
        store.get_execution_id("fn", "k1", 150),
        Some("exec-3".to_string())
    );
}

#[test]
fn putIfAbsent_newKey_returnsNull() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    let result = store.put_if_absent("fn", "key1", "exec-1", 0);
    assert_eq!(result, None);
    assert_eq!(store.get_execution_id("fn", "key1", 1), Some("exec-1".to_string()));
}

#[test]
fn putIfAbsent_existingKey_returnsExistingExecutionId() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    store.put_if_absent("fn", "key1", "exec-1", 0);
    let result = store.put_if_absent("fn", "key1", "exec-2", 1);

    assert_eq!(result, Some("exec-1".to_string()));
    assert_eq!(store.get_execution_id("fn", "key1", 2), Some("exec-1".to_string()));
}

#[test]
fn putIfAbsent_expiredKey_replacesAndReturnsNull() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(100));

    store.put_if_absent("fn", "key1", "exec-1", 0);
    assert_eq!(store.get_execution_id("fn", "key1", 50), Some("exec-1".to_string()));

    // 150ms later: expired
    let result = store.put_if_absent("fn", "key1", "exec-2", 150);
    assert_eq!(result, None);
    assert_eq!(store.get_execution_id("fn", "key1", 160), Some("exec-2".to_string()));
}

#[test]
fn putIfAbsent_differentFunctions_areIndependent() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    let r1 = store.put_if_absent("fn1", "key1", "exec-1", 0);
    let r2 = store.put_if_absent("fn2", "key1", "exec-2", 1);

    assert_eq!(r1, None);
    assert_eq!(r2, None);
    assert_eq!(store.get_execution_id("fn1", "key1", 2), Some("exec-1".to_string()));
    assert_eq!(store.get_execution_id("fn2", "key1", 2), Some("exec-2".to_string()));
}

// ── acquire_or_get / claim model tests ───────────────────────────────────────

#[test]
fn acquireOrGet_newKey_returnsClaimed() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));

    match store.acquire_or_get("fn", "key1", 0) {
        AcquireResult::Claimed(token) => assert!(!token.is_empty()),
        other => panic!("expected Claimed, got {:?}", other),
    }
}

#[test]
fn acquireOrGet_existingStableKey_returnsExisting() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));
    store.put_if_absent("fn", "key1", "exec-1", 0);

    match store.acquire_or_get("fn", "key1", 1) {
        AcquireResult::Existing(id) => assert_eq!(id, "exec-1"),
        other => panic!("expected Existing, got {:?}", other),
    }
}

#[test]
fn acquireOrGet_pendingEntry_returnsPending() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));
    // Acquire once to create a pending entry
    store.acquire_or_get("fn", "key1", 0);

    // Second call sees the pending entry
    assert_eq!(store.acquire_or_get("fn", "key1", 1), AcquireResult::Pending);
}

#[test]
fn acquireOrGet_expiredKey_returnsClaimed() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(100));
    store.put_if_absent("fn", "key1", "exec-1", 0);

    // 150ms later: expired → should claim a new slot
    match store.acquire_or_get("fn", "key1", 150) {
        AcquireResult::Claimed(token) => assert!(!token.is_empty()),
        other => panic!("expected Claimed, got {:?}", other),
    }
}

#[test]
fn publishClaim_makesEntryStable() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));

    let token = match store.acquire_or_get("fn", "key1", 0) {
        AcquireResult::Claimed(t) => t,
        _ => panic!("expected Claimed"),
    };
    // While pending, get_execution_id returns None
    assert_eq!(store.get_execution_id("fn", "key1", 1), None);

    store.publish_claim("fn", "key1", &token, "exec-final", 2);

    // After publish, the entry is stable
    assert_eq!(store.get_execution_id("fn", "key1", 3), Some("exec-final".to_string()));
}

#[test]
fn abandonClaim_removesEntry() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));

    let token = match store.acquire_or_get("fn", "key1", 0) {
        AcquireResult::Claimed(t) => t,
        _ => panic!("expected Claimed"),
    };
    store.abandon_claim("fn", "key1", &token);

    // After abandon, next acquire_or_get wins a new claim
    match store.acquire_or_get("fn", "key1", 1) {
        AcquireResult::Claimed(_) => {}
        other => panic!("expected Claimed after abandon, got {:?}", other),
    }
}

#[test]
fn claimIfMatches_matchingId_returnsClaimed() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));
    store.put_if_absent("fn", "key1", "exec-old", 0);

    match store.claim_if_matches("fn", "key1", "exec-old", 1) {
        AcquireResult::Claimed(token) => assert!(!token.is_empty()),
        other => panic!("expected Claimed, got {:?}", other),
    }
}

#[test]
fn claimIfMatches_differentId_returnsExisting() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));
    store.put_if_absent("fn", "key1", "exec-real", 0);

    match store.claim_if_matches("fn", "key1", "exec-wrong", 1) {
        AcquireResult::Existing(id) => assert_eq!(id, "exec-real"),
        other => panic!("expected Existing, got {:?}", other),
    }
}

#[test]
fn claimIfMatches_missingKey_returnsMissing() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));

    assert_eq!(
        store.claim_if_matches("fn", "nonexistent", "any-id", 0),
        AcquireResult::Missing
    );
}

#[test]
fn getExecutionId_pendingEntry_returnsNone() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(300));
    // A pending claim should not be visible to get_execution_id
    store.acquire_or_get("fn", "key1", 0);

    assert_eq!(store.get_execution_id("fn", "key1", 1), None);
}
