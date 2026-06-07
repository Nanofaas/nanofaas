# Phase B2 — Shared Loadgen Sequence Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse the triplicated loadgen body (`K6Config` construction + the 5-task `install_k6 → run_k6 → fetch → prometheus → report` sequence) across the three VM loadtest scenarios into shared helpers, so the sequence is defined once and the three scenarios differ only by connectivity inputs.

**Architecture:** Extract two pure helpers into `workflow_tasks.loadtest` — `make_loadtest_k6_config(...)` (builds the identical `K6Config`) and `build_loadgen_body_tasks(...)` (builds the identical 5-task list from already-resolved inputs). Route the two eager scenarios (two-vm multipass, azure) through both helpers directly; route proxmox's lazy closures through the same helpers so its `state`-threaded orchestration is preserved while its construction logic is shared. Every scenario's emitted task-ids/titles/argv stay byte-for-byte identical — existing golden/oracle tests are the safety net. The eager-vs-lazy orchestration unification and the thin `(recipe, ConnectivityAdapter)` collapse remain Phase B3.

**Tech Stack:** Python 3.12, `uv`, pytest. `workflow_tasks` library (loadtest tasks), `controlplane_tool` scenarios.

**Scope note (B2 vs B3):** This phase shares the loadgen *sequence body* and `K6Config`. It deliberately does NOT unify the eager (two-vm/azure) vs lazy (proxmox `_ActionTask`/`state`) orchestration shells, nor the ensure/publish/destroy VM lifecycle wiring — those are Phase B3. Keeping the three orchestration shells means zero behavior change and a small, golden-guarded diff.

**Validation gate (from the design spec §4):** CI has no e2e VM coverage. After the code lands, the multipass `two-vm-loadtest` MUST be validated on a real VM before the phase is considered done; azure/proxmox real-VM validation only when credentials are available, otherwise flagged unvalidated in the PR. The unit/golden tests prove the sequence is byte-identical, not that the live run works.

---

## File Structure

- **Create** `tools/workflow-tasks/src/workflow_tasks/loadtest/loadgen_sequence.py` — the two shared builders (`make_loadtest_k6_config`, `build_loadgen_body_tasks`) and the small `LoadgenBodyInputs` dataclass that carries the already-resolved inputs. One responsibility: turn resolved loadgen inputs into the canonical `K6Config` and the canonical 5-task list. No VM-lifecycle, no endpoint resolution, no laziness.
- **Modify** `tools/workflow-tasks/src/workflow_tasks/__init__.py` — re-export the two new builders + `LoadgenBodyInputs` (the scenarios import loadtest symbols from the package root, e.g. `from workflow_tasks import RunK6`).
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` — replace the inline `K6Config` block and the inline 5-task list with calls to the shared helpers.
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py` — same.
- **Modify** `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py` — route its lazy closures (`_k6_config`, `_install_k6`, `_run_k6`, `_fetch_results`, `_capture_prometheus`, `_write_report`) through the shared helpers without changing its `_ActionTask`/`state` orchestration.
- **Create** `tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py` — unit tests for the two builders.

The two new helpers live in `workflow_tasks` (not `controlplane_tool`) because they only touch loadtest tasks/models already owned by the library, and because the library is where the loadtest task primitives (`RunK6`, `FetchVmResults`, etc.) already live. The import-linter contract (`workflow_tasks must not import controlplane_tool`) is respected: the helpers take already-resolved primitives (runner, fetcher, client, paths, urls) as parameters — they never reach back into `controlplane_tool`.

---

## Reference: what is identical today (the triplication being removed)

`K6Config` construction — identical in all three (two_vm `:206-219`, azure `:119-139`, proxmox `:660-683`) except the *source* of `control_plane_url`:

```python
K6Config(
    script_path=Path(remote_paths.script_path),
    target_url=control_plane_url,
    summary_output_path=Path(remote_paths.summary_path),
    stages=tuple(K6Stage(duration=d, target=t) for d, t in two_vm_load_stages(request)),
    env={
        "NANOFAAS_URL": control_plane_url,
        "NANOFAAS_FUNCTION": two_vm_target_function(request),
        **({"NANOFAAS_PAYLOAD": str(remote_paths.payload_path)} if remote_paths.payload_path else {}),
    },
    vus=request.k6_vus,
    duration=request.k6_duration,
    payload_path=Path(remote_paths.payload_path) if remote_paths.payload_path else None,
)
```

