# Package Architecture Checks Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add automated checks and reports that measure and guard internal package cohesion and inter-package coupling for `tools/controlplane/src/controlplane_tool`.

**Architecture:** Use `import-linter` as the hard architectural gate for forbidden imports and package layering rules. Use a small `grimp`-based report command for quantitative coupling/cohesion metrics, with `pydeps` documented as an optional visual exploration tool and GitNexus retained for impact analysis before refactors.

**Tech Stack:** Python 3.12, uv dependency groups, pytest, import-linter, grimp, pydeps, GitNexus MCP.

---

## Current Package Model

The target package tree is:

```text
controlplane_tool.app             entrypoints
controlplane_tool.building        Gradle, image, module build operations
controlplane_tool.cli             Typer command groups
controlplane_tool.cli_validation  CLI validation scenarios and runners
controlplane_tool.core            shared primitives, models, process helpers
controlplane_tool.e2e             E2E runners
controlplane_tool.functions       function catalog and control-plane API helpers
controlplane_tool.infra           VM, runtime, registry, Prometheus, Kubernetes support
controlplane_tool.loadtest        k6, load-test flow, metrics gate, reports
controlplane_tool.orchestation    flow catalog, local flow orchestration, Prefect adapters
controlplane_tool.scenario        scenario models, planner, component library, shared selection resolution
controlplane_tool.sut             SUT preflight helpers
controlplane_tool.tui             interactive TUI
controlplane_tool.workspace       paths, profiles, settings
controlplane_tool.workflow        workflow events, progress, sink models
```

Architectural intent:

```text
core       -> must stay independent from feature packages
tui        -> UI layer only; no runtime/core package should import it
cli        -> command surface; lower-level packages should not import it
app        -> entrypoint surface; internals must not import app
workspace  -> shared path discovery, settings, and saved-profile persistence
workflow   -> shared event/progress primitives; must not import TUI or CLI
```

Do not try to fully freeze every dependency in the first pass. Start with rules that express obvious boundaries and add stricter layer contracts only after the report shows the current graph.

---

### Task 1: Add Architecture Tooling Dependencies

**Files:**
- Modify: `tools/controlplane/pyproject.toml`
- Modify: `tools/controlplane/uv.lock`

**Step 1: Add dev dependencies**

Run from repo root:

```bash
cd tools/controlplane
uv add --dev import-linter grimp pydeps
```

Expected:

```text
Resolved ...
Prepared ...
Installed ...
```

**Step 2: Verify commands are available**

Run:

```bash
uv run lint-imports --help
uv run python -c "import grimp; import pydeps; print('ok')"
```

Expected:

```text
Usage: lint-imports ...
ok
```

**Step 3: Inspect dependency diff**

Run:

```bash
git diff -- pyproject.toml uv.lock
```

Expected: `import-linter`, `grimp`, and `pydeps` are added only to the dev dependency group / lockfile.

**Step 4: Commit**

```bash
git add tools/controlplane/pyproject.toml tools/controlplane/uv.lock
git commit -m "Add package architecture tooling"
```

---

### Task 2: Add Initial Import-Linter Contracts

**Files:**
- Create: `tools/controlplane/.importlinter`
- Test: command-level verification with `uv run lint-imports`

**Step 1: Create the initial contract file**

Create `tools/controlplane/.importlinter`:

```ini
[importlinter]
root_package = controlplane_tool

[importlinter:contract:core_is_independent]
name = core must not import feature packages
type = forbidden
source_modules =
    controlplane_tool.core
forbidden_modules =
    controlplane_tool.app
    controlplane_tool.building
    controlplane_tool.cli
    controlplane_tool.cli_validation
    controlplane_tool.e2e
    controlplane_tool.functions
    controlplane_tool.infra
    controlplane_tool.loadtest
    controlplane_tool.orchestation
    controlplane_tool.scenario
    controlplane_tool.sut
    controlplane_tool.tui
    controlplane_tool.workflow

[importlinter:contract:no_runtime_dependency_on_tui]
name = runtime packages must not depend on tui
type = forbidden
source_modules =
    controlplane_tool.app
    controlplane_tool.building
    controlplane_tool.cli
    controlplane_tool.cli_validation
    controlplane_tool.core
    controlplane_tool.e2e
    controlplane_tool.functions
    controlplane_tool.infra
    controlplane_tool.loadtest
    controlplane_tool.orchestation
    controlplane_tool.scenario
    controlplane_tool.sut
    controlplane_tool.workflow
forbidden_modules =
    controlplane_tool.tui

[importlinter:contract:no_lower_level_dependency_on_cli]
name = lower-level packages must not depend on cli command modules
type = forbidden
source_modules =
    controlplane_tool.building
    controlplane_tool.cli_validation
    controlplane_tool.core
    controlplane_tool.e2e
    controlplane_tool.functions
    controlplane_tool.infra
    controlplane_tool.loadtest
    controlplane_tool.orchestation
    controlplane_tool.scenario
    controlplane_tool.sut
    controlplane_tool.workflow
forbidden_modules =
    controlplane_tool.cli
```

