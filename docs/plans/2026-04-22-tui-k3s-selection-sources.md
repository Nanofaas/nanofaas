# TUI K3s Selection Sources Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Let the interactive controlplane TUI run `k3s-junit-curl` with an explicit selection source so users can execute JavaScript functions through an existing function preset, scenario manifest, or saved profile instead of being forced onto the built-in Java default.

**Architecture:** Keep the existing CLI/E2E resolver as the source of truth. Extend only the `k3s-junit-curl` branch in `NanofaasTUI._run_vm_e2e_scenario()` so the TUI asks where to load the function selection from, then passes `function_preset`, `scenario_file`, or `saved_profile` into `_resolve_run_request()`. Do not add TUI editing of scenario TOML files in this change; the TUI only reuses existing manifests and profiles. Keep the new filtering helpers in `tui_app.py`, not `tui.py`, because this is runtime-specific TUI behavior, not profile-wizard behavior.

**Tech Stack:** Python, Typer, Questionary, Rich, Pydantic, pytest with monkeypatch.

---

### Task 1: Lock compatibility filtering rules with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Add a failing helper test for scenario manifest filtering**

Write a focused test for a new helper such as `_k3s_scenario_file_choices()` that proves the TUI only offers scenario manifests compatible with `k3s-junit-curl`.

Compatibility rule to lock:
- include a manifest only when `load_scenario_file(path).base_scenario == "k3s-junit-curl"`
- ignore manifests for other base scenarios
- ignore invalid manifests instead of crashing the picker

Example test shape:

```python
def test_k3s_scenario_file_choices_only_return_compatible_manifests(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    fake_paths = SimpleNamespace(
        workspace_root=Path("/repo"),
        scenarios_dir=Path("/repo/tools/controlplane/scenarios"),
    )

    monkeypatch.setattr(tui_app, "default_tool_paths", lambda: fake_paths)
    monkeypatch.setattr(
        Path,
        "glob",
        lambda self, pattern: iter(
            [
                fake_paths.scenarios_dir / "k8s-demo-javascript.toml",
                fake_paths.scenarios_dir / "k8s-demo-all.toml",
                fake_paths.scenarios_dir / "broken.toml",
            ]
        ),
    )

    def fake_load(path: Path):  # noqa: ANN001
        if path.name == "k8s-demo-javascript.toml":
            return SimpleNamespace(base_scenario="k3s-junit-curl", name="k8s-demo-javascript")
        if path.name == "k8s-demo-all.toml":
            return SimpleNamespace(base_scenario="helm-stack", name="k8s-demo-all")
        raise ValueError("invalid manifest")

    monkeypatch.setattr(tui_app, "load_scenario_file", fake_load)

    values = [choice.value for choice in tui_app._k3s_scenario_file_choices()]
    assert values == ["tools/controlplane/scenarios/k8s-demo-javascript.toml"]
```

**Step 2: Add a failing helper test for saved-profile filtering**

Write a test for a new helper such as `_k3s_saved_profile_choices()`.

Compatibility rule to lock:
- include profiles whose `scenario.scenario_file` resolves to a manifest with `base_scenario == "k3s-junit-curl"`
- include profiles whose `scenario.base_scenario` is `None` or `"k3s-junit-curl"`
- exclude profiles whose saved scenario targets another base scenario such as `helm-stack` or `container-local`

Example test shape:

```python
def test_k3s_saved_profile_choices_only_return_compatible_profiles(monkeypatch) -> None:
    import controlplane_tool.tui_app as tui_app

    monkeypatch.setattr(tui_app, "list_profiles", lambda: ["demo-javascript", "bad-helm", "generic"])

    def fake_load_profile(name: str):  # noqa: ANN001
        if name == "demo-javascript":
            return SimpleNamespace(
                scenario=SimpleNamespace(
                    base_scenario="k3s-junit-curl",
                    scenario_file=None,
                )
            )
        if name == "bad-helm":
            return SimpleNamespace(
                scenario=SimpleNamespace(
                    base_scenario="helm-stack",
                    scenario_file=None,
                )
            )
        return SimpleNamespace(
            scenario=SimpleNamespace(
                base_scenario=None,
                scenario_file=None,
            )
        )

    monkeypatch.setattr(tui_app, "load_profile", fake_load_profile)

    values = [choice.value for choice in tui_app._k3s_saved_profile_choices()]
    assert values == ["demo-javascript", "generic"]
```