The 5-task body — identical in all three (two_vm `:252-285`, azure `:151-169`, proxmox per-task closures `:685-737`) except the per-lifecycle inputs (install endpoint host/user/key/port, runner, fetcher, prom client URL, run_dir, task_id/title strings):

```python
install_k6_task(task_id=..., title=..., repo_root=..., shell=..., host=..., user=..., private_key=..., port=...)
RunK6(task_id=..., title=..., runner=..., config=k6_config, remote_dir=...)
FetchVmResults(task_id=..., title=..., fetcher=..., remote_source=remote_paths.summary_path, local_dest=run_dir)
CapturePrometheusSnapshot(task_id=..., title=..., client=..., queries=LOADTEST_PROMETHEUS_QUERIES,
                          window=lambda: TimeWindow(start=k6_task.result.started_at, end=k6_task.result.ended_at),
                          output_dir=run_dir)
WriteK6Report(task_id=..., title=..., data_dir=run_dir, output_dir=run_dir)
```

Note `install_k6_task` accepts an optional `port` (proxmox passes it; two-vm/azure omit it → `None`). `LOADTEST_PROMETHEUS_QUERIES`, `two_vm_load_stages`, `two_vm_target_function`, `two_vm_remote_paths` all already live in `controlplane_tool.scenario.two_vm_loadtest_config` and are shared across the three scenarios today.

---

## Task 1: `make_loadtest_k6_config` shared helper

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/loadgen_sequence.py`
- Test: `tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py`

This helper captures the identical `K6Config` construction. It takes the already-built `remote_paths` (a `two_vm_remote_paths` result — duck-typed: has `.script_path`, `.summary_path`, `.payload_path`), the resolved `control_plane_url`, the `target_function` name, the `stages` list, and the raw `vus`/`duration`. It must NOT import `controlplane_tool` (keep `two_vm_load_stages`/`two_vm_target_function` resolution in the callers and pass results in).

- [ ] **Step 1: Write the failing test**

```python
# tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py
from __future__ import annotations

from pathlib import Path
from types import SimpleNamespace

from workflow_tasks.loadtest.loadgen_sequence import make_loadtest_k6_config


def _remote_paths(payload: str | None = None) -> SimpleNamespace:
    return SimpleNamespace(
        script_path="/home/ubuntu/run/script.js",
        summary_path="/home/ubuntu/run/k6-summary.json",
        payload_path=payload,
    )


def test_make_k6_config_maps_fields_and_env() -> None:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(),
        control_plane_url="http://10.0.0.5:8080",
        target_function="echo",
        stages=[("30s", 5), ("1m", 10)],
        vus=None,
        duration=None,
    )
    assert cfg.script_path == Path("/home/ubuntu/run/script.js")
    assert cfg.target_url == "http://10.0.0.5:8080"
    assert cfg.summary_output_path == Path("/home/ubuntu/run/k6-summary.json")
    assert [(s.duration, s.target) for s in cfg.stages] == [("30s", 5), ("1m", 10)]
    assert cfg.env["NANOFAAS_URL"] == "http://10.0.0.5:8080"
    assert cfg.env["NANOFAAS_FUNCTION"] == "echo"
    assert "NANOFAAS_PAYLOAD" not in cfg.env
    assert cfg.payload_path is None


def test_make_k6_config_includes_payload_when_present() -> None:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(payload="/home/ubuntu/run/payload.json"),
        control_plane_url="http://h:8080",
        target_function="echo",
        stages=[],
        vus=4,
        duration="2m",
    )
    assert cfg.env["NANOFAAS_PAYLOAD"] == "/home/ubuntu/run/payload.json"
    assert cfg.payload_path == Path("/home/ubuntu/run/payload.json")
    assert cfg.vus == 4
    assert cfg.duration == "2m"
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py -q`
Expected: FAIL with `ModuleNotFoundError: No module named 'workflow_tasks.loadtest.loadgen_sequence'`.

- [ ] **Step 3: Write minimal implementation**

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/loadgen_sequence.py
"""Shared loadgen sequence builders.

The loadgen body (K6Config + the install_k6/run_k6/fetch/prometheus/report task
sequence) is identical across the multipass/azure/proxmox loadtest scenarios; the
only differences are already-resolved inputs (endpoints, URLs, paths, runner). These
builders capture the shared shape so the sequence is defined once.

This module must not import controlplane_tool (import-linter contract): callers pass
already-resolved primitives in.
"""
from __future__ import annotations

from collections.abc import Sequence
from pathlib import Path
from typing import Any

from workflow_tasks.loadtest.models import K6Config, K6Stage


def make_loadtest_k6_config(
    *,
    remote_paths: Any,
    control_plane_url: str,
    target_function: str,
    stages: Sequence[tuple[str, int]],
    vus: int | None,
    duration: str | None,
) -> K6Config:
    """Build the canonical loadtest K6Config from already-resolved inputs.

    ``remote_paths`` is duck-typed: it needs ``.script_path``, ``.summary_path`` and
    ``.payload_path`` (the result of ``two_vm_remote_paths``).
    """
    payload_path = remote_paths.payload_path
    return K6Config(
        script_path=Path(remote_paths.script_path),
        target_url=control_plane_url,
        summary_output_path=Path(remote_paths.summary_path),
        stages=tuple(K6Stage(duration=d, target=t) for d, t in stages),
        env={
            "NANOFAAS_URL": control_plane_url,
            "NANOFAAS_FUNCTION": target_function,
            **({"NANOFAAS_PAYLOAD": str(payload_path)} if payload_path else {}),
        },
        vus=vus,
        duration=duration,
        payload_path=Path(payload_path) if payload_path else None,
    )
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py -q`
Expected: PASS (2 passed).

