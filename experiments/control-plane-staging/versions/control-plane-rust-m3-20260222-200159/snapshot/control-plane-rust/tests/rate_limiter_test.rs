use control_plane_rust::rate_limiter::RateLimiter;

#[test]
fn rate_limiter_respects_window_capacity() {
    let mut limiter = RateLimiter::new(2);
    assert!(limiter.try_acquire_at(0));
    assert!(limiter.try_acquire_at(0));
    assert!(!limiter.try_acquire_at(0));
    assert!(limiter.try_acquire_at(1000));
}

#[test]
fn window_boundary_does_not_allow_burst() {
    let mut rl = RateLimiter::new(2);
    assert!(rl.try_acquire_at(0));
    assert!(rl.try_acquire_at(500));
    assert!(!rl.try_acquire_at(999)); // full
                                      // New second starts at ms 1000
    assert!(rl.try_acquire_at(1000));
    assert!(rl.try_acquire_at(1001));
    assert!(!rl.try_acquire_at(1002)); // must be full
}

#[test]
fn window_resets_on_new_epoch_second() {
    let mut rl = RateLimiter::new(3);
    // epoch-second 0: use 3 slots
    assert!(rl.try_acquire_at(0));
    assert!(rl.try_acquire_at(500));
    assert!(rl.try_acquire_at(999));
    assert!(!rl.try_acquire_at(999)); // full
                                      // epoch-second 1: fresh window
    assert!(rl.try_acquire_at(1000));
    assert!(rl.try_acquire_at(1500));
    assert!(rl.try_acquire_at(1999));
    assert!(!rl.try_acquire_at(1999)); // full again
}
