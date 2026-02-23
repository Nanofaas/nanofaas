#![allow(non_snake_case)]

use control_plane_rust::e2e_support::resolve_boot_jar;
use std::fs;
use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

fn temp_dir(prefix: &str) -> PathBuf {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .expect("clock")
        .as_nanos();
    let dir = std::env::temp_dir().join(format!("control-plane-rust-{prefix}-{nanos}"));
    fs::create_dir_all(&dir).expect("create temp dir");
    dir
}

#[test]
fn resolveBootJar_prefersProjectVersionOverLexicographicMax() {
    let dir = temp_dir("resolve-boot-jar");
    fs::write(dir.join("control-plane-0.9.2.jar"), []).expect("0.9.2");
    fs::write(dir.join("control-plane-0.10.0.jar"), []).expect("0.10.0");
    fs::write(dir.join("control-plane-0.11.0.jar"), []).expect("0.11.0");
    fs::write(dir.join("control-plane-0.10.0-plain.jar"), []).expect("plain");

    let selected = resolve_boot_jar(&dir, "control-plane", Some("0.10.0")).expect("selected");
    assert_eq!(
        "control-plane-0.10.0.jar",
        selected.file_name().expect("name").to_string_lossy()
    );
}
