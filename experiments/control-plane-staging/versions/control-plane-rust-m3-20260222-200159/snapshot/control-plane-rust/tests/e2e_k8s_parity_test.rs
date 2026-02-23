#![allow(non_snake_case)]

use reqwest::Client;
use serde_json::{json, Value};
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::Command;
use std::sync::{Mutex, OnceLock};
use tokio::time::{sleep, Duration};
use uuid::Uuid;

const IMAGE_TAG: &str = "nanofaas/control-plane-rust-m3:e2e-k8s-parity";
const MOCK_RUNTIME_IMAGE: &str = "hashicorp/http-echo:1.0";
const COLD_MOCK_IMAGE: &str = "nanofaas/cold-mock:e2e";

fn docker_test_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

struct DockerContext {
    network: String,
    container_names: Vec<String>,
}

impl DockerContext {
    fn new(network: String) -> Self {
        Self {
            network,
            container_names: Vec::new(),
        }
    }

    fn register_container(&mut self, name: String) {
        self.container_names.push(name);
    }
}

impl Drop for DockerContext {
    fn drop(&mut self) {
        for name in self.container_names.iter().rev() {
            let _ = docker_cmd(&["rm", "-f", name], None);
        }
        let _ = docker_cmd(&["network", "rm", &self.network], None);
    }
}

fn docker_available() -> bool {
    Command::new("docker")
        .args(["info"])
        .output()
        .map(|output| output.status.success())
        .unwrap_or(false)
}

fn docker_cmd(args: &[&str], cwd: Option<&PathBuf>) -> Result<String, String> {
    let mut cmd = Command::new("docker");
    cmd.args(args);
    if let Some(cwd) = cwd {
        cmd.current_dir(cwd);
    }
    let output = cmd
        .output()
        .map_err(|err| format!("failed to execute docker {:?}: {err}", args))?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    } else {
        Err(format!(
            "docker {:?} failed: {}",
            args,
            String::from_utf8_lossy(&output.stderr).trim()
        ))
    }
}

fn crate_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn ensure_image_built() -> Result<(), String> {
    let root = crate_root();
    docker_cmd(
        &["build", "-t", IMAGE_TAG, "-f", "Dockerfile", "."],
        Some(&root),
    )
    .map(|_| ())
}

fn ensure_cold_mock_image_built() -> Result<(), String> {
    let cold_mock_dir = crate_root().join("tests").join("cold-mock");
    docker_cmd(
        &["build", "-t", COLD_MOCK_IMAGE, "."],
        Some(&cold_mock_dir),
    )
    .map(|_| ())
}

fn reserve_host_port() -> Result<u16, String> {
    let listener = TcpListener::bind("127.0.0.1:0")
        .map_err(|err| format!("failed to reserve host port: {err}"))?;
    let port = listener
        .local_addr()
        .map_err(|err| format!("failed to read local addr: {err}"))?
        .port();
    drop(listener);
    Ok(port)
}

fn start_environment() -> Result<(DockerContext, String), String> {
    ensure_image_built()?;
    ensure_cold_mock_image_built()?;

    let suffix = Uuid::new_v4().simple().to_string();
    let network_name = format!("cp-rust-k8s-e2e-net-{suffix}");
    docker_cmd(&["network", "create", &network_name], None)?;

    let mut ctx = DockerContext::new(network_name.clone());

    let runtime_name = format!("cp-rust-k8s-runtime-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &runtime_name,
            "--network",
            &network_name,
            "--network-alias",
            "function-runtime",
            MOCK_RUNTIME_IMAGE,
            "-text",
            "{\"message\":\"ok\"}",
            "-listen=:8080",
        ],
        None,
    )?;
    ctx.register_container(runtime_name);

    // Cold-start mock: always returns X-Cold-Start: true so cold-start metrics can be tested.
    let cold_runtime_name = format!("cp-rust-k8s-cold-runtime-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &cold_runtime_name,
            "--network",
            &network_name,
            "--network-alias",
            "cold-function-runtime",
            COLD_MOCK_IMAGE,
        ],
        None,
    )?;
    ctx.register_container(cold_runtime_name);

    let host_port = reserve_host_port()?;
    let cp_name = format!("cp-rust-k8s-cp-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &cp_name,
            "--network",
            &network_name,
            "-p",
            &format!("{host_port}:8080"),
            IMAGE_TAG,
        ],
        None,
    )?;
    ctx.register_container(cp_name);

    Ok((ctx, format!("http://127.0.0.1:{host_port}")))
}

