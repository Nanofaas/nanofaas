# Offload Load-Test Experiment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A `offload-loadtest` scenario that drives k6 load against an edge control plane on one Multipass VM, proves part of an offloadable function's traffic lands on a cloud control plane on a second VM, and cross-checks k6 header counts against both control planes' Prometheus metrics.

**Architecture:** Add a third execution role `cloud` through the existing role plumbing (Literal, RoleBindings, bindings, provisioning). The new workflow reuses `k8s_deployment_specs`/Helm for both control-plane VMs (cloud specs retargeted by `dataclasses.replace(spec, role="cloud")`), registers two functions with different offload policies, runs a two-scenario k6 script that counts `X-NanoFaaS-Offloaded` headers, snapshots Prometheus on both VMs, and evaluates a conservation chain (k6 == edge == cloud) in a pure, unit-tested checker.

**Tech Stack:** Python 3.11+ (workflow-tasks + controlplane-tool), k6 JS, Multipass, k3s + Helm, Prometheus.

Spec: `docs/superpowers/specs/2026-07-20-offload-loadtest-experiment-design.md`.

## Global Constraints

- Provider v1: Multipass, three VMs (`stack` = edge, `cloud`, `loadgen`).
- Deployment: k3s + Helm on stack AND cloud; one control-plane image built once with modules `offload,async-queue,sync-queue,k8s-deployment-provider`; edge gets `NANOFAAS_OFFLOAD_TARGETURL` via `controlPlane.extraEnv`.
- Functions: `word-stats-java` (pressure-offloadable, registered on edge AND cloud), `json-transform-java` (`offload: {enabled: false}`, registered ONLY on edge).
- Autoscaler off; fixed replicas; low function concurrency so k6 saturates.
- Conservation tolerance: absolute 5 requests per equality (in-flight at teardown).
- Existing suites must stay green: `cd tools/workflow-tasks && uv run pytest tests`, `cd tools/controlplane && uv run pytest tests`, plus ruff + basedpyright in both packages.
- Run `gitnexus_impact` before editing `build_role_bindings`, `provision_environment`, `k8s_deployment_specs` consumers; `gitnexus_detect_changes(scope="staged")` before each commit.

---

### Task 1: Add the `cloud` execution role to the shared plumbing

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/execution/roles.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/execution/bindings.py`
- Test: `tools/workflow-tasks/tests/execution/test_bindings.py` (add cases)
- Modify: `tools/controlplane/src/controlplane_tool/cli/execution.py` (`build_role_bindings`)
- Modify: `tools/controlplane/src/controlplane_tool/cli/provisioning.py` (`provision_environment` + `_request`)
- Test: `tools/controlplane/tests/cli/test_execution_bindings.py` (create if missing; follow the existing test module for `build_role_bindings` if one exists — search `grep -rn build_role_bindings tools/controlplane/tests`)

**Interfaces:**
- Produces: `ExecutionRole = Literal["host", "stack", "loadgen", "cloud"]`; `RoleBindings(host, stack, loadgen=None, cloud=None)`; `build_role_bindings` returns a bindings object with `.cloud` populated whenever `"cloud" in environment.roles`; `provision_environment` creates/destroys the cloud VM with stack-class provisioning (k3s) when `scenario.workflow == "offload-loadtest"`.

- [ ] **Step 1: Write the failing bindings test** (workflow-tasks)

```python
# tools/workflow-tasks/tests/execution/test_bindings.py  (append)
def test_role_bindings_resolve_the_cloud_role() -> None:
    host = object()
    cloud = object()
    bindings = RoleBindings(host=host, stack=host, cloud=cloud)  # type: ignore[arg-type]
    assert bindings.executor_for("cloud") is cloud


def test_missing_cloud_binding_raises() -> None:
    host = object()
    bindings = RoleBindings(host=host, stack=host)  # type: ignore[arg-type]
    with pytest.raises(ValueError, match="cloud"):
        bindings.executor_for("cloud")
```

- [ ] **Step 2: Run it** — `cd tools/workflow-tasks && uv run pytest tests/execution/test_bindings.py -q --no-cov` — expect FAIL (unexpected keyword `cloud`).

- [ ] **Step 3: Extend the role plumbing (workflow-tasks)**

```python
# roles.py
ExecutionRole = Literal["host", "stack", "loadgen", "cloud"]

# bindings.py — add the field after loadgen
    loadgen: CommandTaskExecutor | None = None
    cloud: CommandTaskExecutor | None = None
