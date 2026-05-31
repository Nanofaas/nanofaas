# Sub-3 — Prefect flow-runtime to library (Implementation Plan)

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move the generic flow-execution runtime (`run_local_flow`, `FlowRunResult`, `LocalFlowDefinition`) from `controlplane_tool/orchestation/` into a new `workflow_tasks/orchestration/` library package; controlplane keeps only flow *assembly* and imports the runtime from the library.

**Architecture:** Verbatim relocation behind a clean package boundary. The runtime is prefect-OPTIONAL (`from prefect import ...` in a try/except → plain-call fallback), so it works in the library env which has no prefect. Two tasks: (1) create the library package + move its test; (2) repoint all controlplane importers and delete the two old source files. No shim (consistent with sub-4).

**Tech Stack:** Python 3.11+, workflow_tasks, pytest, uv.

**Commands:** library `uv run --project tools/workflow-tasks pytest <path>`; controlplane `uv run --project tools/controlplane pytest <path>` (NO `--no-cov` for controlplane).

**Baseline:** both suites currently green (controlplane 1107, workflow-tasks 352 @ 90.88%). Branch: `refactor/wt-sub3-prefect-runtime` (already created, stacked on sub-4). Spec: `docs/superpowers/specs/2026-05-31-prefect-flow-runtime-to-library-design.md`.

**Coverage note:** the prefect-only branch of `run_local_flow` (the `@prefect_flow` wrapper + `_quiet_prefect_runtime` + `_prefect_backend_name`) is unreachable without prefect installed AND `PREFECT_API_URL` set — a path that was already untested even in controlplane. In the library it is marked `# pragma: no cover` so the 90 gate holds; the prefect-importable-but-no-API-URL fallback (`orchestrator_backend="prefect-local"`) is NOT pragma'd and stays coverable.

---

### Task 1: Create `workflow_tasks/orchestration/` package (move runtime + models + test)

**Files:**
- Create: `tools/workflow-tasks/src/workflow_tasks/orchestration/__init__.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/orchestration/models.py`
- Create: `tools/workflow-tasks/src/workflow_tasks/orchestration/runtime.py`
- Create: `tools/workflow-tasks/tests/orchestration/__init__.py`
- Create: `tools/workflow-tasks/tests/orchestration/test_runtime.py`

- [ ] **Step 1: Create `models.py`** (verbatim from `controlplane_tool/orchestation/prefect_models.py`)

```python
from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass
from datetime import datetime
from typing import Generic, TypeVar

T = TypeVar("T")


@dataclass(slots=True)
class FlowRunResult(Generic[T]):
    flow_id: str
    flow_run_id: str
    orchestrator_backend: str
    started_at: datetime
    finished_at: datetime
    status: str
    result: T | None = None
    error: str | None = None

    @classmethod
    def completed(
        cls,
        *,
        flow_id: str,
        flow_run_id: str,
        orchestrator_backend: str,
        started_at: datetime,
        finished_at: datetime,
        result: T | None = None,
    ) -> "FlowRunResult[T]":
        return cls(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            orchestrator_backend=orchestrator_backend,
            started_at=started_at,
            finished_at=finished_at,
            status="completed",
            result=result,
        )

    @classmethod
    def failed(
        cls,
        *,
        flow_id: str,
        flow_run_id: str,
        orchestrator_backend: str,
        started_at: datetime,
        finished_at: datetime,
        error: str,
    ) -> "FlowRunResult[T]":
        return cls(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            orchestrator_backend=orchestrator_backend,
            started_at=started_at,
            finished_at=finished_at,
            status="failed",
            error=error,
        )


@dataclass(slots=True)
class LocalFlowDefinition(Generic[T]):
    flow_id: str
    task_ids: list[str]
    run: Callable[[], T]
```

- [ ] **Step 2: Create `runtime.py`** (verbatim from `controlplane_tool/orchestation/prefect_runtime.py`, with the internal import repointed to `.models` and `# pragma: no cover` on the prefect-only branch)

