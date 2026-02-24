#![allow(non_snake_case)]

use reqwest::Client;
use serde_json::{json, Value};
use std::fs;
use std::net::TcpListener;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::sync::{Mutex, OnceLock};
use tokio::time::{sleep, Duration};
use uuid::Uuid;

const CONTROL_PLANE_IMAGE: &str = "nanofaas/control-plane-rust-m3:e2e";
const WORD_STATS_JAVA_IMAGE: &str = "nanofaas/e2e-word-stats-java:e2e";
const WORD_STATS_NATIVE_IMAGE: &str = "nanofaas/e2e-word-stats-native:e2e";
const JSON_TRANSFORM_JAVA_IMAGE: &str = "nanofaas/e2e-json-transform-java:e2e";
const JSON_TRANSFORM_NATIVE_IMAGE: &str = "nanofaas/e2e-json-transform-native:e2e";

fn docker_test_lock() -> &'static Mutex<()> {
    static LOCK: OnceLock<Mutex<()>> = OnceLock::new();
    LOCK.get_or_init(|| Mutex::new(()))
}

fn control_plane_image_ready() -> &'static OnceLock<()> {
    static READY: OnceLock<()> = OnceLock::new();
    &READY
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

fn docker_cmd(args: &[&str], cwd: Option<&Path>) -> Result<String, String> {
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

fn command(args: &[&str], cwd: Option<&Path>) -> Result<(), String> {
    let mut cmd = Command::new(args[0]);
    cmd.args(&args[1..]);
    if let Some(cwd) = cwd {
        cmd.current_dir(cwd);
    }
    let output = cmd
        .output()
        .map_err(|err| format!("failed to execute {:?}: {err}", args))?;
    if output.status.success() {
        Ok(())
    } else {
        Err(format!(
            "{:?} failed: {}",
            args,
            String::from_utf8_lossy(&output.stderr).trim()
        ))
    }
}

fn crate_root() -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR"))
}

