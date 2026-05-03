# Dynamic Function Catalog Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build a dynamic function catalog that discovers repository example functions from `examples/<runtime>/<function>/`, uses `function.yaml` metadata when available, and keeps both catalog list and function details accurate without editing Python code for each new function.

**Architecture:** Keep `controlplane_tool.function_catalog` as the public API, but replace the static `FUNCTIONS` tuple with a cached loader. The loader scans `examples/`, reads optional `function.yaml` files, applies convention-based fallback for missing metadata, and keeps static presets plus the `tool-metrics-echo` fixture as code-owned entries. CLI and TUI callers continue to consume `FunctionDefinition`; no new TUI widget layer is introduced outside existing `tui-toolkit`/Rich conventions.

**Tech Stack:** Python 3.12, PyYAML, dataclasses, pathlib, Typer, Rich, Questionary/tui-toolkit, pytest, GitNexus.

---

## Evaluation Notes

- The previous high-level design is sound: `function.yaml` should be the canonical per-function metadata source, and fallback discovery is needed because most existing example directories do not have a manifest yet.
- Do not use manifest `name` as the catalog key. Existing `roman-numeral` manifests use the same `name` across runtimes, so catalog keys must be generated as `<family>-<runtime>`, e.g. `roman-numeral-go`.
- Keep v1 small: do not make presets dynamic. Static presets are part of scenario behavior and should only validate against the dynamic function index.
- The existing `function.yaml` schema is already used for function deploy/build metadata. Add catalog-only fields under a `catalog:` extension instead of changing top-level runtime deployment fields.

## Task 1: Preflight and GitNexus Impact Gates

**Files:**
- Inspect: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Inspect: `tools/controlplane/src/controlplane_tool/function_commands.py`
- Inspect: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Inspect: `tools/controlplane/src/controlplane_tool/tui_selection.py`
- Inspect: `tools/controlplane/src/controlplane_tool/scenario_loader.py`

**Step 1: Verify a clean starting point**

Run:

```bash
git status --short --branch
```

Expected: clean worktree on the intended branch.

**Step 2: Run GitNexus impact before editing symbols**

Run these MCP calls and record risk/direct dependents in the implementation notes:

```text
mcp__gitnexus__.impact({repo: "mcFaas", target: "FunctionDefinition", direction: "upstream", includeTests: true})
mcp__gitnexus__.impact({repo: "mcFaas", target: "list_functions", direction: "upstream", includeTests: true})
mcp__gitnexus__.impact({repo: "mcFaas", target: "resolve_function_definition", direction: "upstream", includeTests: true})
mcp__gitnexus__.impact({repo: "mcFaas", target: "resolve_function_preset", direction: "upstream", includeTests: true})
```

Expected: no HIGH/CRITICAL risk ignored. If HIGH/CRITICAL appears, stop and report it before editing.

**Step 3: Confirm current catalog behavior**

Run:

```bash
uv run pytest tests/test_function_catalog.py tests/test_function_commands.py tests/test_tui_choices.py -q
```

Expected: current tests pass before changes.

**Step 4: Commit**

Do not commit preflight-only work.

## Task 2: Add Failing Tests for Manifest and Fallback Discovery

**Files:**
- Modify: `tools/controlplane/tests/test_function_catalog.py`
- Later modify: `tools/controlplane/src/controlplane_tool/function_catalog.py`

**Step 1: Write failing tests for a private discovery helper**

Append tests that describe the desired loader behavior using `tmp_path`. Target a private helper named `_discover_example_functions(examples_root: Path, payloads_root: Path)`.

