# Infra Tasks in workflow_tasks Library

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract VM lifecycle and k6 loadtest operations into reusable `Task` classes inside the `workflow_tasks` library, with Protocol-based ports for all infrastructure dependencies.

**Architecture:** Two new subpackages — `workflow_tasks/vm/` (VM lifecycle) and `workflow_tasks/loadtest/` (k6 tasks) — each with `models.py` (pure dataclasses), `ports.py` (Protocol interfaces), and `tasks.py` (Task implementations). `controlplane_tool` provides concrete adapters injected at call site. Zero new external dependencies added to `workflow_tasks`.

**Tech Stack:** Python 3.11+, stdlib only in `workflow_tasks` (no jinja2/plotly — those stay in `controlplane_tool`). Protocols from `typing`. dataclasses for models and tasks.

---

## File Map

**Create in `tools/workflow-tasks/src/workflow_tasks/`:**
- `vm/__init__.py` — re-exports VmConfig, VmInfo, VmLifecycle, EnsureVmRunning, DestroyVm
- `vm/models.py` — `VmConfig`, `VmInfo`
- `vm/ports.py` — `VmLifecycle` Protocol
- `vm/tasks.py` — `EnsureVmRunning`, `DestroyVm`
- `loadtest/__init__.py` — re-exports all public types
- `loadtest/models.py` — `K6Config`, `K6Stage`, `K6RunResult`, `TimeWindow`, `PrometheusQuery`
- `loadtest/ports.py` — `RemoteFileFetcher`, `PrometheusClient` Protocols
- `loadtest/tasks.py` — `InstallK6`, `RunK6`, `FetchVmResults`, `CapturePrometheusSnapshot`, `WriteK6Report`

**Create in `tools/workflow-tasks/tests/`:**
- `tests/vm/__init__.py`
- `tests/vm/test_vm_tasks.py`
- `tests/loadtest/__init__.py`
- `tests/loadtest/test_loadtest_models.py`
- `tests/loadtest/test_loadtest_tasks.py`

**Modify in `tools/workflow-tasks/`:**
- `src/workflow_tasks/__init__.py` — export new types
- `tests/test_package_boundaries.py` — add import-cleanliness checks for new subpackages
- `tests/test_public_api.py` — add assertions for new exports

**Create in `tools/controlplane/src/controlplane_tool/`:**
- `infra/vm_lifecycle_adapters.py` — `MultipassVmAdapter`, `AzureVmAdapter` implementing `VmLifecycle`
- `loadtest/loadtest_adapters.py` — `VmFileFetcher` (RemoteFileFetcher), `HttpPrometheusClient` (PrometheusClient)

**Modify in `tools/controlplane/src/controlplane_tool/`:**
- `scenario/scenarios/two_vm_loadtest.py` — `TwoVmLoadtestPlan.run()` uses `Workflow` + new tasks
- `scenario/scenarios/azure_vm_loadtest.py` — same

---

## Task 1: `workflow_tasks/vm/` — models, protocol, tasks

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/models.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/ports.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/vm/tasks.py`
- Create: `tools/workflow-tasks/tests/vm/__init__.py`
- Create: `tools/workflow-tasks/tests/vm/test_vm_tasks.py`

- [ ] **Step 1: Write failing tests**

```python
# tools/workflow-tasks/tests/vm/test_vm_tasks.py
from __future__ import annotations

from dataclasses import dataclass
from unittest.mock import MagicMock

import pytest

from workflow_tasks.vm.models import VmConfig, VmInfo
from workflow_tasks.vm.tasks import DestroyVm, EnsureVmRunning


@dataclass
class _FakeLifecycle:
    vm_info: VmInfo
    destroyed: list[VmInfo]

    def ensure_running(self, config: VmConfig) -> VmInfo:
        return self.vm_info

    def destroy(self, info: VmInfo) -> None:
        self.destroyed.append(info)


def _make_lifecycle(name: str = "test-vm") -> _FakeLifecycle:
    return _FakeLifecycle(
        vm_info=VmInfo(name=name, host="10.0.0.1", user="ubuntu", home="/home/ubuntu"),
        destroyed=[],
    )


def test_ensure_vm_running_returns_vm_info() -> None:
    lifecycle = _make_lifecycle("my-vm")
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM running",
        lifecycle=lifecycle,
        config=VmConfig(name="my-vm"),
    )
    info = task.run()
    assert info.name == "my-vm"
    assert info.host == "10.0.0.1"


def test_ensure_vm_running_satisfies_task_protocol() -> None:
    from workflow_tasks.core.task import Task

    lifecycle = _make_lifecycle()
    task = EnsureVmRunning(
        task_id="vm.ensure_running",
        title="Ensure VM running",
        lifecycle=lifecycle,
        config=VmConfig(name="my-vm"),
    )
    assert isinstance(task, Task)


def test_destroy_vm_calls_lifecycle_destroy() -> None:
    lifecycle = _make_lifecycle()
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")
    task = DestroyVm(task_id="vm.destroy", title="Destroy VM", lifecycle=lifecycle, info=info)
    task.run()
    assert info in lifecycle.destroyed


def test_destroy_vm_satisfies_task_protocol() -> None:
    from workflow_tasks.core.task import Task

    lifecycle = _make_lifecycle()
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")
    task = DestroyVm(task_id="vm.destroy", title="Destroy VM", lifecycle=lifecycle, info=info)
    assert isinstance(task, Task)
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/ -v 2>&1 | tail -8
```

Expected: `ModuleNotFoundError: No module named 'workflow_tasks.vm'`

- [ ] **Step 3: Write `vm/models.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/vm/models.py
from __future__ import annotations

from dataclasses import dataclass


@dataclass(frozen=True)
class VmConfig:
    name: str
    cpus: int = 2
    memory: str = "2G"
    disk: str = "20G"


@dataclass(frozen=True)
class VmInfo:
    name: str
    host: str
    user: str
    home: str
```

- [ ] **Step 4: Write `vm/ports.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/vm/ports.py
from __future__ import annotations

from typing import Protocol

from workflow_tasks.vm.models import VmConfig, VmInfo


class VmLifecycle(Protocol):
    def ensure_running(self, config: VmConfig) -> VmInfo: ...
    def destroy(self, info: VmInfo) -> None: ...
```

- [ ] **Step 5: Write `vm/tasks.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/vm/tasks.py
from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from workflow_tasks.vm.models import VmConfig, VmInfo
    from workflow_tasks.vm.ports import VmLifecycle


@dataclass
class EnsureVmRunning:
    task_id: str
    title: str
    lifecycle: "VmLifecycle"
    config: "VmConfig"

    def run(self) -> "VmInfo":
        return self.lifecycle.ensure_running(self.config)


@dataclass
class DestroyVm:
    task_id: str
    title: str
    lifecycle: "VmLifecycle"
    info: "VmInfo"

    def run(self) -> None:
        self.lifecycle.destroy(self.info)
```

- [ ] **Step 6: Write `vm/__init__.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/vm/__init__.py
from workflow_tasks.vm.models import VmConfig, VmInfo
from workflow_tasks.vm.ports import VmLifecycle
from workflow_tasks.vm.tasks import DestroyVm, EnsureVmRunning

__all__ = ["VmConfig", "VmInfo", "VmLifecycle", "EnsureVmRunning", "DestroyVm"]
```

- [ ] **Step 7: Create `tests/vm/__init__.py`**

Empty file:
```python
```

- [ ] **Step 8: Run tests to verify they pass**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/vm/ -v 2>&1 | tail -10
```

Expected: `4 passed`