fn repo_root() -> Result<PathBuf, String> {
    let mut dir = crate_root();
    for _ in 0..12 {
        if dir.join("gradlew").is_file() && dir.join("examples/java").is_dir() {
            return Ok(dir);
        }
        if !dir.pop() {
            break;
        }
    }
    Err("unable to discover repository root from cargo manifest dir".to_string())
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

fn ensure_control_plane_image_built() -> Result<(), String> {
    if control_plane_image_ready().get().is_none() {
        let root = crate_root();
        docker_cmd(
            &["build", "-t", CONTROL_PLANE_IMAGE, "-f", "Dockerfile", "."],
            Some(&root),
        )?;
        let _ = control_plane_image_ready().set(());
    }
    Ok(())
}

fn ensure_spring_example_image_built(
    image_tag: &str,
    module_rel_path: &str,
    jar_file_name: &str,
    gradle_task: &str,
) -> Result<(), String> {
    if docker_cmd(&["image", "inspect", image_tag], None).is_ok() {
        return Ok(());
    }

    let root = repo_root()?;
    let module_dir = root.join(module_rel_path);
    let jar = module_dir.join(format!("build/libs/{jar_file_name}"));

    if !jar.is_file() {
        command(&["./gradlew", gradle_task, "--no-daemon"], Some(&root))?;
    }
    if !jar.is_file() {
        return Err(format!("boot jar not found at {}", jar.display()));
    }

    let context_dir = std::env::temp_dir().join(format!(
        "control-plane-rust-example-context-{}",
        Uuid::new_v4().simple()
    ));
    fs::create_dir_all(context_dir.join("build/libs"))
        .map_err(|err| format!("failed to create temporary docker context: {err}"))?;

    fs::copy(
        module_dir.join("Dockerfile"),
        context_dir.join("Dockerfile"),
    )
    .map_err(|err| format!("failed to copy Dockerfile: {err}"))?;
    fs::copy(
        &jar,
        context_dir.join(format!("build/libs/{jar_file_name}")),
    )
    .map_err(|err| format!("failed to copy boot jar: {err}"))?;

    let build_result = docker_cmd(
        &["build", "-t", image_tag, "-f", "Dockerfile", "."],
        Some(&context_dir),
    );
    let _ = fs::remove_dir_all(&context_dir);
    build_result.map(|_| ())
}

fn ensure_native_example_image_built(
    image_tag: &str,
    dockerfile_rel_path: &str,
) -> Result<(), String> {
    if docker_cmd(&["image", "inspect", image_tag], None).is_ok() {
        return Ok(());
    }
    let root = repo_root()?;
    docker_cmd(
        &["build", "-t", image_tag, "-f", dockerfile_rel_path, "."],
        Some(&root),
    )
    .map(|_| ())
}

fn start_environment() -> Result<(DockerContext, String, u16, u16, u16, u16), String> {
    ensure_control_plane_image_built()?;
    ensure_spring_example_image_built(
        WORD_STATS_JAVA_IMAGE,
        "examples/java/word-stats",
        "word-stats.jar",
        ":examples:java:word-stats:bootJar",
    )?;
    ensure_spring_example_image_built(
        JSON_TRANSFORM_JAVA_IMAGE,
        "examples/java/json-transform",
        "json-transform.jar",
        ":examples:java:json-transform:bootJar",
    )?;
    ensure_native_example_image_built(
        WORD_STATS_NATIVE_IMAGE,
        "examples/java/word-stats-lite/Dockerfile",
    )?;
    ensure_native_example_image_built(
        JSON_TRANSFORM_NATIVE_IMAGE,
        "examples/java/json-transform-lite/Dockerfile",
    )?;

    let suffix = Uuid::new_v4().simple().to_string();
    let network_name = format!("cp-rust-sdk-e2e-net-{suffix}");
    docker_cmd(&["network", "create", &network_name], None)?;
    let mut ctx = DockerContext::new(network_name.clone());

    let word_stats_java_port = reserve_host_port()?;
    let word_stats_java_name = format!("cp-rust-sdk-word-stats-java-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &word_stats_java_name,
            "--network",
            &network_name,
            "--network-alias",
            "word-stats",
            "-p",
            &format!("{word_stats_java_port}:8080"),
            WORD_STATS_JAVA_IMAGE,
        ],
        None,
    )?;
    ctx.register_container(word_stats_java_name);

    let word_stats_native_port = reserve_host_port()?;
    let word_stats_native_name = format!("cp-rust-sdk-word-stats-native-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &word_stats_native_name,
            "--network",
            &network_name,
            "--network-alias",
            "word-stats-lite",
            "-p",
            &format!("{word_stats_native_port}:8080"),
            WORD_STATS_NATIVE_IMAGE,
        ],
        None,
    )?;
    ctx.register_container(word_stats_native_name);

    let json_transform_java_port = reserve_host_port()?;
    let json_transform_java_name = format!("cp-rust-sdk-json-transform-java-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &json_transform_java_name,
            "--network",
            &network_name,
            "--network-alias",
            "json-transform",
            "-p",
            &format!("{json_transform_java_port}:8080"),
            JSON_TRANSFORM_JAVA_IMAGE,
        ],
        None,
    )?;
    ctx.register_container(json_transform_java_name);

    let json_transform_native_port = reserve_host_port()?;
    let json_transform_native_name = format!("cp-rust-sdk-json-transform-native-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &json_transform_native_name,
            "--network",
            &network_name,
            "--network-alias",
            "json-transform-lite",
            "-p",
            &format!("{json_transform_native_port}:8080"),
            JSON_TRANSFORM_NATIVE_IMAGE,
        ],
        None,
    )?;
    ctx.register_container(json_transform_native_name);

    let control_plane_port = reserve_host_port()?;
    let control_plane_name = format!("cp-rust-sdk-cp-{suffix}");
    docker_cmd(
        &[
            "run",
            "-d",
            "--name",
            &control_plane_name,
            "--network",
            &network_name,
            "-p",
            &format!("{control_plane_port}:8080"),
            CONTROL_PLANE_IMAGE,
        ],
        None,
    )?;
    ctx.register_container(control_plane_name);

    Ok((
        ctx,
        format!("http://127.0.0.1:{control_plane_port}"),
        word_stats_java_port,
        word_stats_native_port,
        json_transform_java_port,
        json_transform_native_port,
    ))
}

async fn wait_for_endpoint(client: &Client, url: &str) -> Result<(), String> {
    for _ in 0..120 {
        if let Ok(resp) = client.get(url).send().await {
            if resp.status().is_success() {
                return Ok(());
            }
        }
        sleep(Duration::from_millis(250)).await;
    }
    Err(format!("endpoint health check timed out at {url}"))
}

