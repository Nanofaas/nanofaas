# V2 Controlplane and E2E Integration Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make JavaScript demo functions first-class in `tools/controlplane` catalogs, saved profiles, and at least one VM-backed validation flow without breaking the existing meaning of control-plane `runtime`.

**Architecture:** In `tools/controlplane`, `runtime` on `E2eRequest`, `CliTestRequest`, and scenario TOML still means the control-plane implementation (`java` or `rust`), not the selected function runtime. JavaScript support therefore belongs in `FunctionRuntimeKind`, the function catalog/presets, scenario/profile fixtures, and every generic function-image builder that already branches for `java`, `java-lite`, `go`, `python`, and `exec`. Keep Helm/loadtest expansion and build/release automation out of this plan unless a concrete blocker forces follow-up work.

**Tech Stack:** Python + Typer + Pydantic in `tools/controlplane/`, pytest, TOML scenario/profile assets, Docker/Buildx, Gradle, `nanofaas-cli`, Multipass/k3s dry-run and live E2E commands.

---

## Scope Guardrails

- Do **not** add `"javascript"` to `RuntimeKind` in `tools/controlplane/src/controlplane_tool/models.py`; that type models the control-plane implementation, not function selection.
- Do **not** add JavaScript to `demo-loadtest` or `helm-stack` in this plan until JavaScript metrics/loadtest compatibility is explicitly proven.
- Do **not** extend `scripts/build-push-images.sh`, `scripts/release-manager/release.py`, or npm publishing here; that work belongs to `docs/plans/2026-04-21-v2-packaging-and-release.md`.
- Reuse the existing JavaScript examples under `examples/javascript/`; do not create duplicate demo functions.

### Task 0: Preflight and scope lock

**Files:**
- Inspect: `docs/plans/2026-04-21-v2-packaging-and-release.md`
- Inspect: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Inspect: `tools/controlplane/src/controlplane_tool/scenario_components/images.py`
- Inspect: `tools/controlplane/src/controlplane_tool/scenario_tasks.py`
- Inspect: `tools/controlplane/src/controlplane_tool/container_local_runner.py`

**Step 1: Confirm the control-plane/runtime split**

Run these discovery calls before editing any shared builder symbol:

```text
gitnexus_query(query="tools controlplane javascript presets scenario profiles image builders")
gitnexus_impact(target="function_image_specs", direction="upstream")
gitnexus_impact(target="build_function_image_vm_script", direction="upstream")
gitnexus_impact(target="ContainerLocalE2eRunner", direction="upstream")
```

**Step 2: Write down the boundary in scratch notes**

Use this exact note so the implementation does not drift:

```text
`RuntimeKind` stays `java|rust`.
JavaScript enters through `FunctionRuntimeKind`, preset selection, scenario/profile fixtures, and Dockerfile-based function image builders.
Packaging/release automation stays in docs/plans/2026-04-21-v2-packaging-and-release.md.
```

**Step 3: No code change in this task**

Once the boundary is explicit, move straight to Task 1.

### Task 1: Add JavaScript to the controlplane function catalog

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/models.py`
- Modify: `tools/controlplane/src/controlplane_tool/function_catalog.py`
- Test: `tools/controlplane/tests/test_function_catalog.py`
- Test: `tools/controlplane/tests/test_function_commands.py`

**Step 1: Write the failing catalog tests**

Add the new assertions first:

```python
def test_function_catalog_exposes_javascript_demo_functions() -> None:
    keys = [function.key for function in list_functions()]
    assert "word-stats-javascript" in keys
    assert "json-transform-javascript" in keys


def test_demo_javascript_preset_contains_only_javascript_functions() -> None:
    preset = resolve_function_preset("demo-javascript")
    assert {function.runtime for function in preset.functions} == {"javascript"}
    assert [function.key for function in preset.functions] == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]


