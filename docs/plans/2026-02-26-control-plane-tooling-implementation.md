# Control Plane Local Tooling Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement a local-only Python (`uv`) TUI tool and shell wrapper to configure control-plane builds, run optional tests, and generate an HTML report.

**Architecture:** Build a single Python project under `tooling/controlplane_tui` with clear boundaries: TUI/profile layer, pipeline/step orchestration, adapters for external commands, and reporting. Keep v1 deterministic and testable with dry-run capable step contracts and a metrics-aware report schema.

**Tech Stack:** Python 3.11+, `uv`, `questionary` (TUI), `typer` (CLI), `pydantic` (config models), `jinja2` + `plotly` (report), `pytest`.

---

### Task 1: Bootstrap project and wrapper

**Files:**
- Create: `tooling/controlplane_tui/pyproject.toml`
- Create: `tooling/controlplane_tui/src/controlplane_tool/__init__.py`
- Create: `tooling/controlplane_tui/src/controlplane_tool/main.py`
- Create: `tooling/controlplane_tui/tests/test_cli_smoke.py`
- Create: `scripts/controlplane-tool.sh`

**Step 1: Write the failing test**

```python
def test_cli_help_exits_zero():
    result = run_cli(["--help"])
    assert result.returncode == 0
    assert "Control plane" in result.stdout
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_cli_smoke.py -v`  
Expected: FAIL because package/entrypoint does not exist.

**Step 3: Write minimal implementation**

- Add package skeleton and Typer app exposing `--help`.
- Add `scripts/controlplane-tool.sh` that runs `uv run --project tooling/controlplane_tui controlplane-tool`.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_cli_smoke.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui scripts/controlplane-tool.sh
git commit -m "feat(tooling): bootstrap control plane local tool"
```

### Task 2: Profile model and persistence

**Files:**
- Create: `tooling/controlplane_tui/src/controlplane_tool/models.py`
- Create: `tooling/controlplane_tui/src/controlplane_tool/profiles.py`
- Create: `tooling/controlplane_tui/tests/test_profiles.py`

**Step 1: Write the failing test**

```python
def test_profile_roundtrip(tmp_path):
    profile = sample_profile(name="dev")
    save_profile(profile, root=tmp_path)
    loaded = load_profile("dev", root=tmp_path)
    assert loaded == profile
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_profiles.py -v`  
Expected: FAIL because models and storage APIs are missing.

**Step 3: Write minimal implementation**

- Implement pydantic models for control plane/tests/metrics/report.
- Implement TOML save/load under `tooling/profiles`.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_profiles.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/{models.py,profiles.py} tooling/controlplane_tui/tests/test_profiles.py
git commit -m "feat(tooling): add profile model and toml persistence"
```

### Task 3: Module catalog and interactive wizard

**Files:**
- Create: `tooling/controlplane_tui/src/controlplane_tool/module_catalog.py`
- Create: `tooling/controlplane_tui/src/controlplane_tool/tui.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/main.py`
- Create: `tooling/controlplane_tui/tests/test_tui_choices.py`

**Step 1: Write the failing test**

```python
def test_module_catalog_has_descriptions():
    for module in module_choices():
        assert module.name
        assert module.description
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_tui_choices.py -v`  
Expected: FAIL because catalog/wizard helpers are missing.

**Step 3: Write minimal implementation**