**Step 3: Run the focused helper tests and confirm they fail**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py -k "k3s_scenario_file_choices or k3s_saved_profile_choices" -v
```

Expected: FAIL because the helpers do not exist yet.

**Step 4: Commit the red baseline**

```bash
git add tools/controlplane/tests/test_tui_choices.py
git commit -m "test(tui): lock k3s selection compatibility rules"
```

### Task 2: Lock the runtime TUI flow with failing tests

**Files:**
- Modify: `tools/controlplane/tests/test_tui_choices.py`

**Step 1: Update the existing default-path test**

Change `test_tui_k3s_junit_curl_scenario_runs_shared_flow_not_direct_execute()` so the mocked answers include the new selection-source prompt and explicitly choose the built-in default.

Use this prompt order:

```python
answers = iter(["nanofaas-e2e", "java", True, "default", False])
```

The new order is:
1. VM name
2. runtime
3. cleanup VM
4. selection source
5. dry-run

Lock these assertions:

```python
assert called["request"].function_preset == "demo-java"
assert called["request"].scenario_file is None
assert called["request"].saved_profile is None
assert called["request"].scenario_source == "built-in default"
```

**Step 2: Add a failing preset test for JavaScript**

Write a test that chooses `selection_source == "preset"` and `preset == "demo-javascript"`.

Lock these assertions:

```python
assert called["request"].function_preset == "demo-javascript"
assert called["request"].scenario_file is None
assert called["request"].saved_profile is None
assert called["request"].scenario_source == "explicit CLI override"
assert called["request"].resolved_scenario.function_keys == [
    "word-stats-javascript",
    "json-transform-javascript",
]
```

**Step 3: Add a failing scenario-file test**

Write a test that chooses `tools/controlplane/scenarios/k8s-demo-javascript.toml`.

Lock these assertions:

```python
assert called["request"].function_preset == "demo-javascript"
assert called["request"].saved_profile is None
assert called["request"].scenario_file == resolve_workspace_path(
    Path("tools/controlplane/scenarios/k8s-demo-javascript.toml")
)
assert "scenario file:" in called["request"].scenario_source.lower()
assert called["request"].resolved_scenario.function_keys == [
    "word-stats-javascript",
    "json-transform-javascript",
]
```

**Step 4: Add a failing saved-profile test**

Write a test that chooses the existing saved profile `demo-javascript`.

Lock these assertions:

```python
assert called["request"].saved_profile == "demo-javascript"
assert called["request"].scenario_file is None
assert called["request"].function_preset == "demo-javascript"
assert called["request"].scenario_source == "saved profile: demo-javascript"
assert called["request"].resolved_scenario.function_keys == [
    "word-stats-javascript",
    "json-transform-javascript",
]
```

**Step 5: Add failing no-choice fallback tests**

Add one test for each empty picker case:
- no compatible scenario files
- no compatible saved profiles

Lock this behavior:
- TUI does not crash
- it calls `warning(...)`
- it returns to the selection-source prompt instead of proceeding with an invalid request

Example shape:

```python
def test_tui_k3s_junit_curl_warns_when_no_compatible_saved_profiles(monkeypatch) -> None:
    ...
    assert warnings == ["No compatible saved profiles found for k3s-junit-curl."]
    assert called["request"].function_preset == "demo-javascript"
```

In that test, the first selection-source answer should be `saved-profile`, the second should be `preset`, so the flow proves it reprompts correctly after the warning.

**Step 6: Run the focused tests and confirm they fail**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py -k "k3s_junit_curl" -v
```

Expected:
- the updated default-path test fails until the prompt order changes
- the new JavaScript tests fail because `_run_vm_e2e_scenario()` never asks for a selection source
- the fallback tests fail because empty compatible lists are not handled yet

**Step 7: Commit the red baseline**

```bash
git add tools/controlplane/tests/test_tui_choices.py
git commit -m "test(tui): lock k3s selection source flow"
```