def test_demo_loadtest_preset_still_excludes_javascript_functions() -> None:
    preset = resolve_function_preset("demo-loadtest")
    assert "javascript" not in {function.runtime for function in preset.functions}
```

Add one CLI-facing assertion too:

```python
def test_functions_show_preset_renders_javascript_function_list() -> None:
    result = CliRunner().invoke(app, ["functions", "show-preset", "demo-javascript"])
    assert result.exit_code == 0
    assert "word-stats-javascript" in result.stdout
    assert "json-transform-javascript" in result.stdout
```

**Step 2: Run the targeted tests and verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_function_commands.py -q
```

Expected:
- FAIL because `demo-javascript` does not exist yet
- FAIL because `FunctionRuntimeKind` rejects `"javascript"`

**Step 3: Implement the minimal catalog changes**

Update `tools/controlplane/src/controlplane_tool/models.py`:

```python
FunctionRuntimeKind = Literal[
    "java",
    "java-lite",
    "go",
    "python",
    "exec",
    "javascript",
    "fixture",
]
```

Update `tools/controlplane/src/controlplane_tool/function_catalog.py` with two new definitions and one new preset:

```python
FunctionDefinition(
    key="word-stats-javascript",
    family="word-stats",
    runtime="javascript",
    description="Node.js JavaScript word statistics demo.",
    example_dir=_example_dir("javascript", "word-stats"),
    default_image="localhost:5000/nanofaas/javascript-word-stats:e2e",
    default_payload_file="word-stats-sample.json",
),
FunctionDefinition(
    key="json-transform-javascript",
    family="json-transform",
    runtime="javascript",
    description="Node.js JavaScript JSON transform demo.",
    example_dir=_example_dir("javascript", "json-transform"),
    default_image="localhost:5000/nanofaas/javascript-json-transform:e2e",
    default_payload_file="json-transform-sample.json",
),
```

Add:

```python
_preset(
    "demo-javascript",
    "Node.js JavaScript demo functions.",
    ("word-stats-javascript", "json-transform-javascript"),
),
```

Also append the JavaScript keys to `demo-all`, but leave `demo-loadtest` unchanged.

**Step 4: Re-run the targeted tests and one CLI smoke command**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_function_commands.py -q

./scripts/controlplane.sh functions show-preset demo-javascript
```

Expected:
- pytest PASS
- CLI output lists both JavaScript functions and their example metadata

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/models.py \
  tools/controlplane/src/controlplane_tool/function_catalog.py \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_function_commands.py
git commit -m "feat(controlplane): add javascript function catalog"
```

### Task 2: Add JavaScript saved-profile and scenario fixtures

**Files:**
- Create: `tools/controlplane/profiles/demo-javascript.toml`
- Create: `tools/controlplane/scenarios/k8s-demo-javascript.toml`
- Test: `tools/controlplane/tests/test_profiles.py`
- Test: `tools/controlplane/tests/test_scenario_loader.py`
- Test: `tools/controlplane/tests/test_cli_smoke.py`
- Test: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Write the failing fixture and asset tests**

Add one loader test:

```python
def test_loader_resolves_javascript_scenario_manifest() -> None:
    scenario = load_scenario_file(Path("tools/controlplane/scenarios/k8s-demo-javascript.toml"))
    assert scenario.base_scenario == "k3s-junit-curl"
    assert scenario.function_preset == "demo-javascript"
    assert scenario.function_keys == [
        "word-stats-javascript",
        "json-transform-javascript",
    ]
```

Add one saved-profile roundtrip:

```python
def test_profile_roundtrip_with_javascript_e2e_selection(tmp_path: Path) -> None:
    profile = Profile(
        name="demo-javascript",
        control_plane=ControlPlaneConfig(implementation="java", build_mode="native"),
        scenario=ScenarioSelectionConfig(
            base_scenario="k3s-junit-curl",
            function_preset="demo-javascript",
            namespace="nanofaas-e2e",
        ),
        cli_test=CliTestConfig(default_scenario="cli-stack"),
    )
    save_profile(profile, root=tmp_path)
    loaded = load_profile("demo-javascript", root=tmp_path)
    assert loaded.cli_test.default_scenario == "cli-stack"
```