async fn register_pool_function(
    client: &Client,
    base_url: &str,
    name: &str,
    endpoint_url: &str,
) -> Result<(), String> {
    let resp = client
        .post(format!("{base_url}/v1/functions"))
        .json(&json!({
            "name": name,
            "image": "nanofaas/function-runtime:test",
            "executionMode": "POOL",
            "runtimeMode": "HTTP",
            "endpointUrl": endpoint_url,
            "timeoutMillis": 30_000
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

async fn setup_registered_environment() -> (DockerContext, Client, String) {
    let (
        ctx,
        base_url,
        word_stats_java_port,
        word_stats_native_port,
        json_transform_java_port,
        json_transform_native_port,
    ) = start_environment().expect("start docker environment");

    let client = Client::new();
    wait_for_endpoint(&client, &format!("{base_url}/actuator/health"))
        .await
        .expect("wait for control-plane health");
    wait_for_endpoint(
        &client,
        &format!("http://127.0.0.1:{word_stats_java_port}/actuator/health"),
    )
    .await
    .expect("wait for word-stats-java health");
    wait_for_endpoint(
        &client,
        &format!("http://127.0.0.1:{word_stats_native_port}/health"),
    )
    .await
    .expect("wait for word-stats-native health");
    wait_for_endpoint(
        &client,
        &format!("http://127.0.0.1:{json_transform_java_port}/actuator/health"),
    )
    .await
    .expect("wait for json-transform-java health");
    wait_for_endpoint(
        &client,
        &format!("http://127.0.0.1:{json_transform_native_port}/health"),
    )
    .await
    .expect("wait for json-transform-native health");

    register_pool_function(
        &client,
        &base_url,
        "word-stats",
        "http://word-stats:8080/invoke",
    )
    .await
    .expect("register word-stats");
    register_pool_function(
        &client,
        &base_url,
        "word-stats-lite",
        "http://word-stats-lite:8080/invoke",
    )
    .await
    .expect("register word-stats-lite");
    register_pool_function(
        &client,
        &base_url,
        "json-transform",
        "http://json-transform:8080/invoke",
    )
    .await
    .expect("register json-transform");
    register_pool_function(
        &client,
        &base_url,
        "json-transform-lite",
        "http://json-transform-lite:8080/invoke",
    )
    .await
    .expect("register json-transform-lite");

    (ctx, client, base_url)
}

fn word_stats_payload() -> Value {
    json!({
        "input": {
            "text": "the quick brown fox jumps over the lazy dog the dog",
            "topN": 3
        }
    })
}

fn json_transform_count_payload() -> Value {
    json!({
        "input": {
            "data": [
                {"dept": "eng", "salary": 80000},
                {"dept": "sales", "salary": 60000},
                {"dept": "eng", "salary": 90000},
                {"dept": "sales", "salary": 70000},
                {"dept": "eng", "salary": 85000}
            ],
            "groupBy": "dept",
            "operation": "count"
        }
    })
}

#[tokio::test]
async fn wordStats_syncInvoke_returnsCorrectStatistics() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let response = client
        .post(format!("{base_url}/v1/functions/word-stats:invoke"))
        .json(&word_stats_payload())
        .send()
        .await
        .expect("invoke word-stats");
    assert_eq!(200, response.status().as_u16());

    let body: Value = response.json().await.expect("word-stats json");
    assert_eq!("success", body["status"], "{body}");
    assert_eq!(11, body["output"]["wordCount"]);
    assert_eq!(8, body["output"]["uniqueWords"]);
    assert_eq!("the", body["output"]["topWords"][0]["word"]);
    assert_eq!(3, body["output"]["topWords"][0]["count"]);
}

#[tokio::test]
async fn wordStats_stringInput_treatedAsText() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let response = client
        .post(format!("{base_url}/v1/functions/word-stats:invoke"))
        .json(&json!({"input": "hello world hello"}))
        .send()
        .await
        .expect("invoke word-stats string input");
    assert_eq!(200, response.status().as_u16());

    let body: Value = response.json().await.expect("word-stats string json");
    assert_eq!("success", body["status"], "{body}");
    assert_eq!(3, body["output"]["wordCount"]);
    assert_eq!(2, body["output"]["uniqueWords"]);
}

#[tokio::test]
async fn jsonTransform_syncInvoke_groupAndCount() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let response = client
        .post(format!("{base_url}/v1/functions/json-transform:invoke"))
        .json(&json_transform_count_payload())
        .send()
        .await
        .expect("invoke json-transform count");
    assert_eq!(200, response.status().as_u16());

    let body: Value = response.json().await.expect("json-transform count json");
    assert_eq!("success", body["status"], "{body}");
    assert_eq!("dept", body["output"]["groupBy"]);
    assert_eq!("count", body["output"]["operation"]);
    assert_eq!(3, body["output"]["groups"]["eng"]);
    assert_eq!(2, body["output"]["groups"]["sales"]);
}

#[tokio::test]
async fn jsonTransform_syncInvoke_groupAndAvg() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let payload = json!({
        "input": {
            "data": [
                {"dept": "eng", "salary": 80000},
                {"dept": "eng", "salary": 90000}
            ],
            "groupBy": "dept",
            "operation": "avg",
            "valueField": "salary"
        }
    });

    let response = client
        .post(format!("{base_url}/v1/functions/json-transform:invoke"))
        .json(&payload)
        .send()
        .await
        .expect("invoke json-transform avg");
    assert_eq!(200, response.status().as_u16());

    let body: Value = response.json().await.expect("json-transform avg json");
    assert_eq!("success", body["status"], "{body}");
    assert_eq!(Some(85_000.0), body["output"]["groups"]["eng"].as_f64());
}