### Task 3: Implement the compatibility helpers and the `k3s-junit-curl` prompt flow

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/tui_app.py`

**Step 1: Add explicit selection-source choices**

Near the existing choice builders in `tui_app.py`, add:

```python
_K3S_SELECTION_SOURCE_CHOICES = [
    _choice(
        "Built-in default",
        "default",
        "Reuse the built-in scenario-aware default selection for k3s-junit-curl.",
    ),
    _choice(
        "Function preset",
        "preset",
        "Choose a catalog preset such as demo-java or demo-javascript.",
    ),
    _choice(
        "Scenario file",
        "scenario-file",
        "Choose an existing TOML manifest from tools/controlplane/scenarios/.",
    ),
    _choice(
        "Saved profile",
        "saved-profile",
        "Choose an existing saved profile whose scenario is compatible with k3s-junit-curl.",
    ),
]
```

**Step 2: Implement `_k3s_scenario_file_choices()`**

Keep this helper in `tui_app.py`.

Required behavior:
- scan `default_tool_paths().scenarios_dir.glob("*.toml")`
- `load_scenario_file(path)` each manifest
- include only manifests whose `base_scenario == "k3s-junit-curl"`
- silently skip invalid or unreadable manifests
- return values relative to `workspace_root`, matching existing TUI conventions

**Step 3: Implement `_k3s_saved_profile_choices()`**

Keep this helper in `tui_app.py`.

Required behavior:
- call `list_profiles()`
- `load_profile(name)` for each candidate
- inspect `profile.scenario`
- if `scenario.scenario_file` is set, resolve and load that manifest; include only if it resolves to `base_scenario == "k3s-junit-curl"`
- if `scenario.scenario_file` is unset, include the profile only when `scenario.base_scenario in {None, "k3s-junit-curl"}`
- skip invalid or unreadable profiles instead of crashing
- reuse `_saved_profile_description(name)` for descriptions so profile summaries stay consistent

**Step 4: Prompt for selection source before dry-run**

In the `k3s-junit-curl` branch of `_run_vm_e2e_scenario()`, change the prompt order to:
1. VM name
2. runtime
3. cleanup VM
4. selection source
5. dry-run

Use `_select_described_value(...)` for the selection-source prompt.

**Step 5: Resolve the selection source with explicit reprompt rules**

Implement this exact behavior:

```python
function_preset = None
scenario_file = None
saved_profile = None
selection_source = None

while True:
    selection_source = _ask(...)
    if selection_source == "default":
        break
    if selection_source == "preset":
        function_preset = _ask(...)
        break
    if selection_source == "scenario-file":
        choices = _k3s_scenario_file_choices()
        if not choices:
            warning("No compatible scenario files found for k3s-junit-curl.")
            continue
        scenario_file = Path(_ask(...))
        break
    if selection_source == "saved-profile":
        choices = _k3s_saved_profile_choices()
        if not choices:
            warning("No compatible saved profiles found for k3s-junit-curl.")
            continue
        saved_profile = _ask(...)
        break
```

Do not fall back silently to `demo-java` when the user explicitly chose `scenario-file` or `saved-profile` and no compatible options exist.

**Step 6: Pass the chosen source into `_resolve_run_request()`**

Replace the current hardcoded call with:

```python
request = _resolve_run_request(
    scenario="k3s-junit-curl",
    runtime=runtime,
    lifecycle="multipass",
    name=vm_name,
    host=None,
    user="ubuntu",
    home=None,
    cpus=4,
    memory="12G",
    disk="30G",
    cleanup_vm=cleanup_vm,
    namespace=None,
    local_registry=None,
    function_preset=function_preset,
    functions_csv=None,
    scenario_file=scenario_file,
    saved_profile=saved_profile,
)
```

Preserve the built-in default behavior by leaving all three selection fields as `None` when `selection_source == "default"`.

**Step 7: Surface the chosen source in the live workflow summary**

Add summary lines that make the chosen source visible:

```python
summary_lines = [
    "Scenario: k3s-junit-curl",
    "Mode: self-bootstrapping VM-backed scenario",
    f"VM Name: {vm_name}",
    f"Control-plane runtime: {runtime}",
    f"Cleanup VM at end: {'yes' if cleanup_vm else 'no'}",
    f"Selection source: {selection_source}",
]
```

When relevant, append:
- `Function preset: demo-javascript`
- `Scenario file: tools/controlplane/scenarios/k8s-demo-javascript.toml`
- `Saved profile: demo-javascript`

**Step 8: Run the focused tests and make them pass**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py -k "k3s_" -v
```