- [ ] **Step 5: Re-export from the package root**

Add `make_loadtest_k6_config` to `tools/workflow-tasks/src/workflow_tasks/__init__.py`. Find the existing loadtest import block (it already imports `K6Config`, `RunK6`, `CapturePrometheusSnapshot`, etc.) and add the new name to both the import and `__all__`:

```python
from workflow_tasks.loadtest.loadgen_sequence import make_loadtest_k6_config
```

and add `"make_loadtest_k6_config",` to `__all__`.

- [ ] **Step 6: Verify the re-export imports**

Run: `uv run --project tools/workflow-tasks python -c "from workflow_tasks import make_loadtest_k6_config; print('ok')"`
Expected: `ok`.

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/loadtest/loadgen_sequence.py \
        tools/workflow-tasks/src/workflow_tasks/__init__.py \
        tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py
git commit -m "feat(loadtest): add make_loadtest_k6_config shared helper"
```

---

## Task 2: `build_loadgen_body_tasks` shared builder

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/loadgen_sequence.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Test: `tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py`

This builder returns the canonical 5-task list (`install_k6_task`, `RunK6`, `FetchVmResults`, `CapturePrometheusSnapshot`, `WriteK6Report`) from already-resolved inputs, carried in a `LoadgenBodyInputs` dataclass. Task-ids/titles are passed in (they carry per-lifecycle suffixes like "(Azure)"), so the builder reproduces today's strings exactly. The `CapturePrometheusSnapshot.window` thunk reads the `RunK6` task's `.result` lazily — the builder wires the window lambda to the `RunK6` instance it just created (matching all three scenarios today).

- [ ] **Step 1: Write the failing test**

```python
# append to tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py
from workflow_tasks.loadtest.loadgen_sequence import (
    LoadgenBodyInputs,
    build_loadgen_body_tasks,
)
from workflow_tasks.loadtest.models import PrometheusQuery


class _FakeRunner:
    pass


class _FakeFetcher:
    pass


class _FakeClient:
    pass


def _inputs(tmp_path) -> LoadgenBodyInputs:
    cfg = make_loadtest_k6_config(
        remote_paths=_remote_paths(),
        control_plane_url="http://h:8080",
        target_function="echo",
        stages=[("30s", 5)],
        vus=None,
        duration=None,
    )
    return LoadgenBodyInputs(
        task_ids=("loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
                  "metrics.prometheus_snapshot", "loadtest.write_report"),
        titles=("Install k6 on loadgen VM", "Run k6 loadtest", "Fetch k6 results from loadgen VM",
                "Capture Prometheus snapshots", "Write loadtest report"),
        install_k6_kwargs={"repo_root": tmp_path, "shell": object(), "host": "1.2.3.4",
                           "user": "ubuntu", "private_key": None, "port": None},
        runner=_FakeRunner(),
        fetcher=_FakeFetcher(),
        prometheus_client=_FakeClient(),
        prometheus_queries=(PrometheusQuery(name="q", expr="up", required=True),),
        k6_config=cfg,
        remote_dir="/home/ubuntu",
        remote_summary_path="/home/ubuntu/run/k6-summary.json",
        run_dir=tmp_path / "run",
    )