```python
from __future__ import annotations

from contextlib import contextmanager
from collections.abc import Callable
from datetime import UTC, datetime
import os
from typing import Generator
from typing import TypeVar
from uuid import uuid4

from workflow_tasks.orchestration.models import FlowRunResult

T = TypeVar("T")


def _now_utc() -> datetime:
    return datetime.now(UTC)


def _generated_flow_run_id() -> str:
    return str(uuid4())


def _prefect_backend_name() -> str:  # pragma: no cover - prefect-only path
    return "prefect-local" if not os.getenv("PREFECT_API_URL") else "prefect-api"


@contextmanager
def _quiet_prefect_runtime() -> Generator[None, None, None]:  # pragma: no cover - prefect-only path
    from prefect.events.clients import NullEventsClient
    from prefect.events.worker import EventsWorker
    from prefect.logging.configuration import setup_logging
    from prefect.settings import (
        PREFECT_LOGGING_LEVEL,
        PREFECT_LOGGING_LOG_PRINTS,
        PREFECT_LOGGING_TO_API_ENABLED,
        PREFECT_SERVER_ALLOW_EPHEMERAL_MODE,
        temporary_settings,
    )

    previous_override = getattr(EventsWorker, "_client_override", None)
    try:
        with temporary_settings(
            {
                PREFECT_LOGGING_LEVEL: "CRITICAL",
                PREFECT_LOGGING_TO_API_ENABLED: False,
                PREFECT_LOGGING_LOG_PRINTS: False,
                PREFECT_SERVER_ALLOW_EPHEMERAL_MODE: False,
            }
        ):
            setup_logging(incremental=False)
            EventsWorker.set_client_override(NullEventsClient)
            yield
    finally:
        if previous_override is None:
            EventsWorker.set_client_override(None)
        else:
            client_type, client_kwargs = previous_override
            EventsWorker.set_client_override(client_type, **dict(client_kwargs))
        setup_logging(incremental=False)


def _run_without_prefect(
    flow_id: str,
    flow_fn: Callable[..., T],
    *args: object,
    orchestrator_backend: str = "none",
    **kwargs: object,
) -> FlowRunResult[T]:
    started_at = _now_utc()
    flow_run_id = _generated_flow_run_id()
    try:
        result = flow_fn(*args, **kwargs)
    except Exception as exc:
        return FlowRunResult.failed(
            flow_id=flow_id,
            flow_run_id=flow_run_id,
            orchestrator_backend=orchestrator_backend,
            started_at=started_at,
            finished_at=_now_utc(),
            error=str(exc),
        )
    return FlowRunResult.completed(
        flow_id=flow_id,
        flow_run_id=flow_run_id,
        orchestrator_backend=orchestrator_backend,
        started_at=started_at,
        finished_at=_now_utc(),
        result=result,
    )


def run_local_flow(
    flow_id: str,
    flow_fn: Callable[..., T],
    *args: object,
    **kwargs: object,
) -> FlowRunResult[T]:
    try:
        from prefect import flow as prefect_flow
        from prefect.runtime import flow_run as prefect_flow_run
    except ImportError:
        return _run_without_prefect(flow_id, flow_fn, *args, orchestrator_backend="none", **kwargs)

    if not os.getenv("PREFECT_API_URL"):  # pragma: no cover - requires prefect installed
        return _run_without_prefect(
            flow_id,
            flow_fn,
            *args,
            orchestrator_backend="prefect-local",
            **kwargs,
        )

    started_at = _now_utc()  # pragma: no cover - prefect-only path below
    captured_flow_run_id = _generated_flow_run_id()

    @prefect_flow(name=flow_id, log_prints=False)
    def _prefect_wrapper() -> T:
        nonlocal captured_flow_run_id
        runtime_flow_run_id = getattr(prefect_flow_run, "id", None)
        if runtime_flow_run_id:
            captured_flow_run_id = str(runtime_flow_run_id)
        return flow_fn(*args, **kwargs)

    try:
        with _quiet_prefect_runtime():
            result = _prefect_wrapper()
    except Exception as exc:
        return FlowRunResult.failed(
            flow_id=flow_id,
            flow_run_id=captured_flow_run_id,
            orchestrator_backend=_prefect_backend_name(),
            started_at=started_at,
            finished_at=_now_utc(),
            error=str(exc),
        )

    return FlowRunResult.completed(
        flow_id=flow_id,
        flow_run_id=captured_flow_run_id,
        orchestrator_backend=_prefect_backend_name(),
        started_at=started_at,
        finished_at=_now_utc(),
        result=result,
    )
```