Add asset existence checks:

```python
def test_demo_javascript_profile_exists() -> None:
    assert resolve_workspace_path(Path("tools/controlplane/profiles/demo-javascript.toml")).exists()
```

**Step 2: Run the targeted tests and verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_wrapper_docs.py -q
```

Expected:
- FAIL because `demo-javascript.toml` and `k8s-demo-javascript.toml` do not exist
- FAIL because the new fixture paths cannot be loaded from the repository workspace

**Step 3: Create the fixture files**

Create `tools/controlplane/profiles/demo-javascript.toml`:

```toml
name = "demo-javascript"

[control_plane]
implementation = "java"
build_mode = "native"

[scenario]
base_scenario = "k3s-junit-curl"
function_preset = "demo-javascript"
namespace = "nanofaas-e2e"

[cli_test]
default_scenario = "cli-stack"
```

Create `tools/controlplane/scenarios/k8s-demo-javascript.toml`:

```toml
name = "k8s-demo-javascript"
base_scenario = "k3s-junit-curl"
runtime = "java"
function_preset = "demo-javascript"
namespace = "nanofaas-e2e"
local_registry = "localhost:5000"

[invoke]
mode = "smoke"
payload_dir = "payloads"

[payloads]
word-stats-javascript = "word-stats-sample.json"
json-transform-javascript = "json-transform-sample.json"
```

Keep `cli-stack` as the saved-profile default CLI scenario because that flow already builds selected function images in-VM.

**Step 4: Re-run the targeted tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_wrapper_docs.py -q
```

Expected:
- pytest PASS
- the new scenario/profile fixtures load and round-trip through tests
- do **not** expect `k3s-junit-curl` or `cli-stack` dry-run to pass yet; recipe-based builders still reject `javascript` until Task 3

**Step 5: Commit**

```bash
git add \
  tools/controlplane/profiles/demo-javascript.toml \
  tools/controlplane/scenarios/k8s-demo-javascript.toml \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_wrapper_docs.py
git commit -m "feat(controlplane): add javascript e2e fixtures"
```

### Task 3: Teach the generic builders how to build JavaScript images

**Files:**
- Modify: `tools/controlplane/src/controlplane_tool/scenario_components/images.py`
- Modify: `tools/controlplane/src/controlplane_tool/scenario_tasks.py`
- Modify: `tools/controlplane/src/controlplane_tool/container_local_runner.py`
- Test: `tools/controlplane/tests/test_scenario_component_library.py`
- Test: `tools/controlplane/tests/test_scenario_tasks.py`
- Test: `tools/controlplane/tests/test_cli_runtime.py`
- Test: `tools/controlplane/tests/test_e2e_commands.py`
- Test: `tools/controlplane/tests/test_cli_test_commands.py`

**Step 1: Write the failing builder tests**

Add a recipe-builder test:

```python
def test_image_component_planners_support_javascript_selected_functions() -> None:
    resolved_scenario = ResolvedScenario(
        name="demo-javascript",
        base_scenario="cli-stack",
        runtime="java",
        functions=[
            ResolvedFunction(
                key="word-stats-javascript",
                family="word-stats",
                runtime="javascript",
                description="JS demo",
            )
        ],
        function_keys=["word-stats-javascript"],
    )
    context = _managed_context(scenario="cli-stack", resolved_scenario=resolved_scenario)
    selected_operations = images.plan_build_selected_functions(context)
    assert any(
        "examples/javascript/word-stats/Dockerfile" in " ".join(operation.argv)
        for operation in selected_operations
    )
```

Add a VM script test:

```python
def test_build_function_images_vm_script_supports_javascript_dockerfiles() -> None:
    script = build_function_images_vm_script(
        remote_dir="/srv/nanofaas",
        functions=[("localhost:5000/nanofaas/javascript-word-stats:e2e", "javascript", "word-stats")],
    )
    assert "examples/javascript/word-stats/Dockerfile" in script
```

Add a container-local branch test:

```python
def test_container_local_runner_builds_javascript_function_images(tmp_path: Path, monkeypatch) -> None:
    runner = ContainerLocalE2eRunner(tmp_path)
    called = {}

    def fake_run(command, check=True):  # noqa: ANN001
        called["command"] = command
        return ShellExecutionResult(command=command, return_code=0)

    monkeypatch.setattr(runner, "_run", fake_run)
    runner._build_function_image("example/image:tag", "javascript", "word-stats")

    assert called["command"] == [
        "docker",
        "build",
        "-t",
        "example/image:tag",
        "-f",
        "examples/javascript/word-stats/Dockerfile",
        ".",
    ]
```

Add the recipe-backed command-resolution tests here, after the builder support exists:

```python
def test_e2e_run_dry_run_accepts_demo_javascript_preset() -> None:
    result = CliRunner().invoke(
        app,
        ["e2e", "run", "k3s-junit-curl", "--function-preset", "demo-javascript", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "word-stats-javascript" in result.stdout


def test_cli_test_run_saved_profile_demo_javascript_defaults_to_cli_stack() -> None:
    result = CliRunner().invoke(
        app,
        ["cli-test", "run", "--saved-profile", "demo-javascript", "--dry-run"],
    )
    assert result.exit_code == 0
    assert "Scenario: cli-stack" in result.stdout


def test_helm_stack_rejects_javascript_selection_before_backend() -> None:
    result = CliRunner().invoke(
        app,
        ["e2e", "run", "helm-stack", "--functions", "word-stats-javascript", "--dry-run"],
    )
    assert result.exit_code == 2
    assert "javascript" in (result.stdout + result.stderr)
```

**Step 2: Run the targeted tests and verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_scenario_tasks.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_cli_test_commands.py -q
```

Expected:
- FAIL with `Unsupported function runtime: 'javascript'`
- FAIL because the Dockerfile path is not recognized yet

**Step 3: Implement the minimal builder support**

In `tools/controlplane/src/controlplane_tool/scenario_components/images.py`, extend `_dockerfile_for_runtime_kind`:

```python
dockerfile_map = {
    "exec": Path(f"examples/bash/{family}/Dockerfile"),
    "go": Path(f"examples/go/{family}/Dockerfile"),
    "java-lite": Path(f"examples/java/{family}-lite/Dockerfile"),
    "python": Path(f"examples/python/{family}/Dockerfile"),
    "javascript": Path(f"examples/javascript/{family}/Dockerfile"),
}
```

Apply the same mapping in `tools/controlplane/src/controlplane_tool/scenario_tasks.py`.

In `tools/controlplane/src/controlplane_tool/container_local_runner.py`, add one more branch:

```python
elif runtime_kind == "javascript":
    self._run([adapter, "build", "-t", image, "-f", f"examples/javascript/{family}/Dockerfile", "."])
```

Do not touch `deploy_host_runner.py`; it already reuses `example_dir` and `function.yaml` generation generically.

**Step 4: Re-run the targeted tests and the recipe-backed dry-run commands**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_scenario_tasks.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_cli_test_commands.py -q

./scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run
./scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-javascript.toml --dry-run
./scripts/controlplane.sh cli-test run --saved-profile demo-javascript --dry-run
```

Expected:
- pytest PASS
- dry-run output no longer contains any `Unsupported function runtime`
- the `k3s-junit-curl` and `cli-stack` planners now accept JavaScript selections from both scenario-file and saved-profile entrypoints
- the targeted unit tests remain the source of truth for the `container-local` JavaScript builder branch, because `container-local --dry-run` only prints the wrapper step and does not execute `_build_function_image`

**Step 5: Commit**