```

- [ ] **Step 4: Rerun Step 2** — expect PASS. Then the full workflow-tasks suite: `uv run pytest tests -q`.

- [ ] **Step 5: Extend `build_role_bindings` (controlplane)** — in ALL provider branches, mirror the existing `loadgen` handling exactly:

```python
# local branch:
        return RoleBindings(host=host, stack=host, loadgen=host, cloud=host), None
# azure/proxmox branch, after loadgen_result:
        cloud_result = provider_remote("cloud") if "cloud" in environment.roles else None
        cloud = cloud_result[0] if cloud_result else None
        return (
            RoleBindings(host=host, stack=stack, loadgen=loadgen, cloud=cloud),
            VmFileFetcher(provider, fetch_request),
        )
# multipass/external tail branch (the generic `remote(role)` one): same pattern —
        cloud = remote("cloud")[0] if "cloud" in environment.roles else None
```

Note: the cloud executor must get the SAME `KUBECONFIG` default env treatment as `stack` (it runs kubectl/helm). In `provider_remote`/`remote`, change the condition to `if role in ("stack", "cloud")` and read the kubeconfig from `environment.target(role)`.

- [ ] **Step 6: Extend `provision_environment`** — read the function fully first. Mirror the `dedicated_loadgen` block for cloud:

```python
        dedicated_cloud = scenario.workflow == "offload-loadtest" and "cloud" in environment.roles
        if dedicated_cloud:
            cloud_request = _request(environment, "cloud", loadtest=True)
            cloud_cleanup = _destroy_task(orchestrator, cloud_request, role="cloud")
            if cloud_cleanup is not None:
                cleanup_tasks.append(cloud_cleanup)
            _ensure(orchestrator, cloud_request, post_ensure_verifier, role="cloud")
```

The cloud VM must receive STACK-class provisioning (k3s + docker), not loadgen-class: inspect `_request` and the ansible/base provisioning selection for the stack role and reuse the same path for `cloud` (if provisioning is keyed on role name, map `cloud -> stack profile`; keep the VM NAME from `environment.roles["cloud"]`).

- [ ] **Step 7: Add a controlplane test** for bindings with a 3-role multipass environment asserting `.cloud` is not None and its kubeconfig env matches the stack pattern. Run `cd tools/controlplane && uv run pytest tests -q` — full suite green (the HIGH-blast-radius check: no existing loadtest/validate test may change).

- [ ] **Step 8: Commit** — `git commit -m "Add cloud execution role"` (after `gitnexus_detect_changes(scope="staged")`).

### Task 2: Scenario type, environment file, and function policy model

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/config/scenario.py`
- Create: `tools/controlplane/environments/multipass-offload.yaml`
- Test: `tools/controlplane/tests/config/test_scenario_offload_loadtest.py`

**Interfaces:**
- Produces: `WorkflowName` includes `"offload-loadtest"`; `ScenarioConfig(workflow="offload-loadtest", functions=[...])` valid with no backend; environment file with roles stack/cloud/loadgen parseable by `EnvironmentConfig`.

- [ ] **Step 1: Failing test**

```python
from controlplane_tool.config.environment import EnvironmentConfig
from controlplane_tool.config.scenario import ScenarioConfig
from pathlib import Path
import pytest, yaml

REPO_ROOT = Path(__file__).resolve().parents[4]

def test_offload_loadtest_scenario_parses_without_backend() -> None:
    config = ScenarioConfig(
        workflow="offload-loadtest",
        functions=["word-stats-java", "json-transform-java"],
    )
    assert config.autoscaling is False

def test_offload_loadtest_rejects_autoscaling() -> None:
    with pytest.raises(ValueError, match="autoscaling"):
        ScenarioConfig(
            workflow="offload-loadtest",
            functions=["word-stats-java"],
            autoscaling=True,
        )

def test_multipass_offload_environment_has_three_roles() -> None:
    payload = yaml.safe_load(
        (REPO_ROOT / "tools/controlplane/environments/multipass-offload.yaml").read_text()
    )
    environment = EnvironmentConfig.model_validate(payload)
    assert set(environment.roles) == {"stack", "cloud", "loadgen"}
```

- [ ] **Step 2: Run** — expect FAIL (literal rejects `offload-loadtest`).

- [ ] **Step 3: Implement** — `WorkflowName = Literal["validate", "cli", "loadtest", "offload", "offload-loadtest"]`; in the validator, extend the autoscaling rule (`self.autoscaling and self.workflow != "loadtest"` already rejects it — verify the error message matches, adjust the test regex if needed) and add nothing else. Environment file:

```yaml
provider: multipass
roles:
  stack:
    name: nanofaas-edge
  cloud:
    name: nanofaas-cloud
  loadgen:
    name: nanofaas-loadgen
    cpus: 2
    memory: 2G
    disk: 10G
```

- [ ] **Step 4: Run tests + full controlplane suite.** Commit `Add offload-loadtest scenario type and environment`.

### Task 3: k6 mixed-policy script

**Files:**
- Create: `tools/controlplane/assets/k6/offload-mixed.js`

**Interfaces:**
- Consumes env vars set by Task 4's `K6Config.env`: `NANOFAAS_URL`, `OFFLOADABLE_FUNCTION`, `CONTROL_FUNCTION`, `OFFLOADABLE_RATE`, `CONTROL_RATE`, `DURATION`.
- Produces (in the k6 summary JSON): counters `offloaded_requests` and `control_429` plus standard `http_reqs` etc., tagged by `function`.

- [ ] **Step 1: Write the script** (no unit test — validated by `k6 inspect` in Task 6 and by the real run in Task 8):

```javascript
import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

const BASE_URL = __ENV.NANOFAAS_URL || 'http://localhost:8080';
const OFFLOADABLE = __ENV.OFFLOADABLE_FUNCTION || 'word-stats-java';
const CONTROL = __ENV.CONTROL_FUNCTION || 'json-transform-java';
const DURATION = __ENV.DURATION || '60s';

const offloadedRequests = new Counter('offloaded_requests');
const control429 = new Counter('control_429');

export const options = {
    scenarios: {
        offloadable: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.OFFLOADABLE_RATE || 20),
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 60,
            env: { FUNCTION: OFFLOADABLE, KIND: 'offloadable' },
        },
        control: {
            executor: 'constant-arrival-rate',
            rate: Number(__ENV.CONTROL_RATE || 20),
            timeUnit: '1s',
            duration: DURATION,
            preAllocatedVUs: 60,
            env: { FUNCTION: CONTROL, KIND: 'control' },
        },
    },
    thresholds: {
        // the control function is EXPECTED to shed load as 429s
        'http_req_failed{kind:offloadable}': ['rate<0.05'],
    },
};

export default function () {
    const fn = __ENV.FUNCTION;
    const kind = __ENV.KIND;
    const res = http.post(
        `${BASE_URL}/v1/functions/${fn}:invoke`,
        JSON.stringify({ input: { text: 'the quick brown fox', seq: __ITER } }),
        {
            headers: { 'Content-Type': 'application/json' },
            timeout: '30s',
            tags: { function: fn, kind: kind },
        },
    );
    const offloaded = res.headers['X-Nanofaas-Offloaded'] !== undefined
        || res.headers['X-NanoFaaS-Offloaded'] !== undefined;
    if (res.status === 200 && offloaded) {
        offloadedRequests.add(1, { function: fn });
    }
    if (res.status === 429) {
        control429.add(1, { function: fn });
    }
    check(res, {
        'offloadable is 200': (r) => kind !== 'offloadable' || r.status === 200,
        'control is 200 or 429': (r) => kind !== 'control' || r.status === 200 || r.status === 429,
        'control never offloaded': (r) => kind !== 'control' || !offloaded,
    }, { function: fn, kind: kind });
}
```

- [ ] **Step 2: Sanity-check locally** — `k6 inspect tools/controlplane/assets/k6/offload-mixed.js` (if k6 is installed on the host; otherwise defer to Task 8). Commit `Add mixed-policy offload k6 script`.