NOTE: the `# pragma: no cover` on the `if not os.getenv(...)` line and the lines below covers the branch that is only reachable when `prefect` is importable (the library env has no prefect, so the `except ImportError` path is what runs and stays covered). Keep the `except ImportError` branch WITHOUT a pragma.

- [ ] **Step 3: Create `__init__.py`** (re-export the public API)

```python
from __future__ import annotations

from workflow_tasks.orchestration.models import FlowRunResult, LocalFlowDefinition
from workflow_tasks.orchestration.runtime import run_local_flow

__all__ = ["FlowRunResult", "LocalFlowDefinition", "run_local_flow"]
```

- [ ] **Step 4: Create the test package + move the runtime test**

Create `tools/workflow-tasks/tests/orchestration/__init__.py` (empty file).

Create `tools/workflow-tasks/tests/orchestration/test_runtime.py` (moved from `controlplane_tool` test, repointed import + a model test to keep `FlowRunResult`/`LocalFlowDefinition` covered):

```python
from __future__ import annotations

from datetime import datetime

from workflow_tasks.orchestration import (
    FlowRunResult,
    LocalFlowDefinition,
    run_local_flow,
)


def test_run_local_flow_returns_normalized_run_metadata() -> None:
    def sample_flow() -> str:
        return "ok"

    result = run_local_flow("sample.flow", sample_flow)

    assert result.result == "ok"
    assert result.status == "completed"
    assert result.flow_id == "sample.flow"
    assert result.flow_run_id
    assert result.orchestrator_backend in {"none", "prefect-local"}
    assert isinstance(result.started_at, datetime)
    assert isinstance(result.finished_at, datetime)
    assert result.started_at <= result.finished_at


def test_run_local_flow_failure_suppresses_prefect_console_noise(capsys) -> None:
    def broken_flow() -> str:
        raise RuntimeError("boom")

    result = run_local_flow("sample.broken", broken_flow)

    captured = capsys.readouterr()
    assert result.status == "failed"
    assert result.error == "boom"
    assert "Beginning flow run" not in captured.out
    assert "Beginning flow run" not in captured.err
    assert "EventsWorker" not in captured.out
    assert "EventsWorker" not in captured.err


def test_flow_run_result_constructors() -> None:
    now = datetime.now()
    ok = FlowRunResult.completed(
        flow_id="f", flow_run_id="r", orchestrator_backend="none",
        started_at=now, finished_at=now, result=42,
    )
    assert ok.status == "completed"
    assert ok.result == 42
    bad = FlowRunResult.failed(
        flow_id="f", flow_run_id="r", orchestrator_backend="none",
        started_at=now, finished_at=now, error="nope",
    )
    assert bad.status == "failed"
    assert bad.error == "nope"


def test_local_flow_definition_holds_callable() -> None:
    definition = LocalFlowDefinition(flow_id="f", task_ids=["a", "b"], run=lambda: "done")
    assert definition.flow_id == "f"
    assert definition.task_ids == ["a", "b"]
    assert definition.run() == "done"
```

- [ ] **Step 5: Run the library suite (incl. coverage gate)**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks -q`
Expected: all pass; `Required test coverage of 90% reached` (total ≥ 90%). If coverage dips below 90, verify the prefect-only branch carries `# pragma: no cover` as written.

- [ ] **Step 6: Run public-API + boundary guards**

Run: `uv run --project tools/workflow-tasks pytest tools/workflow-tasks/tests/test_public_api.py tools/workflow-tasks/tests/test_package_boundaries.py -q`
Expected: pass (the new package imports nothing from controlplane).

- [ ] **Step 7: Commit**

