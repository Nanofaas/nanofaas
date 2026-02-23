#![allow(non_snake_case)]

use std::sync::{Arc, Mutex};

#[test]
fn main_delegatesToSpringApplicationRun() {
    let args = vec!["--spring.main.web-application-type=none".to_string()];
    let captured: Arc<Mutex<Vec<String>>> = Arc::new(Mutex::new(Vec::new()));
    let captured_ref = Arc::clone(&captured);

    control_plane_rust::application::main_with_runner(&args, |runner_args| {
        *captured_ref.lock().expect("captured lock") = runner_args.to_vec();
    });

    assert_eq!(*captured.lock().expect("captured lock"), args);
}
