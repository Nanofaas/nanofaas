# Mock K8s Pods Support For DEPLOYMENT Preflight Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make tooling metrics runs pass with DEPLOYMENT preflight by supporting the Kubernetes Pod APIs required by `KubernetesImageValidator`.

**Architecture:** Keep the fix inside the tooling mock Kubernetes server, not in control-plane business logic. Extend the mock server to expose `pods` resources and emulate a minimal successful image-pull lifecycle so image validation can complete. Lock behavior with failing tests first, then re-run live wrapper QA profiles.

**Tech Stack:** Python 3.12, `pytest`, in-repo TUI tool (`tooling/controlplane_tui`), Java control-plane runtime (`gradlew :control-plane:bootRun` via wrapper).

---

### Task 1: Reproduce and lock the failure contract (RED)

**Files:**
- Modify: `tooling/controlplane_tui/tests/test_mockk8s_runtime.py`
- Create: `tooling/controlplane_tui/tests/test_mockk8s_server_pods.py`

**Step 1: Add failing test for Pod API discovery**

```python
def test_api_v1_resources_include_pods(...):
    body = _request("GET", f"{session.url}/api/v1")
    names = {r["name"] for r in body["resources"]}
    assert "pods" in names
```

**Step 2: Add failing test for Pod create/get/delete lifecycle**

```python
def test_pods_create_get_delete_roundtrip(...):
    _request("POST", f"{base}/api/v1/namespaces/default/pods", payload)
    got = _request("GET", f"{base}/api/v1/namespaces/default/pods/{name}")
    assert got["metadata"]["name"] == name
    _request("DELETE", f"{base}/api/v1/namespaces/default/pods/{name}")
```

**Step 3: Add failing test for validator-compatible status**

```python
def test_created_validation_pod_is_immediately_running(...):
    pod = _request("GET", f"{base}/api/v1/namespaces/default/pods/{name}")
    assert pod["status"]["phase"] in {"Running", "Succeeded"}
```

**Step 4: Run tests to verify RED**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_mockk8s_server_pods.py tooling/controlplane_tui/tests/test_mockk8s_runtime.py -q`  
Expected: FAIL (missing `/pods` support).

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/tests/test_mockk8s_server_pods.py tooling/controlplane_tui/tests/test_mockk8s_runtime.py
git commit -m "test(tooling): lock mock k8s pod api contract for image validation"
```

### Task 2: Implement Pod APIs in mock server (GREEN)

**Files:**
- Modify: `tooling/controlplane_tui/src/controlplane_tool/mockk8s_server.py`
- Test: `tooling/controlplane_tui/tests/test_mockk8s_server_pods.py`

**Step 1: Extend state with Pods store**

```python
pods: dict[tuple[str, str], dict[str, object]] = field(default_factory=dict)
```

**Step 2: Expose pods in `/api/v1` resource list**

```python
{"name": "pods", "namespaced": True, "kind": "Pod"}
```

**Step 3: Route `/api/v1/namespaces/{ns}/pods[/name]`**

```python
if parts[:3] == ["api", "v1", "namespaces"] and parts[4] == "pods":
    ...
```

**Step 4: Implement create/get/list/delete for pods**

```python
if kind == "pods":
    return self.server.state.pods
```

**Step 5: On Pod create, inject minimal running status**

```python
body.setdefault("status", {"phase": "Running", "containerStatuses": [{"state": {"running": {}}}]})
```

**Step 6: Re-run focused tests**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_mockk8s_server_pods.py tooling/controlplane_tui/tests/test_mockk8s_runtime.py -q`  
Expected: PASS.

**Step 7: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/mockk8s_server.py tooling/controlplane_tui/tests/test_mockk8s_server_pods.py tooling/controlplane_tui/tests/test_mockk8s_runtime.py
git commit -m "feat(tooling): add mock kubernetes pods api for deployment image validation"
```

### Task 3: Regression guard on metrics flow with managed runtimes

**Files:**
- Modify: `tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py`

**Step 1: Add regression test for preflight failure message removal**

```python
def test_metrics_step_does_not_fail_with_pods_not_found(...):
    ...
    assert "pods" not in detail.lower()
```

**Step 2: Run target adapter tests**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py -q`  
Expected: PASS.

**Step 3: Commit**

```bash
git add tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py
git commit -m "test(tooling): prevent regression on deployment preflight pod path"
```

### Task 4: End-to-end QA with wrapper profiles

**Files:**
- Verify artifacts under: `tooling/runs/<timestamp>-qa-live-metrics/`
- Verify artifacts under: `tooling/runs/<timestamp>-qa-live-full/`

**Step 1: Run metrics profile**

Run: `scripts/controlplane-tool.sh --profile-name qa-live-metrics --use-saved-profile`  
Expected: `Run status: passed`

**Step 2: Run full profile**

Run: `scripts/controlplane-tool.sh --profile-name qa-live-full --use-saved-profile`  
Expected: all steps passed, including `test_metrics_prometheus_k6`.

**Step 3: Validate outputs**

```bash
jq -r '.final_status,.steps[] | @json' tooling/runs/*qa-live-metrics/summary.json
jq -r '.final_status,.steps[] | @json' tooling/runs/*qa-live-full/summary.json
```

Expected: final status `passed`; `metrics/observed-metrics.json` present.

**Step 4: Commit QA evidence updates if docs changed**

```bash
git add docs/quickstart.md docs/testing.md tooling/controlplane_tui/README.md
git commit -m "docs(tooling): document mock k8s pod support for deployment preflight"
```

### Task 5: Final verification gate before completion

**Files:**
- Verify full tooling suite and changed docs/tests.

**Step 1: Run full tooling tests**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests -q`  
Expected: PASS.

**Step 2: Sanity-check no leaked processes**

Run:
```bash
lsof -nP -iTCP:8080 -sTCP:LISTEN || true
lsof -nP -iTCP:8081 -sTCP:LISTEN || true
ps -ef | rg "controlplane_tool.mockk8s_server|control-plane:bootRun" -n || true
```
Expected: no leaked tool-managed runtimes after command completion.

**Step 3: Final commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/mockk8s_server.py tooling/controlplane_tui/tests/test_mockk8s_server_pods.py tooling/controlplane_tui/tests/test_mockk8s_runtime.py tooling/controlplane_tui/tests/test_adapters_metrics_prometheus_bootstrap.py
git commit -m "fix(tooling): make deployment preflight compatible with image-validator pod checks"
```
