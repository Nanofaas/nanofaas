# One-VM Loadtest Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the issues found in the post-merge review of PR #114 (`one-vm-helm-loadtest`) — autoscaling verification racing the load, masked kubectl errors, duplicated k6 stages, adapter boilerplate, doc inconsistency — and surface the scenario in the TUI, where it is currently missing.

**Architecture:** The autoscaling scale-up check moves from "poll residual state after k6 finishes" to "sample replicas on a background thread *while* k6 runs": a `ReplicaProbe` (extracted, with honest error reporting) is shared by a `ReplicaWatcher` thread and the existing `VerifyAutoscalingReplicas` task; a thin `RunK6WithReplicaWatch` composite keeps the workflow task list flat and the task ids stable. The TUI gains the scenario by extending the existing `_PLATFORM_VALIDATION_CHOICES` + dispatch sets in `tui/app.py` (the helm-stack/two-vm code path already handles it end-to-end). Everything else is small deletions/edits.

**Tech Stack:** Python 3.11+ (`controlplane_tool`, `workflow_tasks`), pytest via `uv`, k6 JS asset, questionary-based TUI.

---

## Conventions

- Repo root: `/Users/micheleciavotta/Downloads/mcFaas`. Work on a feature branch (e.g. `fix/one-vm-loadtest-hardening`), in a worktree if isolation is needed (`superpowers:using-git-worktrees`).
- Python tests: `uv run --project tools/controlplane pytest <paths> -q` from the repo root. Full controlplane suite: `uv run --project tools/controlplane pytest tools/controlplane/tests -q`.
- KNOWN pre-existing flake (do NOT fix, do not be confused by it): `tools/controlplane/tests/test_vm_commands.py::test_vm_provision_base_dry_run_prints_planned_ansible_command` fails when the checkout path is long (git worktrees): Rich wraps the playbook path and splits `provision-base.yml` across lines. It passes in short-path checkouts. Treat it as unrelated.
- Per project CLAUDE.md: if GitNexus MCP tools are available, run `gitnexus_impact` before editing existing symbols and `gitnexus_detect_changes()` before commits; if unavailable, skip (a post-commit hook reindexes).
- Commit after every task.

## Review findings being fixed

| # | Finding | Task |
|---|---------|------|
| F1 | `VerifyAutoscalingReplicas` starts polling only after `RunK6` completes → measures residual scale-up; timing-sensitive (depends on the 60 s downscale cooldown still holding replicas up) | 3 |
| F2 | `kubectl ... \|\| echo 0` masks all kubectl errors; the `return_code != 0` branch is nearly dead; wrong deployment name degrades into a generic "Scale-up not observed" | 2 |
| F3 | k6 stages duplicated in `autoscaling.js` `options.stages` AND `K6Config(stages=...)`; the CLI `--stage` flags built by `_build_k6_argv` override script options, so the JS copy is dead weight that can silently drift | 4 |
| F4 | 15 lines of identical hook boilerplate added to the three two-VM adapters in `loadtest_adapter.py`, duplicating the `getattr` fallback defaults already in `loadtest_flow.py` | 5 |
| F5 | `docs/testing.md` smoke list: the one-vm line is the only one without `--dry-run` (copy-paste of the block would provision a VM); the generous k6 `rate<0.30` threshold is uncommented | 4, 6 |
| F6 | `one-vm-helm-loadtest` missing from the TUI (`Validation -> platform`) | 1 |

