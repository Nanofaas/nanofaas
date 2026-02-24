use control_plane_rust::idempotency::IdempotencyStore;
use std::time::Duration;

#[test]
fn idempotency_key_expires_after_ttl() {
    let mut store = IdempotencyStore::new_with_ttl(Duration::from_millis(100));
    store.put_with_timestamp("fn", "k1", "exec-1", 0);
    assert_eq!(store.get_execution_id("fn", "k1", 50), Some("exec-1".to_string()));
    assert_eq!(store.get_execution_id("fn", "k1", 150), None);
}