```bash
git add tools/workflow-tasks/src/workflow_tasks/orchestration tools/workflow-tasks/tests/orchestration
git commit -m "feat(workflow-tasks): add orchestration flow-runtime (run_local_flow + models)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Repoint controlplane importers and delete the old runtime files

**Files (repoint imports — replace `controlplane_tool.orchestation.prefect_runtime` AND `controlplane_tool.orchestation.prefect_models` with `workflow_tasks.orchestration`):**
- Modify (src): `app/main.py:13`, `cli/commands.py:9`, `cli/e2e_commands.py:15`, `cli/loadtest_commands.py:288`, `cli/vm_commands.py:13`, `loadtest/loadtest_flows.py:17`, `loadtest/loadtest_runner.py:10`, `orchestation/flow_catalog.py:15`, `orchestation/infra_flows.py:31`, `orchestation/pipeline.py:8`, `orchestation/prefect_deployments.py:8`, `scenario/scenario_flows.py:8`, `tui/workflow_controller.py:19`
- Modify (tests): `test_cli_commands.py:8`, `test_e2e_commands.py:6`, `test_pipeline.py:6`, `test_tui_choices.py:19`, `test_vm_commands.py:5`
- Delete: `tools/controlplane/src/controlplane_tool/orchestation/prefect_runtime.py`
- Delete: `tools/controlplane/src/controlplane_tool/orchestation/prefect_models.py`
- Delete: `tools/controlplane/tests/test_prefect_runtime.py` (moved to the library in Task 1)

- [ ] **Step 1: Repoint all importers (both module paths → `workflow_tasks.orchestration`)**

Run this from the repo root:

```bash
grep -rl "controlplane_tool.orchestation.prefect_runtime\|controlplane_tool.orchestation.prefect_models" \
  tools/controlplane/src/controlplane_tool tools/controlplane/tests --include="*.py" \
| while read -r f; do
  perl -i -pe 's/\bcontrolplane_tool\.orchestation\.prefect_runtime\b/workflow_tasks.orchestration/g; s/\bcontrolplane_tool\.orchestation\.prefect_models\b/workflow_tasks.orchestration/g' "$f"
done
```

Both old modules map to the same `workflow_tasks.orchestration` package, which re-exports all three names, so every `from ... import run_local_flow` / `import FlowRunResult` / `import LocalFlowDefinition` / `import FlowRunResult, LocalFlowDefinition` line resolves correctly.

- [ ] **Step 2: Delete the moved source + test files**

```bash
rm tools/controlplane/src/controlplane_tool/orchestation/prefect_runtime.py \
   tools/controlplane/src/controlplane_tool/orchestation/prefect_models.py \
   tools/controlplane/tests/test_prefect_runtime.py
```

- [ ] **Step 3: Verify no dangling references**

Run: `grep -rn "orchestation.prefect_runtime\|orchestation.prefect_models" tools/controlplane/src tools/controlplane/tests --include="*.py" | grep -v egg-info`
Expected: EMPTY.

- [ ] **Step 4: Run the controlplane suite**

Run: `uv run --project tools/controlplane pytest tools/controlplane/tests -q`
Expected: 1106 passed (1107 baseline − 1 for the moved `test_prefect_runtime.py`), 0 failed.

- [ ] **Step 5: Import-linter + ruff**

Run: `uv run --project tools/controlplane lint-imports --config tools/controlplane/.importlinter`
Expected: `Contracts: 5 kept, 0 broken.`
Run: `uv run --project tools/controlplane ruff check tools/controlplane/src/controlplane_tool`
Expected: `All checks passed!`

- [ ] **Step 6: Commit**

```bash
git add -A tools/controlplane
git commit -m "refactor(controlplane): import flow-runtime from workflow_tasks.orchestration; delete moved files

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

- **Spec coverage:** boundary move (runtime+models→library) = Task 1; controlplane repoint + delete = Task 2; no-shim = Task 2 Step 1-2; tests moved = Task 1 Step 4 + Task 2 Step 2; import-linter/ruff/suite green = Task 1 Step 5-6 + Task 2 Step 4-5. `flow_catalog`/`infra_flows`/`pipeline`/`adapters`/`prefect_deployments` stay (only their import lines change). ✓
- **Placeholders:** none — full code given for both new files, exact grep/perl/rm commands, exact expected counts.
- **Type consistency:** `FlowRunResult`, `LocalFlowDefinition`, `run_local_flow` names identical across both tasks and the `__init__` re-export; internal `runtime.py` imports `FlowRunResult` from `workflow_tasks.orchestration.models`. ✓
