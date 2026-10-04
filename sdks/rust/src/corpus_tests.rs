//! Executes `sdks/runtime-contract/saturation-wire-corpus.json` against this runtime: first the
//! shared Python validator, then a typed parse that rejects unknown fields, then every scenario
//! through the real router, callback dispatcher and counters.

// Several model fields exist only so `deny_unknown_fields` proves the whole corpus parses.
#![allow(dead_code)]

use std::collections::{BTreeMap, HashMap};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::Duration;

use axum::body::Body;
use axum::http::{HeaderMap, Request};
use http_body_util::BodyExt;
use serde::Deserialize;
use serde_json::{Value, json};
use tokio::net::TcpListener;
use tokio::sync::{oneshot, watch};
use tokio::task::JoinHandle;
use tower::ServiceExt;

use crate::callback::Identity;
use crate::dispatcher::{CallbackReservation, CallbackSnapshot};
use crate::limits::HandlerReservation;
use crate::runtime::RunState;
use crate::test_support::{FakeCallbackServer, RecordedCallback, Reply};
use crate::types::InvocationResult;
use crate::{BoxError, Context, Error, Runtime, RuntimeSettings};

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Corpus {
    schema_version: String,
    contract_definitions: HashMap<String, Definitions>,
    policy: Policy,
    runtime_configurations: HashMap<String, RuntimeConfig>,
    scenarios: Vec<Scenario>,
    mutation_tests: Vec<Value>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Policy {
    maximum_scenario_deadline_ms: u64,
    definitions_ref: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Definitions {
    vocabulary: HashMap<String, Vec<String>>,
    actor_action_compatibility: HashMap<String, Vec<String>>,
    handler_lifecycles: HashMap<String, HandlerLifecycle>,
    handler_behavior_lifecycle_refs: HashMap<String, Vec<String>>,
    callback_lifecycles: HashMap<String, CallbackLifecycle>,
    callback_behavior_lifecycle_refs: HashMap<String, Vec<String>>,
    wire_outcomes: HashMap<String, WireOutcome>,
    callback_envelopes: HashMap<String, CallbackEnvelope>,
    callback_request_template: CallbackRequestTemplate,
    size_relation_operators: HashMap<String, String>,
    final_counters_rule: Expression,
    identity_rules: Vec<Rule>,
    cross_field_rules: Vec<Rule>,
    observation_sets: HashMap<String, Vec<String>>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HandlerLifecycle {
    started: Option<bool>,
    cancel_requested: Option<bool>,
    terminal: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackLifecycle {
    required: Option<bool>,
    attempted: Option<bool>,
    delivered: Option<bool>,
    attempts: Expression,
    terminal: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct WireOutcome {
    connection_outcome: String,
    status: u16,
    body: Value,
    required_headers: HashMap<String, String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackEnvelope {
    emits_request: Option<bool>,
    payload: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackRequestTemplate {
    method: String,
    url: Expression,
    headers: HashMap<String, Expression>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Expression {
    operator: String,
    value: Option<Value>,
    path: Option<String>,
    callback_url_path: Option<String>,
    execution_id_path: Option<String>,
    suffix: Option<String>,
    field: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Rule {
    id: String,
    scope: String,
    operator: String,
    source: String,
    expected: Option<String>,
    ignore_null: Option<bool>,
    increment: Option<i64>,
    value: Option<Value>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct RuntimeConfig {
    max_concurrent_handlers: usize,
    max_input_bytes: usize,
    max_output_bytes: usize,
    max_pending_callbacks: usize,
    max_pending_callback_bytes: usize,
    handler_timeout_ms: u64,
    callback_attempt_timeout_ms: u64,
    callback_max_attempts: usize,
    body_read_timeout_ms: u64,
    shutdown_timeout_ms: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Scenario {
    id: String,
    kind: String,
    implementation_owners: Vec<String>,
    runtime_config_ref: String,
    requests: Vec<CorpusRequest>,
    backend: Backend,
    harness: HarnessPlan,
    initial_counters: BTreeMap<String, usize>,
    expected: Expected,
    deadline_ms: u64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CorpusRequest {
    id: String,
    role: String,
    method: String,
    path: String,
    metadata: Metadata,
    payload: Payload,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Metadata {
    execution_id: Option<String>,
    dispatch_attempt: Option<u32>,
    trace_id: Option<String>,
    callback_url: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Payload {
    input_bytes: usize,
    relation_to_input_limit: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Backend {
    handlers: Vec<HandlerBackend>,
    callbacks: Vec<CallbackBackend>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HandlerBackend {
    request_id: String,
    behavior: String,
    output_bytes: usize,
    output_relation_to_limit: String,
    barrier: Option<String>,
}

#[derive(Clone, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct CallbackBackend {
    request_id: String,
    behavior: String,
    barrier: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct HarnessPlan {
    barriers: Vec<BarrierPlan>,
    actions: Vec<Action>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct BarrierPlan {
    id: String,
    initial_state: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Action {
    sequence: u32,
    actor: String,
    action: String,
    request_id: Option<String>,
    barrier: Option<String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Expected {
    responses: Vec<ExpectedResponse>,
    handlers: Vec<ExpectedHandler>,
    callbacks: Vec<ExpectedCallback>,
    identity: ExpectedIdentity,
    observation_set_ref: String,
    observations: Vec<String>,
    final_counters: BTreeMap<String, usize>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedResponse {
    request_id: String,
    outcome_ref: String,
    connection_outcome: String,
    status: u16,
    body: Value,
    required_headers: HashMap<String, String>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedHandler {
    request_id: String,
    lifecycle_ref: String,
    started: Option<bool>,
    cancel_requested: Option<bool>,
    terminal: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedCallback {
    request_id: String,
    lifecycle_ref: String,
    envelope_ref: String,
    required: Option<bool>,
    attempted: Option<bool>,
    delivered: Option<bool>,
    attempts: usize,
    terminal: String,
    dispatch_attempts: Vec<u32>,
    request_projection: Option<Projection>,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Projection {
    method: String,
    url: String,
    headers: HashMap<String, String>,
    payload: Value,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct ExpectedIdentity {
    execution_id: Option<String>,
    request_dispatch_attempts: Vec<u32>,
    runtime_redispatch_count: u32,
}

#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn consumes_the_shared_runtime_saturation_wire_contract() {
    let path = corpus_path();
    run_shared_validator(&path, true);
    let corpus: Corpus = serde_json::from_slice(&std::fs::read(&path).unwrap())
        .expect("the corpus parses into the typed model");
    let definitions = &corpus.contract_definitions[&corpus.policy.definitions_ref];
    assert_eq!(
        corpus.scenarios.len(),
        definitions.vocabulary["scenarioKinds"].len()
    );
    assert!(
        !corpus.mutation_tests.is_empty(),
        "mutation fixtures are required"
    );
    let logs = capture_logs();
    for scenario in &corpus.scenarios {
        assert_typed_projection(corpus.policy.maximum_scenario_deadline_ms, scenario);
        let config = &corpus.runtime_configurations[&scenario.runtime_config_ref];
        let deadline = Duration::from_millis(scenario.deadline_ms);
        tokio::time::timeout(deadline, run_scenario(scenario, config, &logs))
            .await
            .unwrap_or_else(|_| panic!("{} exceeded its {deadline:?} deadline", scenario.id));
    }
}

fn corpus_path() -> PathBuf {
    std::env::var_os("NANOFAAS_SATURATION_CORPUS").map_or_else(
        || {
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../runtime-contract/saturation-wire-corpus.json")
        },
        PathBuf::from,
    )
}

/// Runs the language-neutral validator (schema, references, mutations) with a 10 s deadline.
pub(crate) fn run_shared_validator(corpus: &Path, mutations: bool) {
    let validator = corpus.with_file_name("validate_saturation_wire_corpus.py");
    let mut command = Command::new("python3");
    command.arg(&validator);
    if mutations {
        command.arg("--run-mutations");
    }
    let mut child = command
        .arg(corpus)
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .expect("python3 runs the shared validator");
    let deadline = std::time::Instant::now() + Duration::from_secs(10);
    while child.try_wait().unwrap().is_none() {
        if std::time::Instant::now() > deadline {
            let _ = child.kill();
            panic!("the shared validator exceeded its 10 s deadline");
        }
        std::thread::sleep(Duration::from_millis(20));
    }
    let output = child.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "shared validator failed:\n{}{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr)
    );
}

fn assert_typed_projection(maximum_deadline_ms: u64, scenario: &Scenario) {
    let id = &scenario.id;
    assert!(!id.is_empty() && !scenario.kind.is_empty() && !scenario.runtime_config_ref.is_empty());
    assert!(!scenario.requests.is_empty(), "{id}: requests");
    assert_eq!(
        scenario.backend.handlers.len(),
        scenario.requests.len(),
        "{id}: handlers"
    );
    assert_eq!(
        scenario.backend.callbacks.len(),
        scenario.requests.len(),
        "{id}: callbacks"
    );
    assert!(!scenario.harness.actions.is_empty(), "{id}: actions");
    assert!(
        !scenario.expected.observations.is_empty(),
        "{id}: observations"
    );
    assert!(
        scenario.deadline_ms > 0 && scenario.deadline_ms <= maximum_deadline_ms,
        "{id}: deadline"
    );
    for handler in &scenario.expected.handlers {
        assert!(
            handler.started.is_some() && handler.cancel_requested.is_some(),
            "{id}: handler booleans"
        );
    }
    for callback in &scenario.expected.callbacks {
        assert!(
            callback.required.is_some()
                && callback.attempted.is_some()
                && callback.delivered.is_some(),
            "{id}: callback booleans"
        );
        assert!(!callback.lifecycle_ref.is_empty() && !callback.envelope_ref.is_empty());
        assert!(
            callback.attempts == 0 || callback.request_projection.is_some(),
            "{id}: projection"
        );
    }
    assert!(
        scenario
            .expected
            .final_counters
            .values()
            .all(|count| *count == 0),
        "{id}: undrained"
    );
}

/// Captures every `tracing` event as JSON lines, once per test binary.
fn capture_logs() -> Arc<Mutex<Vec<u8>>> {
    static LOGS: OnceLock<Arc<Mutex<Vec<u8>>>> = OnceLock::new();
    LOGS.get_or_init(|| {
        let buffer = Arc::new(Mutex::new(Vec::new()));
        let sink = Arc::clone(&buffer);
        let _ = tracing_subscriber::fmt()
            .json()
            .with_writer(move || LogWriter(Arc::clone(&sink)))
            .try_init();
        buffer
    })
    .clone()
}

struct LogWriter(Arc<Mutex<Vec<u8>>>);

impl Write for LogWriter {
    fn write(&mut self, data: &[u8]) -> std::io::Result<usize> {
        self.0.lock().unwrap().extend_from_slice(data);
        Ok(data.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

#[derive(Clone, Debug, Default)]
struct HandlerObservation {
    started: bool,
    cancel_requested: bool,
    terminal: String,
}

/// What the corpus handler needs: its scripted behavior and where to record what happened.
struct World {
    kind: String,
    max_output_bytes: usize,
    backends: HashMap<String, HandlerBackend>,
    barriers: HashMap<String, watch::Sender<bool>>,
    handlers: Mutex<HashMap<String, HandlerObservation>>,
    redispatched: Mutex<bool>,
}

impl World {
    fn open_barrier(&self, name: &str) {
        self.barriers[name].send_replace(true);
    }

    fn record(&self, request_id: &str, change: impl FnOnce(&mut HandlerObservation)) {
        change(
            self.handlers
                .lock()
                .unwrap()
                .entry(request_id.to_owned())
                .or_default(),
        );
    }
}

/// Records the runtime's cancellation of a blocked handler: its future is dropped.
struct CancelProbe {
    world: Arc<World>,
    request_id: String,
    ctx: Context,
}

impl Drop for CancelProbe {
    fn drop(&mut self) {
        let terminal = if self.world.kind == "handler-timeout" {
            "timed-out"
        } else {
            "cancelled"
        };
        let cancel_requested = self.ctx.is_cancelled();
        self.world.record(&self.request_id, |observed| {
            observed.cancel_requested = cancel_requested;
            observed.terminal = terminal.to_owned();
        });
    }
}

async fn corpus_handler(world: Arc<World>, ctx: Context) -> Result<Value, BoxError> {
    let request_id = ctx.metadata()["requestId"].clone();
    let backend = world.backends[&request_id].clone();
    {
        let mut handlers = world.handlers.lock().unwrap();
        if handlers.contains_key(&request_id) {
            *world.redispatched.lock().unwrap() = true;
            return Err("runtime redispatched a request".into());
        }
        handlers.insert(
            request_id.clone(),
            HandlerObservation {
                started: true,
                ..Default::default()
            },
        );
    }
    if let Some(barrier) = &backend.barrier {
        world.open_barrier(barrier);
    }
    match backend.behavior.as_str() {
        "succeed" if backend.output_relation_to_limit == "above-limit" => {
            world.record(&request_id, |o| o.terminal = "output-rejected".into());
            Ok(Value::String("x".repeat(world.max_output_bytes + 1)))
        }
        "succeed" => {
            world.record(&request_id, |o| o.terminal = "succeeded".into());
            Ok(json!({"result": "ok"}))
        }
        "fail" => {
            world.record(&request_id, |o| o.terminal = "failed".into());
            Err("corpus handler failure".into())
        }
        "block-until-cancelled" => {
            let _probe = CancelProbe {
                world: Arc::clone(&world),
                request_id,
                ctx,
            };
            std::future::pending::<()>().await;
            unreachable!("only cancellation ends a blocked handler")
        }
        other => Err(format!("unexpected invoked behavior {other}").into()),
    }
}

struct Sent {
    task: Option<JoinHandle<(u16, HeaderMap, Vec<u8>)>>,
    result: Option<(u16, HeaderMap, Vec<u8>)>,
    cancelled: bool,
}

struct Harness<'a> {
    scenario: &'a Scenario,
    world: Arc<World>,
    runtime: Arc<Runtime>,
    callbacks: FakeCallbackServer,
    sent: HashMap<String, Sent>,
    stop_signal: Option<oneshot::Sender<()>>,
    served: Option<JoinHandle<Result<(), Error>>>,
    held_callback: Option<CallbackReservation>,
    held_handler: Option<HandlerReservation>,
    starts: usize,
    stops: usize,
}

async fn run_scenario(scenario: &Scenario, config: &RuntimeConfig, logs: &Arc<Mutex<Vec<u8>>>) {
    let mut harness = Harness::new(scenario, config).await;
    let mut initial_checked = false;
    for action in &scenario.harness.actions {
        if !initial_checked
            && matches!(
                action.action.as_str(),
                "send-request" | "probe-health" | "begin-stop"
            )
        {
            harness.verify_counters("initial", &scenario.initial_counters);
            initial_checked = true;
        }
        harness.run(action).await;
    }
    assert!(
        initial_checked,
        "{}: initial counters never observed",
        scenario.id
    );
    harness.drain();
    harness.runtime.shared().limits.wait_until_idle().await;
    if harness.served.is_some() {
        harness.begin_stop();
        harness.await_stop().await;
    }
    harness.verify(logs);
}

impl<'a> Harness<'a> {
    async fn new(scenario: &'a Scenario, config: &RuntimeConfig) -> Self {
        let world = Arc::new(World {
            kind: scenario.kind.clone(),
            max_output_bytes: config.max_output_bytes,
            backends: scenario
                .backend
                .handlers
                .iter()
                .map(|backend| (backend.request_id.clone(), backend.clone()))
                .collect(),
            barriers: scenario
                .harness
                .barriers
                .iter()
                .map(|barrier| (barrier.id.clone(), watch::channel(false).0))
                .collect(),
            handlers: Mutex::new(HashMap::new()),
            redispatched: Mutex::new(false),
        });
        let callbacks = FakeCallbackServer::start(callback_reply(scenario)).await;
        let initial = &scenario.initial_counters;
        let max_callback_payload_bytes = match initial["pendingCallbacks"] {
            0 => config.max_pending_callback_bytes,
            count => initial["pendingCallbackBytes"] / count,
        };
        let settings = RuntimeSettings {
            callback_url: Some(callbacks.url.clone()),
            handler_timeout: Duration::from_millis(config.handler_timeout_ms),
            max_concurrent_handlers: config.max_concurrent_handlers,
            max_input_bytes: config.max_input_bytes,
            max_output_bytes: config.max_output_bytes,
            max_pending_callbacks: config.max_pending_callbacks,
            max_pending_callback_bytes: config.max_pending_callback_bytes,
            max_callback_payload_bytes,
            body_read_timeout: Duration::from_millis(config.body_read_timeout_ms),
            callback_attempt_timeout: Duration::from_millis(config.callback_attempt_timeout_ms),
            callback_max_attempts: config.callback_max_attempts,
            shutdown_timeout: Duration::from_millis(config.shutdown_timeout_ms),
            ..RuntimeSettings::default()
        };
        let handler_world = Arc::clone(&world);
        let runtime = Runtime::with_settings(settings)
            .register("corpus", move |ctx: Context, _: Value| {
                corpus_handler(Arc::clone(&handler_world), ctx)
            })
            .without_callback_backoff();
        Self {
            scenario,
            world,
            runtime: Arc::new(runtime),
            callbacks,
            sent: HashMap::new(),
            stop_signal: None,
            served: None,
            held_callback: None,
            held_handler: None,
            starts: 0,
            stops: 0,
        }
    }

    async fn run(&mut self, action: &Action) {
        let request_id = || {
            action
                .request_id
                .clone()
                .expect("the action names a request")
        };
        let barrier = || action.barrier.clone().expect("the action names a barrier");
        match action.action.as_str() {
            "start-runtime" | "start-runtime-again" => self.start().await,
            "send-request" | "probe-health" => self.send(&request_id()),
            "await-response" => self.await_response(&request_id()).await,
            "await-callback" => self.await_callback(&request_id()).await,
            "await-barrier" => {
                let mut opened = self.world.barriers[&barrier()].subscribe();
                opened.wait_for(|open| *open).await.unwrap();
            }
            "release-barrier" => self.world.open_barrier(&barrier()),
            "cancel-request" => {
                let sent = self.sent.get_mut(&request_id()).unwrap();
                sent.task.take().unwrap().abort();
                sent.cancelled = true;
            }
            "fill-callback-capacity" | "fill-handler-capacity" => self.fill_capacity().await,
            "drain-callbacks" | "drain-handlers" => self.drain(),
            "begin-stop" => {
                self.begin_stop();
                let mut state = self.runtime.state();
                // A fast stop can already be back to Idle: anything but Running means it began.
                state
                    .wait_for(|state| *state != RunState::Running)
                    .await
                    .unwrap();
                if let Some(barrier) = &action.barrier {
                    self.world.open_barrier(barrier);
                }
            }
            "await-stop" => self.await_stop().await,
            // The next send-request is deliberately a new control-plane request.
            "control-plane-redispatch" => {}
            other => panic!("unsupported corpus action {other}"),
        }
    }

    async fn start(&mut self) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (stop, stopped) = oneshot::channel::<()>();
        let runtime = Arc::clone(&self.runtime);
        let served = tokio::spawn(async move {
            runtime
                .serve(listener, async move {
                    let _ = stopped.await;
                })
                .await
        });
        self.runtime
            .state()
            .wait_for(|state| *state == RunState::Running)
            .await
            .unwrap();
        self.stop_signal = Some(stop);
        self.served = Some(served);
        self.starts += 1;
    }

    fn begin_stop(&mut self) {
        let _ = self.stop_signal.take().expect("serving").send(());
    }

    async fn await_stop(&mut self) {
        let served = self.served.take().expect("serving");
        served.await.unwrap().expect("the runtime stops cleanly");
        self.stops += 1;
    }

    fn send(&mut self, request_id: &str) {
        let request = self.request(request_id);
        let mut builder = Request::builder()
            .method(request.method.as_str())
            .uri(&request.path);
        let metadata = &request.metadata;
        if let Some(id) = &metadata.execution_id {
            builder = builder.header("x-execution-id", id);
        }
        if let Some(id) = &metadata.trace_id {
            builder = builder.header("x-trace-id", id);
        }
        if let Some(attempt) = metadata.dispatch_attempt {
            builder = builder.header("x-dispatch-attempt", attempt.to_string());
        }
        let body = match request.role.as_str() {
            "invoke" => Body::from(sized_body(request_id, request.payload.input_bytes)),
            _ => Body::empty(),
        };
        let router = self.runtime.router();
        let request = builder.body(body).unwrap();
        let task = tokio::spawn(async move {
            let response = router.oneshot(request).await.unwrap();
            let (parts, body) = response.into_parts();
            let bytes = body.collect().await.unwrap().to_bytes().to_vec();
            (parts.status.as_u16(), parts.headers, bytes)
        });
        self.sent.insert(
            request_id.to_owned(),
            Sent {
                task: Some(task),
                result: None,
                cancelled: false,
            },
        );
    }

    async fn await_response(&mut self, request_id: &str) {
        let sent = self.sent.get_mut(request_id).expect("sent");
        sent.result = Some(sent.task.take().expect("pending").await.unwrap());
    }

    async fn await_callback(&self, request_id: &str) {
        let expected = self.expected_callback(request_id);
        let execution_id = self.request(request_id).metadata.execution_id.clone();
        self.callbacks
            .wait_for(expected.attempts, |recorded| {
                Some(execution_id_of(recorded)) == execution_id.as_deref()
            })
            .await;
        if expected.attempts > 0 {
            self.runtime.shared().dispatcher.wait_until_idle().await;
        }
    }

    /// Brings the counters to the scenario's `initialCounters`: a held callback reservation
    /// (optionally serialized and parked in delivery) and a held handler slot.
    async fn fill_capacity(&mut self) {
        let want = &self.scenario.initial_counters;
        let shared = self.runtime.shared();
        if want["pendingCallbacks"] > 0 && shared.dispatcher.snapshot().pending_callbacks == 0 {
            let reservation = shared.dispatcher.try_reserve().expect("callback capacity");
            let serialized = want["serializedCallbackBytes"];
            if serialized == 0 {
                self.held_callback = Some(reservation);
            } else {
                let base = serde_json::to_vec(&InvocationResult::failure("CAPACITY", ""))
                    .unwrap()
                    .len();
                let padding = "x".repeat(serialized - base);
                let identity = Identity {
                    execution_id: "capacity".into(),
                    trace_id: None,
                    dispatch_attempt: Some("1".into()),
                };
                shared
                    .dispatcher
                    .submit(
                        reservation,
                        &identity,
                        &InvocationResult::failure("CAPACITY", padding),
                    )
                    .unwrap_or_else(|_| panic!("capacity callback refused"));
                self.callbacks
                    .wait_for(1, |r| r.path == "/capacity:complete")
                    .await;
            }
        }
        if want["activeHandlers"] > 0 && self.held_handler.is_none() {
            let reservation = shared
                .limits
                .try_reserve_handler()
                .expect("handler capacity");
            reservation.retain_input(want["inputBytes"]);
            self.held_handler = Some(reservation);
        }
    }

    fn drain(&mut self) {
        self.callbacks.release_held();
        self.held_callback = None;
        self.held_handler = None;
    }

    fn verify(&self, logs: &Arc<Mutex<Vec<u8>>>) {
        let id = &self.scenario.id;
        for expected in &self.scenario.expected.responses {
            let sent = &self.sent[&expected.request_id];
            if expected.connection_outcome == "client-disconnected" {
                assert!(
                    sent.cancelled && sent.result.is_none(),
                    "{id}: expected no response"
                );
                continue;
            }
            let (status, headers, body) = sent.result.as_ref().expect("response awaited");
            assert_eq!(*status, expected.status, "{id}: status");
            let body: Value = serde_json::from_slice(body).unwrap();
            assert_eq!(body, expected.body, "{id}: body");
            for (name, value) in &expected.required_headers {
                let actual = headers.get(name).map(|v| v.to_str().unwrap().to_owned());
                assert!(
                    actual.is_some_and(|a| a.eq_ignore_ascii_case(value)),
                    "{id}: header {name}"
                );
            }
        }
        let handlers = self.world.handlers.lock().unwrap().clone();
        for expected in &self.scenario.expected.handlers {
            let actual = handlers.get(&expected.request_id);
            if expected.started == Some(false) {
                assert!(actual.is_none(), "{id}: handler unexpectedly started");
                continue;
            }
            let actual = actual.unwrap_or_else(|| panic!("{id}: handler never started"));
            assert!(actual.started, "{id}: started");
            assert_eq!(
                Some(actual.cancel_requested),
                expected.cancel_requested,
                "{id}: cancel"
            );
            assert_eq!(actual.terminal, expected.terminal, "{id}: terminal");
        }
        for expected in &self.scenario.expected.callbacks {
            self.verify_callback(expected);
        }
        self.verify_counters("final", &self.scenario.expected.final_counters);
        self.verify_observations(&handlers, logs);
    }

    fn verify_callback(&self, expected: &ExpectedCallback) {
        let id = &self.scenario.id;
        let request = self.request(&expected.request_id);
        let attempts: Vec<RecordedCallback> = self
            .callbacks
            .received()
            .into_iter()
            .filter(|r| {
                Some(execution_id_of(r)) == request.metadata.execution_id.as_deref()
                    && r.headers
                        .get("x-dispatch-attempt")
                        .and_then(|v| v.to_str().ok())
                        == request
                            .metadata
                            .dispatch_attempt
                            .map(|a| a.to_string())
                            .as_deref()
            })
            .collect();
        assert_eq!(attempts.len(), expected.attempts, "{id}: callback attempts");
        let delivered = attempts.iter().any(|r| (200..300).contains(&r.status));
        assert_eq!(Some(delivered), expected.delivered, "{id}: delivered");
        let dispatch: Vec<u32> = attempts
            .iter()
            .map(|r| {
                r.headers["x-dispatch-attempt"]
                    .to_str()
                    .unwrap()
                    .parse()
                    .unwrap()
            })
            .collect();
        assert_eq!(
            dispatch, expected.dispatch_attempts,
            "{id}: dispatch attempts"
        );
        if let (Some(projection), Some(last)) = (&expected.request_projection, attempts.last()) {
            let path = projection.url.split_once("://").unwrap().1;
            let path = &path[path.find('/').unwrap()..];
            assert_eq!(last.method, projection.method, "{id}: callback method");
            assert_eq!(last.path, path, "{id}: callback path");
            assert_eq!(last.body, projection.payload, "{id}: callback payload");
            for (name, value) in &projection.headers {
                assert_eq!(
                    last.headers[name.as_str()],
                    value.as_str(),
                    "{id}: callback {name}"
                );
            }
        }
    }

    fn verify_counters(&self, phase: &str, expected: &BTreeMap<String, usize>) {
        let shared = self.runtime.shared();
        let limits = shared.limits.snapshot();
        let callbacks: CallbackSnapshot = shared.dispatcher.snapshot();
        let actual = BTreeMap::from([
            ("activeHandlers".to_owned(), limits.active_handlers),
            ("inputBytes".to_owned(), limits.input_bytes),
            ("outputBytes".to_owned(), limits.output_bytes),
            ("pendingCallbacks".to_owned(), callbacks.pending_callbacks),
            (
                "pendingCallbackBytes".to_owned(),
                callbacks.pending_callback_bytes,
            ),
            (
                "serializedCallbackBytes".to_owned(),
                callbacks.serialized_callback_bytes,
            ),
        ]);
        assert_eq!(&actual, expected, "{}: {phase} counters", self.scenario.id);
    }

    fn verify_observations(
        &self,
        handlers: &HashMap<String, HandlerObservation>,
        logs: &Arc<Mutex<Vec<u8>>>,
    ) {
        let shared = self.runtime.shared();
        let real: Vec<RecordedCallback> = self
            .callbacks
            .received()
            .into_iter()
            .filter(|r| r.path != "/capacity:complete")
            .collect();
        let mut observed: HashMap<&str, bool> = HashMap::new();
        observed.insert(
            "wire-response",
            self.sent
                .values()
                .any(|s| s.result.as_ref().is_some_and(|r| !r.2.is_empty())),
        );
        observed.insert(
            "no-wire-response",
            self.sent
                .values()
                .any(|s| s.cancelled && s.result.is_none()),
        );
        observed.insert(
            "health-response",
            self.sent.iter().any(|(id, s)| {
                self.request(id).role == "health" && s.result.as_ref().is_some_and(|r| r.0 == 200)
            }),
        );
        observed.insert("handler-start", handlers.values().any(|h| h.started));
        observed.insert(
            "handler-cancel",
            handlers.values().any(|h| h.cancel_requested),
        );
        observed.insert("callback-attempt", !real.is_empty());
        observed.insert(
            "callback-delivery",
            real.iter().any(|r| (200..300).contains(&r.status)),
        );
        observed.insert("stop-complete", self.stops > 0);
        observed.insert("restart-complete", self.starts > 1 && self.stops > 0);
        let limits = shared.limits.snapshot();
        observed.insert(
            "counters-zero",
            (
                limits.active_handlers,
                limits.input_bytes,
                limits.output_bytes,
            ) == (0, 0, 0)
                && shared.dispatcher.snapshot() == CallbackSnapshot::default(),
        );
        observed.insert(
            "callback-failure-metric",
            shared.metrics.callback_drops.get() > 0,
        );
        observed.insert(
            "runtime-redispatch-zero",
            !*self.world.redispatched.lock().unwrap() && handlers.len() <= self.sent.len(),
        );
        let execution_ids: Vec<&str> = self
            .scenario
            .requests
            .iter()
            .filter_map(|r| r.metadata.execution_id.as_deref())
            .collect();
        observed.insert("structured-log", logged_exhaustion(logs, &execution_ids));
        for name in &self.scenario.expected.observations {
            assert!(
                observed.get(name.as_str()).copied().unwrap_or(false),
                "{}: missing observation {name} in {observed:?}",
                self.scenario.id
            );
        }
    }

    fn request(&self, id: &str) -> &'a CorpusRequest {
        self.scenario
            .requests
            .iter()
            .find(|r| r.id == id)
            .expect("known request")
    }

    fn expected_callback(&self, id: &str) -> &'a ExpectedCallback {
        self.scenario
            .expected
            .callbacks
            .iter()
            .find(|c| c.request_id == id)
            .expect("known callback")
    }
}

/// Every callback backend reply the corpus scripts: the capacity fixture parks in delivery until
/// drained; `retryable-failure` always answers 503; everything else is delivered.
fn callback_reply(
    scenario: &Scenario,
) -> impl Fn(&RecordedCallback) -> Reply + Send + Sync + 'static {
    let failing: Vec<(String, String)> = scenario
        .backend
        .callbacks
        .iter()
        .filter(|backend| backend.behavior == "retryable-failure")
        .filter_map(|backend| {
            let request = scenario
                .requests
                .iter()
                .find(|r| r.id == backend.request_id)?;
            Some((
                request.metadata.execution_id.clone()?,
                request.metadata.dispatch_attempt?.to_string(),
            ))
        })
        .collect();
    move |recorded| {
        if recorded.path == "/capacity:complete" {
            return Reply::HoldThen(204);
        }
        let attempt = recorded
            .headers
            .get("x-dispatch-attempt")
            .and_then(|v| v.to_str().ok())
            .unwrap_or_default();
        let key = (execution_id_of(recorded).to_owned(), attempt.to_owned());
        Reply::Status(if failing.contains(&key) { 503 } else { 204 })
    }
}

fn execution_id_of(recorded: &RecordedCallback) -> &str {
    recorded
        .path
        .trim_start_matches('/')
        .trim_end_matches(":complete")
}

/// `{"input":"xxx…","metadata":{"requestId":"<id>"}}`, exactly `size` bytes long.
fn sized_body(request_id: &str, size: usize) -> String {
    let prefix = r#"{"input":""#;
    let suffix = format!(r#"","metadata":{{"requestId":"{request_id}"}}}}"#);
    let padding = size
        .checked_sub(prefix.len() + suffix.len())
        .expect("the corpus size holds the request identity");
    let body = format!("{prefix}{}{suffix}", "x".repeat(padding));
    assert_eq!(body.len(), size);
    body
}

fn logged_exhaustion(logs: &Arc<Mutex<Vec<u8>>>, execution_ids: &[&str]) -> bool {
    let logs = logs.lock().unwrap();
    String::from_utf8_lossy(&logs).lines().any(|line| {
        let Ok(record) = serde_json::from_str::<Value>(line) else {
            return false;
        };
        let fields = &record["fields"];
        fields["message"] == "callback delivery exhausted"
            && fields["execution_id"]
                .as_str()
                .is_some_and(|id| execution_ids.contains(&id))
    })
}