### Task 4: `offload_loadtest` workflow specs (workflow-tasks)

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/workflows/offload_loadtest.py`
- Test: `tools/workflow-tasks/tests/workflows/test_offload_loadtest.py`

**Interfaces:**
- Consumes: `ValidateFunction`, `ValidateWorkflowRequest`, `k8s_deployment_specs`, `registration_specs` internals (`_curl` pattern), `CommandTaskSpec`.
- Produces:
  - `OffloadLoadtestRequest(offloadable: ValidateFunction, control: ValidateFunction, build: Build = "docker", namespace: str = "nanofaas-e2e", registry: str = "localhost:5000")`
  - `edge_deployment_specs(request, offload_target_url: str) -> tuple[CommandTaskSpec, ...]` — role `stack`, extraEnv with `NANOFAAS_OFFLOAD_TARGETURL=<offload_target_url>`, modules `offload,async-queue,sync-queue`.
  - `cloud_deployment_specs(request) -> tuple[CommandTaskSpec, ...]` — the SAME `k8s_deployment_specs` output retargeted with `dataclasses.replace(spec, role="cloud", task_id="cloud." + spec.task_id)`, no offload env.
  - `offload_registration_specs(request) -> tuple[CommandTaskSpec, ...]` — edge: both functions (`json-transform` body carries `"offload":{"enabled":false}`, low `concurrency=2`, `queueSize=8` for both); cloud: `word-stats-java` only.

Key implementation notes (repeat in code):
- edge Helm extra values: append `--set controlPlane.extraEnv[0].name=NANOFAAS_OFFLOAD_TARGETURL --set controlPlane.extraEnv[0].value=<url>` to the `helm.deploy.control-plane` spec via `replace(spec, argv=spec.argv + extra)` — do NOT modify `k8s_deployment_specs` (HIGH blast radius; retarget/extend by `replace` only).
- both deployments use `ValidateWorkflowRequest(backend="k8s", functions=(...), additional_modules=("offload", "async-queue", "sync-queue"))`; cloud request's functions = `(offloadable,)` only.

- [ ] **Step 1: Failing tests** (representative — write all of these):

```python
def test_edge_specs_carry_the_offload_target_env() -> None:
    specs = edge_deployment_specs(_request(), "http://10.0.0.9:30080")
    helm = next(s for s in specs if s.task_id == "helm.deploy.control-plane")
    joined = " ".join(helm.argv)
    assert "controlPlane.extraEnv[0].name=NANOFAAS_OFFLOAD_TARGETURL" in joined
    assert "controlPlane.extraEnv[0].value=http://10.0.0.9:30080" in joined
    assert all(s.role == "stack" for s in specs)

def test_cloud_specs_are_retargeted_and_have_no_offload_env() -> None:
    specs = cloud_deployment_specs(_request())
    assert all(s.role == "cloud" for s in specs)
    assert all(s.task_id.startswith("cloud.") for s in specs)
    assert not any("OFFLOAD" in " ".join(s.argv) for s in specs)

def test_registrations_encode_the_two_policies() -> None:
    specs = offload_registration_specs(_request())
    by_id = {s.task_id: s for s in specs}
    edge_control = " ".join(by_id["offload-loadtest.register.edge.json-transform-java"].argv)
    assert '"offload":{"enabled":false}' in edge_control
    edge_offloadable = " ".join(by_id["offload-loadtest.register.edge.word-stats-java"].argv)
    assert '"offload"' not in edge_offloadable  # pressure default
    assert "offload-loadtest.register.cloud.word-stats-java" in by_id
    assert "offload-loadtest.register.cloud.json-transform-java" not in by_id
    cloud = by_id["offload-loadtest.register.cloud.word-stats-java"]
    assert cloud.role == "cloud"
```

- [ ] **Step 2: Run, expect import failure. Step 3: implement. Step 4: focused tests pass, full workflow-tasks suite green. Step 5: commit** `Add offload-loadtest workflow specs`.

### Task 5: Conservation checker (pure, unit-tested)

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/offload_conservation.py`
- Test: `tools/workflow-tasks/tests/loadtest/test_offload_conservation.py`

**Interfaces:**
- Produces:

```python
@dataclass(frozen=True)
class ConservationReport:
    passed: bool
    failures: tuple[str, ...]
    numbers: dict[str, float]

def evaluate_conservation(
    *,
    k6_summary: Mapping[str, Any],
    edge_metrics: str,   # raw prometheus text from the edge
    cloud_metrics: str,  # raw prometheus text from the cloud
    offloadable: str,
    control: str,
    tolerance: int = 5,
) -> ConservationReport: ...
```

Checks (each producing a named failure string when violated):
1. `k6 http_reqs 200 for offloadable` vs edge `function_success_total{function=offloadable}` within tolerance.
2. `k6 offloaded_requests{function=offloadable}` vs edge `nanofaas_offload_total{function=offloadable}` summed over triggers `depth`+`est_wait`, vs cloud `function_success_total{function=offloadable}` — pairwise within tolerance; also `> 0` (the experiment must actually offload).
3. control: edge `nanofaas_offload_total{function=control}` == 0 or absent; cloud text contains NO meter mentioning the control function.
4. `nanofaas_offload_failure_total` absent/0 on the edge; `function_retry_total` 0 for both functions on the edge.

Parsing helpers: reuse the prometheus text parsing already present in `workflow_tasks/loadtest/prometheus.py` if it parses raw text; otherwise implement a small `_metric_value(text, name, labels) -> float` with a line-prefix match (same technique as `OffloadPressureE2eTest.metric`). k6 summary: counters live under `metrics.offloaded_requests.values.count` (verify against a fixture captured from a real k6 run; per-tag breakdown requires `--summary-trend-stats` defaults plus tagged sub-metrics `offloaded_requests{function:...}` — check a real summary in Task 8 and adjust the fixture then; write the parser against submetric keys defensively: fall back to the untagged counter when submetrics are missing).