def test_build_loadgen_body_tasks_ids_and_titles(tmp_path) -> None:
    tasks = build_loadgen_body_tasks(_inputs(tmp_path))
    assert [t.task_id for t in tasks] == [
        "loadgen.install_k6", "loadgen.run_k6", "loadgen.fetch_results",
        "metrics.prometheus_snapshot", "loadtest.write_report",
    ]
    assert [t.title for t in tasks] == [
        "Install k6 on loadgen VM", "Run k6 loadtest", "Fetch k6 results from loadgen VM",
        "Capture Prometheus snapshots", "Write loadtest report",
    ]


def test_build_loadgen_body_window_reads_run_k6_result(tmp_path) -> None:
    from datetime import datetime, timezone
    from workflow_tasks.loadtest.models import K6RunResult

    tasks = build_loadgen_body_tasks(_inputs(tmp_path))
    run_k6 = tasks[1]
    prom = tasks[3]
    started = datetime(2026, 6, 6, 12, 0, 0, tzinfo=timezone.utc)
    ended = datetime(2026, 6, 6, 12, 5, 0, tzinfo=timezone.utc)
    run_k6._result = K6RunResult(  # noqa: SLF001
        summary_path=Path("/x"), started_at=started, ended_at=ended, passed=True
    )
    window = prom.window()
    assert window.start == started
    assert window.end == ended
```

- [ ] **Step 2: Run test to verify it fails**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py -q`
Expected: FAIL with `ImportError: cannot import name 'LoadgenBodyInputs'`.

- [ ] **Step 3: Write minimal implementation**

Append to `tools/workflow-tasks/src/workflow_tasks/loadtest/loadgen_sequence.py`:

```python
from dataclasses import dataclass

from workflow_tasks.loadtest.models import PrometheusQuery, TimeWindow
from workflow_tasks.loadtest.tasks import (
    CapturePrometheusSnapshot,
    FetchVmResults,
    RunK6,
    WriteK6Report,
)
from workflow_tasks.infra.ansible import install_k6_task


@dataclass
class LoadgenBodyInputs:
    """Already-resolved inputs for the canonical loadgen body (5 tasks).

    ``task_ids``/``titles`` are 5-tuples in sequence order (install_k6, run_k6,
    fetch, prometheus, report) — passed in so per-lifecycle title suffixes are
    reproduced exactly. ``install_k6_kwargs`` is forwarded verbatim to
    ``install_k6_task`` (host/user/private_key/port/repo_root/shell).
    """
    task_ids: tuple[str, str, str, str, str]
    titles: tuple[str, str, str, str, str]
    install_k6_kwargs: dict[str, Any]
    runner: Any
    fetcher: Any
    prometheus_client: Any
    prometheus_queries: tuple[PrometheusQuery, ...]
    k6_config: K6Config
    remote_dir: str
    remote_summary_path: str
    run_dir: Path


def build_loadgen_body_tasks(inputs: LoadgenBodyInputs) -> list[Any]:
    """Build the canonical 5-task loadgen body from resolved inputs.

    Returns [install_k6, run_k6, fetch, prometheus, report]. The prometheus window
    thunk reads the run_k6 task's result lazily (same as all three scenarios today).
    """
    install = install_k6_task(
        task_id=inputs.task_ids[0],
        title=inputs.titles[0],
        **inputs.install_k6_kwargs,
    )
    run_k6 = RunK6(
        task_id=inputs.task_ids[1],
        title=inputs.titles[1],
        runner=inputs.runner,
        config=inputs.k6_config,
        remote_dir=inputs.remote_dir,
    )
    fetch = FetchVmResults(
        task_id=inputs.task_ids[2],
        title=inputs.titles[2],
        fetcher=inputs.fetcher,
        remote_source=inputs.remote_summary_path,
        local_dest=inputs.run_dir,
    )
    prometheus = CapturePrometheusSnapshot(
        task_id=inputs.task_ids[3],
        title=inputs.titles[3],
        client=inputs.prometheus_client,
        queries=inputs.prometheus_queries,
        window=lambda: TimeWindow(start=run_k6.result.started_at, end=run_k6.result.ended_at),
        output_dir=inputs.run_dir,
    )
    report = WriteK6Report(
        task_id=inputs.task_ids[4],
        title=inputs.titles[4],
        data_dir=inputs.run_dir,
        output_dir=inputs.run_dir,
    )
    return [install, run_k6, fetch, prometheus, report]
```