#[tokio::test]
async fn jsonTransform_asyncInvoke_pollForResult() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let payload = json!({
        "input": {
            "data": [
                {"category": "A", "value": 10},
                {"category": "B", "value": 20},
                {"category": "A", "value": 30}
            ],
            "groupBy": "category",
            "operation": "sum",
            "valueField": "value"
        }
    });

    let enqueue = client
        .post(format!("{base_url}/v1/functions/json-transform:enqueue"))
        .json(&payload)
        .send()
        .await
        .expect("enqueue json-transform");
    assert_eq!(202, enqueue.status().as_u16());
    let enqueue_json: Value = enqueue.json().await.expect("enqueue json");
    let execution_id = enqueue_json["executionId"]
        .as_str()
        .expect("execution id")
        .to_string();

    let drain = client
        .post(format!(
            "{base_url}/v1/internal/functions/json-transform:drain-once"
        ))
        .send()
        .await
        .expect("drain json-transform");
    assert_eq!(200, drain.status().as_u16());

    for _ in 0..40 {
        let status = client
            .get(format!("{base_url}/v1/executions/{execution_id}"))
            .send()
            .await
            .expect("execution status");
        assert_eq!(200, status.status().as_u16());

        let body: Value = status.json().await.expect("execution body");
        if body["status"] == "success" {
            assert_eq!(Some(40.0), body["output"]["groups"]["A"].as_f64(), "{body}");
            assert_eq!(Some(20.0), body["output"]["groups"]["B"].as_f64(), "{body}");
            return;
        }
        sleep(Duration::from_millis(100)).await;
    }

    panic!("json-transform async execution did not reach SUCCESS");
}

#[tokio::test]
async fn wordStatsLite_syncInvoke_returnsCorrectStatistics() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let response = client
        .post(format!("{base_url}/v1/functions/word-stats-lite:invoke"))
        .json(&word_stats_payload())
        .send()
        .await
        .expect("invoke word-stats-lite");
    assert_eq!(200, response.status().as_u16());

    let body: Value = response.json().await.expect("word-stats-lite json");
    assert_eq!("success", body["status"], "{body}");
    assert_eq!(11, body["output"]["wordCount"]);
    assert_eq!(8, body["output"]["uniqueWords"]);
    assert_eq!(3, body["output"]["topWords"].as_array().unwrap().len());
}

#[tokio::test]
async fn jsonTransformLite_syncInvoke_groupAndCount() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let response = client
        .post(format!(
            "{base_url}/v1/functions/json-transform-lite:invoke"
        ))
        .json(&json_transform_count_payload())
        .send()
        .await
        .expect("invoke json-transform-lite count");
    assert_eq!(200, response.status().as_u16());

    let body: Value = response
        .json()
        .await
        .expect("json-transform-lite count json");
    assert_eq!("success", body["status"], "{body}");
    assert_eq!("dept", body["output"]["groupBy"]);
    assert_eq!("count", body["output"]["operation"]);
    assert_eq!(3, body["output"]["groups"]["eng"]);
    assert_eq!(2, body["output"]["groups"]["sales"]);
}

#[tokio::test]
async fn jsonTransformLite_syncInvoke_groupAndAvg() {
    let _guard = docker_test_lock().lock().expect("docker e2e lock");
    if !docker_available() {
        eprintln!("skipping dockerized sdk examples e2e: docker not available");
        return;
    }

    let (_ctx, client, base_url) = setup_registered_environment().await;

    let payload = json!({
        "input": {
            "data": [
                {"dept": "eng", "salary": 80000},
                {"dept": "eng", "salary": 90000}
            ],
            "groupBy": "dept",
            "operation": "avg",
            "valueField": "salary"
        }
    });

    let response = client
        .post(format!(
            "{base_url}/v1/functions/json-transform-lite:invoke"
        ))
        .json(&payload)
        .send()
        .await
        .expect("invoke json-transform-lite avg");
    assert_eq!(200, response.status().as_u16());

    let body: Value = response.json().await.expect("json-transform-lite avg json");
    assert_eq!("success", body["status"], "{body}");
    assert_eq!(Some(85_000.0), body["output"]["groups"]["eng"].as_f64());
}