```bash
git add \
  tools/controlplane/src/controlplane_tool/scenario_components/images.py \
  tools/controlplane/src/controlplane_tool/scenario_tasks.py \
  tools/controlplane/src/controlplane_tool/container_local_runner.py \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_scenario_tasks.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_cli_test_commands.py
git commit -m "feat(controlplane): build javascript demo images in e2e flows"
```

### Task 4: Update the docs so JavaScript is presented as a supported controlplane option

**Files:**
- Modify: `README.md`
- Modify: `docs/testing.md`
- Modify: `docs/tutorial-function.md`
- Modify: `tools/controlplane/README.md`
- Test: `tools/controlplane/tests/test_docs_links.py`
- Test: `tools/controlplane/tests/test_cli_smoke.py`
- Test: `tools/controlplane/tests/test_wrapper_docs.py`

**Step 1: Write the failing docs assertions**

Extend `tools/controlplane/tests/test_docs_links.py` with command-level assertions:

```python
assert "scripts/controlplane.sh functions show-preset demo-javascript" in tool_readme
assert "scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run" in tool_readme
assert "scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run" in tool_readme
assert "scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run" in testing
assert "scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run" in testing
assert "scripts/controlplane.sh functions show-preset demo-javascript" in root_readme
```

Also add one profile-fixture assertion in `tools/controlplane/tests/test_wrapper_docs.py`:

```python
def test_javascript_profile_fixture_exists_for_saved_profile_flow() -> None:
    profile_path = resolve_workspace_path(Path("tools/controlplane/profiles/demo-javascript.toml"))
    assert profile_path.exists()
    profile = profile_path.read_text(encoding="utf-8")
    assert 'default_scenario = "cli-stack"' in profile
```

**Step 2: Run the docs tests and verify they fail**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_wrapper_docs.py -q
```

Expected:
- FAIL because the docs do not mention `demo-javascript` yet

**Step 3: Update the docs**

In `tools/controlplane/README.md`, add JavaScript examples to the recommended commands section:

```markdown
scripts/controlplane.sh functions show-preset demo-javascript
scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run
scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run
```

In `README.md` and `docs/testing.md`, replace the old v1-only limitation with language like:

```markdown
JavaScript function authoring remains first-class under `function-sdk-javascript/` and `examples/javascript/`.
V2 also wires JavaScript into `tools/controlplane` catalogs, saved profiles, and VM-backed dry-run/E2E flows such as `k3s-junit-curl` and `cli-stack`.
Build/publish automation remains tracked separately in `docs/plans/2026-04-21-v2-packaging-and-release.md`.
```

In `docs/tutorial-function.md`, replace the “JavaScript v1 currently covers SDK/examples/`fn-init` only” bullet with a next step that points readers to the new controlplane commands instead of repeating the obsolete limitation.

**Step 4: Re-run the docs tests**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_wrapper_docs.py -q
```

Expected:
- pytest PASS
- all docs consistently describe `demo-javascript` as available in controlplane flows

**Step 5: Commit**

```bash
git add \
  README.md \
  docs/testing.md \
  docs/tutorial-function.md \
  tools/controlplane/README.md \
  tools/controlplane/tests/test_docs_links.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_wrapper_docs.py
git commit -m "docs(controlplane): document javascript e2e support"
```

### Task 5: Run the regression sweep and live-proof the happy path

**Files:**
- Test: `tools/controlplane/tests/test_function_catalog.py`
- Test: `tools/controlplane/tests/test_function_commands.py`
- Test: `tools/controlplane/tests/test_profiles.py`
- Test: `tools/controlplane/tests/test_scenario_loader.py`
- Test: `tools/controlplane/tests/test_e2e_commands.py`
- Test: `tools/controlplane/tests/test_cli_test_commands.py`
- Test: `tools/controlplane/tests/test_cli_smoke.py`
- Test: `tools/controlplane/tests/test_wrapper_docs.py`
- Test: `tools/controlplane/tests/test_scenario_component_library.py`
- Test: `tools/controlplane/tests/test_scenario_tasks.py`
- Test: `tools/controlplane/tests/test_cli_runtime.py`
- Test: `tools/controlplane/tests/test_docs_links.py`