- [ ] **Step 4: Run test to verify it passes**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py -q`
Expected: PASS (4 passed).

- [ ] **Step 5: Re-export from the package root**

Add to the loadtest import block in `tools/workflow-tasks/src/workflow_tasks/__init__.py`:

```python
from workflow_tasks.loadtest.loadgen_sequence import (
    LoadgenBodyInputs,
    build_loadgen_body_tasks,
    make_loadtest_k6_config,
)
```

(replace the Task-1 single-name import with this grouped import) and add `"LoadgenBodyInputs",` and `"build_loadgen_body_tasks",` to `__all__`.

- [ ] **Step 6: Run the full workflow_tasks suite (no regressions)**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests -q`
Expected: PASS (all green; import-linter contract test, if present, stays green — no controlplane_tool import added).

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/loadtest/loadgen_sequence.py \
        tools/workflow-tasks/src/workflow_tasks/__init__.py \
        tools/workflow-tasks/tests/loadtest/test_loadgen_sequence.py
git commit -m "feat(loadtest): add build_loadgen_body_tasks shared sequence builder"
```

---

## Task 3: Route two-vm (multipass) through the shared helpers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py:206-287`
- Test: `tools/controlplane/tests/test_two_vm_loadtest_plan.py` (existing — the golden safety net)

Replace the inline `K6Config` block (`:206-219`) and the inline `Workflow(tasks=[...])` body (`:250-285`) with calls to the shared helpers. The `RunK6` instance is now returned by the builder (index 1); the cleanup tasks and `Workflow(...).run()` wrapper stay. Behavior must be byte-for-byte identical.

- [ ] **Step 1: Run the existing golden test to capture the baseline (must pass before)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_plan.py -q`
Expected: PASS. (This is the safety net — it asserts task-ids/titles/ordering for two-vm.)

- [ ] **Step 2: Replace the K6Config + task body with shared helpers**

In `two_vm_loadtest.py`, change the imports near the top (the `from workflow_tasks import (...)` block) to add the two builders:

```python
from workflow_tasks import (
    CapturePrometheusSnapshot,
    DestroyVm,
    EnsureVmRunning,
    FetchVmResults,
    LoadgenBodyInputs,
    RunK6,
    TimeWindow,
    Workflow,
    build_loadgen_body_tasks,
    install_k6_task,
    make_loadtest_k6_config,
    workflow_step,
    WriteK6Report,
)
```

Replace the K6Config block (`:206-219`) with:

```python
        k6_config = make_loadtest_k6_config(
            remote_paths=remote_paths,
            control_plane_url=control_plane_url,
            target_function=two_vm_target_function(request),
            stages=two_vm_load_stages(request),
            vus=request.k6_vus,
            duration=request.k6_duration,
        )
```

Replace the `loadgen_runner`/`fetcher`/`prom_client`/`k6_task`/`Workflow(...)` block (`:221-287`) with:

```python
        loadgen_runner = OrchestratorVmRunner(vm_runner_impl.vm, request.loadgen_vm)
        fetcher = VmFileFetcher(vm=vm_runner_impl.vm, request=request.loadgen_vm)
        prom_client = HttpPrometheusClient(url=two_vm_prometheus_url(request.vm, host=stack_info.host))

        body = build_loadgen_body_tasks(
            LoadgenBodyInputs(
                task_ids=(
                    "loadgen.install_k6",
                    "loadgen.run_k6",
                    "loadgen.fetch_results",
                    "metrics.prometheus_snapshot",
                    "loadtest.write_report",
                ),
                titles=(
                    "Install k6 on loadgen VM",
                    "Run k6 loadtest",
                    "Fetch k6 results from loadgen VM",
                    "Capture Prometheus snapshots",
                    "Write loadtest report",
                ),
                install_k6_kwargs={
                    "repo_root": self.runner.paths.workspace_root,
                    "shell": self.runner.shell,
                    "host": loadgen_info.host,
                    "user": request.loadgen_vm.user,
                    "private_key": _find_ssh_private_key_path(find_ssh_public_key()),
                },
                runner=loadgen_runner,
                fetcher=fetcher,
                prometheus_client=prom_client,
                prometheus_queries=LOADTEST_PROMETHEUS_QUERIES,
                k6_config=k6_config,
                remote_dir=remote_home,
                remote_summary_path=remote_paths.summary_path,
                run_dir=run_dir,
            )
        )

        cleanup: list = []
        if getattr(request, "cleanup_vm", True):
            cleanup = [
                DestroyVm(
                    task_id="vm.loadgen.destroy",
                    title="Destroy loadgen VM",
                    lifecycle=lifecycle,
                    info=loadgen_info,
                ),
                DestroyVm(
                    task_id="vm.stack.destroy",
                    title="Destroy stack VM",
                    lifecycle=setup.lifecycle,
                    info=stack_info,
                ),
            ]

        Workflow(tasks=body, cleanup_tasks=cleanup).run()
