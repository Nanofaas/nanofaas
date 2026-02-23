#![allow(non_snake_case)]

use control_plane_rust::config::KubernetesClientConfig;
use std::collections::HashMap;
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
fn kubernetesClient_withoutServiceAccountToken_buildsClient() {
    let root = temp_dir("k8s-client-missing-token");
    let missing_token_path = root.join("missing-token");
    let ca_path = root.join("ca.crt");

    let config = KubernetesClientConfig::new(missing_token_path, ca_path, |_key| None);
    let client = config.kubernetes_client().expect("client");

    assert!(client.master_url().is_none());
    assert!(client.oauth_token().is_none());
}

#[test]
fn kubernetesClient_withInClusterCredentials_buildsConfiguredClient() {
    let root = temp_dir("k8s-client-in-cluster");
    let token_path = root.join("token");
    let ca_path = root.join("ca.crt");
    fs::write(&token_path, "token-123\n").expect("token write");
    fs::write(
        &ca_path,
        "-----BEGIN CERTIFICATE-----\nmock\n-----END CERTIFICATE-----\n",
    )
    .expect("ca write");

    let env = HashMap::from([
        (
            "KUBERNETES_SERVICE_HOST".to_string(),
            "10.1.2.3".to_string(),
        ),
        ("KUBERNETES_SERVICE_PORT".to_string(), "6443".to_string()),
    ]);

    let config = KubernetesClientConfig::new(token_path, ca_path.clone(), move |key| {
        env.get(key).cloned()
    });
    let client = config.kubernetes_client().expect("client");
    let expected_ca = ca_path
        .canonicalize()
        .expect("canonical")
        .display()
        .to_string();

    assert!(client
        .master_url()
        .expect("master")
        .starts_with("https://10.1.2.3:6443"));
    assert_eq!(client.oauth_token(), Some("token-123"));
    assert_eq!(client.ca_cert_file(), Some(expected_ca.as_str()));
}

#[test]
fn kubernetesClient_inClusterWithoutHostOrPort_throwsIllegalStateException() {
    let root = temp_dir("k8s-client-missing-host-port");
    let token_path = root.join("token");
    let ca_path = root.join("ca.crt");
    fs::write(&token_path, "token-123").expect("token write");
    fs::write(&ca_path, "mock").expect("ca write");

    let config = KubernetesClientConfig::new(token_path, ca_path, |key| {
        if key == "KUBERNETES_SERVICE_HOST" {
            Some("10.1.2.3".to_string())
        } else {
            None
        }
    });

    let err = config.kubernetes_client().expect_err("must fail");
    assert!(err
        .to_string()
        .contains("Missing Kubernetes service host/port"));
}

#[test]
fn kubernetesClient_inClusterWithBlankToken_throwsIllegalStateException() {
    let root = temp_dir("k8s-client-blank-token");
    let token_path = root.join("token");
    let ca_path = root.join("ca.crt");
    fs::write(&token_path, "   \n\t").expect("token write");
    fs::write(&ca_path, "mock").expect("ca write");

    let config = KubernetesClientConfig::new(token_path, ca_path, |key| match key {
        "KUBERNETES_SERVICE_HOST" => Some("10.1.2.3".to_string()),
        "KUBERNETES_SERVICE_PORT" => Some("6443".to_string()),
        _ => None,
    });

    let err = config.kubernetes_client().expect_err("must fail");
    assert!(err.to_string().contains("ServiceAccount token is empty"));
}

#[test]
fn kubernetesClient_inClusterWhenTokenUnreadable_throwsIllegalStateException() {
    let root = temp_dir("k8s-client-unreadable-token");
    let token_path = root.join("token-dir");
    let ca_path = root.join("ca.crt");
    fs::create_dir_all(&token_path).expect("token dir");
    fs::write(&ca_path, "mock").expect("ca write");

    let config = KubernetesClientConfig::new(token_path, ca_path, |key| match key {
        "KUBERNETES_SERVICE_HOST" => Some("10.1.2.3".to_string()),
        "KUBERNETES_SERVICE_PORT" => Some("6443".to_string()),
        _ => None,
    });

    let err = config.kubernetes_client().expect_err("must fail");
    assert!(err
        .to_string()
        .contains("Failed to read in-cluster ServiceAccount credentials"));
}
