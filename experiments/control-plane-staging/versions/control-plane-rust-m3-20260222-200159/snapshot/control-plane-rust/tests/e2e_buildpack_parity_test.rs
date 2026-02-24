#![allow(non_snake_case)]

use reqwest::Client;
use serde_json::{json, Value};
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::Command;
use std::sync::{Mutex, OnceLock};
use tokio::time::{sleep, Duration};
use uuid::Uuid;

const IMAGE_TAG: &str = "nanofaas/control-plane-rust-m3:e2e-buildpack-parity";
const MOCK_RUNTIME_IMAGE: &str = "hashicorp/http-echo:1.0";

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

    let suffix = Uuid::new_v4().simple().to_string();
    let network_name = format!("cp-rust-buildpack-e2e-net-{suffix}");
    docker_cmd(&["network", "create", &network_name], None)?;

    let mut ctx = DockerContext::new(network_name.clone());

    let runtime_name = format!("cp-rust-buildpack-runtime-{suffix}");
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

    let host_port = reserve_host_port()?;
    let cp_name = format!("cp-rust-buildpack-cp-{suffix}");
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

async fn register_pool_function(client: &Client, base_url: &str, name: &str) -> Result<(), String> {
    let resp = client
        .post(format!("{base_url}/v1/functions"))
        .json(&json!({
            "name": name,
            "image": "nanofaas/function-runtime:buildpack",
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

#[tokio::test]
async fn buildpackRegisterInvokeAndPoll() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping buildpack parity e2e: docker not available");
        return;
    }

    let (_ctx, base_url) = start_environment().expect("start docker environment");
    let client = Client::new();
    wait_for_health(&client, &base_url)
        .await
        .expect("wait for health");

    register_pool_function(&client, &base_url, "bp-echo")
        .await
        .expect("register pool function");

    let invoke = client
        .post(format!("{base_url}/v1/functions/bp-echo:invoke"))
        .json(&json!({"input": "hi"}))
        .send()
        .await
        .expect("invoke request");
    assert_eq!(200, invoke.status().as_u16());
    let invoke_json: Value = invoke.json().await.expect("invoke json");
    assert_eq!("success", invoke_json["status"]);

    let enqueue = client
        .post(format!("{base_url}/v1/functions/bp-echo:enqueue"))
        .json(&json!({"input": "payload"}))
        .send()
        .await
        .expect("enqueue request");
    assert_eq!(202, enqueue.status().as_u16());
    let enqueue_json: Value = enqueue.json().await.expect("enqueue json");
    let execution_id = enqueue_json["executionId"]
        .as_str()
        .expect("execution id")
        .to_string();

    let drain = client
        .post(format!(
            "{base_url}/v1/internal/functions/bp-echo:drain-once"
        ))
        .send()
        .await
        .expect("drain request");
    assert_eq!(200, drain.status().as_u16());

    for _ in 0..30 {
        let status = client
            .get(format!("{base_url}/v1/executions/{execution_id}"))
            .send()
            .await
            .expect("execution status");
        assert_eq!(200, status.status().as_u16());
        let status_json: Value = status.json().await.expect("status json");
        if status_json["status"] == "success" {
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
    assert!(text.contains("function_enqueue_total{function=\"bp-echo\"}"));
}
