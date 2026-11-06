const MAX_HISTORY: usize = 100000;
const MAX_STEPS: usize = 4096;
static mut INPUT: [f64; MAX_HISTORY] = [0.0; MAX_HISTORY];
static mut OUTPUT: [f64; MAX_STEPS] = [0.0; MAX_STEPS];

#[unsafe(no_mangle)]
pub extern "C" fn abi_version() -> i32 { 1 }
#[unsafe(no_mangle)]
pub extern "C" fn input_ptr() -> *mut f64 { std::ptr::addr_of_mut!(INPUT).cast() }
#[unsafe(no_mangle)]
pub extern "C" fn output_ptr() -> *mut f64 { std::ptr::addr_of_mut!(OUTPUT).cast() }

/// The host serializes access to this instance's linear memory and exported functions.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn forecast(n: i32, steps: i32, alpha: f64, beta: f64, phi: f64) -> i32 {
    if n < 1 || n as usize > MAX_HISTORY || steps < 1 || steps as usize > MAX_STEPS { return 1; }
    // Fixed, separate buffers; validated lengths never escape either allocation.
    let input = unsafe { std::slice::from_raw_parts(input_ptr(), n as usize) };
    let output = unsafe { std::slice::from_raw_parts_mut(output_ptr(), steps as usize) };
    holt(input, output, alpha, beta, phi)
}

fn holt(rates: &[f64], output: &mut [f64], alpha: f64, beta: f64, phi: f64) -> i32 {
    if rates.is_empty() || rates.len() > MAX_HISTORY || output.is_empty() || output.len() > MAX_STEPS
        || [alpha, beta, phi].iter().any(|v| !v.is_finite() || *v <= 0.0 || *v > 1.0)
        || rates.iter().any(|v| !v.is_finite() || *v < 0.0) { return 1; }
    let mut level = rates[0];
    let mut slope = 0.0;
    for &rate in &rates[1..] {
        let previous = level;
        level = alpha * rate + (1.0 - alpha) * (level + phi * slope);
        slope = beta * (level - previous) + (1.0 - beta) * phi * slope;
        if !level.is_finite() || !slope.is_finite() { return 2; }
    }
    let mut damped = 0.0;
    let mut factor = 1.0;
    for value in output {
        factor *= phi;
        damped += factor;
        let next = level + damped * slope;
        if !next.is_finite() { return 2; }
        *value = next.max(0.0);
    }
    0
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn known_paths_and_damping() {
        for (input, weights, expected) in [
            (vec![5.0], (0.4, 0.4, 0.95), vec![5.0, 5.0]),
            (vec![0.0; 4], (1.0, 1.0, 1.0), vec![0.0, 0.0]),
            (vec![1.0, 2.0, 3.0], (1.0, 1.0, 1.0), vec![4.0, 5.0]),
            (vec![3.0, 2.0, 1.0], (1.0, 1.0, 1.0), vec![0.0, 0.0]),
            (vec![10.0, 20.0], (1.0, 1.0, 0.5), vec![25.0, 27.5]),
        ] {
            let mut out = vec![0.0; expected.len()];
            assert_eq!(holt(&input, &mut out, weights.0, weights.1, weights.2), 0);
            assert_eq!(out, expected);
        }
    }
    #[test]
    fn validates_bounds_and_values() {
        let mut out = [0.0; 1];
        for input in [vec![], vec![-1.0], vec![f64::NAN], vec![f64::INFINITY], vec![0.0; 100001]] {
            assert_eq!(holt(&input, &mut out, 0.4, 0.4, 0.95), 1);
        }
        for bad in [0.0, -0.1, 1.1, f64::NAN, f64::INFINITY] {
            assert_eq!(holt(&[1.0], &mut out, bad, 0.4, 0.95), 1);
            assert_eq!(holt(&[1.0], &mut out, 0.4, bad, 0.95), 1);
            assert_eq!(holt(&[1.0], &mut out, 0.4, 0.4, bad), 1);
        }
        assert_eq!(holt(&[1.0], &mut [], 1.0, 1.0, 1.0), 1);
        assert_eq!(holt(&[1.0], &mut vec![0.0; 4097], 1.0, 1.0, 1.0), 1);
        assert_eq!(holt(&[0.0, f64::MAX], &mut out, 1.0, 1.0, 1.0), 2);
    }
    #[test]
    fn maximum_history_and_horizon() {
        let mut out = vec![0.0; 4096];
        assert_eq!(holt(&vec![7.0; 100000], &mut out, f64::MIN_POSITIVE, 1.0, 1.0), 0);
        assert!(out.iter().all(|v| *v == 7.0));
    }
}