```

Note: `install_k6_task` for multipass omits `port` (defaults to `None`) — keep it omitted to preserve today's argv exactly. `CapturePrometheusSnapshot`/`RunK6`/`TimeWindow`/`WriteK6Report` may now be unused-imports in this file; remove any that ruff flags (Step 4).

- [ ] **Step 3: Run the golden test to verify identical task-ids/titles**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_two_vm_loadtest_plan.py tools/controlplane/tests/test_two_vm_stack_prelude_argv.py tools/controlplane/tests/test_two_vm_loadtest_components.py -q`
Expected: PASS (unchanged — the body task-ids/titles match).

- [ ] **Step 4: Lint (remove any now-unused imports)**

Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py`
Expected: clean (fix any F401 unused-import by deleting the dead names from the import block).

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py
git commit -m "refactor(two-vm): route loadgen body through shared builder"
```

---

## Task 4: Route azure through the shared helpers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py:119-175`
- Test: `tools/controlplane/tests/test_azure_vm_loadtest_components.py`, `test_azure_vm_loadtest_runner.py` (existing safety net)

Azure uses skeleton-derived task-ids/titles (`s_install_k6.task_id`, etc.) carrying "(Azure)" suffixes. The builder must reproduce them — pass the skeleton strings in.

- [ ] **Step 1: Run the existing azure tests (baseline)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_azure_vm_loadtest_components.py tools/controlplane/tests/test_azure_vm_loadtest_runner.py -q`
Expected: PASS.

- [ ] **Step 2: Replace the K6Config + task body with shared helpers**

Add to the `from workflow_tasks import (...)` block in `azure_vm_loadtest.py`: `LoadgenBodyInputs`, `build_loadgen_body_tasks`, `make_loadtest_k6_config`.

Replace the K6Config block (`:119-139`) with:

```python
        k6_config = make_loadtest_k6_config(
            remote_paths=remote_paths,
            control_plane_url=control_plane_url,
            target_function=two_vm_target_function(request),
            stages=two_vm_load_stages(request),
            vus=request.k6_vus,
            duration=request.k6_duration,
        )
```

Replace the `loadgen_runner`/`fetcher`/`prom_client`/`k6_task`/`workflow = Workflow(...)` block (`:141-175`) with:

```python
        loadgen_runner = OrchestratorVmRunner(azure_orch, request.loadgen_vm)
        fetcher = VmFileFetcher(vm=azure_orch, request=request.loadgen_vm)
        prom_client = HttpPrometheusClient(
            url=two_vm_prometheus_url(request.vm, host=stack_info.host)
        )

        body = build_loadgen_body_tasks(
            LoadgenBodyInputs(
                task_ids=(s_install_k6.task_id, s_run_k6.task_id, s_fetch.task_id,
                          s_prom.task_id, s_report.task_id),
                titles=(s_install_k6.title, s_run_k6.title, s_fetch.title,
                        s_prom.title, s_report.title),
                install_k6_kwargs={
                    "repo_root": self.runner.paths.workspace_root,
                    "shell": self.runner.shell,
                    "host": azure_orch.connection_host(request.loadgen_vm),
                    "user": request.loadgen_vm.user,
                    "private_key": azure_orch.ssh_private_key_path(request.loadgen_vm),
                },
                runner=loadgen_runner,
                fetcher=fetcher,
                prometheus_client=prom_client,
                prometheus_queries=LOADTEST_PROMETHEUS_QUERIES,
                k6_config=k6_config,
                remote_dir=remote_home,
                remote_summary_path=remote_paths.summary_path,
                run_dir=run_dir,
            )
        )
        workflow = Workflow(
            tasks=body,
            cleanup_tasks=[
                DestroyVm(task_id=s_destroy.task_id, title=s_destroy.title, lifecycle=lifecycle, info=loadgen_info),
            ],
        )
        workflow.run()
```

(Azure's install_k6 omits `port` today — keep it omitted.)

- [ ] **Step 3: Run azure tests to verify identical**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_azure_vm_loadtest_components.py tools/controlplane/tests/test_azure_vm_loadtest_runner.py -q`
Expected: PASS (unchanged).