**Step 1: Run the full targeted pytest sweep**

Run:

```bash
uv run --project tools/controlplane pytest \
  tools/controlplane/tests/test_function_catalog.py \
  tools/controlplane/tests/test_function_commands.py \
  tools/controlplane/tests/test_profiles.py \
  tools/controlplane/tests/test_scenario_loader.py \
  tools/controlplane/tests/test_e2e_commands.py \
  tools/controlplane/tests/test_cli_test_commands.py \
  tools/controlplane/tests/test_cli_smoke.py \
  tools/controlplane/tests/test_wrapper_docs.py \
  tools/controlplane/tests/test_scenario_component_library.py \
  tools/controlplane/tests/test_scenario_tasks.py \
  tools/controlplane/tests/test_cli_runtime.py \
  tools/controlplane/tests/test_docs_links.py -q
```

Expected:
- PASS across the entire JavaScript integration surface

**Step 2: Run the dry-run command matrix**

Run:

```bash
./scripts/controlplane.sh functions show-preset demo-javascript
./scripts/controlplane.sh e2e run k3s-junit-curl --function-preset demo-javascript --dry-run
./scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-javascript.toml --dry-run
./scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript --dry-run
```

Expected:
- every command resolves JavaScript functions correctly
- dry-run output shows `examples/javascript/.../Dockerfile` wherever images are built
- no command reports `Unsupported function runtime: 'javascript'`
- `container-local` remains covered by the targeted runner/unit tests rather than this dry-run matrix

**Step 3: Run the canonical real VM-backed proof if infrastructure is available**

Preferred proof command:

```bash
./scripts/controlplane.sh cli-test run cli-stack --saved-profile demo-javascript
```

Expected:
- VM bootstrap completes
- selected JavaScript images build and push to the local registry
- control-plane deployment becomes healthy
- the `nanofaas-cli` lifecycle (`fn apply`, `fn list`, `invoke`, `enqueue`, `fn delete`) completes for both JavaScript functions

**Step 4: Run the scenario-manifest proof if the same infrastructure is still available**

Run:

```bash
./scripts/controlplane.sh e2e run --scenario-file tools/controlplane/scenarios/k8s-demo-javascript.toml
```

Expected:
- the `k3s-junit-curl` validation path completes for both JavaScript functions
- the new scenario manifest is proven in a real VM-backed flow, not only in dry-run

**Step 5: If either live proof cannot run, record the reason explicitly**

Allowed reasons:
- no Docker-compatible runtime
- no Multipass / inaccessible external VM
- network or registry restrictions

Do not claim end-to-end success without the command output.

**Step 6: Capture the final diff scope**

Run:

```bash
git status --short
git diff --stat HEAD~4..HEAD
```

Expected:
- only controlplane catalog/fixture/builder/docs files changed
- no accidental packaging/release automation drift

**Step 7: No extra commit here unless verification forces a final patch**

If verification is clean, stop here.

## Expected End State

- `tools/controlplane` exposes `demo-javascript` alongside the existing presets.
- `tools/controlplane/scenarios/k8s-demo-javascript.toml` and `tools/controlplane/profiles/demo-javascript.toml` exist and drive dry-run flows.
- VM-backed scenario planning and local container-local image building understand `examples/javascript/<family>/Dockerfile`.
- `cli-stack` is the canonical saved-profile-backed JavaScript VM validation path and is proven with one real CLI-stack run before the work is closed.
- `helm-stack`/`demo-loadtest` remain unchanged and explicitly reject JavaScript until a separate compatibility proof is implemented.
- Docs stop describing controlplane JavaScript support as “v1 intentionally unsupported”.
