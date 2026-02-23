#[derive(Debug, Clone)]
pub struct RateLimiter {
    capacity_per_second: usize,
    used_in_window: usize,
    window_start_millis: u64,
}

impl RateLimiter {
    pub fn new(capacity_per_second: usize) -> Self {
        Self {
            capacity_per_second,
            used_in_window: 0,
            window_start_millis: u64::MAX, // sentinel: no window seen yet
        }
    }

    pub fn try_acquire_at(&mut self, now_millis: u64) -> bool {
        let current_second = now_millis / 1000;
        if current_second != self.window_start_millis {
            self.window_start_millis = current_second;
            self.used_in_window = 0;
        }
        if self.used_in_window >= self.capacity_per_second {
            return false;
        }
        self.used_in_window += 1;
        true
    }
}