async fn wait_for_health(client: &Client, base_url: &str) -> Result<(), String> {
    let url = format!("{base_url}/actuator/health");
    for _ in 0..80 {
        if let Ok(resp) = client.get(&url).send().await {
            if resp.status().is_success() {
                return Ok(());
            }
        }
        sleep(Duration::from_millis(250)).await;
    }
    Err(format!("control-plane health check timed out at {url}"))
}

async fn register_pool_function(
    client: &Client,
    base_url: &str,
    name: &str,
    image: &str,
) -> Result<(), String> {
    let resp = client
        .post(format!("{base_url}/v1/functions"))
        .json(&json!({
            "name": name,
            "image": image,
            "executionMode": "POOL",
            "runtimeMode": "HTTP",
            "endpointUrl": "http://function-runtime:8080/invoke"
        }))
        .send()
        .await
        .map_err(|err| format!("register request failed: {err}"))?;
    if resp.status().as_u16() != 201 {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(format!("register failed with status {status}: {body}"));
    }
    Ok(())
}

async fn register_pool_function_with_endpoint(
    client: &Client,
    base_url: &str,
    name: &str,
    image: &str,
    endpoint_url: &str,
) -> Result<(), String> {
    let resp = client
        .post(format!("{base_url}/v1/functions"))
        .json(&json!({
            "name": name,
            "image": image,
            "executionMode": "POOL",
            "runtimeMode": "HTTP",
            "endpointUrl": endpoint_url
        }))
        .send()
        .await
        .map_err(|err| format!("register request failed: {err}"))?;
    if resp.status().as_u16() != 201 {
        let status = resp.status();
        let body = resp.text().await.unwrap_or_default();
        return Err(format!("register failed with status {status}: {body}"));
    }
    Ok(())
}

#[tokio::test]
async fn k8sRegisterInvokeAndPoll() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping k8s parity e2e: docker not available");
        return;
    }

    let (_ctx, base_url) = start_environment().expect("start docker environment");
    let client = Client::new();
    wait_for_health(&client, &base_url)
        .await
        .expect("wait for health");

    register_pool_function(
        &client,
        &base_url,
        "k8s-echo",
        "nanofaas/function-runtime:e2e",
    )
    .await
    .expect("register pool function");

    let list = client
        .get(format!("{base_url}/v1/functions"))
        .send()
        .await
        .expect("list functions");
    assert_eq!(200, list.status().as_u16());
    let list_json: Value = list.json().await.expect("list json");
    assert!(
        list_json
            .as_array()
            .unwrap_or(&Vec::new())
            .iter()
            .any(|f| f["name"] == "k8s-echo"),
        "{list_json}"
    );

    let invoke_1 = client
        .post(format!("{base_url}/v1/functions/k8s-echo:invoke"))
        .json(&json!({"input":"hi"}))
        .send()
        .await
        .expect("invoke 1");
    assert_eq!(200, invoke_1.status().as_u16());

    let invoke_2 = client
        .post(format!("{base_url}/v1/functions/k8s-echo:invoke"))
        .json(&json!({"input":"hello"}))
        .send()
        .await
        .expect("invoke 2");
    assert_eq!(200, invoke_2.status().as_u16());

    let enqueue_1 = client
        .post(format!("{base_url}/v1/functions/k8s-echo:enqueue"))
        .header("Idempotency-Key", "abc")
        .json(&json!({"input":"payload"}))
        .send()
        .await
        .expect("enqueue 1");
    assert_eq!(202, enqueue_1.status().as_u16());
    let enqueue_1_json: Value = enqueue_1.json().await.expect("enqueue 1 json");
    let execution_id = enqueue_1_json["executionId"]
        .as_str()
        .expect("execution id")
        .to_string();

    let enqueue_2 = client
        .post(format!("{base_url}/v1/functions/k8s-echo:enqueue"))
        .header("Idempotency-Key", "abc")
        .json(&json!({"input":"payload"}))
        .send()
        .await
        .expect("enqueue 2");
    assert_eq!(202, enqueue_2.status().as_u16());
    let enqueue_2_json: Value = enqueue_2.json().await.expect("enqueue 2 json");
    assert_eq!(execution_id, enqueue_2_json["executionId"]);

    let drain = client
        .post(format!(
            "{base_url}/v1/internal/functions/k8s-echo:drain-once"
        ))
        .send()
        .await
        .expect("drain");
    assert_eq!(200, drain.status().as_u16());

    for _ in 0..30 {
        let status = client
            .get(format!("{base_url}/v1/executions/{execution_id}"))
            .send()
            .await
            .expect("execution status");
        assert_eq!(200, status.status().as_u16());
        let status_json: Value = status.json().await.expect("status json");
        if status_json["status"] == "SUCCESS" {
            break;
        }
        sleep(Duration::from_millis(100)).await;
    }

    let scrape = client
        .get(format!("{base_url}/actuator/prometheus"))
        .send()
        .await
        .expect("scrape request");
    assert_eq!(200, scrape.status().as_u16());
    let text = scrape.text().await.expect("prometheus text");
    assert!(text.contains("function_enqueue_total"));
    assert!(text.contains("function_success_total"));
}

