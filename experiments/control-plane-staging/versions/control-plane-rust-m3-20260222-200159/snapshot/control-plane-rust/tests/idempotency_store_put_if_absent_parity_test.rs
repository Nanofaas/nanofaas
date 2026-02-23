#![allow(non_snake_case)]

use control_plane_rust::idempotency::IdempotencyStore;
use std::time::Duration;

#[test]
fn putIfAbsent_newKey_returnsNull() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    let result = store.put_if_absent("fn", "key1", "exec-1", 0);

    assert_eq!(result, None);
    assert_eq!(
        store.get_execution_id("fn", "key1", 1),
        Some("exec-1".to_string())
    );
}

#[test]
fn putIfAbsent_existingKey_returnsExistingExecutionId() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    store.put_if_absent("fn", "key1", "exec-1", 0);
    let result = store.put_if_absent("fn", "key1", "exec-2", 1);

    assert_eq!(result, Some("exec-1".to_string()));
    assert_eq!(
        store.get_execution_id("fn", "key1", 2),
        Some("exec-1".to_string())
    );
}

#[test]
fn putIfAbsent_expiredKey_replacesAndReturnsNull() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(100));

    store.put_if_absent("fn", "key1", "exec-1", 0);
    assert_eq!(
        store.get_execution_id("fn", "key1", 50),
        Some("exec-1".to_string())
    );

    let result = store.put_if_absent("fn", "key1", "exec-2", 150);
    assert_eq!(result, None);
    assert_eq!(
        store.get_execution_id("fn", "key1", 151),
        Some("exec-2".to_string())
    );
}

#[test]
fn putIfAbsent_differentFunctions_areIndependent() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    let r1 = store.put_if_absent("fn1", "key1", "exec-1", 0);
    let r2 = store.put_if_absent("fn2", "key1", "exec-2", 0);

    assert_eq!(r1, None);
    assert_eq!(r2, None);
    assert_eq!(
        store.get_execution_id("fn1", "key1", 1),
        Some("exec-1".to_string())
    );
    assert_eq!(
        store.get_execution_id("fn2", "key1", 1),
        Some("exec-2".to_string())
    );
}
