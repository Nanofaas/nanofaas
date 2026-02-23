#![allow(non_snake_case)]

use std::path::{Path, PathBuf};

fn repo_root() -> PathBuf {
    let mut current = Path::new(env!("CARGO_MANIFEST_DIR")).to_path_buf();
    while !current.join("settings.gradle").exists() {
        if !current.pop() {
            break;
        }
    }
    current
}

#[test]
fn issue001_structureExists() {
    let root = repo_root();
    assert!(root.join("control-plane").is_dir());
    assert!(root.join("function-runtime").is_dir());
    assert!(root.join("common").is_dir());
}

#[test]
fn issue002_buildConfigExists() {
    let root = repo_root();
    assert!(root.join("build.gradle").exists());
    assert!(root.join("gradle.properties").exists());
}

#[test]
fn issue003_dockerfilesExist() {
    let root = repo_root();
    assert!(root.join("control-plane/Dockerfile").exists());
    assert!(root.join("function-runtime/Dockerfile").exists());
}

#[test]
fn issue004_k8sManifestsExist() {
    let root = repo_root();
    assert!(root.join("k8s/namespace.yaml").exists());
    assert!(root.join("k8s/serviceaccount.yaml").exists());
    assert!(root.join("k8s/rbac.yaml").exists());
    assert!(root.join("k8s/control-plane-deployment.yaml").exists());
    assert!(root.join("k8s/control-plane-service.yaml").exists());
}

#[test]
fn issue005_openApiExists() {
    let root = repo_root();
    assert!(root.join("openapi.yaml").exists());
}

#[test]
fn issue019_sloDocExists() {
    let root = repo_root();
    assert!(root.join("docs/slo.md").exists());
}

#[test]
fn issue020_quickstartDocExists() {
    let root = repo_root();
    assert!(root.join("docs/quickstart.md").exists());
}

#[test]
fn issue021_exampleFunctionDocExists() {
    let root = repo_root();
    assert!(root.join("docs/example-function.md").exists());
}