Out of scope (deliberate): real-VM smoke run (manual gate, listed in Final Verification); restructuring the pre-existing mismatch between `scenario_task_ids()` recipe ids and live flow event ids (pre-dates PR #114, shared with two-vm).

## File map

- `tools/controlplane/src/controlplane_tool/tui/app.py` — TUI choice + dispatch membership (Task 1)
- `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py` — `ReplicaProbe` extraction + error de-masking (Task 2), `ReplicaWatcher` + `RunK6WithReplicaWatch` + watcher-aware `VerifyAutoscalingReplicas` (Task 3)
- `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py` — exports (Tasks 2–3)
- `tools/controlplane/src/controlplane_tool/scenario/one_vm_loadtest_adapter.py` — wire watcher into `post_loadgen_tasks` (Task 3)
- `tools/controlplane/assets/k6/autoscaling.js` — drop embedded stages, comment threshold (Task 4)
- `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py` — remove protocol + adapter boilerplate (Task 5)
- `docs/testing.md` — `--dry-run` consistency (Task 6)
- Tests: `tools/controlplane/tests/test_tui_scenario_choices.py` (new), `test_autoscaling_tasks.py`, `test_one_vm_loadtest_adapter.py`, `test_loadtest_flow.py` (existing, must stay green), `test_e2e_runner.py` (existing, must stay green)

---

### Task 1: Surface `one-vm-helm-loadtest` in the TUI

The TUI menu `Validation -> platform` builds from `_PLATFORM_VALIDATION_CHOICES` in `tools/controlplane/src/controlplane_tool/tui/app.py` (~line 523). Dispatch happens in two membership checks: the tuple at ~line 983 (`scenario_choice in ("k3s-junit-curl", "helm-stack", "two-vm-loadtest", ...)`) and the set at ~line 996 inside `_run_vm_e2e_scenario` (`scenario in {"helm-stack", "two-vm-loadtest"}`). That second branch resolves the request via `_resolve_run_request`, builds the dashboard from `E2eRunner.plan(request).phase_titles` (complete for one-vm, includes the autoscaling tail), and runs via `build_scenario_flow(..., request=request)` (the `request is not None` early branch honors the TUI-resolved request, including the cleanup answer). The memory ternary `"8G" if scenario == "two-vm-loadtest" else "12G"` already gives one-vm 12G — correct, since stack + k6 share the VM; do not change it.

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui/app.py`
- Test: create `tools/controlplane/tests/test_tui_scenario_choices.py`

- [ ] **Step 1: Write the failing test**

Create `tools/controlplane/tests/test_tui_scenario_choices.py`:

```python
from __future__ import annotations

from controlplane_tool.tui import app as tui_app


def _choice_values(choices) -> list[str]:
    return [choice.value for choice in choices]


def test_platform_validation_choices_include_one_vm_helm_loadtest() -> None:
    values = _choice_values(tui_app._PLATFORM_VALIDATION_CHOICES)
    assert "one-vm-helm-loadtest" in values
    # Keep it next to its siblings so the menu reads stack -> one-vm -> two-vm.
    assert values.index("helm-stack") < values.index("one-vm-helm-loadtest") < values.index("two-vm-loadtest")


def test_one_vm_helm_loadtest_routes_through_vm_e2e_dispatch() -> None:
    import inspect

    source = inspect.getsource(tui_app)
    # Dispatch tuple: scenario_choice in (...)
    assert '"one-vm-helm-loadtest"' in source
    dispatch_line = next(
        line for line in source.splitlines()
        if "scenario_choice in (" in line
    )
    assert "one-vm-helm-loadtest" in dispatch_line
    # Request-resolution branch inside _run_vm_e2e_scenario.
    vm_e2e_source = inspect.getsource(tui_app.TuiApp._run_vm_e2e_scenario)
    membership_line = next(
        line for line in vm_e2e_source.splitlines()
        if "scenario in {" in line and "helm-stack" in line
    )
    assert "one-vm-helm-loadtest" in membership_line
```

NOTE: the class owning `_run_vm_e2e_scenario` may not be named `TuiApp` — open `app.py`, find the actual owner of `_run_vm_e2e_scenario`, and use that name in the test. If `_DescribedChoice` exposes the value under a different attribute than `.value`, adapt `_choice_values` (read the `_DescribedChoice` definition at the top of `app.py`).

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_tui_scenario_choices.py -q`
Expected: FAIL (`"one-vm-helm-loadtest" in values` assertion).

- [ ] **Step 3: Implement**

In `tools/controlplane/src/controlplane_tool/tui/app.py`:

1. In `_PLATFORM_VALIDATION_CHOICES`, insert between the `helm-stack` and `two-vm-loadtest` entries:

```python
    _DescribedChoice(
        "one-vm-helm-loadtest — Helm stack + k6 + autoscaling check on one VM",
        "one-vm-helm-loadtest",
        "Bootstrap the Helm stack on one managed VM, run the k6 load test from the same VM, "
        "and verify autoscaling scale-up/scale-down without a second load generator VM.",
    ),
```

2. In the dispatch tuple (~line 983) add the scenario:

```python
            if scenario_choice in ("k3s-junit-curl", "helm-stack", "one-vm-helm-loadtest", "two-vm-loadtest", "azure-vm-loadtest", "proxmox-vm-loadtest"):
```

3. In `_run_vm_e2e_scenario` (~line 996) extend the set:

```python
        if scenario in {"helm-stack", "one-vm-helm-loadtest", "two-vm-loadtest"}:
```

Leave the memory ternary untouched (one-vm falls into the 12G default).

- [ ] **Step 4: Run the test, then the full suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_tui_scenario_choices.py -q` → PASS
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q` → green (modulo the known path-wrap flake).

- [ ] **Step 5: Optional manual sanity (recommended, no VM needed)**

Run `scripts/controlplane.sh tui`, navigate `Validation -> platform`, confirm the new entry renders and that selecting it asks the cleanup question (then back out without confirming a run).

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui/app.py tools/controlplane/tests/test_tui_scenario_choices.py
git commit -m "feat(tui): surface one-vm-helm-loadtest under Validation -> platform

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: F2 — extract `ReplicaProbe` and stop masking kubectl errors

Current state (`tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`): `VerifyAutoscalingReplicas._replica_count` runs `kubectl get deployment ... 2>/dev/null || echo 0`, so the process exits 0 even when kubectl fails and every error reads as "0 replicas". Extract the probing into a standalone `ReplicaProbe` (Task 3 will share it with the watcher) and make errors legible.

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`
- Modify: `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py`
- Test: `tools/controlplane/tests/test_autoscaling_tasks.py`

- [ ] **Step 1: Write the failing tests**

Add to `tools/controlplane/tests/test_autoscaling_tasks.py` (reuse the existing `_Result` dataclass; add a failing-runner helper):

```python
class _FailingRunner:
    def __init__(self, stderr: str, return_code: int = 1) -> None:
        self.stderr = stderr
        self.return_code = return_code

    def run_vm_command(self, argv, *, env, remote_dir, dry_run):
        return _Result(return_code=self.return_code, stdout="", stderr=self.stderr)


def test_replica_probe_reports_missing_deployment_clearly() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe

    probe = ReplicaProbe(
        runner=_FailingRunner('Error from server (NotFound): deployments.apps "fn-x" not found'),
        namespace="nanofaas",
        deployment_name="fn-x",
        remote_dir="/home/ubuntu/mcFaas",
    )
    try:
        probe.desired_replicas()
    except RuntimeError as exc:
        assert "not found" in str(exc)
        assert "fn-x" in str(exc)
        return
    raise AssertionError("expected RuntimeError")


def test_replica_probe_propagates_kubectl_errors() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe

    probe = ReplicaProbe(
        runner=_FailingRunner("Unable to connect to the server: dial tcp: lookup ..."),
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    try:
        probe.ready_replicas()
    except RuntimeError as exc:
        assert "Unable to connect" in str(exc)
        return
    raise AssertionError("expected RuntimeError")


def test_replica_probe_treats_empty_jsonpath_output_as_zero() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe

    probe = ReplicaProbe(
        runner=_Runner([""]),  # readyReplicas is absent from status when 0
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    assert probe.ready_replicas() == 0
```

(Check `_Runner([""])`: the existing `_Runner` pops values and returns stdout `"0"` when exhausted — passing `[""]` makes the first call return empty stdout with rc 0, which is the case under test.)

- [ ] **Step 2: Run to verify failure**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_autoscaling_tasks.py -q`
Expected: FAIL (ImportError: `ReplicaProbe`).

- [ ] **Step 3: Implement**

In `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`, add above `VerifyAutoscalingReplicas`:

```python
@dataclass(frozen=True)
class ReplicaProbe:
    """Reads deployment replica counts over the VM command runner.

    Errors are surfaced, not masked: a missing deployment and an unreachable
    cluster must be distinguishable from "0 replicas" when diagnosing a run.
    """

    runner: VmCommandRunner
    namespace: str
    deployment_name: str
    remote_dir: str

    def ready_replicas(self) -> int:
        return self._replica_count("{.status.readyReplicas}")

    def desired_replicas(self) -> int:
        return self._replica_count("{.spec.replicas}")

    def _replica_count(self, jsonpath: str) -> int:
        deployment = shlex.quote(self.deployment_name)
        namespace = shlex.quote(self.namespace)
        output = shlex.quote(f"jsonpath={jsonpath}")
        result = self.runner.run_vm_command(
            (
                "bash",
                "-lc",
                f"kubectl get deployment {deployment} -n {namespace} -o {output}",
            ),
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        if result.return_code != 0:
            detail = (result.stderr or result.stdout or "").strip()
            if "NotFound" in detail:
                raise RuntimeError(
                    f"deployment {self.deployment_name!r} not found in namespace {self.namespace!r}: {detail}"
                )
            raise RuntimeError(detail or f"kubectl replica query failed (exit {result.return_code})")
        raw = (result.stdout or "").strip()
        if not raw:
            # jsonpath yields empty output when the field is absent (e.g. readyReplicas at 0).
            return 0
        try:
            return int(raw)
        except ValueError as exc:
            raise RuntimeError(f"invalid replica count: {result.stdout!r}") from exc
```

Then rewire `VerifyAutoscalingReplicas` to delegate: delete its `_replica_count` method and replace `_ready_replicas`/`_desired_replicas` with:

```python
    def _probe(self) -> ReplicaProbe:
        return ReplicaProbe(
            runner=self.runner,
            namespace=self.namespace,
            deployment_name=self.deployment_name,
            remote_dir=self.remote_dir,
        )

    def _ready_replicas(self) -> int:
        return self._probe().ready_replicas()

    def _desired_replicas(self) -> int:
        return self._probe().desired_replicas()
```

(Keep the `VerifyAutoscalingReplicas` field list unchanged in this task — the existing tests construct it with `runner=`/`namespace=`/... and must keep passing.)

Update `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py`:

```python
from controlplane_tool.autoscaling.tasks import (
    AutoscalingSummary,
    ReplicaProbe,
    VerifyAutoscalingReplicas,
)

__all__ = ["AutoscalingSummary", "ReplicaProbe", "VerifyAutoscalingReplicas"]
```

- [ ] **Step 4: Run the test file, verify the existing quoting/scale tests still pass**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_autoscaling_tasks.py -q`
Expected: PASS, including the pre-existing tests (the command no longer contains `|| echo 0` or `2>/dev/null`; the quoting test asserts on quoted names only, which still holds).

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/autoscaling tools/controlplane/tests/test_autoscaling_tasks.py
git commit -m "fix(loadtest): de-mask kubectl errors behind ReplicaProbe extraction

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: F1 — sample replicas concurrently with the k6 load

Today the scale-up check starts only after `RunK6.run()` returns, so it measures residual state and relies on the 60 s downscale cooldown. Fix: a `ReplicaWatcher` thread samples `ReplicaProbe` while k6 runs; a `RunK6WithReplicaWatch` composite wraps the existing `RunK6` (keeping task id `autoscaling.run_k6` so plan-shape tests don't change); `VerifyAutoscalingReplicas` consumes the watcher's max and keeps its polling loop only as a fallback. The runner shells out one process per call (`multipass exec`/ssh), so concurrent calls from the watcher thread are safe.

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`
- Modify: `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/one_vm_loadtest_adapter.py`
- Test: `tools/controlplane/tests/test_autoscaling_tasks.py`, `tools/controlplane/tests/test_one_vm_loadtest_adapter.py`

- [ ] **Step 1: Write the failing tests**

Add to `tools/controlplane/tests/test_autoscaling_tasks.py`:

```python
def test_replica_watcher_records_max_while_running() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe, ReplicaWatcher

    runner = _Runner(["1", "1", "2", "3", "2", "1"])
    probe = ReplicaProbe(
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    watcher = ReplicaWatcher(probe, poll_interval_seconds=0.01)

    watcher.start()
    import time as _time
    deadline = _time.time() + 2.0
    while watcher.max_observed < 3 and _time.time() < deadline:
        _time.sleep(0.01)
    watcher.stop()

    assert watcher.max_observed >= 3


def test_replica_watcher_survives_probe_errors() -> None:
    from controlplane_tool.autoscaling.tasks import ReplicaProbe, ReplicaWatcher

    probe = ReplicaProbe(
        runner=_FailingRunner("Unable to connect to the server"),
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
    )
    watcher = ReplicaWatcher(probe, poll_interval_seconds=0.01)
    watcher.start()
    import time as _time
    _time.sleep(0.05)
    watcher.stop()  # must not raise; errors recorded, watcher keeps sampling

    assert watcher.max_observed == 0


def test_run_k6_with_replica_watch_starts_and_stops_watcher_around_run() -> None:
    from controlplane_tool.autoscaling.tasks import RunK6WithReplicaWatch

    events: list[str] = []

    class _FakeWatcher:
        max_observed = 2

        def start(self) -> None:
            events.append("watch.start")

        def stop(self) -> None:
            events.append("watch.stop")

    class _FakeRunK6:
        def run(self):
            events.append("k6.run")
            return "k6-result"

    task = RunK6WithReplicaWatch(
        task_id="autoscaling.run_k6",
        title="Run autoscaling k6",
        run_k6=_FakeRunK6(),
        watcher=_FakeWatcher(),
    )

    assert task.run() == "k6-result"
    assert events == ["watch.start", "k6.run", "watch.stop"]


def test_run_k6_with_replica_watch_stops_watcher_on_k6_failure() -> None:
    from controlplane_tool.autoscaling.tasks import RunK6WithReplicaWatch

    events: list[str] = []

    class _FakeWatcher:
        def start(self) -> None:
            events.append("watch.start")

        def stop(self) -> None:
            events.append("watch.stop")

    class _BoomRunK6:
        def run(self):
            raise RuntimeError("k6 exploded")

    task = RunK6WithReplicaWatch(
        task_id="autoscaling.run_k6",
        title="Run autoscaling k6",
        run_k6=_BoomRunK6(),
        watcher=_FakeWatcher(),
    )
    try:
        task.run()
    except RuntimeError:
        pass
    assert events == ["watch.start", "watch.stop"]


def test_verify_uses_watcher_max_and_skips_scale_up_polling(monkeypatch) -> None:
    monkeypatch.setattr("controlplane_tool.autoscaling.tasks.time.sleep", lambda _: None)

    class _WatcherStub:
        max_observed = 3

    # Only the scale-down phase should hit kubectl: desired goes straight to 0.
    runner = _Runner(["0"])
    task = VerifyAutoscalingReplicas(
        task_id="autoscaling.verify_replicas",
        title="Verify autoscaling replicas",
        runner=runner,
        namespace="nanofaas",
        deployment_name="fn-word-stats-java",
        remote_dir="/home/ubuntu/mcFaas",
        scale_up_polls=2,
        scale_down_initial_delay_seconds=0,
        scale_down_polls=1,
        poll_interval_seconds=1,
        watcher=_WatcherStub(),
    )

    summary = task.run()

    assert summary.max_replicas_observed == 3
    assert summary.final_desired_replicas == 0
    # One kubectl call total (the final desired check), no scale-up polling.
    assert len(runner.commands) == 1
```

- [ ] **Step 2: Run to verify failure**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_autoscaling_tasks.py -q`
Expected: FAIL (ImportError: `ReplicaWatcher` / `RunK6WithReplicaWatch`; TypeError for the `watcher=` kwarg).

- [ ] **Step 3: Implement in `tools/controlplane/src/controlplane_tool/autoscaling/tasks.py`**

Add `import threading` at the top (next to the existing imports), then:

```python
class ReplicaWatcher:
    """Samples deployment replicas on a background thread while load runs.

    Scale-up must be observed DURING the k6 run: checking afterwards only sees
    residual state and races the autoscaler's downscale cooldown.
    """

    def __init__(self, probe: ReplicaProbe, poll_interval_seconds: float = 2.0) -> None:
        self._probe = probe
        self._poll_interval = poll_interval_seconds
        self._stop = threading.Event()
        self._thread: threading.Thread | None = None
        self._max_observed = 0
        self.errors: list[str] = []

    @property
    def max_observed(self) -> int:
        return self._max_observed

    def start(self) -> None:
        if self._thread is not None:
            raise RuntimeError("ReplicaWatcher already started")
        self._stop.clear()
        self._thread = threading.Thread(target=self._loop, name="replica-watcher", daemon=True)
        self._thread.start()

    def stop(self) -> None:
        if self._thread is None:
            return
        self._stop.set()
        self._thread.join()
        self._thread = None

    def _loop(self) -> None:
        while not self._stop.is_set():
            try:
                ready = self._probe.ready_replicas()
                desired = self._probe.desired_replicas()
                self._max_observed = max(self._max_observed, ready, desired)
            except RuntimeError as exc:
                # A transient probe failure must not kill the watcher mid-load;
                # errors are kept for diagnostics.
                self.errors.append(str(exc))
            self._stop.wait(self._poll_interval)


@dataclass
class RunK6WithReplicaWatch:
    """Runs k6 while a ReplicaWatcher samples the target deployment."""

    task_id: str
    title: str
    run_k6: object
    watcher: object

    def run(self):
        self.watcher.start()
        try:
            return self.run_k6.run()
        finally:
            self.watcher.stop()
```

Then extend `VerifyAutoscalingReplicas`:

1. Add a field (after `poll_interval_seconds`): `watcher: object | None = None`
2. Replace the scale-up section at the top of `run()`:

```python
    def run(self) -> AutoscalingSummary:
        max_replicas = self.watcher.max_observed if self.watcher is not None else 0
        if max_replicas <= 1:
            # Fallback: no watcher (or it observed nothing) — poll residual state.
            for _ in range(self.scale_up_polls):
                time.sleep(self.poll_interval_seconds)
                ready = self._ready_replicas()
                desired = self._desired_replicas()
                max_replicas = max(max_replicas, ready, desired)
                if max_replicas > 1:
                    break

        if max_replicas <= 1:
            raise RuntimeError(f"Scale-up not observed: max replicas stayed at {max_replicas}")
```

(The rest of `run()` — the 90 s delay + scale-down polling — stays exactly as it is.)

Update `tools/controlplane/src/controlplane_tool/autoscaling/__init__.py`:

```python
from controlplane_tool.autoscaling.tasks import (
    AutoscalingSummary,
    ReplicaProbe,
    ReplicaWatcher,
    RunK6WithReplicaWatch,
    VerifyAutoscalingReplicas,
)

__all__ = [
    "AutoscalingSummary",
    "ReplicaProbe",
    "ReplicaWatcher",
    "RunK6WithReplicaWatch",
    "VerifyAutoscalingReplicas",
]
```

- [ ] **Step 4: Wire into the adapter**

In `tools/controlplane/src/controlplane_tool/scenario/one_vm_loadtest_adapter.py`:

1. Update the import: `from controlplane_tool.autoscaling.tasks import ReplicaProbe, ReplicaWatcher, RunK6WithReplicaWatch, VerifyAutoscalingReplicas`
2. In `post_loadgen_tasks`, before the returned list, build the shared watcher:

```python
        loadgen_runner = self.loadgen_runner(ctx)
        probe = ReplicaProbe(
            runner=loadgen_runner,
            namespace=setup.context.namespace,
            deployment_name=f"fn-{function_name}",
            remote_dir=ctx.loadgen_info.home,
        )
        watcher = ReplicaWatcher(probe)
```

3. Replace the `RunK6(...)` element of the returned list with the composite, KEEPING task id `autoscaling.run_k6` (plan-shape tests in `test_e2e_runner.py` and `test_loadtest_flow.py` depend on it):

```python
            RunK6WithReplicaWatch(
                task_id="autoscaling.run_k6",
                title="Run autoscaling k6",
                run_k6=RunK6(
                    task_id="autoscaling.run_k6.inner",
                    title="Run autoscaling k6 (inner)",
                    runner=loadgen_runner,
                    config=K6Config(
                        script_path=autoscaling_script,
                        target_url=ctx.control_plane_url,
                        summary_output_path=autoscaling_summary,
                        stages=(
                            K6Stage(duration="10s", target=10),
                            K6Stage(duration="20s", target=20),
                            K6Stage(duration="90s", target=20),
                            K6Stage(duration="10s", target=0),
                        ),
                        env={
                            "NANOFAAS_URL": ctx.control_plane_url,
                            "NANOFAAS_FUNCTION": function_name,
                        },
                    ),
                    remote_dir=ctx.loadgen_info.home,
                ),
                watcher=watcher,
            ),
```

4. Pass the same `watcher` to `VerifyAutoscalingReplicas(... , watcher=watcher)`. Also reuse `loadgen_runner` (don't call `self.loadgen_runner(ctx)` twice).

5. Update `tools/controlplane/tests/test_one_vm_loadtest_adapter.py::test_one_vm_adapter_builds_autoscaling_tail_tasks`: the second task is now the composite —

```python
    from controlplane_tool.autoscaling.tasks import RunK6WithReplicaWatch, VerifyAutoscalingReplicas

    assert [task.task_id for task in tasks] == [
        "autoscaling.register_function",
        "autoscaling.run_k6",
        "autoscaling.verify_replicas",
    ]
    assert isinstance(tasks[0], RegisterFunctions)
    assert isinstance(tasks[1], RunK6WithReplicaWatch)
    assert isinstance(tasks[1].run_k6, RunK6)
    assert tasks[1].run_k6.config.script_path == Path("/home/ubuntu/two-vm-loadtest/scripts/autoscaling.js")
    assert tasks[1].run_k6.config.env["NANOFAAS_FUNCTION"] == "word-stats-java"
    assert isinstance(tasks[2], VerifyAutoscalingReplicas)
    assert tasks[2].watcher is tasks[1].watcher
```

- [ ] **Step 5: Run the touched test files, then the full controlplane suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_autoscaling_tasks.py tools/controlplane/tests/test_one_vm_loadtest_adapter.py tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_loadtest_flow.py -q` → PASS
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q` → green (modulo known flake).

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src tools/controlplane/tests
git commit -m "fix(loadtest): observe autoscaling scale-up concurrently with the k6 run

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: F3 + F5a — single source of truth for k6 stages; comment the threshold

`_build_k6_argv` (in `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py`) passes `--stage duration:target` flags built from `K6Config.stages`, and k6 CLI flags override script `options.stages` — the copy inside `autoscaling.js` is dead and can silently drift.

**Files:**
- Modify: `tools/controlplane/assets/k6/autoscaling.js`
- Test: `tools/controlplane/tests/test_autoscaling_tasks.py` (asset guard)

- [ ] **Step 1: Write the failing guard test**

Add to `tools/controlplane/tests/test_autoscaling_tasks.py`:

```python
def test_autoscaling_k6_asset_has_no_embedded_stages() -> None:
    from pathlib import Path

    asset = (
        Path(__file__).resolve().parents[1]
        / "assets"
        / "k6"
        / "autoscaling.js"
    )
    content = asset.read_text(encoding="utf-8")
    # Stages live in K6Config (passed as --stage CLI flags, which override script
    # options); an embedded copy would silently drift.
    assert "stages" not in content
    assert "http_req_failed" in content  # threshold stays in the script
```

- [ ] **Step 2: Run to verify failure**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_autoscaling_tasks.py::test_autoscaling_k6_asset_has_no_embedded_stages -q`
Expected: FAIL (`"stages" not in content`).

- [ ] **Step 3: Edit the asset**

In `tools/controlplane/assets/k6/autoscaling.js` replace the `options` block:

```javascript
export const options = {
    // Stages are injected by the workflow via `k6 run --stage ...` (see
    // K6Config in one_vm_loadtest_adapter.py); CLI flags override script
    // options, so they are deliberately NOT duplicated here.
    thresholds: {
        // Generous on purpose: scale-from-zero means the first wave of requests
        // hits cold starts and may time out before replicas come up.
        http_req_failed: ['rate<0.30'],
    },
};
```

- [ ] **Step 4: Run the guard test → PASS, then commit**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_autoscaling_tasks.py -q` → PASS

```bash
git add tools/controlplane/assets/k6/autoscaling.js tools/controlplane/tests/test_autoscaling_tasks.py
git commit -m "refactor(loadtest): k6 stages single-sourced from K6Config; comment threshold rationale

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: F4 — drop the duplicated adapter hook boilerplate

PR #114 added five hook methods to the `LoadtestAdapter` protocol and pasted identical 15-line implementations into the three two-VM adapters in `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py` — but `loadtest_flow.py` already reads every hook via `getattr` with exactly those defaults (`_uses_dedicated_loadgen_vm` → True, `_loadgen_info_for` → `ctx.stack_info`, `_post_loadgen_tasks/_ids/_titles` → `[]`). Make the `getattr` fallbacks the single source of defaults.

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`
- Modify (comment only): `tools/controlplane/src/controlplane_tool/scenario/loadtest_flow.py`
- Tests: existing `tools/controlplane/tests/test_loadtest_flow.py` (two-vm static-id tests are the regression net)

- [ ] **Step 1: Remove the boilerplate**

In `loadtest_adapter.py`:
1. From the `LoadtestAdapter` protocol, delete the five declarations added by PR #114:
   `uses_dedicated_loadgen_vm`, `loadgen_info`, `post_loadgen_tasks`, `post_loadgen_task_ids`, `post_loadgen_task_titles`.
2. Find every concrete implementation block (three of them — `grep -n "def uses_dedicated_loadgen_vm" tools/controlplane/src/controlplane_tool/scenario/loadtest_adapter.py`) and delete each 5-method block (they all return `True` / `ctx.stack_info` / `[]` / `[]` / `[]`).
3. Do NOT touch `one_vm_loadtest_adapter.py` — its overrides are the whole point.

- [ ] **Step 2: Document the optional-hook contract**

In `loadtest_flow.py`, above `_uses_dedicated_loadgen_vm`, add:

```python
# Optional adapter hooks. Two-VM adapters don't implement them; the getattr
# fallbacks below ARE the defaults (dedicated loadgen VM, stack_info reuse,
# no post-loadgen tail). One-VM adapters override them.
```

- [ ] **Step 3: Run the flow/adapter/e2e test files, then the full suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_loadtest_flow.py tools/controlplane/tests/test_e2e_runner.py tools/controlplane/tests/test_one_vm_loadtest_adapter.py -q` → PASS
Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q` → green (modulo known flake).

- [ ] **Step 4: Commit**

```bash
git add tools/controlplane/src
git commit -m "refactor(loadtest): single source for optional adapter hook defaults (drop boilerplate)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: F5b — docs consistency

**Files:**
- Modify: `docs/testing.md`

- [ ] **Step 1: Add `--dry-run` to the smoke list**

In `docs/testing.md` (~line 379), change:

```
scripts/controlplane.sh e2e run one-vm-helm-loadtest
```

to:

```
scripts/controlplane.sh e2e run one-vm-helm-loadtest --dry-run
```

(every sibling line in that block carries `--dry-run`; without it, copy-pasting the block provisions a real VM).

- [ ] **Step 2: Verify the dry-run actually works, then commit**

Run: `scripts/controlplane.sh e2e run one-vm-helm-loadtest --dry-run` — expected: prints the planned steps and exits 0 without touching Multipass. If `--dry-run` is not supported for this scenario, STOP and report (the doc fix would then need a different form, e.g. moving the line out of the copy-paste block).

```bash
git add docs/testing.md
git commit -m "docs(testing): dry-run flag on one-vm-helm-loadtest smoke line

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Final Verification

- [ ] `uv run --project tools/controlplane pytest tools/controlplane/tests -q` — green (modulo the known path-wrap flake, which must be the ONLY failure, and only under long worktree paths)
- [ ] `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests -q` — green (no workflow-tasks file is touched, this guards against accidental contract drift)
- [ ] TUI manual check: `scripts/controlplane.sh tui` → `Validation -> platform` shows `one-vm-helm-loadtest`
- [ ] **Real-VM smoke (the actual gate for F1, requires Multipass, ~15–20 min):** `scripts/controlplane.sh e2e run one-vm-helm-loadtest` — verify: run completes; `tools/controlplane/runs/<run>/` contains `k6-summary.json`, `autoscaling-k6-summary.json`, `metrics/prometheus-snapshots.json`, `report.html`; the autoscaling phase reports `max_replicas_observed > 1` (now sampled during load) and final desired 0. This is the same validation pattern used for the two-vm B1a work.