- Add module metadata catalog.
- Add questionary-based wizard to select runtime/build mode/modules/tests.
- Wire `run` command to create profile from wizard choices.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_tui_choices.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/{module_catalog.py,tui.py,main.py} tooling/controlplane_tui/tests/test_tui_choices.py
git commit -m "feat(tooling): add interactive module-aware wizard"
```

### Task 4: Pipeline runner and command adapters

**Files:**
- Create: `tooling/controlplane_tui/src/controlplane_tool/pipeline.py`
- Create: `tooling/controlplane_tui/src/controlplane_tool/adapters.py`
- Create: `tooling/controlplane_tui/tests/test_pipeline.py`

**Step 1: Write the failing test**

```python
def test_pipeline_stops_on_build_failure(tmp_path):
    runner = PipelineRunner(...failing_compile_adapter...)
    result = runner.run(sample_profile(), run_dir=tmp_path)
    assert result.final_status == "failed"
    assert "compile" in [s.name for s in result.steps if s.status == "failed"]
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_pipeline.py -v`  
Expected: FAIL because pipeline contracts do not exist.

**Step 3: Write minimal implementation**

- Add step/result dataclasses and stable statuses.
- Add adapters for gradle/cargo/docker/k6/prometheus command execution.
- Add compile/image steps and preflight command checks.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_pipeline.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/{pipeline.py,adapters.py} tooling/controlplane_tui/tests/test_pipeline.py
git commit -m "feat(tooling): implement pipeline runner and command adapters"
```

### Task 5: Metrics model and HTML report generation

**Files:**
- Create: `tooling/controlplane_tui/src/controlplane_tool/report.py`
- Create: `tooling/controlplane_tui/src/controlplane_tool/templates/report.html.j2`
- Create: `tooling/controlplane_tui/tests/test_report.py`

**Step 1: Write the failing test**

```python
def test_report_contains_required_sections(tmp_path):
    report_path = render_report(sample_summary(), output_dir=tmp_path)
    text = report_path.read_text()
    assert "Run metadata" in text
    assert "Step timeline" in text
    assert "Metrics over time" in text
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_report.py -v`  
Expected: FAIL because report module/template does not exist.

**Step 3: Write minimal implementation**

- Build summary-to-template renderer.
- Embed plotly graphs for timeline and metric series.
- Persist `summary.json` + `report.html` in run folder.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_report.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/{report.py,templates/report.html.j2} tooling/controlplane_tui/tests/test_report.py
git commit -m "feat(tooling): add html report generation with metrics charts"
```

### Task 6: Mock-K8s E2E entrypoint wiring and full run integration

**Files:**
- Create: `tooling/controlplane_tui/src/controlplane_tool/mockk8s.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/pipeline.py`
- Modify: `tooling/controlplane_tui/src/controlplane_tool/main.py`
- Create: `tooling/controlplane_tui/tests/test_run_integration.py`

**Step 1: Write the failing test**

```python
def test_run_with_tests_emits_summary_and_report(tmp_path):
    result = run_with_stubbed_adapters(profile_with_tests(), runs_root=tmp_path)
    assert (result.run_dir / "summary.json").exists()
    assert (result.run_dir / "report.html").exists()
    assert result.final_status in {"passed", "failed"}
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_run_integration.py -v`  
Expected: FAIL because integration flow is incomplete.

**Step 3: Write minimal implementation**

- Add mock-k8s command hook for Fabric8-based Java suite trigger.
- Wire optional test execution gates from profile.
- Ensure finalization always writes artifacts.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_run_integration.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add tooling/controlplane_tui/src/controlplane_tool/{mockk8s.py,pipeline.py,main.py} tooling/controlplane_tui/tests/test_run_integration.py
git commit -m "feat(tooling): wire mock-k8s test phase and end-to-end run output"
```

### Task 7: Verification and documentation update

**Files:**
- Modify: `docs/testing.md`
- Modify: `docs/quickstart.md`
- Modify: `README.md`

**Step 1: Write the failing test**

```python
def test_docs_reference_new_tooling_command():
    assert "scripts/controlplane-tool.sh" in readme_text
```

**Step 2: Run test to verify it fails**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_docs_links.py -v`  
Expected: FAIL because docs are missing references.

**Step 3: Write minimal implementation**

- Document tool usage, prerequisites, profiles, and outputs.

**Step 4: Run test to verify it passes**

Run: `uv run --project tooling/controlplane_tui pytest tooling/controlplane_tui/tests/test_docs_links.py -v`  
Expected: PASS.

**Step 5: Commit**

```bash
git add README.md docs/testing.md docs/quickstart.md tooling/controlplane_tui/tests/test_docs_links.py
git commit -m "docs: add control plane local tooling usage"
```