```python
from pathlib import Path

import pytest

from controlplane_tool.function_catalog import _discover_example_functions


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def test_discovers_function_from_manifest_catalog_metadata(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(payloads / "roman-numeral-sample.json", "{}")
    _write(
        examples / "go" / "roman-numeral" / "function.yaml",
        """
name: roman-numeral
image: nanofaas/roman-numeral:latest
catalog:
  family: roman-numeral
  runtime: go
  description: Go roman numeral demo.
  defaultImage: localhost:5000/nanofaas/go-roman-numeral:e2e
  defaultPayload: roman-numeral-sample.json
""".strip(),
    )

    functions = _discover_example_functions(examples, payloads)

    assert [function.key for function in functions] == ["roman-numeral-go"]
    function = functions[0]
    assert function.family == "roman-numeral"
    assert function.runtime == "go"
    assert function.description == "Go roman numeral demo."
    assert function.default_image == "localhost:5000/nanofaas/go-roman-numeral:e2e"
    assert function.default_payload_file == "roman-numeral-sample.json"
    assert function.example_dir == examples / "go" / "roman-numeral"


def test_discovers_function_with_convention_fallback(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(examples / "bash" / "word-stats" / "Dockerfile", "FROM scratch")
    _write(payloads / "word-stats-sample.json", "{}")

    functions = _discover_example_functions(examples, payloads)

    assert [function.key for function in functions] == ["word-stats-exec"]
    function = functions[0]
    assert function.family == "word-stats"
    assert function.runtime == "exec"
    assert function.default_image == "localhost:5000/nanofaas/bash-word-stats:e2e"
    assert function.default_payload_file == "word-stats-sample.json"


def test_discovery_rejects_duplicate_keys(tmp_path: Path) -> None:
    examples = tmp_path / "examples"
    payloads = tmp_path / "payloads"
    _write(examples / "go" / "same" / "Dockerfile", "FROM scratch")
    _write(
        examples / "go" / "other" / "function.yaml",
        """
catalog:
  family: same
  runtime: go
  description: duplicate
""".strip(),
    )

    with pytest.raises(ValueError, match="Duplicate function key: same-go"):
        _discover_example_functions(examples, payloads)
```

**Step 2: Run tests and verify red**

Run:

```bash
uv run pytest tests/test_function_catalog.py::test_discovers_function_from_manifest_catalog_metadata tests/test_function_catalog.py::test_discovers_function_with_convention_fallback tests/test_function_catalog.py::test_discovery_rejects_duplicate_keys -q
```

Expected: FAIL because `_discover_example_functions` does not exist.

**Step 3: Commit**

Do not commit red tests alone unless the team workflow explicitly wants red commits.

## Task 3: Add PyYAML Dependency and Implement Minimal Discovery

**Files:**
- Modify: `tools/controlplane/pyproject.toml`
- Modify: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Modify generated lock if dependency workflow requires it: `tools/controlplane/uv.lock`
- Test: `tools/controlplane/tests/test_function_catalog.py`

**Step 1: Add dependency**

Modify `tools/controlplane/pyproject.toml` dependencies:

```toml
"pyyaml>=6.0.2",
```

Run:

```bash
uv lock
```

Expected: lockfile updates successfully if this project tracks one for the tool.

**Step 2: Implement the discovery helper**

In `function_catalog.py`, add `yaml.safe_load` parsing and these helpers. Keep names private.

```python
import yaml


_RUNTIME_DIR_TO_CATALOG_RUNTIME: dict[str, FunctionRuntimeKind] = {
    "bash": "exec",
    "go": "go",
    "java": "java",
    "javascript": "javascript",
    "python": "python",
}


def _runtime_from_dir(runtime_dir: str, family: str) -> FunctionRuntimeKind:
    if runtime_dir == "java" and family.endswith("-lite"):
        return "java-lite"
    try:
        return _RUNTIME_DIR_TO_CATALOG_RUNTIME[runtime_dir]
    except KeyError as exc:
        raise ValueError(f"Unsupported function runtime directory: {runtime_dir}") from exc


def _family_from_dir(function_dir_name: str, runtime: FunctionRuntimeKind) -> str:
    if runtime == "java-lite" and function_dir_name.endswith("-lite"):
        return function_dir_name.removesuffix("-lite")
    return function_dir_name


def _image_runtime_prefix(runtime_dir: str, runtime: FunctionRuntimeKind) -> str:
    if runtime == "exec":
        return "bash"
    if runtime == "java-lite":
        return "java-lite"
    return runtime_dir


def _default_image(runtime_dir: str, runtime: FunctionRuntimeKind, family: str) -> str:
    prefix = _image_runtime_prefix(runtime_dir, runtime)
    return f"localhost:5000/nanofaas/{prefix}-{family}:e2e"


def _default_payload(payloads_root: Path, family: str) -> str | None:
    candidate = f"{family}-sample.json"
    return candidate if (payloads_root / candidate).exists() else None
```