- [ ] **Step 4: Lint**

Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py`
Expected: clean (remove F401 unused imports — likely `RunK6`, `FetchVmResults`, `CapturePrometheusSnapshot`, `WriteK6Report`, `TimeWindow`, `K6Config`, `K6Stage`, `InstallK6`). Leave `install_k6_task` only if still referenced; here it is no longer used directly, so remove it too.

- [ ] **Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py
git commit -m "refactor(azure): route loadgen body through shared builder"
```

---

## Task 5: Route proxmox lazy closures through the shared helpers

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py:660-737`
- Test: `tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py`, `test_proxmox_prelude_argv.py`, `test_proxmox_prelude_workflow.py` (existing safety net)

Proxmox's orchestration (`_ActionTask`/`state`/lazy closures) stays. Only the *construction* inside the closures changes: `_k6_config` calls `make_loadtest_k6_config`; the per-task closures (`_install_k6`, `_run_k6`, `_fetch_results`, `_capture_prometheus`, `_write_report`) build their tasks via `build_loadgen_body_tasks` once the lazy inputs are resolved, then dispatch the right task. Because proxmox runs each task in its own closure (not as one `Workflow`), the cleanest fit is: keep `_k6_config` using the shared K6Config helper now (clear win, low risk); for the 5 task closures, build the whole body list once (lazily, when the first body task runs) and have each closure run its element. This preserves task-ids/titles/argv and the lazy endpoint resolution.

- [ ] **Step 1: Run proxmox tests (baseline)**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py tools/controlplane/tests/test_proxmox_prelude_argv.py tools/controlplane/tests/test_proxmox_prelude_workflow.py -q`
Expected: PASS.

- [ ] **Step 2: Share the K6Config construction**

Add to the `from workflow_tasks import (...)` block in `proxmox_vm_loadtest.py`: `LoadgenBodyInputs`, `build_loadgen_body_tasks`, `make_loadtest_k6_config`.

Replace the body of `_k6_config` (`:660-683`) with:

```python
        def _k6_config() -> K6Config:
            return make_loadtest_k6_config(
                remote_paths=_remote_paths(),
                control_plane_url=_control_plane_url(),
                target_function=two_vm_target_function(request),
                stages=two_vm_load_stages(request),
                vus=request.k6_vus,
                duration=request.k6_duration,
            )
```

- [ ] **Step 3: Share the 5-task body construction (lazy)**

Replace the five task closures (`_install_k6`, `_run_k6`, `_fetch_results`, `_capture_prometheus`, `_write_report`, `:685-737`) with a single lazy body builder plus thin dispatch closures. Insert before `_install_k6`:

```python
        def _body() -> list:
            if "body" not in state:
                host, port = proxmox_orch.ssh_endpoint(loadgen_request)
                state["body"] = build_loadgen_body_tasks(
                    LoadgenBodyInputs(
                        task_ids=(s_install_k6.task_id, s_run_k6.task_id, s_fetch.task_id,
                                  s_prom.task_id, s_report.task_id),
                        titles=(s_install_k6.title, s_run_k6.title, s_fetch.title,
                                s_prom.title, s_report.title),
                        install_k6_kwargs={
                            "repo_root": self.runner.paths.workspace_root,
                            "shell": self.runner.shell,
                            "host": host,
                            "user": loadgen_request.user,
                            "private_key": proxmox_orch.ssh_private_key_path(loadgen_request),
                            "port": port,
                        },
                        runner=cast(Any, _loadgen_runner()),
                        fetcher=VmFileFetcher(vm=proxmox_orch, request=loadgen_request),
                        prometheus_client=HttpPrometheusClient(
                            url=f"http://{state['prometheus_host']}:{state['prometheus_port']}"
                        ),
                        prometheus_queries=LOADTEST_PROMETHEUS_QUERIES,
                        k6_config=_k6_config(),
                        remote_dir=state["loadgen_info"].home,
                        remote_summary_path=_remote_paths().summary_path,
                        run_dir=_run_dir(),
                    )
                )
            return state["body"]

        def _install_k6() -> None:
            _body()[0].run()

        def _run_k6() -> None:
            _body()[1].run()

        def _fetch_results() -> None:
            _body()[2].run()

        def _capture_prometheus() -> None:
            _body()[3].run()

        def _write_report() -> None:
            _body()[4].run()
```

**Why this preserves behavior:** `_body()` is built lazily on first task (`_install_k6`), at which point `publish_ports`, `ensure_loadgen`, and the endpoint are all resolved (same preconditions the old closures relied on). `install_k6_task` receives the same host/port/key. `RunK6` is `_body()[1]`; the prometheus window thunk inside `build_loadgen_body_tasks` reads *that* `RunK6` instance's `.result` — identical to the old `state["k6_task"]` wiring (the old code stored `k6_task` in state and the prometheus closure read it; now the builder closes over the same instance). The proxmox install argv (with `port`) is preserved because `install_k6_kwargs` includes `port`.