- [ ] Steps: failing tests with THREE fixtures (all-good; offload-count mismatch beyond tolerance; control leaked to cloud) → implement → focused pass → suite green → commit `Add offload conservation checker`.

### Task 6: Plan assembly + CLI dispatch

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/plans/offload_loadtest.py`
- Modify: `tools/controlplane/src/controlplane_tool/cli/product.py`
- Test: `tools/controlplane/tests/plans/test_offload_loadtest.py`

**Interfaces:**
- Produces: `build_offload_loadtest_plan(config, environment, bindings, *, run_dir, repo_root=None, fetcher=None) -> Workflow`.
- Consumes: Task 4 spec builders, Task 5 checker, `build_loadtest_workflow`/`RunK6`/`CapturePrometheusSnapshot` from `workflow_tasks.loadtest`, `workflow_from_specs`.

Assembly order (mirrors the spec):
1. cloud deployment specs, 2. edge deployment specs (offload target = `http://<cloud-ip>:30080`, where the cloud IP comes from `environment.target("cloud")` / the connectivity discovery used for `control_plane_url` today — read `cli/product.py` around `control_plane_url` to reuse the same mechanism for a `cloud_url`), 3. registrations, 4. k6 install on loadgen (reuse `install_k6_task`), 5. `RunK6` with `K6Config(script_path=assets/k6/offload-mixed.js, target_url=edge_url, env={"NANOFAAS_URL": edge_url, "OFFLOADABLE_FUNCTION": ..., "CONTROL_FUNCTION": ..., "OFFLOADABLE_RATE": "20", "CONTROL_RATE": "20", "DURATION": "60s"}, summary_output_path=...)`, 6. Prometheus snapshot from EDGE (`http://<edge>:30090`) and from CLOUD (`http://<cloud>:30090`), 7. a `FunctionTask` running `evaluate_conservation` on the fetched artifacts and writing `run_dir/offload-report.json`, failing the workflow when `passed` is False.
Cleanup: function deletions on both roles + the existing helm cleanup retargeted for cloud (same `replace(role=...)` trick).

- [ ] Steps: failing plan-shape tests (cloud tasks precede edge helm? no ordering constraint needed beyond: all deployments before registrations before k6 before snapshots before report; assert exact task-id sequence like `tests/plans/test_offload.py` does) → implement → `uv run controlplane-tool plan scenarios-v2/offload-loadtest.yaml` renders (create the scenario file here: `workflow: offload-loadtest` + the two functions) → full suite + ruff + basedpyright → commit `Assemble offload load-test plan`.

### Task 7: TUI entry

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py` (`LOADTEST_MENU` + `_SCENARIO_FILES`: `("loadtest", "offload"): "offload-loadtest.yaml"`)
- Modify: `tools/controlplane/tests/test_tui_app.py`, `tools/controlplane/tests/test_tui_navigation.py` (expected lists, as done for `validate-offload.yaml`)

- [ ] Steps: update expectations first (failing) → add the menu entry → full suite → commit `Expose offload load-test in the TUI`.

### Task 8: Real Multipass run and spec update

No code. Execute and record.

- [ ] **Step 1:** `cd tools/controlplane && uv run controlplane-tool run scenarios-v2/offload-loadtest.yaml --environment environments/multipass-offload.yaml --provision`
- [ ] **Step 2:** On failure: fix forward (expected first-run friction: cloud VM provisioning class, IP discovery, k6 submetric names in the summary — adjust the Task 5 parser against the REAL k6 summary captured in the run dir).
- [ ] **Step 3:** On success: verify `offload-report.json` shows `passed: true`, offloaded count > 0, and paste the numbers into the spec under a "## Results (v1)" section.
- [ ] **Step 4:** Commit `Validate offload load-test on multipass` (spec update + any parser fixes).

## Self-review notes

- Spec coverage: topology (T1+T2), image/modules+extraEnv (T4), policies/registrations (T4), k6 with header counters and tags (T3), conservation chain 1-4 (T5), report (T6), real run (T8). Latency local-vs-offloaded comparison: included in the report via k6 tagged durations (T5 `numbers`), informative only.
- The `cloud` role touches HIGH-blast-radius plumbing: T1 keeps every change additive with defaults (`cloud=None`) and requires both full suites before its commit.
- k6 summary submetric naming is the known unknown: T5 parses defensively and T8 reconciles against a real summary.