- [ ] **Step 9: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/vm/ \
    tools/workflow-tasks/tests/vm/ && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): add vm/ subpackage with EnsureVmRunning and DestroyVm tasks

VmLifecycle Protocol abstracts VM provisioner — concrete adapters
live in controlplane_tool and are injected at call site.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: `workflow_tasks/loadtest/` — models and ports

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/models.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/ports.py`
- Create: `tools/workflow-tasks/tests/loadtest/__init__.py`
- Create: `tools/workflow-tasks/tests/loadtest/test_loadtest_models.py`

- [ ] **Step 1: Write failing tests**

```python
# tools/workflow-tasks/tests/loadtest/test_loadtest_models.py
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path

from workflow_tasks.loadtest.models import (
    K6Config,
    K6RunResult,
    K6Stage,
    PrometheusQuery,
    TimeWindow,
)


def test_k6_stage_is_frozen() -> None:
    stage = K6Stage(duration="30s", target=10)
    assert stage.duration == "30s"
    assert stage.target == 10


def test_k6_config_defaults() -> None:
    config = K6Config(
        script_path=Path("/scripts/test.js"),
        target_url="http://localhost:8080",
        summary_output_path=Path("/results/summary.json"),
    )
    assert config.stages == ()
    assert config.env == {}
    assert config.vus is None
    assert config.duration is None
    assert config.payload_path is None


def test_k6_run_result_passed_flag() -> None:
    now = datetime.now(timezone.utc)
    result = K6RunResult(
        summary_path=Path("/results/summary.json"),
        started_at=now,
        ended_at=now,
        passed=True,
    )
    assert result.passed is True


def test_time_window_stores_start_end() -> None:
    start = datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc)
    end = datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc)
    window = TimeWindow(start=start, end=end)
    assert window.start == start
    assert window.end == end


def test_prometheus_query_defaults() -> None:
    q = PrometheusQuery(name="requests_total", expr="sum(http_requests_total)")
    assert q.required is False
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_loadtest_models.py -v 2>&1 | tail -8
```

Expected: `ModuleNotFoundError: No module named 'workflow_tasks.loadtest'`

- [ ] **Step 3: Write `loadtest/models.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/models.py
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path


@dataclass(frozen=True)
class K6Stage:
    duration: str
    target: int


@dataclass(frozen=True)
class K6Config:
    script_path: Path
    target_url: str
    summary_output_path: Path
    stages: tuple[K6Stage, ...] = ()
    env: dict[str, str] = field(default_factory=dict)
    vus: int | None = None
    duration: str | None = None
    payload_path: Path | None = None

    def __post_init__(self) -> None:
        object.__setattr__(self, "env", dict(self.env))


@dataclass(frozen=True)
class K6RunResult:
    summary_path: Path
    started_at: datetime
    ended_at: datetime
    passed: bool


@dataclass(frozen=True)
class TimeWindow:
    start: datetime
    end: datetime


@dataclass(frozen=True)
class PrometheusQuery:
    name: str
    expr: str
    required: bool = False
```

- [ ] **Step 4: Write `loadtest/ports.py`**

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/ports.py
from __future__ import annotations

from pathlib import Path
from typing import Protocol

from workflow_tasks.loadtest.models import TimeWindow


class RemoteFileFetcher(Protocol):
    def fetch_from(self, remote: str, local: Path) -> None: ...


class PrometheusClient(Protocol):
    def query_range(
        self,
        expr: str,
        window: TimeWindow,
        step_seconds: int = 5,
    ) -> list[dict[str, float | str]]: ...
```

- [ ] **Step 5: Create empty `__init__.py` files**

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/__init__.py
# (populated in Task 5)
```

```python
# tools/workflow-tasks/tests/loadtest/__init__.py
```

- [ ] **Step 6: Run tests to verify they pass**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_loadtest_models.py -v 2>&1 | tail -8
```

Expected: `5 passed`

- [ ] **Step 7: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/loadtest/ \
    tools/workflow-tasks/tests/loadtest/ && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): add loadtest/ models and Protocol ports

K6Config, K6Stage, K6RunResult, TimeWindow, PrometheusQuery dataclasses.
RemoteFileFetcher and PrometheusClient Protocols for infrastructure injection.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: `workflow_tasks/loadtest/tasks.py` — InstallK6 and RunK6

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py` (partial — InstallK6, RunK6)
- Modify: `tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py` (create)

`VmCommandRunner` is already in `workflow_tasks.tasks.executors` — import from there.

- [ ] **Step 1: Write failing tests**

```python
# tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path

import pytest

from workflow_tasks.loadtest.models import K6Config, K6RunResult, K6Stage
from workflow_tasks.loadtest.tasks import InstallK6, RunK6


@dataclass
class _VmResult:
    return_code: int
    stdout: str = ""
    stderr: str = ""


class _RecordingVmRunner:
    def __init__(self, return_code: int = 0) -> None:
        self.return_code = return_code
        self.commands: list[tuple[tuple[str, ...], dict, str | None, bool]] = []

    def run_vm_command(
        self,
        argv: tuple[str, ...],
        *,
        env: dict[str, str],
        remote_dir: str | None,
        dry_run: bool,
    ) -> _VmResult:
        self.commands.append((argv, env, remote_dir, dry_run))
        return _VmResult(return_code=self.return_code)


def _make_k6_config(tmp_path: Path) -> K6Config:
    return K6Config(
        script_path=Path("/remote/scripts/test.js"),
        target_url="http://10.0.0.1:8080",
        summary_output_path=Path("/remote/results/summary.json"),
        stages=(K6Stage(duration="30s", target=5),),
        env={"NANOFAAS_FUNCTION": "my-fn"},
    )


def test_install_k6_runs_bash_install_command() -> None:
    runner = _RecordingVmRunner()
    task = InstallK6(task_id="loadgen.install_k6", title="Install k6", runner=runner, remote_dir="/home/ubuntu")
    task.run()
    assert len(runner.commands) == 1
    argv, _, remote_dir, _ = runner.commands[0]
    assert argv[0] == "bash"
    assert "k6" in argv[-1]
    assert remote_dir == "/home/ubuntu"


def test_install_k6_raises_on_nonzero_exit() -> None:
    runner = _RecordingVmRunner(return_code=1)
    task = InstallK6(task_id="loadgen.install_k6", title="Install k6", runner=runner, remote_dir="/home/ubuntu")
    with pytest.raises(RuntimeError):
        task.run()