#[tokio::test]
async fn k8sSyncQueueBackpressure() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping k8s parity e2e: docker not available");
        return;
    }

    let (_ctx, base_url) = start_environment().expect("start docker environment");
    let client = Client::new();
    wait_for_health(&client, &base_url)
        .await
        .expect("wait for health");

    register_pool_function(
        &client,
        &base_url,
        "k8s-echo-ok",
        "nanofaas/function-runtime:e2e",
    )
    .await
    .expect("register admitted function");
    register_pool_function(
        &client,
        &base_url,
        "k8s-echo-sync-queue",
        "sync-reject-depth",
    )
    .await
    .expect("register rejected function");

    let warmup = client
        .post(format!("{base_url}/v1/functions/k8s-echo-ok:invoke"))
        .json(&json!({"input":"warmup"}))
        .send()
        .await
        .expect("warmup");
    assert_eq!(200, warmup.status().as_u16());

    let rejected = client
        .post(format!(
            "{base_url}/v1/functions/k8s-echo-sync-queue:invoke"
        ))
        .json(&json!({"input":{"message":"sync"}}))
        .send()
        .await
        .expect("rejected invoke");
    assert_eq!(429, rejected.status().as_u16());
    assert_eq!(
        Some("3"),
        rejected
            .headers()
            .get("Retry-After")
            .and_then(|value| value.to_str().ok())
    );
    assert_eq!(
        Some("depth"),
        rejected
            .headers()
            .get("X-Queue-Reject-Reason")
            .and_then(|value| value.to_str().ok())
    );

    let scrape = client
        .get(format!("{base_url}/actuator/prometheus"))
        .send()
        .await
        .expect("scrape request");
    assert_eq!(200, scrape.status().as_u16());
    let text = scrape.text().await.expect("prometheus text");
    assert!(text.contains("sync_queue_admitted_total"));
    assert!(text.contains("sync_queue_rejected_total"));
    assert!(text.contains("sync_queue_wait_seconds_count"));
    assert!(text.contains("sync_queue_depth"));
}

#[tokio::test]
async fn k8sColdStartMetrics_areRecorded() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping k8s parity e2e: docker not available");
        return;
    }

    let (_ctx, base_url) = start_environment().expect("start docker environment");
    let client = Client::new();
    wait_for_health(&client, &base_url)
        .await
        .expect("wait for health");

    // cold-function-runtime always returns X-Cold-Start:true so cold-start metrics are recorded.
    register_pool_function_with_endpoint(
        &client,
        &base_url,
        "k8s-cold-fn",
        "nanofaas/function-runtime:e2e",
        "http://cold-function-runtime:8080",
    )
    .await
    .expect("register cold function");

    // function-runtime returns no cold-start headers, so warm-start metrics are recorded.
    register_pool_function(
        &client,
        &base_url,
        "k8s-warm-fn",
        "nanofaas/function-runtime:e2e",
    )
    .await
    .expect("register warm function");

    let cold_invoke = client
        .post(format!("{base_url}/v1/functions/k8s-cold-fn:invoke"))
        .json(&json!({"input": "cold"}))
        .send()
        .await
        .expect("cold invoke");
    assert_eq!(200, cold_invoke.status().as_u16());

    for payload in ["warm-1", "warm-2"] {
        let invoke = client
            .post(format!("{base_url}/v1/functions/k8s-warm-fn:invoke"))
            .json(&json!({"input": payload}))
            .send()
            .await
            .expect("warm invoke");
        assert_eq!(200, invoke.status().as_u16());
    }

    let scrape = client
        .get(format!("{base_url}/actuator/prometheus"))
        .send()
        .await
        .expect("scrape request");
    assert_eq!(200, scrape.status().as_u16());
    let text = scrape.text().await.expect("prometheus text");
    assert!(text.contains("function_cold_start_total{function=\"k8s-cold-fn\"}"));
    assert!(text.contains("function_warm_start_total{function=\"k8s-warm-fn\"}"));
    assert!(text.contains("function_init_duration_ms_count{function=\"k8s-cold-fn\"}"));
}