**Step 2: Run the contracts**

Run:

```bash
cd tools/controlplane
uv run lint-imports
```

Expected:

```text
Contracts: 3 kept, 0 broken.
```

If a contract fails, do not weaken it immediately. Inspect the import path and decide whether the dependency is a real boundary violation or whether the first contract is too broad for the current architecture.

**Step 3: Commit**

```bash
git add tools/controlplane/.importlinter
git commit -m "Add initial controlplane import contracts"
```

---

### Task 3: Add Pytest Coverage for Import Contracts

**Files:**
- Create: `tools/controlplane/tests/test_import_contracts.py`

**Step 1: Write the failing test**

Create `tools/controlplane/tests/test_import_contracts.py`:

```python
from __future__ import annotations

from pathlib import Path
import subprocess


def test_controlplane_import_contracts_pass() -> None:
    tool_root = Path(__file__).resolve().parents[1]

    result = subprocess.run(
        ["uv", "run", "lint-imports"],
        cwd=tool_root,
        text=True,
        capture_output=True,
        check=False,
    )

    assert result.returncode == 0, result.stdout + result.stderr
```

**Step 2: Run test**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_import_contracts.py -q
```

Expected:

```text
1 passed
```

**Step 3: Run package layout tests together**

Run:

```bash
uv run pytest tests/test_package_layout.py tests/test_import_contracts.py -q
```

Expected:

```text
3 passed
```

**Step 4: Commit**

```bash
git add tools/controlplane/tests/test_import_contracts.py
git commit -m "Test controlplane import contracts"
```

---

### Task 4: Add Package Coupling Report Command

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/devtools/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/devtools/package_report.py`
- Modify: `tools/controlplane/pyproject.toml`
- Test: `tools/controlplane/tests/test_package_report.py`

**Step 1: Write failing tests**

Create `tools/controlplane/tests/test_package_report.py`:

```python
from __future__ import annotations

from controlplane_tool.devtools.package_report import (
    PackageMetrics,
    calculate_metrics,
    format_metrics_table,
)


def test_calculate_metrics_counts_internal_outgoing_and_incoming_edges() -> None:
    metrics = calculate_metrics(
        packages=[
            "controlplane_tool.core",
            "controlplane_tool.loadtest",
            "controlplane_tool.tui",
        ],
        edges=[
            ("controlplane_tool.core.models", "controlplane_tool.core.net_utils"),
            ("controlplane_tool.loadtest.k6_ops", "controlplane_tool.core.models"),
            ("controlplane_tool.tui.app", "controlplane_tool.loadtest.loadtest_models"),
        ],
    )

    by_package = {metric.package: metric for metric in metrics}

    assert by_package["controlplane_tool.core"] == PackageMetrics(
        package="controlplane_tool.core",
        internal_imports=1,
        outgoing_imports=0,
        incoming_imports=2,
        instability=0.0,
    )
    assert by_package["controlplane_tool.loadtest"] == PackageMetrics(
        package="controlplane_tool.loadtest",
        internal_imports=0,
        outgoing_imports=1,
        incoming_imports=1,
        instability=0.5,
    )
    assert by_package["controlplane_tool.tui"] == PackageMetrics(
        package="controlplane_tool.tui",
        internal_imports=0,
        outgoing_imports=1,
        incoming_imports=0,
        instability=1.0,
    )


def test_format_metrics_table_includes_header_and_package_rows() -> None:
    table = format_metrics_table(
        [
            PackageMetrics(
                package="controlplane_tool.core",
                internal_imports=1,
                outgoing_imports=0,
                incoming_imports=2,
                instability=0.0,
            )
        ]
    )

    assert "package" in table
    assert "internal" in table
    assert "outgoing" in table
    assert "incoming" in table
    assert "instability" in table
    assert "controlplane_tool.core" in table
```

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_package_report.py -q
```

Expected:

```text
ModuleNotFoundError: No module named 'controlplane_tool.devtools'
```

**Step 2: Add the devtools package**

Create `tools/controlplane/src/controlplane_tool/devtools/__init__.py`:

```python
from __future__ import annotations
```

**Step 3: Add the report implementation**

Create `tools/controlplane/src/controlplane_tool/devtools/package_report.py`:

```python
from __future__ import annotations

from dataclasses import dataclass
import argparse
from collections.abc import Iterable, Sequence

import grimp