def test_run_k6_passes_summary_export_flag(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    argv = runner.commands[0][0]
    assert "--summary-export" in argv
    assert str(config.summary_output_path) in argv


def test_run_k6_injects_env_vars_as_e_flags(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    argv = runner.commands[0][0]
    argv_str = " ".join(argv)
    assert "NANOFAAS_FUNCTION=my-fn" in argv_str


def test_run_k6_returns_k6_run_result_with_timing(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    result = task.run()
    assert isinstance(result, K6RunResult)
    assert result.summary_path == config.summary_output_path
    assert result.started_at <= result.ended_at
    assert result.passed is True


def test_run_k6_marks_failed_on_nonzero_exit(tmp_path: Path) -> None:
    runner = _RecordingVmRunner(return_code=1)
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    result = task.run()
    assert result.passed is False


def test_run_k6_result_property_raises_before_run(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    with pytest.raises(RuntimeError, match="not been called"):
        _ = task.result


def test_run_k6_result_property_returns_after_run(tmp_path: Path) -> None:
    runner = _RecordingVmRunner()
    config = _make_k6_config(tmp_path)
    task = RunK6(task_id="loadgen.run_k6", title="Run k6", runner=runner, config=config, remote_dir="/home/ubuntu")
    task.run()
    assert task.result.passed is True
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_loadtest_tasks.py -v 2>&1 | tail -10
```

Expected: `ImportError: cannot import name 'InstallK6' from 'workflow_tasks.loadtest.tasks'`

- [ ] **Step 3: Write `loadtest/tasks.py` (InstallK6 and RunK6)**

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    from workflow_tasks.loadtest.models import K6Config, K6RunResult
    from workflow_tasks.tasks.executors import VmCommandRunner


_K6_INSTALL_CMD: tuple[str, ...] = (
    "bash",
    "-lc",
    (
        "which k6 || ("
        "curl -fsSL https://pkg.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg"
        " && echo 'deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main'"
        " | sudo tee /etc/apt/sources.list.d/k6.list"
        " && sudo apt-get update -qq && sudo apt-get install -y k6)"
    ),
)


def _build_k6_argv(config: "K6Config") -> tuple[str, ...]:
    args: list[str] = ["k6", "run", "--summary-export", str(config.summary_output_path)]
    if config.vus is not None:
        args.extend(["--vus", str(config.vus)])
    if config.duration is not None:
        args.extend(["--duration", config.duration])
    if config.vus is None and config.duration is None:
        for stage in config.stages:
            args.extend(["--stage", f"{stage.duration}:{stage.target}"])
    for key, value in config.env.items():
        args.extend(["-e", f"{key}={value}"])
    if config.payload_path is not None:
        args.extend(["-e", f"NANOFAAS_PAYLOAD={config.payload_path}"])
    args.append(str(config.script_path))
    return tuple(args)


@dataclass
class InstallK6:
    task_id: str
    title: str
    runner: "VmCommandRunner"
    remote_dir: str

    def run(self) -> None:
        result = self.runner.run_vm_command(
            _K6_INSTALL_CMD,
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        if result.return_code != 0:
            raise RuntimeError(result.stderr or result.stdout or f"k6 install failed (exit {result.return_code})")


@dataclass
class RunK6:
    task_id: str
    title: str
    runner: "VmCommandRunner"
    config: "K6Config"
    remote_dir: str
    _result: "K6RunResult | None" = field(default=None, init=False, repr=False, compare=False)

    def run(self) -> "K6RunResult":
        from workflow_tasks.loadtest.models import K6RunResult

        started_at = datetime.now(timezone.utc)
        result = self.runner.run_vm_command(
            _build_k6_argv(self.config),
            env={},
            remote_dir=self.remote_dir,
            dry_run=False,
        )
        ended_at = datetime.now(timezone.utc)
        self._result = K6RunResult(
            summary_path=self.config.summary_output_path,
            started_at=started_at,
            ended_at=ended_at,
            passed=result.return_code == 0,
        )
        return self._result

    @property
    def result(self) -> "K6RunResult":
        if self._result is None:
            raise RuntimeError("RunK6.run() has not been called")
        return self._result
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_loadtest_tasks.py -v 2>&1 | tail -12
```

Expected: `8 passed`

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py \
    tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): add InstallK6 and RunK6 tasks to loadtest/ subpackage

RunK6.result property allows downstream tasks to access timing info
(started_at, ended_at) for prometheus window calculation.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: `workflow_tasks/loadtest/tasks.py` — FetchVmResults, CapturePrometheusSnapshot, WriteK6Report

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py` (add 3 tasks)
- Modify: `tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py` (add tests)

`WriteK6Report` generates HTML with stdlib only (no jinja2, no plotly).

- [ ] **Step 1: Write failing tests (append to existing test file)**

Read `tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py` first, then append:

```python
# --- append to test_loadtest_tasks.py ---
import json
from datetime import timezone

from workflow_tasks.loadtest.models import PrometheusQuery, TimeWindow
from workflow_tasks.loadtest.tasks import (
    CapturePrometheusSnapshot,
    FetchVmResults,
    WriteK6Report,
)


class _RecordingFetcher:
    def __init__(self) -> None:
        self.calls: list[tuple[str, Path]] = []

    def fetch_from(self, remote: str, local: Path) -> None:
        self.calls.append((remote, local))


class _RecordingPrometheusClient:
    def __init__(self, points: list[dict] | None = None) -> None:
        self._points = points or [{"timestamp": "2026-01-01T10:00:00Z", "value": 1.0}]
        self.calls: list[tuple[str, TimeWindow, int]] = []

    def query_range(
        self, expr: str, window: TimeWindow, step_seconds: int = 5
    ) -> list[dict]:
        self.calls.append((expr, window, step_seconds))
        return self._points


def _make_window() -> TimeWindow:
    return TimeWindow(
        start=datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc),
        end=datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc),
    )


def test_fetch_vm_results_calls_fetcher(tmp_path: Path) -> None:
    fetcher = _RecordingFetcher()
    task = FetchVmResults(
        task_id="loadgen.fetch_results",
        title="Fetch results",
        fetcher=fetcher,
        remote_source="/remote/results",
        local_dest=tmp_path / "results",
    )
    returned = task.run()
    assert fetcher.calls == [("/remote/results", tmp_path / "results")]
    assert returned == tmp_path / "results"


def test_fetch_vm_results_creates_local_dest(tmp_path: Path) -> None:
    fetcher = _RecordingFetcher()
    dest = tmp_path / "deep" / "nested" / "results"
    task = FetchVmResults(
        task_id="loadgen.fetch_results",
        title="Fetch results",
        fetcher=fetcher,
        remote_source="/remote/results",
        local_dest=dest,
    )
    task.run()
    assert dest.exists()


def test_capture_prometheus_snapshot_queries_all_metrics(tmp_path: Path) -> None:
    client = _RecordingPrometheusClient()
    queries = (
        PrometheusQuery(name="req_total", expr="sum(http_requests_total)"),
        PrometheusQuery(name="latency", expr="http_req_duration"),
    )
    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=queries,
        window=_make_window(),
        output_dir=tmp_path,
    )
    task.run()
    queried_exprs = [call[0] for call in client.calls]
    assert "sum(http_requests_total)" in queried_exprs
    assert "http_req_duration" in queried_exprs


def test_capture_prometheus_snapshot_writes_json(tmp_path: Path) -> None:
    client = _RecordingPrometheusClient()
    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=(PrometheusQuery(name="req", expr="http_requests_total"),),
        window=_make_window(),
        output_dir=tmp_path,
    )
    dest = task.run()
    assert dest.exists()
    data = json.loads(dest.read_text())
    assert "queries" in data
    assert "req" in data["queries"]


def test_capture_prometheus_snapshot_accepts_callable_window(tmp_path: Path) -> None:
    client = _RecordingPrometheusClient()
    called: list[bool] = []

    def lazy_window() -> TimeWindow:
        called.append(True)
        return _make_window()

    task = CapturePrometheusSnapshot(
        task_id="metrics.snapshot",
        title="Capture snapshots",
        client=client,
        queries=(PrometheusQuery(name="req", expr="http_requests_total"),),
        window=lazy_window,
        output_dir=tmp_path,
    )
    task.run()
    assert called == [True]