Implement `_discover_example_functions`:

```python
def _discover_example_functions(examples_root: Path, payloads_root: Path) -> list[FunctionDefinition]:
    if not examples_root.exists():
        return []

    discovered: list[FunctionDefinition] = []
    seen: set[str] = set()

    for runtime_root in sorted(path for path in examples_root.iterdir() if path.is_dir()):
        runtime_dir = runtime_root.name
        if runtime_dir == "build":
            continue

        for example_dir in sorted(path for path in runtime_root.iterdir() if path.is_dir()):
            manifest = _load_function_manifest(example_dir / "function.yaml")
            catalog = manifest.get("catalog", {}) if isinstance(manifest, dict) else {}

            fallback_family = _family_from_dir(
                example_dir.name,
                _runtime_from_dir(runtime_dir, example_dir.name),
            )
            runtime = catalog.get("runtime") or _runtime_from_dir(runtime_dir, example_dir.name)
            family = catalog.get("family") or _family_from_dir(example_dir.name, runtime)
            key = f"{family}-{runtime}"

            if key in seen:
                raise ValueError(f"Duplicate function key: {key}")
            seen.add(key)

            discovered.append(
                FunctionDefinition(
                    key=key,
                    family=family,
                    runtime=runtime,
                    description=(
                        catalog.get("description")
                        or f"{family} {runtime} example function."
                    ),
                    example_dir=example_dir,
                    default_image=(
                        catalog.get("defaultImage")
                        or _default_image(runtime_dir, runtime, family)
                    ),
                    default_payload_file=(
                        catalog.get("defaultPayload")
                        or _default_payload(payloads_root, family)
                    ),
                )
            )

    return discovered
```

Add `_load_function_manifest`:

```python
def _load_function_manifest(path: Path) -> dict[str, object]:
    if not path.exists():
        return {}
    data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
    if not isinstance(data, dict):
        raise ValueError(f"Invalid function manifest: {path}")
    return data
```

Adjust names/types for mypy/pytest if needed, but do not add schema beyond the fields in this plan.

**Step 3: Run red tests and verify green**

Run:

```bash
uv run pytest tests/test_function_catalog.py::test_discovers_function_from_manifest_catalog_metadata tests/test_function_catalog.py::test_discovers_function_with_convention_fallback tests/test_function_catalog.py::test_discovery_rejects_duplicate_keys -q
```

Expected: PASS.

**Step 4: Commit**

```bash
git add tools/controlplane/pyproject.toml tools/controlplane/uv.lock tools/controlplane/src/controlplane_tool/function_catalog.py tools/controlplane/tests/test_function_catalog.py
git commit -m "Add dynamic function discovery"
```

If `tools/controlplane/uv.lock` does not change, omit it from `git add`.

## Task 4: Replace Static Function Index With Dynamic Catalog

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Test: `tools/controlplane/tests/test_function_catalog.py`

**Step 1: Write failing tests for public catalog behavior**

Add:

```python
def test_function_catalog_discovers_repository_examples() -> None:
    keys = [function.key for function in list_functions()]

    assert "word-stats-java" in keys
    assert "json-transform-java" in keys
    assert "word-stats-java-lite" in keys
    assert "json-transform-java-lite" in keys
    assert "word-stats-go" in keys
    assert "json-transform-go" in keys
    assert "word-stats-python" in keys
    assert "json-transform-python" in keys
    assert "word-stats-javascript" in keys
    assert "json-transform-javascript" in keys
    assert "word-stats-exec" in keys
    assert "json-transform-exec" in keys
    assert "roman-numeral-java" in keys
    assert "roman-numeral-go" in keys
    assert "roman-numeral-python" in keys
    assert "roman-numeral-exec" in keys
    assert "tool-metrics-echo" in keys


def test_resolve_function_definition_uses_dynamic_index() -> None:
    function = resolve_function_definition("roman-numeral-go")

    assert function.family == "roman-numeral"
    assert function.runtime == "go"
    assert function.example_dir is not None
```

**Step 2: Run tests and verify red**