Expected: PASS

**Step 9: Commit**

```bash
git add tools/controlplane/src/controlplane_tool/tui_app.py tools/controlplane/tests/test_tui_choices.py
git commit -m "feat(tui): add k3s selection sources"
```

### Task 4: Document the TUI behavior and lock docs tests

**Files:**
- Modify: `tools/controlplane/README.md`
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Update the canonical docs**

Add one short paragraph explaining that `scripts/controlplane.sh tui` can now drive `k3s-junit-curl` through the same selection model already supported by the CLI:
- built-in default
- function preset
- scenario file
- saved profile

Include explicit examples already present in the repo:
- `demo-javascript`
- `tools/controlplane/scenarios/k8s-demo-javascript.toml`

Suggested wording:

```md
Within `Validation -> platform -> k3s-junit-curl`, the TUI can now reuse the built-in default selection, a function preset such as `demo-javascript`, a scenario manifest such as `tools/controlplane/scenarios/k8s-demo-javascript.toml`, or a compatible saved profile such as `demo-javascript`.
```

Also state the compatibility rule for saved profiles briefly:

```md
The TUI only offers saved profiles and scenario manifests compatible with `k3s-junit-curl`; incompatible entries are filtered out instead of failing at execution time.
```

**Step 2: Extend the docs-link test**

Add assertions in `tools/controlplane/tests/test_docs_links.py` for the new TUI wording, for example:

```python
assert "Validation -> platform -> k3s-junit-curl" in testing
assert "tools/controlplane/scenarios/k8s-demo-javascript.toml" in testing
assert "compatible saved profile such as `demo-javascript`" in tool_readme
assert "filtered out instead of failing at execution time" in tool_readme
```

**Step 3: Run the docs test**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_docs_links.py -v
```

Expected: PASS

**Step 4: Commit**

```bash
git add README.md docs/testing.md tools/controlplane/README.md tools/controlplane/tests/test_docs_links.py
git commit -m "docs(tui): document k3s selection sources"
```

### Task 5: Final verification and manual smoke

**Files:**
- No new product files

**Step 1: Run the targeted automated verification**

Run:

```bash
cd tools/controlplane
uv run pytest tests/test_tui_choices.py tests/test_docs_links.py tests/test_e2e_commands.py -v
```

Expected:
- TUI choice tests pass
- docs-link tests pass
- existing CLI selection tests stay green

**Step 2: Run one manual TUI smoke check**

Run:

```bash
./scripts/controlplane.sh tui
```

Navigate:
1. `Validation`
2. `platform`
3. `k3s-junit-curl — self-bootstrapping VM stack with curl + JUnit verification`
4. enter VM name
5. choose runtime
6. choose cleanup mode
7. choose `Scenario file`
8. choose `tools/controlplane/scenarios/k8s-demo-javascript.toml`
9. choose `Dry-run`

Expected:
- the dry-run plan resolves JavaScript functions instead of `word-stats-java` / `json-transform-java`
- the workflow summary shows `Selection source: scenario-file`
- the workflow summary shows the chosen scenario manifest path

**Step 3: Confirm scope**

Run:

```bash
git diff --stat
```

Expected:
- changes limited to `tools/controlplane/src/controlplane_tool/tui_app.py`
- `tools/controlplane/tests/test_tui_choices.py`
- docs + docs tests only

**Step 4: Commit verification-only follow-ups if needed**

```bash
git add -A
git commit -m "test(tui): verify k3s selection flow"
```

## Out of Scope

- creating or editing new scenario TOML files directly from the TUI
- changing CLI semantics in `scripts/controlplane.sh e2e run ...`
- changing `helm-stack` selection behavior in this task
- changing `cli-stack` TUI behavior in this task

## Assumptions

- The initial scope is only `Validation -> platform -> k3s-junit-curl`.
- Existing scenario manifests remain the source of truth; the TUI only reuses them.
- The built-in default for bare `k3s-junit-curl` remains `demo-java` unless the user explicitly picks another source.
