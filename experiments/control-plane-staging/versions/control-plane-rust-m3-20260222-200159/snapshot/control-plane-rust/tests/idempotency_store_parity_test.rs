#![allow(non_snake_case)]

use control_plane_rust::idempotency::IdempotencyStore;
use std::time::Duration;

#[test]
fn put_andGet_returnsStoredExecutionId() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));
    store.put_with_timestamp("myFunction", "key123", "exec-456", 0);

    let result = store.get_execution_id("myFunction", "key123", 1);

    assert_eq!(result, Some("exec-456".to_string()));
}

#[test]
fn get_withUnknownKey_returnsEmpty() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    let result = store.get_execution_id("myFunction", "unknown", 0);

    assert_eq!(result, None);
}

#[test]
fn get_withDifferentFunction_returnsEmpty() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));
    store.put_with_timestamp("function1", "key123", "exec-456", 0);

    let result = store.get_execution_id("function2", "key123", 1);

    assert_eq!(result, None);
}

#[test]
fn get_afterTtlExpired_returnsEmpty() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(100));
    store.put_with_timestamp("myFunction", "key123", "exec-456", 0);

    assert_eq!(
        store.get_execution_id("myFunction", "key123", 50),
        Some("exec-456".to_string())
    );
    assert_eq!(store.get_execution_id("myFunction", "key123", 150), None);
}

#[test]
fn size_returnsNumberOfEntries() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    assert_eq!(store.size(), 0);

    store.put_with_timestamp("fn1", "key1", "exec1", 0);
    assert_eq!(store.size(), 1);

    store.put_with_timestamp("fn2", "key2", "exec2", 0);
    assert_eq!(store.size(), 2);

    store.put_with_timestamp("fn1", "key3", "exec3", 0);
    assert_eq!(store.size(), 3);
}

#[test]
fn eviction_removesExpiredEntries() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(50));

    store.put_with_timestamp("fn1", "key1", "exec1", 0);
    store.put_with_timestamp("fn2", "key2", "exec2", 0);
    assert_eq!(store.size(), 2);

    let _ = store.get_execution_id("fn1", "key1", 100);
    let _ = store.get_execution_id("fn2", "key2", 100);

    assert_eq!(store.get_execution_id("fn1", "key1", 101), None);
    assert_eq!(store.get_execution_id("fn2", "key2", 101), None);
}

#[test]
fn put_overwritesExistingKey() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    store.put_with_timestamp("myFunction", "key123", "exec-1", 0);
    assert_eq!(
        store.get_execution_id("myFunction", "key123", 1),
        Some("exec-1".to_string())
    );

    store.put_with_timestamp("myFunction", "key123", "exec-2", 2);
    assert_eq!(
        store.get_execution_id("myFunction", "key123", 3),
        Some("exec-2".to_string())
    );
}

#[test]
fn multipleEntries_areIsolated() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_secs(15 * 60));

    store.put_with_timestamp("fn1", "keyA", "exec1", 0);
    store.put_with_timestamp("fn1", "keyB", "exec2", 0);
    store.put_with_timestamp("fn2", "keyA", "exec3", 0);

    assert_eq!(
        store.get_execution_id("fn1", "keyA", 1),
        Some("exec1".to_string())
    );
    assert_eq!(
        store.get_execution_id("fn1", "keyB", 1),
        Some("exec2".to_string())
    );
    assert_eq!(
        store.get_execution_id("fn2", "keyA", 1),
        Some("exec3".to_string())
    );
}