Run:

```bash
uv run pytest tests/test_function_catalog.py::test_function_catalog_discovers_repository_examples tests/test_function_catalog.py::test_resolve_function_definition_uses_dynamic_index -q
```

Expected: FAIL because public API still uses static `FUNCTIONS`.

**Step 3: Replace static tuple/index**

In `function_catalog.py`:

- Remove the static `FUNCTIONS` tuple.
- Keep a code-owned fixture tuple:

```python
_FIXTURE_FUNCTIONS: tuple[FunctionDefinition, ...] = (
    FunctionDefinition(
        key="tool-metrics-echo",
        family="metrics-echo",
        runtime="fixture",
        description="Deterministic fixture used by the controlplane metrics flow.",
        example_dir=None,
        default_image=None,
        default_payload_file="echo-sample.json",
    ),
)
```

- Add cached public catalog functions:

```python
def _load_functions() -> tuple[FunctionDefinition, ...]:
    discovered = _discover_example_functions(
        _EXAMPLES_ROOT,
        _PATHS.scenario_payloads_dir,
    )
    return tuple([*discovered, *_FIXTURE_FUNCTIONS])


def list_functions() -> list[FunctionDefinition]:
    return list(_load_functions())


def _function_index() -> dict[str, FunctionDefinition]:
    return {function.key: function for function in _load_functions()}


def resolve_function_definition(key: str) -> FunctionDefinition:
    try:
        return _function_index()[key]
    except KeyError as exc:
        raise ValueError(f"Unknown function: {key}") from exc
```

Do not cache yet unless tests expose performance problems. A fresh load keeps tests and local file edits straightforward.

**Step 4: Run tests and verify green**

Run:

```bash
uv run pytest tests/test_function_catalog.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/function_catalog.py tools/controlplane/tests/test_function_catalog.py
git commit -m "Load function catalog from examples"
```

## Task 5: Add and Enrich Function Manifests for Existing Examples

**Files:**
- Create or modify:
  - `examples/bash/json-transform/function.yaml`
  - `examples/bash/word-stats/function.yaml`
  - `examples/go/json-transform/function.yaml`
  - `examples/go/word-stats/function.yaml`
  - `examples/java/json-transform/function.yaml`
  - `examples/java/json-transform-lite/function.yaml`
  - `examples/java/word-stats/function.yaml`
  - `examples/java/word-stats-lite/function.yaml`
  - `examples/python/json-transform/function.yaml`
  - `examples/python/word-stats/function.yaml`
- Modify:
  - `examples/bash/roman-numeral/function.yaml`
  - `examples/go/roman-numeral/function.yaml`
  - `examples/java/roman-numeral/function.yaml`
  - `examples/javascript/json-transform/function.yaml`
  - `examples/javascript/word-stats/function.yaml`
  - `examples/python/roman-numeral/function.yaml`
- Test: `tools/controlplane/tests/test_function_catalog.py`

**Step 1: Write a failing metadata preservation test**

Add:

```python
def test_dynamic_catalog_preserves_existing_metadata() -> None:
    function = resolve_function_definition("word-stats-java")

    assert function.description == "Spring Boot Java word statistics demo."
    assert function.default_image == "localhost:5000/nanofaas/java-word-stats:e2e"
    assert function.default_payload_file == "word-stats-sample.json"


def test_dynamic_catalog_exposes_manifest_backed_roman_numeral_details() -> None:
    function = resolve_function_definition("roman-numeral-java")

    assert function.description == "Java roman numeral conversion demo."
    assert function.default_image == "localhost:5000/nanofaas/java-roman-numeral:e2e"
    assert function.default_payload_file is None
```

**Step 2: Run tests and verify red**

Run:

```bash
uv run pytest tests/test_function_catalog.py::test_dynamic_catalog_preserves_existing_metadata tests/test_function_catalog.py::test_dynamic_catalog_exposes_manifest_backed_roman_numeral_details -q
```

Expected: FAIL until manifests carry explicit descriptions/default images.

**Step 3: Add catalog metadata**

For each manifest, add a `catalog:` section. Use this pattern:

```yaml
catalog:
  family: word-stats
  runtime: java
  description: Spring Boot Java word statistics demo.
  defaultImage: localhost:5000/nanofaas/java-word-stats:e2e
  defaultPayload: word-stats-sample.json
```

Runtime-specific values:

- `word-stats-java`: `runtime: java`, `defaultImage: localhost:5000/nanofaas/java-word-stats:e2e`
- `json-transform-java`: `runtime: java`, `defaultImage: localhost:5000/nanofaas/java-json-transform:e2e`
- `word-stats-java-lite`: `runtime: java-lite`, `defaultImage: localhost:5000/nanofaas/java-lite-word-stats:e2e`
- `json-transform-java-lite`: `runtime: java-lite`, `defaultImage: localhost:5000/nanofaas/java-lite-json-transform:e2e`
- `word-stats-go`: `runtime: go`, `defaultImage: localhost:5000/nanofaas/go-word-stats:e2e`
- `json-transform-go`: `runtime: go`, `defaultImage: localhost:5000/nanofaas/go-json-transform:e2e`
- `word-stats-python`: `runtime: python`, `defaultImage: localhost:5000/nanofaas/python-word-stats:e2e`
- `json-transform-python`: `runtime: python`, `defaultImage: localhost:5000/nanofaas/python-json-transform:e2e`
- `word-stats-javascript`: `runtime: javascript`, `defaultImage: localhost:5000/nanofaas/javascript-word-stats:e2e`
- `json-transform-javascript`: `runtime: javascript`, `defaultImage: localhost:5000/nanofaas/javascript-json-transform:e2e`
- `word-stats-exec`: `runtime: exec`, `defaultImage: localhost:5000/nanofaas/bash-word-stats:e2e`
- `json-transform-exec`: `runtime: exec`, `defaultImage: localhost:5000/nanofaas/bash-json-transform:e2e`
- `roman-numeral-java`: `runtime: java`, `defaultImage: localhost:5000/nanofaas/java-roman-numeral:e2e`, no default payload
- `roman-numeral-go`: `runtime: go`, `defaultImage: localhost:5000/nanofaas/go-roman-numeral:e2e`, no default payload
- `roman-numeral-python`: `runtime: python`, `defaultImage: localhost:5000/nanofaas/python-roman-numeral:e2e`, no default payload
- `roman-numeral-exec`: `runtime: exec`, `defaultImage: localhost:5000/nanofaas/bash-roman-numeral:e2e`, no default payload

Keep existing top-level deploy fields (`name`, `image`, `timeoutMs`, `concurrency`, `executionMode`, `x-cli`) unchanged when already present.

**Step 4: Run tests and verify green**

Run:

```bash
uv run pytest tests/test_function_catalog.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add examples tools/controlplane/tests/test_function_catalog.py
git commit -m "Add catalog metadata to example functions"
```

## Task 6: Keep Static Presets Valid Against Dynamic Functions

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Test: `tools/controlplane/tests/test_function_catalog.py`
- Test: `tools/controlplane/tests/test_tui_selection.py`

**Step 1: Write tests for preset compatibility**

Add or update tests:

```python
def test_static_presets_resolve_against_dynamic_catalog() -> None:
    preset = resolve_function_preset("demo-java")

    assert [function.key for function in preset.functions] == [
        "word-stats-java",
        "json-transform-java",
    ]


def test_demo_all_does_not_auto_include_new_discovered_functions() -> None:
    preset = resolve_function_preset("demo-all")

    assert "roman-numeral-java" not in [function.key for function in preset.functions]
```

**Step 2: Run tests**

Run:

```bash
uv run pytest tests/test_function_catalog.py::test_static_presets_resolve_against_dynamic_catalog tests/test_function_catalog.py::test_demo_all_does_not_auto_include_new_discovered_functions -q
```

Expected: PASS or fail only because current preset construction still depends on stale global index.

**Step 3: Adjust preset construction if needed**

Keep `PRESETS` static, but ensure `_preset(...)` calls `resolve_function_definition(...)` after dynamic loading works. Do not auto-generate presets.

If import-time preset construction causes ordering problems, change `PRESETS` to static specs and resolve lazily:

```python
@dataclass(frozen=True)
class FunctionPresetSpec:
    name: str
    description: str
    keys: tuple[str, ...]
```

