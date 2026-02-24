use control_plane_rust::idempotency::IdempotencyStore;
use std::time::Duration;

#[test]
fn put_if_absent_matches_java_semantics() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(100));
    assert_eq!(store.put_if_absent("fn", "k1", "exec-1", 0), None);
    assert_eq!(store.put_if_absent("fn", "k1", "exec-2", 10), Some("exec-1".to_string()));
    assert_eq!(store.put_if_absent("fn", "k1", "exec-3", 150), None);
    assert_eq!(store.get_execution_id("fn", "k1", 150), Some("exec-3".to_string()));
}