def test_write_k6_report_generates_html(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    summary = {
        "metrics": {
            "http_req_duration": {
                "type": "trend",
                "values": {"avg": 123.4, "p(90)": 200.5, "p(95)": 350.2},
            },
            "http_reqs": {
                "type": "counter",
                "values": {"count": 1000, "rate": 10.5},
            },
        }
    }
    (data_dir / "k6-summary.json").write_text(json.dumps(summary), encoding="utf-8")

    task = WriteK6Report(
        task_id="loadtest.write_report",
        title="Write report",
        data_dir=data_dir,
        output_dir=tmp_path,
    )
    report_path = task.run()
    assert report_path.exists()
    html = report_path.read_text()
    assert "http_req_duration" in html
    assert "http_reqs" in html


def test_write_k6_report_includes_prometheus_section_when_snapshot_present(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    (data_dir / "metrics").mkdir(parents=True)
    (data_dir / "k6-summary.json").write_text(json.dumps({"metrics": {}}), encoding="utf-8")
    snapshot = {
        "queries": {
            "function_dispatch_total": {"points": [{"timestamp": "t", "value": 1.0}]}
        }
    }
    (data_dir / "metrics" / "prometheus-snapshot.json").write_text(
        json.dumps(snapshot), encoding="utf-8"
    )

    task = WriteK6Report(
        task_id="loadtest.write_report",
        title="Write report",
        data_dir=data_dir,
        output_dir=tmp_path,
    )
    html = task.run().read_text()
    assert "function_dispatch_total" in html


def test_write_k6_report_works_without_prometheus_snapshot(tmp_path: Path) -> None:
    data_dir = tmp_path / "data"
    data_dir.mkdir()
    (data_dir / "k6-summary.json").write_text(json.dumps({"metrics": {}}), encoding="utf-8")

    task = WriteK6Report(
        task_id="loadtest.write_report",
        title="Write report",
        data_dir=data_dir,
        output_dir=tmp_path,
    )
    report_path = task.run()
    assert report_path.exists()
```

- [ ] **Step 2: Run tests to verify new tests fail**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/test_loadtest_tasks.py -v -k "fetch or capture or write" 2>&1 | tail -10
```

Expected: `ImportError: cannot import name 'FetchVmResults'`

- [ ] **Step 3: Append FetchVmResults, CapturePrometheusSnapshot, WriteK6Report to `tasks.py`**

Read `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py` first, then append:

```python
# --- append to loadtest/tasks.py ---
import json
from collections.abc import Callable
from pathlib import Path

from workflow_tasks.loadtest.models import PrometheusQuery, TimeWindow
from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher


@dataclass
class FetchVmResults:
    task_id: str
    title: str
    fetcher: RemoteFileFetcher
    remote_source: str
    local_dest: Path

    def run(self) -> Path:
        self.local_dest.mkdir(parents=True, exist_ok=True)
        self.fetcher.fetch_from(self.remote_source, self.local_dest)
        return self.local_dest


@dataclass
class CapturePrometheusSnapshot:
    task_id: str
    title: str
    client: PrometheusClient
    queries: tuple[PrometheusQuery, ...]
    window: TimeWindow | Callable[[], TimeWindow]
    output_dir: Path

    def _resolve_window(self) -> TimeWindow:
        if callable(self.window):
            return self.window()
        return self.window

    def run(self) -> Path:
        window = self._resolve_window()
        metrics_dir = self.output_dir / "metrics"
        metrics_dir.mkdir(parents=True, exist_ok=True)

        result: dict[str, dict] = {}
        for q in self.queries:
            entry: dict[str, object] = {"query": q.expr, "required": q.required, "points": []}
            try:
                points = self.client.query_range(q.expr, window)
            except RuntimeError as exc:
                if q.required:
                    raise RuntimeError(f"required query '{q.name}' failed: {exc}") from exc
                entry["error"] = str(exc)
                result[q.name] = entry
                continue
            if q.required and not points:
                raise RuntimeError(f"required query '{q.name}' returned no data")
            entry["points"] = points
            result[q.name] = entry

        snapshot = {
            "start": window.start.isoformat(),
            "end": window.end.isoformat(),
            "queries": result,
        }
        dest = metrics_dir / "prometheus-snapshot.json"
        dest.write_text(json.dumps(snapshot, indent=2), encoding="utf-8")
        return dest


def _render_k6_html(k6_summary: dict, prom_snapshot: dict | None) -> str:
    metrics = k6_summary.get("metrics", {})
    rows: list[str] = []
    for name, entry in metrics.items():
        if not isinstance(entry, dict):
            continue
        values = entry.get("values", {})
        formatted = " | ".join(
            f"{k}: {v:.3g}" if isinstance(v, float) else f"{k}: {v}"
            for k, v in values.items()
        )
        rows.append(f"<tr><td>{name}</td><td>{entry.get('type', '')}</td><td>{formatted}</td></tr>")

    prom_section = ""
    if prom_snapshot:
        queries = prom_snapshot.get("queries", {})
        prom_rows = [
            f"<tr><td>{metric}</td><td>{len(data.get('points', []))} points</td></tr>"
            for metric, data in queries.items()
            if isinstance(data, dict)
        ]
        if prom_rows:
            prom_section = (
                "<h2>Prometheus Metrics</h2>"
                "<table><tr><th>Metric</th><th>Data</th></tr>"
                + "".join(prom_rows)
                + "</table>"
            )

    return (
        "<!DOCTYPE html>\n"
        '<html lang="en">\n'
        "<head>\n"
        '<meta charset="utf-8">\n'
        "<title>k6 Loadtest Report</title>\n"
        "<style>\n"
        "  body { font-family: sans-serif; max-width: 1000px; margin: 0 auto; padding: 24px; }\n"
        "  h1, h2 { border-bottom: 1px solid #eee; padding-bottom: 8px; }\n"
        "  table { border-collapse: collapse; width: 100%; margin: 16px 0; }\n"
        "  th, td { border: 1px solid #ddd; padding: 8px 12px; text-align: left; }\n"
        "  th { background: #f5f5f5; font-weight: 600; }\n"
        "  tr:hover { background: #fafafa; }\n"
        "</style>\n"
        "</head>\n"
        "<body>\n"
        "<h1>k6 Loadtest Report</h1>\n"
        "<h2>k6 Metrics</h2>\n"
        "<table>\n"
        "<tr><th>Metric</th><th>Type</th><th>Values</th></tr>\n"
        + "".join(rows)
        + "\n</table>\n"
        + prom_section
        + "\n</body>\n</html>"
    )


@dataclass
class WriteK6Report:
    task_id: str
    title: str
    data_dir: Path
    output_dir: Path

    def run(self) -> Path:
        k6_summary_path = self.data_dir / "k6-summary.json"
        k6_summary = json.loads(k6_summary_path.read_text(encoding="utf-8"))

        prom_path = self.data_dir / "metrics" / "prometheus-snapshot.json"
        prom_snapshot: dict | None = None
        if prom_path.exists():
            try:
                prom_snapshot = json.loads(prom_path.read_text(encoding="utf-8"))
            except json.JSONDecodeError:
                pass

        self.output_dir.mkdir(parents=True, exist_ok=True)
        html = _render_k6_html(k6_summary, prom_snapshot)
        dest = self.output_dir / "report.html"
        dest.write_text(html, encoding="utf-8")
        return dest
```

- [ ] **Step 4: Run all loadtest task tests to verify they pass**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/loadtest/ -v 2>&1 | tail -20
```

Expected: all tests pass (8 from Task 3 + 8 from this task = 16 total)

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py \
    tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): add FetchVmResults, CapturePrometheusSnapshot, WriteK6Report tasks

WriteK6Report generates report.html from k6-summary.json using stdlib only.
CapturePrometheusSnapshot accepts lazy window (Callable[[], TimeWindow])
to resolve timing after RunK6 completes.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Public API — exports and boundary tests

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/__init__.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/__init__.py`
- Modify: `tools/workflow-tasks/tests/test_package_boundaries.py`
- Modify: `tools/workflow-tasks/tests/test_public_api.py`

- [ ] **Step 1: Populate `loadtest/__init__.py`**

Read the file first, then write:

```python
# tools/workflow-tasks/src/workflow_tasks/loadtest/__init__.py
from workflow_tasks.loadtest.models import (
    K6Config,
    K6RunResult,
    K6Stage,
    PrometheusQuery,
    TimeWindow,
)
from workflow_tasks.loadtest.ports import PrometheusClient, RemoteFileFetcher
from workflow_tasks.loadtest.tasks import (
    CapturePrometheusSnapshot,
    FetchVmResults,
    InstallK6,
    RunK6,
    WriteK6Report,
)

__all__ = [
    "K6Config", "K6RunResult", "K6Stage", "PrometheusQuery", "TimeWindow",
    "RemoteFileFetcher", "PrometheusClient",
    "InstallK6", "RunK6", "FetchVmResults", "CapturePrometheusSnapshot", "WriteK6Report",
]
```

- [ ] **Step 2: Update `workflow_tasks/__init__.py`**

Read the file (currently at line 54). Add imports after the existing content:

Find the line:
```python
    "phase", "step", "success", "warning", "skip", "fail",
    "workflow_log", "workflow_step", "status",
]
```

Replace with:
```python
    "phase", "step", "success", "warning", "skip", "fail",
    "workflow_log", "workflow_step", "status",
    # vm
    "VmConfig", "VmInfo", "VmLifecycle", "EnsureVmRunning", "DestroyVm",
    # loadtest
    "K6Config", "K6Stage", "K6RunResult", "TimeWindow", "PrometheusQuery",
    "RemoteFileFetcher", "PrometheusClient",
    "InstallK6", "RunK6", "FetchVmResults", "CapturePrometheusSnapshot", "WriteK6Report",
]
```

And add the import lines after `from workflow_tasks.workflow.reporting import (`:

```python
from workflow_tasks.vm import DestroyVm, EnsureVmRunning, VmConfig, VmInfo, VmLifecycle
from workflow_tasks.loadtest import (
    CapturePrometheusSnapshot,
    FetchVmResults,
    InstallK6,
    K6Config,
    K6RunResult,
    K6Stage,
    PrometheusClient,
    PrometheusQuery,
    RemoteFileFetcher,
    RunK6,
    TimeWindow,
    WriteK6Report,
)
```

- [ ] **Step 3: Add boundary tests**

Read `tests/test_package_boundaries.py`, then append:

```python
def test_vm_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.vm")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)


def test_loadtest_subpackage_does_not_import_controlplane_tool() -> None:
    for key in list(sys.modules.keys()):
        if key.startswith("controlplane_tool"):
            del sys.modules[key]
    importlib.import_module("workflow_tasks.loadtest")
    assert not any(k.startswith("controlplane_tool") for k in sys.modules)
```

- [ ] **Step 4: Add public API tests**

Read `tests/test_public_api.py`, then append:

```python
def test_public_api_exports_vm_tasks() -> None:
    assert hasattr(workflow_tasks, "VmConfig")
    assert hasattr(workflow_tasks, "VmInfo")
    assert hasattr(workflow_tasks, "VmLifecycle")
    assert hasattr(workflow_tasks, "EnsureVmRunning")
    assert hasattr(workflow_tasks, "DestroyVm")


def test_public_api_exports_loadtest_tasks() -> None:
    assert hasattr(workflow_tasks, "K6Config")
    assert hasattr(workflow_tasks, "K6Stage")
    assert hasattr(workflow_tasks, "K6RunResult")
    assert hasattr(workflow_tasks, "TimeWindow")
    assert hasattr(workflow_tasks, "PrometheusQuery")
    assert hasattr(workflow_tasks, "RemoteFileFetcher")
    assert hasattr(workflow_tasks, "PrometheusClient")
    assert hasattr(workflow_tasks, "InstallK6")
    assert hasattr(workflow_tasks, "RunK6")
    assert hasattr(workflow_tasks, "FetchVmResults")
    assert hasattr(workflow_tasks, "CapturePrometheusSnapshot")
    assert hasattr(workflow_tasks, "WriteK6Report")
```

- [ ] **Step 5: Run full suite**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest -q --tb=short 2>&1 | tail -8
```

Expected: all tests pass, coverage ≥ 90%

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/workflow-tasks/src/workflow_tasks/loadtest/__init__.py \
    tools/workflow-tasks/src/workflow_tasks/__init__.py \
    tools/workflow-tasks/tests/test_package_boundaries.py \
    tools/workflow-tasks/tests/test_public_api.py && \
git commit -m "$(cat <<'EOF'
feat(workflow-tasks): export vm/ and loadtest/ from public API

All new task types, models, and Protocol ports are accessible
from the top-level workflow_tasks namespace.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `controlplane_tool` VM lifecycle adapters

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py`
- Create: `tools/controlplane/tests/test_vm_lifecycle_adapters.py`

- [ ] **Step 1: Write failing tests**

```python
# tools/controlplane/tests/test_vm_lifecycle_adapters.py
from __future__ import annotations

from unittest.mock import MagicMock

from workflow_tasks.vm.models import VmConfig, VmInfo

from controlplane_tool.infra.vm_lifecycle_adapters import MultipassVmAdapter


def _make_mock_vm(connection_host: str = "10.0.0.1") -> MagicMock:
    vm = MagicMock()
    vm.connection_host.return_value = connection_host
    return vm


def test_multipass_ensure_running_calls_vm_ensure_and_returns_info() -> None:
    vm = _make_mock_vm("192.168.64.5")
    adapter = MultipassVmAdapter(vm)
    config = VmConfig(name="my-vm", cpus=2, memory="4G", disk="20G")

    info = adapter.ensure_running(config)

    vm.ensure_running.assert_called_once()
    assert info.name == "my-vm"
    assert info.host == "192.168.64.5"
    assert info.user == "ubuntu"


def test_multipass_destroy_calls_vm_stop() -> None:
    vm = _make_mock_vm()
    adapter = MultipassVmAdapter(vm)
    info = VmInfo(name="my-vm", host="10.0.0.1", user="ubuntu", home="/home/ubuntu")

    adapter.destroy(info)

    vm.stop.assert_called_once()
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_vm_lifecycle_adapters.py -v 2>&1 | tail -8
```

Expected: `ModuleNotFoundError: No module named 'controlplane_tool.infra.vm_lifecycle_adapters'`

- [ ] **Step 3: Write `vm_lifecycle_adapters.py`**

First read `tools/controlplane/src/controlplane_tool/infra/vm/vm_models.py` to understand `VmRequest` fields, then write:

```python
# tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py
from __future__ import annotations

from typing import TYPE_CHECKING

from workflow_tasks.vm.models import VmConfig, VmInfo

if TYPE_CHECKING:
    from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator


class MultipassVmAdapter:
    """Implements VmLifecycle using VmOrchestrator with multipass lifecycle."""

    def __init__(self, orchestrator: "VmOrchestrator") -> None:
        self._vm = orchestrator

    def ensure_running(self, config: VmConfig) -> VmInfo:
        from controlplane_tool.infra.vm.vm_models import VmRequest

        request = VmRequest(
            lifecycle="multipass",
            name=config.name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
        )
        self._vm.ensure_running(request)
        host = self._vm.connection_host(request)
        return VmInfo(name=config.name, host=host, user="ubuntu", home=f"/home/ubuntu")

    def destroy(self, info: VmInfo) -> None:
        from controlplane_tool.infra.vm.vm_models import VmRequest

        request = VmRequest(lifecycle="multipass", name=info.name)
        self._vm.stop(request)
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_vm_lifecycle_adapters.py -v 2>&1 | tail -8
```

Expected: `2 passed`

- [ ] **Step 5: Run full controlplane suite**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```

Expected: all existing tests still pass

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py \
    tools/controlplane/tests/test_vm_lifecycle_adapters.py && \
git commit -m "$(cat <<'EOF'
feat(controlplane): add MultipassVmAdapter implementing VmLifecycle protocol

Bridges VmOrchestrator to the workflow_tasks VmLifecycle Protocol.
AzureVmAdapter to be added when azure-vm-loadtest is wired.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `controlplane_tool` loadtest adapters

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py`
- Create: `tools/controlplane/tests/test_loadtest_adapters.py`

- [ ] **Step 1: Write failing tests**

```python
# tools/controlplane/tests/test_loadtest_adapters.py
from __future__ import annotations

from datetime import datetime, timezone
from pathlib import Path
from unittest.mock import MagicMock, patch

from workflow_tasks.loadtest.models import TimeWindow

from controlplane_tool.loadtest.loadtest_adapters import HttpPrometheusClient, VmFileFetcher


def _make_window() -> TimeWindow:
    return TimeWindow(
        start=datetime(2026, 1, 1, 10, 0, tzinfo=timezone.utc),
        end=datetime(2026, 1, 1, 10, 30, tzinfo=timezone.utc),
    )


def test_vm_file_fetcher_calls_transfer_from(tmp_path: Path) -> None:
    vm = MagicMock()
    vm.transfer_from.return_value = MagicMock(return_code=0)
    request = MagicMock()
    fetcher = VmFileFetcher(vm=vm, request=request)

    fetcher.fetch_from("/remote/results", tmp_path / "local")

    vm.transfer_from.assert_called_once_with(
        request, source="/remote/results", destination=tmp_path / "local"
    )


def test_vm_file_fetcher_raises_on_nonzero() -> None:
    vm = MagicMock()
    vm.transfer_from.return_value = MagicMock(return_code=1, stderr="permission denied", stdout="")
    fetcher = VmFileFetcher(vm=vm, request=MagicMock())

    import pytest
    with pytest.raises(RuntimeError, match="permission denied"):
        fetcher.fetch_from("/remote/results", Path("/local"))


def test_http_prometheus_client_delegates_to_query_fn() -> None:
    window = _make_window()
    fake_points = [{"timestamp": "t", "value": 1.0}]

    with patch(
        "controlplane_tool.loadtest.loadtest_adapters.query_prometheus_range_series",
        return_value=fake_points,
    ) as mock_fn:
        client = HttpPrometheusClient(url="http://prometheus:9090")
        result = client.query_range("http_requests_total", window)

    assert result == fake_points
    mock_fn.assert_called_once_with(
        "http://prometheus:9090",
        "http_requests_total",
        window.start,
        window.end,
        5,
    )
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_loadtest_adapters.py -v 2>&1 | tail -8
```

Expected: `ModuleNotFoundError: No module named 'controlplane_tool.loadtest.loadtest_adapters'`

- [ ] **Step 3: Write `loadtest_adapters.py`**

```python
# tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py
from __future__ import annotations

from pathlib import Path
from typing import TYPE_CHECKING

from workflow_tasks.loadtest.models import TimeWindow

from controlplane_tool.loadtest.metrics import query_prometheus_range_series

if TYPE_CHECKING:
    from controlplane_tool.infra.vm.vm_adapter import VmOrchestrator
    from controlplane_tool.infra.vm.vm_models import VmRequest


class VmFileFetcher:
    """Implements RemoteFileFetcher using VmOrchestrator.transfer_from()."""

    def __init__(self, vm: "VmOrchestrator", request: "VmRequest") -> None:
        self._vm = vm
        self._request = request

    def fetch_from(self, remote: str, local: Path) -> None:
        result = self._vm.transfer_from(self._request, source=remote, destination=local)
        return_code = getattr(result, "return_code", 0)
        if return_code != 0:
            stderr = getattr(result, "stderr", "") or ""
            stdout = getattr(result, "stdout", "") or ""
            raise RuntimeError(stderr or stdout or f"transfer failed (exit {return_code})")


class HttpPrometheusClient:
    """Implements PrometheusClient using the Prometheus HTTP API."""

    def __init__(self, url: str) -> None:
        self._url = url

    def query_range(
        self,
        expr: str,
        window: TimeWindow,
        step_seconds: int = 5,
    ) -> list[dict[str, float | str]]:
        return query_prometheus_range_series(
            self._url, expr, window.start, window.end, step_seconds
        )
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_loadtest_adapters.py -v 2>&1 | tail -8
```

Expected: `3 passed`

- [ ] **Step 5: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/loadtest/loadtest_adapters.py \
    tools/controlplane/tests/test_loadtest_adapters.py && \
git commit -m "$(cat <<'EOF'
feat(controlplane): add VmFileFetcher and HttpPrometheusClient adapters

VmFileFetcher implements RemoteFileFetcher via VmOrchestrator.transfer_from().
HttpPrometheusClient implements PrometheusClient via query_prometheus_range_series().

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: Wire `TwoVmLoadtestPlan` to use Workflow + new tasks

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py`
- Create: `tools/controlplane/tests/test_two_vm_loadtest_plan.py`

Context: `TwoVmLoadtestPlan.run()` currently delegates to `runner._execute_steps()`.
The new implementation builds a `Workflow` with the library task classes and calls `workflow.run()`.
When called inside `bind_workflow_sink` (TUI context), events are emitted to the dashboard.
When called without a sink (E2E runner), events are silently dropped — that is acceptable.

`EnsureVmRunning` tasks are run via `workflow_step()` before the main `Workflow` so their
output (`VmInfo`) can configure subsequent tasks (like `RunK6`).

Before writing: read `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` and `tools/controlplane/src/controlplane_tool/infra/vm/vm_adapter.py` to understand `VmOrchestrator` methods.

- [ ] **Step 1: Write failing tests**

```python
# tools/controlplane/tests/test_two_vm_loadtest_plan.py
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from unittest.mock import MagicMock, patch

import pytest

from workflow_tasks.vm.models import VmConfig, VmInfo


@dataclass
class _FakeLifecycle:
    vm_info: VmInfo
    destroyed: list[str] = field(default_factory=list)

    def ensure_running(self, config: VmConfig) -> VmInfo:
        return self.vm_info

    def destroy(self, info: VmInfo) -> None:
        self.destroyed.append(info.name)


def _make_vm_info(name: str) -> VmInfo:
    return VmInfo(name=name, host="10.0.0.1", user="ubuntu", home="/home/ubuntu")


def test_two_vm_loadtest_plan_has_expected_task_ids() -> None:
    """task_ids must include all steps for TUI dry-run planning."""
    from controlplane_tool.e2e.e2e_models import E2eRequest
    from controlplane_tool.scenario.scenarios.two_vm_loadtest import TwoVmLoadtestPlan
    from controlplane_tool.scenario.catalog import resolve_scenario
    from controlplane_tool.infra.vm.vm_models import VmRequest

    runner = MagicMock()
    runner.paths.workspace_root = Path("/repo")
    request = MagicMock(spec=E2eRequest)
    request.vm = VmRequest(lifecycle="multipass", name="nanofaas-e2e")
    request.loadgen_vm = VmRequest(lifecycle="multipass", name="nanofaas-e2e-loadgen")
    request.k6_payload = None
    request.k6_script = None

    scenario = resolve_scenario("two-vm-loadtest")
    steps = []  # no steps needed for task_ids test
    plan = TwoVmLoadtestPlan(scenario=scenario, request=request, steps=steps, runner=runner)

    ids = plan.task_ids
    assert "vm.stack.ensure_running" in ids
    assert "vm.loadgen.ensure_running" in ids
    assert "loadgen.install_k6" in ids
    assert "loadgen.run_k6" in ids
    assert "loadgen.fetch_results" in ids
    assert "metrics.prometheus_snapshot" in ids
    assert "loadtest.write_report" in ids
```

- [ ] **Step 2: Run test to verify it fails**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_two_vm_loadtest_plan.py::test_two_vm_loadtest_plan_has_expected_task_ids -v 2>&1 | tail -10
```

Expected: FAIL — `task_ids` currently uses recipe steps, not the new task IDs.

- [ ] **Step 3: Rewrite `TwoVmLoadtestPlan`**

Read `tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py` fully, then replace with:

```python
# tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import TYPE_CHECKING

from workflow_tasks import (
    CapturePrometheusSnapshot,
    DestroyVm,
    EnsureVmRunning,
    FetchVmResults,
    InstallK6,
    RunK6,
    TimeWindow,
    Workflow,
    WriteK6Report,
    workflow_step,
)
from workflow_tasks.loadtest.models import K6Config, K6Stage, PrometheusQuery

from controlplane_tool.e2e.e2e_models import E2eRequest
from controlplane_tool.infra.vm.vm_lifecycle_adapters import MultipassVmAdapter
from controlplane_tool.loadtest.loadtest_adapters import HttpPrometheusClient, VmFileFetcher
from controlplane_tool.scenario.catalog import ScenarioDefinition
from controlplane_tool.scenario.components.executor import ScenarioPlanStep
from controlplane_tool.scenario.two_vm_loadtest_config import (
    two_vm_control_plane_url,
    two_vm_load_stages,
    two_vm_prometheus_url,
    two_vm_remote_paths,
    two_vm_target_function,
)

if TYPE_CHECKING:
    from controlplane_tool.e2e.e2e_runner import E2eRunner


_PROMETHEUS_QUERIES: tuple[PrometheusQuery, ...] = (
    PrometheusQuery("function_dispatch_total", "function_dispatch_total", required=True),
    PrometheusQuery("function_success_total", "function_success_total", required=True),
    PrometheusQuery("function_error_total", "function_error_total"),
    PrometheusQuery("function_latency_ms", "function_latency_ms"),
    PrometheusQuery("function_e2e_latency_ms", "function_e2e_latency_ms"),
    PrometheusQuery("process_cpu_usage", "process_cpu_usage"),
    PrometheusQuery("jvm_memory_used_bytes", "jvm_memory_used_bytes"),
)

_STATIC_TASK_IDS: tuple[str, ...] = (
    "vm.stack.ensure_running",
    "vm.loadgen.ensure_running",
    "loadgen.install_k6",
    "loadgen.run_k6",
    "loadgen.fetch_results",
    "metrics.prometheus_snapshot",
    "loadtest.write_report",
    "vm.loadgen.destroy",
)


@dataclass
class TwoVmLoadtestPlan:
    scenario: ScenarioDefinition
    request: E2eRequest
    steps: list[ScenarioPlanStep]
    runner: "E2eRunner" = field(repr=False, compare=False)

    @property
    def task_ids(self) -> list[str]:
        return list(_STATIC_TASK_IDS)

    def run(self, event_listener=None) -> None:
        from controlplane_tool.e2e.two_vm_loadtest_runner import TwoVmLoadtestRunner
        from controlplane_tool.infra.vm.vm_models import VmRequest
        from workflow_tasks.vm.models import VmConfig

        request = self.request
        vm_runner_impl = TwoVmLoadtestRunner(repo_root=self.runner.paths.workspace_root)
        lifecycle = MultipassVmAdapter(vm_runner_impl.vm)

        stack_config = VmConfig(
            name=request.vm.name,
            cpus=getattr(request.vm, "cpus", 4),
            memory=getattr(request.vm, "memory", "8G"),
            disk=getattr(request.vm, "disk", "40G"),
        )
        loadgen_config = VmConfig(
            name=request.loadgen_vm.name,
            cpus=getattr(request.loadgen_vm, "cpus", 2),
            memory=getattr(request.loadgen_vm, "memory", "2G"),
            disk=getattr(request.loadgen_vm, "disk", "10G"),
        )

        ensure_stack = EnsureVmRunning(
            task_id="vm.stack.ensure_running",
            title="Ensure stack VM running",
            lifecycle=lifecycle,
            config=stack_config,
        )
        ensure_loadgen = EnsureVmRunning(
            task_id="vm.loadgen.ensure_running",
            title="Ensure loadgen VM running",
            lifecycle=lifecycle,
            config=loadgen_config,
        )

        with workflow_step(task_id=ensure_stack.task_id, title=ensure_stack.title):
            stack_info = ensure_stack.run()
        with workflow_step(task_id=ensure_loadgen.task_id, title=ensure_loadgen.title):
            loadgen_info = ensure_loadgen.run()

        remote_home = loadgen_info.home
        remote_paths = two_vm_remote_paths(
            remote_home,
            payload_name=request.k6_payload.name if request.k6_payload is not None else None,
        )
        run_dir = vm_runner_impl._create_run_dir()

        k6_config = K6Config(
            script_path=Path(remote_paths.script_path),
            target_url=two_vm_control_plane_url(request.vm, host=stack_info.host),
            summary_output_path=Path(remote_paths.summary_path),
            stages=tuple(
                K6Stage(duration=d, target=t)
                for d, t in two_vm_load_stages(request)
            ),
            env={
                "NANOFAAS_URL": two_vm_control_plane_url(request.vm, host=stack_info.host),
                "NANOFAAS_FUNCTION": two_vm_target_function(request),
                **({"NANOFAAS_PAYLOAD": str(remote_paths.payload_path)} if remote_paths.payload_path else {}),
            },
            vus=request.k6_vus,
            duration=request.k6_duration,
            payload_path=Path(remote_paths.payload_path) if remote_paths.payload_path else None,
        )

        class _LoadgenVmRunner:
            def run_vm_command(self_, argv, *, env, remote_dir, dry_run):  # noqa: N805
                return vm_runner_impl.vm.exec_argv(request.loadgen_vm, argv, cwd=remote_dir or remote_home)

        loadgen_runner = _LoadgenVmRunner()
        fetcher = VmFileFetcher(vm=vm_runner_impl.vm, request=request.loadgen_vm)
        prom_client = HttpPrometheusClient(
            url=two_vm_prometheus_url(request.vm, host=stack_info.host)
        )

        k6_task = RunK6(
            task_id="loadgen.run_k6",
            title="Run k6 loadtest",
            runner=loadgen_runner,
            config=k6_config,
            remote_dir=remote_home,
        )

        workflow = Workflow(
            tasks=[
                InstallK6(
                    task_id="loadgen.install_k6",
                    title="Install k6 on loadgen VM",
                    runner=loadgen_runner,
                    remote_dir=remote_home,
                ),
                k6_task,
                FetchVmResults(
                    task_id="loadgen.fetch_results",
                    title="Fetch k6 results from loadgen VM",
                    fetcher=fetcher,
                    remote_source=remote_paths.summary_path,
                    local_dest=run_dir,
                ),
                CapturePrometheusSnapshot(
                    task_id="metrics.prometheus_snapshot",
                    title="Capture Prometheus snapshots",
                    client=prom_client,
                    queries=_PROMETHEUS_QUERIES,
                    window=lambda: TimeWindow(
                        start=k6_task.result.started_at,
                        end=k6_task.result.ended_at,
                    ),
                    output_dir=run_dir,
                ),
                WriteK6Report(
                    task_id="loadtest.write_report",
                    title="Write loadtest report",
                    data_dir=run_dir,
                    output_dir=run_dir,
                ),
            ],
            cleanup_tasks=[
                DestroyVm(
                    task_id="vm.loadgen.destroy",
                    title="Destroy loadgen VM",
                    lifecycle=lifecycle,
                    info=loadgen_info,
                ),
            ],
        )
        workflow.run()


def build_two_vm_loadtest_plan(
    runner: "E2eRunner",
    request: E2eRequest,
) -> TwoVmLoadtestPlan:
    from controlplane_tool.scenario.catalog import resolve_scenario

    scenario = resolve_scenario("two-vm-loadtest")
    return TwoVmLoadtestPlan(scenario=scenario, request=request, steps=[], runner=runner)
```

- [ ] **Step 4: Run test to verify it passes**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_two_vm_loadtest_plan.py -v 2>&1 | tail -8
```

Expected: `1 passed`

- [ ] **Step 5: Run full suite**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```

Expected: all existing tests pass

- [ ] **Step 6: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/two_vm_loadtest.py \
    tools/controlplane/tests/test_two_vm_loadtest_plan.py && \
git commit -m "$(cat <<'EOF'
feat(controlplane): wire TwoVmLoadtestPlan to use Workflow + library tasks

Replaces recipe-based _execute_steps() delegation with a Workflow
composed of EnsureVmRunning, InstallK6, RunK6, FetchVmResults,
CapturePrometheusSnapshot, WriteK6Report, DestroyVm.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Wire `AzureVmLoadtestPlan` + add `AzureVmAdapter`

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py`
- Modify: `tools/controlplane/tests/test_vm_lifecycle_adapters.py`

Before writing: read `tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py` to understand `AzureVmOrchestrator` API.

- [ ] **Step 1: Read `azure_vm_adapter.py`**

```bash
cat /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/infra/vm/azure_vm_adapter.py | head -60
```

Understand: which method ensures VM is running, which returns the host IP, which stops/deletes.

- [ ] **Step 2: Add `AzureVmAdapter` test**

Read `tests/test_vm_lifecycle_adapters.py`, then append:

```python
from controlplane_tool.infra.vm_lifecycle_adapters import AzureVmAdapter


def test_azure_ensure_running_returns_vm_info() -> None:
    azure_vm = MagicMock()
    azure_vm.connection_host.return_value = "4.5.6.7"
    adapter = AzureVmAdapter(azure_vm)
    config = VmConfig(name="azure-loadgen", cpus=2, memory="8G", disk="30G")

    info = adapter.ensure_running(config)

    azure_vm.ensure_running.assert_called_once()
    assert info.host == "4.5.6.7"
    assert info.name == "azure-loadgen"


def test_azure_destroy_calls_vm_stop() -> None:
    azure_vm = MagicMock()
    adapter = AzureVmAdapter(azure_vm)
    info = VmInfo(name="azure-loadgen", host="4.5.6.7", user="ubuntu", home="/home/ubuntu")

    adapter.destroy(info)

    azure_vm.stop.assert_called_once()
```

- [ ] **Step 3: Run test to verify it fails**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_vm_lifecycle_adapters.py::test_azure_ensure_running_returns_vm_info -v 2>&1 | tail -8
```

Expected: `ImportError: cannot import name 'AzureVmAdapter'`

- [ ] **Step 4: Add `AzureVmAdapter` to `vm_lifecycle_adapters.py`**

Read `tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py`, then append:

```python
class AzureVmAdapter:
    """Implements VmLifecycle using AzureVmOrchestrator."""

    def __init__(self, orchestrator: "AzureVmOrchestrator") -> None:
        self._vm = orchestrator

    def ensure_running(self, config: VmConfig) -> VmInfo:
        from controlplane_tool.infra.vm.vm_models import VmRequest

        request = VmRequest(
            lifecycle="azure",
            name=config.name,
            cpus=config.cpus,
            memory=config.memory,
            disk=config.disk,
        )
        self._vm.ensure_running(request)
        host = self._vm.connection_host(request)
        return VmInfo(name=config.name, host=host, user="ubuntu", home="/home/ubuntu")

    def destroy(self, info: VmInfo) -> None:
        from controlplane_tool.infra.vm.vm_models import VmRequest

        request = VmRequest(lifecycle="azure", name=info.name)
        self._vm.stop(request)
```

Also add `AzureVmOrchestrator` to the TYPE_CHECKING imports block at the top of the file.

- [ ] **Step 5: Wire `AzureVmLoadtestPlan.run()` using new tasks**

First read these files to understand what azure-specific config functions exist:
```bash
cat /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py
find /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane/src -name "azure*config*" -o -name "*azure*loadtest*config*" | grep -v __pycache__
```

Rewrite `AzureVmLoadtestPlan` using the identical structure to `TwoVmLoadtestPlan` from Task 8. Copy Task 8's full `run()` implementation and change:
- `MultipassVmAdapter(vm_runner_impl.vm)` → `AzureVmAdapter(azure_orchestrator)` where `azure_orchestrator` is built from the request (check how the old `build_azure_vm_loadtest_plan` built it)
- Use azure-specific config functions if they exist (e.g., `azure_vm_control_plane_url`); if they don't exist, use the same `two_vm_*` functions — the two scenarios share the same k6/prometheus config logic
- `_STATIC_TASK_IDS` and `_PROMETHEUS_QUERIES` are identical to TwoVmLoadtestPlan — define them once and import, or duplicate

Replace `build_azure_vm_loadtest_plan()` to return `AzureVmLoadtestPlan(scenario=..., request=request, steps=[], runner=runner)` (same as two-vm).

- [ ] **Step 6: Run tests to verify everything passes**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest tests/test_vm_lifecycle_adapters.py tests/test_two_vm_loadtest_plan.py -v 2>&1 | tail -10
```

Expected: all tests pass

- [ ] **Step 7: Run full suite**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q --tb=short 2>&1 | tail -6
```

Expected: all tests pass

- [ ] **Step 8: Commit**

```bash
cd /Users/micheleciavotta/Downloads/mcFaas && git add \
    tools/controlplane/src/controlplane_tool/infra/vm_lifecycle_adapters.py \
    tools/controlplane/src/controlplane_tool/scenario/scenarios/azure_vm_loadtest.py \
    tools/controlplane/tests/test_vm_lifecycle_adapters.py && \
git commit -m "$(cat <<'EOF'
feat(controlplane): wire AzureVmLoadtestPlan + add AzureVmAdapter

AzureVmAdapter implements VmLifecycle for azure-vm-loadtest.
AzureVmLoadtestPlan.run() uses the same Workflow composition as
TwoVmLoadtestPlan.

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Final Verification

```bash
# workflow-tasks library: all tests pass with ≥90% coverage
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest -q 2>&1 | tail -4

# controlplane: full suite still green
cd /Users/micheleciavotta/Downloads/mcFaas/tools/controlplane && uv run pytest -q 2>&1 | tail -4

# boundary check: no controlplane_tool imports in workflow_tasks
cd /Users/micheleciavotta/Downloads/mcFaas/tools/workflow-tasks && uv run pytest tests/test_package_boundaries.py -v 2>&1 | tail -8
```