Then:

```python
def _resolve_preset(spec: FunctionPresetSpec) -> FunctionPreset:
    return FunctionPreset(
        name=spec.name,
        description=spec.description,
        functions=tuple(resolve_function_definition(key) for key in spec.keys),
    )
```

Use `PRESET_SPECS` internally and build `list_function_presets()` from them.

**Step 4: Run related tests**

Run:

```bash
uv run pytest tests/test_function_catalog.py tests/test_tui_selection.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/function_catalog.py tools/controlplane/tests/test_function_catalog.py tools/controlplane/tests/test_tui_selection.py
git commit -m "Resolve presets against dynamic function catalog"
```

## Task 7: Update CLI Function Details

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/function_commands.py`
- Test: `tools/controlplane/tests/test_function_commands.py`

**Step 1: Write CLI tests**

Add:

```python
from typer.testing import CliRunner

from controlplane_tool.main import app


def test_functions_list_includes_dynamic_roman_numeral_function() -> None:
    result = CliRunner().invoke(app, ["functions", "list"])

    assert result.exit_code == 0
    assert "roman-numeral-go" in result.stdout


def test_functions_show_prints_dynamic_function_details() -> None:
    result = CliRunner().invoke(app, ["functions", "show", "roman-numeral-go"])

    assert result.exit_code == 0
    assert "roman-numeral-go" in result.stdout
    assert "Go roman numeral conversion demo." in result.stdout
    assert "localhost:5000/nanofaas/go-roman-numeral:e2e" in result.stdout
    assert "examples/go/roman-numeral" in result.stdout
```

**Step 2: Run tests**

Run:

```bash
uv run pytest tests/test_function_commands.py::test_functions_list_includes_dynamic_roman_numeral_function tests/test_function_commands.py::test_functions_show_prints_dynamic_function_details -q
```

Expected: PASS if CLI already consumes dynamic definitions correctly. If FAIL, update CLI output to use `FunctionDefinition` fields only.

**Step 3: Minimal CLI implementation if needed**

Do not add new CLI fields beyond existing detail fields. Ensure `functions_show` prints:

- Key
- Family
- Runtime
- Description
- Example Dir if present
- Default Image if present
- Default Payload if present

**Step 4: Run tests**

Run:

```bash
uv run pytest tests/test_function_commands.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/function_commands.py tools/controlplane/tests/test_function_commands.py
git commit -m "Show dynamic function details in CLI"
```

## Task 8: Update TUI Function Catalog and Details Tests

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`
- Test: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Write TUI tests**

Add tests that monkeypatch static view acknowledgements and capture Rich output, matching existing `test_tui_function_catalog_waits_for_acknowledge_after_static_views` style.

```python
def test_tui_function_catalog_lists_dynamic_functions(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    printed: list[str] = []
    selections = iter(["all", "back"])

    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(selections))
    monkeypatch.setattr(tui_app, "_acknowledge_static_view", lambda *args, **kwargs: None)
    monkeypatch.setattr(tui_app.console, "print", lambda value, *args, **kwargs: printed.append(str(value)))

    NanofaasTUI()._functions_menu()

    assert any("roman-numeral-go" in item for item in printed)


def test_tui_function_details_show_dynamic_metadata(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    printed: list[str] = []
    selections = iter(["details", "roman-numeral-go", "back"])

    monkeypatch.setattr(tui_app, "_select_value", lambda *args, **kwargs: next(selections))
    monkeypatch.setattr(tui_app, "_acknowledge_static_view", lambda *args, **kwargs: None)
    monkeypatch.setattr(tui_app.console, "print", lambda value, *args, **kwargs: printed.append(str(value)))

    NanofaasTUI()._functions_menu()

    rendered = "\n".join(printed)
    assert "roman-numeral-go" in rendered
    assert "Go roman numeral conversion demo." in rendered
    assert "localhost:5000/nanofaas/go-roman-numeral:e2e" in rendered
```

Adjust choice value `"details"` if `_CATALOG_VIEW_CHOICES` uses a different value for detail view.

**Step 2: Run tests and verify red/green**

Run:

```bash
uv run pytest tests/test_tui_choices.py::test_tui_function_catalog_lists_dynamic_functions tests/test_tui_choices.py::test_tui_function_details_show_dynamic_metadata -q
```

Expected: PASS if TUI already consumes dynamic definitions. If details omit default image or example dir, update the TUI details rows.

**Step 3: Update TUI details minimally if needed**

In `NanofaasTUI._functions_menu`, ensure detail rows include:

```python
rows = [
    ("Key", fn.key),
    ("Family", fn.family),
    ("Runtime", fn.runtime),
    ("Description", getattr(fn, "description", "—")),
    ("Example dir", str(getattr(fn, "example_dir", "—") or "—")),
    ("Default image", str(getattr(fn, "default_image", "—") or "—")),
    ("Payload file", str(getattr(fn, "default_payload_file", "—") or "—")),
]
```

Do not create new reusable widgets in `controlplane_tool`. If a genuinely reusable TUI widget becomes necessary, move it to `tools/tui-toolkit` in a separate reviewed change.

**Step 4: Run tests**

Run:

```bash
uv run pytest tests/test_tui_choices.py -q
```

Expected: PASS.

**Step 5: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py tools/controlplane/tests/test_tui_choices.py
git commit -m "Render dynamic function catalog in TUI"
```

## Task 9: Scenario and Selection Regression Coverage

**Files:**
- Modify if needed: `tools/controlplane/src/controlplane_tool/scenario_loader.py`
- Modify if needed: `tools/controlplane/src/controlplane_tool/tui_selection.py`
- Test: existing scenario and selection tests

**Step 1: Run scenario and selection tests**

Run:

```bash
uv run pytest tests/test_function_catalog.py tests/test_tui_selection.py tests/test_e2e_commands.py tests/test_loadtest_commands.py tests/test_models_validation.py -q
```

Expected: PASS.

**Step 2: Fix only dynamic-catalog regressions**

If failures appear:

- Unknown preset/function failures: inspect `PRESET_SPECS` keys.
- Payload failures: inspect `catalog.defaultPayload` and fallback `_default_payload`.
- Buildable selection failures: inspect `_is_buildable_function` in `tui_selection.py`.

Do not change scenario semantics or preset membership in this task.

**Step 3: Run focused failed tests**

Run the exact failing tests again after any fix.

Expected: PASS.

**Step 4: Commit if files changed**

```bash
git add tools/controlplane/src/controlplane_tool/scenario_loader.py tools/controlplane/src/controlplane_tool/tui_selection.py tests
git commit -m "Preserve scenario selection with dynamic catalog"
```

Skip the commit if no files changed.

## Task 10: Final Verification and GitNexus Change Detection

**Files:**
- No planned source edits.
- Inspect: git diff and GitNexus results.

**Step 1: Run targeted verification**

Run:

```bash
uv run pytest tests/test_function_catalog.py tests/test_function_commands.py tests/test_tui_choices.py tests/test_tui_selection.py -q
```

Expected: PASS.

**Step 2: Run broad verification**

Run:

```bash
uv run pytest -q -k 'not test_vm_registry_dry_run_prints_both_registry_commands'
```

Expected: PASS with one deselected test. The deselected test is the known Rich line-wrapping assertion unrelated to this feature.

**Step 3: Run controlplane CLI smoke checks**

Run:

```bash
uv run controlplane-tool functions list
uv run controlplane-tool functions show roman-numeral-go
uv run controlplane-tool functions show word-stats-java
```

Expected:

- `functions list` includes current catalog keys plus `roman-numeral-*`.
- `roman-numeral-go` details include manifest-backed description and image.
- `word-stats-java` details preserve the old description, image, and payload.

**Step 4: Run GitNexus detect changes**

Run MCP:

```text
mcp__gitnexus__.detect_changes({repo: "mcFaas", scope: "all"})
```

Expected: changed symbols and affected processes match the catalog/scenario/TUI surface. Investigate any unrelated affected processes before finishing.

**Step 5: Final commit if needed**

If there are remaining uncommitted test/doc cleanup changes:

```bash
git status --short
git add <specific files>
git commit -m "Verify dynamic function catalog"
```

Do not use broad `git add .`.