- [ ] **Step 4: Run proxmox tests to verify identical**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests/test_proxmox_vm_loadtest_plan.py tools/controlplane/tests/test_proxmox_prelude_argv.py tools/controlplane/tests/test_proxmox_prelude_workflow.py -q`
Expected: PASS (unchanged — task-ids/titles/argv identical).

- [ ] **Step 5: Lint**

Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py`
Expected: clean (remove any now-unused imports — likely `InstallK6`, and possibly `RunK6`/`FetchVmResults`/`CapturePrometheusSnapshot`/`WriteK6Report`/`TimeWindow`/`install_k6_task` if no longer referenced; keep `cast`, `K6Config` if still used by signatures).

- [ ] **Step 6: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/scenario/scenarios/proxmox_vm_loadtest.py
git commit -m "refactor(proxmox): route loadgen body through shared builder"
```

---

## Task 6: Full-suite verification + dead-code sweep

**Files:**
- Possibly modify: any of the three scenarios (final import cleanup)

- [ ] **Step 1: Run the full controlplane suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q`
Expected: PASS (same count as before B2 started — no test added/removed in controlplane; the new tests live in workflow_tasks).

- [ ] **Step 2: Run the full workflow_tasks suite**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests -q`
Expected: PASS (includes the new `test_loadgen_sequence.py`; import-linter contract green).

- [ ] **Step 3: Confirm the triplication is gone**

Run: `grep -n "K6Config(" tools/controlplane/src/controlplane_tool/scenario/scenarios/*.py`
Expected: no direct `K6Config(` construction left in the three scenarios (only `make_loadtest_k6_config` calls). The only `RunK6(`/`FetchVmResults(`/`CapturePrometheusSnapshot(`/`WriteK6Report(` constructions remaining are inside `build_loadgen_body_tasks`.

Run: `grep -rn "RunK6(\|CapturePrometheusSnapshot(\|WriteK6Report(\|FetchVmResults(" tools/controlplane/src/controlplane_tool/scenario/scenarios/*.py`
Expected: empty (all construction moved to the shared builder).

- [ ] **Step 4: Lint the whole touched tree**

Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool/scenario/scenarios/ && uv run --project tools/workflow-tasks ruff check tools/workflow-tasks/src/workflow_tasks/loadtest/`
Expected: clean.

- [ ] **Step 5: Commit any final cleanup**

```bash
git add -A
git commit -m "chore(loadtest): final import cleanup after loadgen-body unification"
```

(Skip if nothing changed.)

---

## Real-VM Validation (post-merge gate — NOT in CI)

Per the design spec §4/§5, after the code is reviewed and the unit/golden suites are green, validate on a real VM before declaring B2 done:

- [ ] **multipass `two-vm-loadtest`** — run end-to-end from the TUI/CLI; confirm the loadgen body executes (k6 installs, runs, results fetched, Prometheus snapshot has data, report written). This is the always-required gate.
- [ ] **azure / proxmox** — validate when credentials/hardware are available; otherwise flag in the PR that azure/proxmox ship unvalidated for B2 (the golden/argv tests prove the sequence is byte-identical, not that the live run works).

---

## Self-Review Notes

- **Spec coverage:** Design spec §3 Phase B2 = "Extract the install_k6 → run_k6 → fetch → prometheus → report (+ ensure/destroy loadgen) sequence into one builder parametrized by the ConnectivityAdapter; collapse the three hand-built loadgen blocks." This plan extracts the K6Config + 5-task body (install_k6 → report) into shared builders and routes all three through them. The ensure/destroy-loadgen lifecycle wiring and the eager-vs-lazy orchestration shell are explicitly deferred to B3 (documented in the Scope note) — B2 here shares the *sequence body*, which is the triplicated, drift-prone part. If full ensure/destroy unification is required inside B2, that becomes an additional task, but it forces the eager/lazy merge that B3 owns.
- **Type consistency:** `LoadgenBodyInputs` fields and `make_loadtest_k6_config`/`build_loadgen_body_tasks` signatures are used identically in Tasks 3/4/5. `install_k6_kwargs` is forwarded verbatim to `install_k6_task` (multipass/azure omit `port`; proxmox includes it) — matching today's argv.
- **No placeholders:** every code step shows the full replacement.
