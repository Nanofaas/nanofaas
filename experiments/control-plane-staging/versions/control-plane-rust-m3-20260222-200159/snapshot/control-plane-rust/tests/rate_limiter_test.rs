use control_plane_rust::rate_limiter::RateLimiter;

#[test]
fn rate_limiter_respects_window_capacity() {
    let mut limiter = RateLimiter::new(2);
    assert!(limiter.try_acquire_at(0));
    assert!(limiter.try_acquire_at(0));
    assert!(!limiter.try_acquire_at(0));
    assert!(limiter.try_acquire_at(1000));
}