ROOT_PACKAGE = "controlplane_tool"
TOP_LEVEL_PACKAGES = (
    "controlplane_tool.app",
    "controlplane_tool.building",
    "controlplane_tool.cli",
    "controlplane_tool.cli_validation",
    "controlplane_tool.core",
    "controlplane_tool.e2e",
    "controlplane_tool.functions",
    "controlplane_tool.infra",
    "controlplane_tool.loadtest",
    "controlplane_tool.orchestation",
    "controlplane_tool.scenario",
    "controlplane_tool.sut",
    "controlplane_tool.tui",
    "controlplane_tool.workflow",
)


@dataclass(frozen=True)
class PackageMetrics:
    package: str
    internal_imports: int
    outgoing_imports: int
    incoming_imports: int
    instability: float


def _top_level_package(module: str, packages: Sequence[str]) -> str | None:
    for package in packages:
        if module == package or module.startswith(f"{package}."):
            return package
    return None


def calculate_metrics(
    *,
    packages: Sequence[str],
    edges: Iterable[tuple[str, str]],
) -> list[PackageMetrics]:
    internal_counts = {package: 0 for package in packages}
    outgoing_counts = {package: 0 for package in packages}
    incoming_counts = {package: 0 for package in packages}

    for importer, imported in edges:
        importer_package = _top_level_package(importer, packages)
        imported_package = _top_level_package(imported, packages)
        if importer_package is None or imported_package is None:
            continue
        if importer_package == imported_package:
            internal_counts[importer_package] += 1
            continue
        outgoing_counts[importer_package] += 1
        incoming_counts[imported_package] += 1

    metrics: list[PackageMetrics] = []
    for package in packages:
        outgoing = outgoing_counts[package]
        incoming = incoming_counts[package]
        denominator = incoming + outgoing
        instability = round(outgoing / denominator, 2) if denominator else 0.0
        metrics.append(
            PackageMetrics(
                package=package,
                internal_imports=internal_counts[package],
                outgoing_imports=outgoing,
                incoming_imports=incoming,
                instability=instability,
            )
        )
    return metrics


def format_metrics_table(metrics: Sequence[PackageMetrics]) -> str:
    header = f"{'package':38} {'internal':>8} {'outgoing':>8} {'incoming':>8} {'instability':>11}"
    rows = [header, "-" * len(header)]
    for metric in metrics:
        rows.append(
            f"{metric.package:38} "
            f"{metric.internal_imports:8d} "
            f"{metric.outgoing_imports:8d} "
            f"{metric.incoming_imports:8d} "
            f"{metric.instability:11.2f}"
        )
    return "\n".join(rows)


def _iter_grimp_edges(root_package: str) -> list[tuple[str, str]]:
    graph = grimp.build_graph(root_package, include_external_packages=False)
    modules = sorted(
        module
        for module in graph.modules
        if module == root_package or module.startswith(f"{root_package}.")
    )
    edges: list[tuple[str, str]] = []
    for importer in modules:
        for imported in sorted(graph.find_modules_directly_imported_by(importer)):
            if imported == root_package or imported.startswith(f"{root_package}."):
                edges.append((importer, imported))
    return edges


def build_current_metrics() -> list[PackageMetrics]:
    return calculate_metrics(
        packages=TOP_LEVEL_PACKAGES,
        edges=_iter_grimp_edges(ROOT_PACKAGE),
    )


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Report internal and cross-package imports for controlplane_tool."
    )
    parser.parse_args()
    print(format_metrics_table(build_current_metrics()))


if __name__ == "__main__":
    main()
```

**Step 4: Add command entrypoint**

Modify `tools/controlplane/pyproject.toml`:

```toml
[project.scripts]
controlplane-tool = "controlplane_tool.app.main:main"
controlplane-package-report = "controlplane_tool.devtools.package_report:main"
```

**Step 5: Run tests**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_package_report.py -q
```

Expected:

```text
2 passed
```

**Step 6: Run the report command**

Run:

```bash
uv run controlplane-package-report
```

Expected: a table with one row for each top-level package and no traceback.

**Step 7: Commit**

```bash
git add tools/controlplane/pyproject.toml \
  tools/controlplane/src/controlplane_tool/devtools \
  tools/controlplane/tests/test_package_report.py
git commit -m "Add controlplane package coupling report"
```

---

### Task 5: Add Documentation for Architecture Checks

**Files:**
- Modify: `tools/controlplane/README.md`

**Step 1: Write a documentation test first**

Append to `tools/controlplane/tests/test_wrapper_docs.py` or create `tools/controlplane/tests/test_architecture_docs.py`:

```python
from __future__ import annotations

from pathlib import Path

from controlplane_tool.app.paths import resolve_workspace_path


def test_tool_readme_documents_package_architecture_checks() -> None:
    readme = resolve_workspace_path(Path("tools/controlplane/README.md")).read_text(
        encoding="utf-8"
    )

    assert "Package architecture checks" in readme
    assert "uv run lint-imports" in readme
    assert "uv run controlplane-package-report" in readme
    assert "uv run pydeps controlplane_tool" in readme
    assert "GitNexus" in readme
```

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_architecture_docs.py -q
```

Expected:

```text
FAILED ... AssertionError
```

**Step 2: Add README section**

Append this section to `tools/controlplane/README.md` near the developer/testing commands:

```markdown
## Package architecture checks

The Python package is split into semantic packages under `controlplane_tool/`.
Use the import contracts as the hard boundary check:

```bash
uv run lint-imports
```

Use the coupling report to inspect package cohesion and cross-package imports:

```bash
uv run controlplane-package-report
```

Use `pydeps` for a visual graph when investigating a dependency tangle:

```bash
uv run pydeps controlplane_tool --show-deps --max-bacon 2
```

Use GitNexus before moving public symbols or changing flow-level dependencies:

```text
gitnexus_context({name: "LoadtestRunner"})
gitnexus_impact({target: "LoadtestRunner", direction: "upstream"})
gitnexus_detect_changes({scope: "staged"})
```
```

**Step 3: Run documentation test**

Run:

```bash
uv run pytest tests/test_architecture_docs.py -q
```

Expected:

```text
1 passed
```

**Step 4: Commit**

```bash
git add tools/controlplane/README.md tools/controlplane/tests/test_architecture_docs.py
git commit -m "Document controlplane package architecture checks"
```

---

### Task 6: Add Full Verification Gate

**Files:**
- No new files unless a failure requires a narrow fix.

**Step 1: Run import contracts**

Run:

```bash
cd tools/controlplane
uv run lint-imports
```

Expected:

```text
Contracts: 3 kept, 0 broken.
```

**Step 2: Run package report**

Run:

```bash
uv run controlplane-package-report
```

Expected:

```text
package                                internal outgoing incoming instability
...
controlplane_tool.core
...
```

Do not assert specific metric values in the command output. The report is diagnostic; the import contracts are the gate.

**Step 3: Run focused tests**

Run:

```bash
uv run pytest tests/test_package_layout.py tests/test_import_contracts.py tests/test_package_report.py tests/test_architecture_docs.py -q
```

Expected:

```text
6 passed
```

The exact count may be higher if `test_package_layout.py` gains tests. Check for zero failures.

**Step 4: Run full suite**

Run:

```bash
uv run pytest -q
```

Expected:

```text
... passed
```

Current baseline before this plan is `862 passed`; the expected total after adding the new tests should increase.

**Step 5: Run GitNexus staged change detection**

Run through MCP:

```text
gitnexus_detect_changes({scope: "staged"})
```

Expected: risk is not HIGH or CRITICAL. If it is HIGH/CRITICAL, inspect the reported symbols before committing.

**Step 6: Final commit if needed**

If any fixes were needed after the earlier task commits:

```bash
git add tools/controlplane
git commit -m "Verify package architecture checks"
```

---

### Task 7: Optional CI Integration

Only do this if the project already has a Python controlplane CI step that runs `uv run pytest`.

**Files:**
- Modify: `.github/workflows/*.yml` for the controlplane test job

**Step 1: Locate workflow**

Run:

```bash
rg -n "tools/controlplane|uv run pytest|controlplane-tool" .github/workflows
```

**Step 2: Add import contract command near pytest**

Add:

```yaml
- name: Check controlplane package import contracts
  working-directory: tools/controlplane
  run: uv run lint-imports
```

**Step 3: Do not add package report to CI as a hard gate**

The report should remain diagnostic unless a threshold is intentionally designed later.

**Step 4: Commit**

```bash
git add .github/workflows
git commit -m "Run controlplane import contracts in CI"
```

---

## Follow-Up Hardening Ideas

Do not implement these in the first pass unless the initial contracts are stable:

1. Add an `import-linter` `layers` contract once the package layering is agreed.
2. Add JSON output to `controlplane-package-report` for trend tracking.
3. Add thresholds for instability only after observing a few report runs.
4. Add a generated `package-deps.svg` artifact locally, but do not commit generated images by default.
5. Use GitNexus impact analysis before moving any package boundary again.

---

## Completion Checklist

- `uv run lint-imports` passes.
- `uv run controlplane-package-report` prints a package table.
- `uv run pytest tests/test_package_layout.py tests/test_import_contracts.py tests/test_package_report.py tests/test_architecture_docs.py -q` passes.
- `uv run pytest -q` passes.
- `gitnexus_detect_changes({scope: "staged"})` reviewed before final commit.
- Documentation explains when to use import-linter, grimp report, pydeps, and GitNexus.
