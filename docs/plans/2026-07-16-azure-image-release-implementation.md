# Azure Image Release Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Build, benchmark, and publish the complete AMD64/ARM64 NanoFaaS image matrix from a controlled Azure run, with AMD64 performance gating before QEMU ARM64 work and GHCR publication.

**Architecture:** Restore the deleted image-matrix behavior behind `controlplane-tool images`, but express commands with the current `workflow_tasks.CommandTaskSpec` and role bindings instead of restoring the removed `shellcraft` stack. A separate `controlplane-tool release` layer prepares versions and composes the existing Azure provisioning, local registry, load-test, metrics, QEMU, and GHCR promotion capabilities. Host, Multipass, and Proxmox can build experimental images; only a pinned Azure release environment can promote GHCR tags.

**Tech Stack:** Python 3.11+, Typer, Pydantic, `workflow-tasks`, Docker Buildx/BuildKit, QEMU/binfmt, Spring Boot Buildpacks, k3s, k6, Prometheus, Azure VMs, GHCR, skopeo, syft, cosign.

---

## Execution protocol

- Execute in the existing `codex/azure-image-release-plan` worktree or create a fresh implementation worktree from its merged commit.
- Use `superpowers:subagent-driven-development` in the current session, with one implementation agent and one review pass per task.
- The subagent API does not expose model selection. Treat the difficulty labels below as routing/review guidance; do not claim that a different model was selected when the runtime cannot select one.
- Every implementation agent must read this plan and the approved design at `docs/plans/2026-07-16-azure-image-release-design.md`.
- Before editing a listed existing symbol, rerun `gitnexus_impact(..., direction="upstream")`. Warn before proceeding on HIGH or CRITICAL results.
- Before every commit, run `gitnexus_detect_changes(scope="staged")` and review all direct dependants.
- After every commit, run `npx gitnexus analyze`; preserve embeddings if `.gitnexus/meta.json` reports a non-zero embedding count.

## Known high-risk seams

GitNexus currently reports HIGH risk for `build_loadtest_plan`, `ValidateWorkflowRequest`, and `k8s_deployment_specs`. Their changes below must be additive and default-compatible. The ordinary `run`, `plan`, TUI load-test path, and validation workflows must retain their current task lists when no prebuilt image set is supplied.

## Final command surface

```bash
# Portable, non-publishing operations
controlplane-tool images plan --version v0.18.0 --arch all --flavor all
controlplane-tool images build --version v0.18.0 --arch amd64 --environment environments/multipass.yaml

# Version preparation on the host; review and commit before release
controlplane-tool release prepare v0.18.0

# Authoritative release; only this command can write GHCR release tags
controlplane-tool release plan v0.18.0 --environment environments/azure-release.yaml
controlplane-tool release run v0.18.0 --environment environments/azure-release.yaml --provision
```

### Task 1: Add deterministic version preparation

**Difficulty:** Medium
**Commit:** `Add release version preparation`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/release/versioning.py`
- Create: `tools/controlplane/tests/release/test_versioning.py`

Release-time outputs of this command, not files to change while implementing the
helper: `build.gradle`, `deploy/helm/nanofaas/Chart.yaml`,
`deploy/helm/nanofaas/values.yaml`, `deploy/k8s/control-plane-deployment.yaml`,
`runtimes/watchdog/Cargo.toml`, `runtimes/watchdog/Cargo.lock`,
`sdks/python/pyproject.toml`, `sdks/python/uv.lock`,
`functions/python/roman-numeral/uv.lock`, `tools/fn-init/src/fn_init/main.py`, and
`clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java`.

**Step 1: Write failing version tests**

Create a temporary repository fixture containing every authoritative version form and assert:

```python
def test_prepare_version_updates_all_authoritative_files(version_repo: Path) -> None:
    changed = prepare_version(version_repo, "v0.18.0")
    assert changed == EXPECTED_VERSION_FILES
    assert read_project_version(version_repo) == "0.18.0"
    assert "v0.18.0" in (version_repo / "deploy/helm/nanofaas/values.yaml").read_text()


def test_prepare_version_rejects_partial_old_version_state(version_repo: Path) -> None:
    chart = version_repo / "deploy/helm/nanofaas/Chart.yaml"
    chart.write_text(chart.read_text().replace("0.17.0", "0.16.0"))
    with pytest.raises(ValueError, match="version files disagree"):
        prepare_version(version_repo, "v0.18.0")


def test_prepare_version_rejects_invalid_or_non_incrementing_version(version_repo: Path) -> None:
    with pytest.raises(ValueError):
        prepare_version(version_repo, "latest")
    with pytest.raises(ValueError):
        prepare_version(version_repo, "v0.17.0")
```

**Step 2: Run the tests and confirm failure**

Run: `uv run pytest -q tests/release/test_versioning.py` from `tools/controlplane`
Expected: FAIL because `controlplane_tool.release.versioning` does not exist.

**Step 3: Implement the minimal version helper**

Use one strict semantic-version parser and a curated mapping of exact replacements. Do not parse and re-emit YAML. The public API is:

```python
VERSION_RE = re.compile(r"^v?(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)$")

def normalize_version(value: str) -> tuple[str, str]:
    """Return (`0.18.0`, `v0.18.0`) or raise ValueError."""

def read_project_version(repo_root: Path) -> str:
    """Read the single Gradle project version."""

def verify_version_consistency(repo_root: Path) -> str:
    """Require every curated file to contain the same project version."""

def prepare_version(repo_root: Path, requested: str) -> tuple[Path, ...]:
    """Validate first, then replace all files; never leave a partial update."""
```

Build all rewritten contents in memory, validate replacement counts, and only then write files. Update lockfiles mechanically in this function; the task's verification step will regenerate and compare them with Cargo/uv.

**Step 4: Run focused verification**

Run: `uv run pytest -q tests/release/test_versioning.py` from `tools/controlplane`
Expected: PASS.

Run: `./gradlew verifyHelmVersionSync :nanofaas-cli:test`
Expected: PASS with the repository's unchanged current version.

Run: `uv lock --check` from `sdks/python` and `uv lock --check` from `functions/python/roman-numeral`
Expected: both PASS.

**Step 5: Stage, inspect, and commit**

Stage only the new helper, its tests, and the CLI version assertion. Run GitNexus staged change detection, then commit.

### Task 2: Restore and modernize the image matrix

**Difficulty:** High
**Commit:** `Restore portable image matrix planning`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/images/__init__.py`
- Create: `tools/controlplane/src/controlplane_tool/images/plan.py`
- Create: `tools/controlplane/tests/images/test_plan.py`
- Modify: `functions/java/roman-numeral/build.gradle`
- Test: `tools/controlplane/tests/test_function_catalog.py`

**Step 1: Inspect the recoverable implementation**

Read, but do not restore verbatim:

```bash
git show ee47d9a2^:tools/controlplane/src/controlplane_tool/building/image_plan.py
git show ee47d9a2^:tools/controlplane/tests/test_image_plan.py
```

Preserve its tag and flavor semantics. Replace `shellcraft.PlannedCommand` with the current `CommandTaskSpec`. Build targets must come from `list_functions()` so `roman-numeral` and future manifest-backed functions cannot silently disappear.

**Step 2: Write failing catalog and matrix tests**

Test the complete current catalog:

```python
def test_all_images_expand_to_current_52_cell_matrix(repo_root: Path) -> None:
    plan = plan_image_matrix(repo_root, version="v0.18.0", arches=("amd64", "arm64"), flavors=("jvm", "native"))
    assert len(plan.cells) == 52
    assert {cell.target for cell in plan.cells} == {
        "control-plane", "java-warm-echo", "watchdog",
        *expected_function_image_names(),
    }


def test_release_tags_encode_architecture_and_flavor() -> None:
    assert image_tag("v0.18.0", "amd64", "native") == "v0.18.0-amd64-native"
    assert image_tag("v0.18.0", "arm64", "default") == "v0.18.0-arm64"


def test_amd64_cells_precede_arm64_cells() -> None:
    plan = plan_image_matrix(...)
    arches = [cell.arch for cell in plan.cells]
    assert arches == sorted(arches, key=("amd64", "arm64").index)
```

Also assert that fixture functions without images are ignored, every discovered Dockerfile is represented, Java functions have JVM/native cells, Java Lite has native cells, and other languages use the default flavor.

**Step 3: Run the focused tests and confirm failure**

Run: `uv run pytest -q tests/images/test_plan.py tests/test_function_catalog.py` from `tools/controlplane`
Expected: FAIL because the matrix module is absent and Roman Numeral lacks native build configuration.

**Step 4: Implement the matrix model**

Keep the public model small:

```python
ImageArch = Literal["amd64", "arm64"]
ImageFlavor = Literal["jvm", "native", "default"]

@dataclass(frozen=True, slots=True)
class ImageCell:
    target: str
    arch: ImageArch
    flavor: ImageFlavor
    local_image: str
    build: CommandTaskSpec
    local_push: CommandTaskSpec

@dataclass(frozen=True, slots=True)
class ImagePlan:
    version: str
    cells: tuple[ImageCell, ...]
```

Dockerfile cells use single-platform `docker buildx build --load --platform linux/<arch>`. Native Spring cells reuse existing Gradle `bootBuildImage` tasks and `imagePlatform`; JVM Spring cells run `bootJar` followed by the existing Dockerfile. All tasks use role `stack` when an environment is supplied and `host` otherwise. The build registry is configurable but defaults to `localhost:5000/nanofaas` for remote work.

**Step 5: Add Roman Numeral native parity**

Bring `functions/java/roman-numeral/build.gradle` in line with word-stats/json-transform: add the GraalVM plugin and the same configurable `bootBuildImage` block, using `roman-numeral.jar` and `functionImage`. Do not introduce a shared Gradle plugin in this task.

**Step 6: Verify matrix and Gradle configuration**

Run: `uv run pytest -q tests/images/test_plan.py tests/test_function_catalog.py`
Expected: PASS.

Run: `./gradlew :functions:java:roman-numeral:tasks --all`
Expected: output contains `bootBuildImage` and `nativeCompile`.

**Step 7: Commit after impact review**

Rerun impact analysis for `list_functions` and the Roman Gradle project before editing. Run staged change detection and commit.

### Task 3: Restore `controlplane-tool images` as a portable command group

**Difficulty:** Medium
**Commit:** `Add image matrix commands`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/cli/images.py`
- Create: `tools/controlplane/tests/cli/test_images_command.py`
- Modify: `tools/controlplane/src/controlplane_tool/app/main.py:5-25`

**Step 1: Write failing CLI tests**

```python
def test_images_plan_lists_cells_without_running_commands() -> None:
    result = runner.invoke(app, ["images", "plan", "--version", "v0.18.0", "--only", "watchdog"])
    assert result.exit_code == 0
    assert "watchdog:v0.18.0-amd64" in result.stdout
    assert "watchdog:v0.18.0-arm64" in result.stdout


def test_images_build_defaults_to_no_external_publication(monkeypatch) -> None:
    result = runner.invoke(app, ["images", "build", "--version", "v0.18.0", "--dry-run"])
    assert result.exit_code == 0
    assert "ghcr.io" not in result.stdout
    assert "docker push ghcr.io" not in result.stdout
```

Test `--arch`, `--flavor`, `--only`, unknown targets, environment loading, dry-run output, and failure propagation. Assert there is no GHCR push option on the portable command.

**Step 2: Run and confirm failure**

Run: `uv run pytest -q tests/cli/test_images_command.py` from `tools/controlplane`
Expected: FAIL because the `images` group is absent.

**Step 3: Implement the group**

Expose a Typer sub-app with only `plan` and `build`. `plan` renders `CommandTaskSpec` values. `build` loads the optional environment, creates role bindings through `build_role_bindings`, converts the matrix specs with `workflow_from_specs`, and runs the workflow. Local-registry pushes are explicit and allowed; GHCR references are rejected here.

Register the sub-app in `app/main.py`:

```python
from controlplane_tool.cli.images import images_app
app.add_typer(images_app, name="images")
```

**Step 4: Verify CLI behavior**

Run: `uv run pytest -q tests/cli/test_images_command.py tests/cli/test_command_surface.py`
Expected: PASS.

Run: `uv run controlplane-tool images plan --version v0.18.0 --only control-plane --arch amd64 --flavor all`
Expected: JVM and native AMD64 commands, no execution.

**Step 5: Commit**

Run impact analysis for `app/main.py` command registration and staged change detection, then commit.

### Task 4: Deploy exact prebuilt images through the existing load-test workflow

**Difficulty:** High — HIGH GitNexus seam
**Commit:** `Allow load tests to use prebuilt images`

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/validate.py:20-270`
- Modify: `tools/controlplane/src/controlplane_tool/plans/loadtest.py:25-120`
- Modify: `tools/workflow-tasks/tests/workflows/test_validate.py`
- Modify: `tools/controlplane/tests/plans/test_loadtest.py`

**Step 1: Rerun and report impact analysis**

Rerun upstream impact for `ValidateWorkflowRequest`, `k8s_deployment_specs`, and `build_loadtest_plan`. Confirm the known HIGH risk and list all d=1 callers before editing.

**Step 2: Write backward-compatibility and prebuilt-image tests**

```python
def test_k8s_prebuilt_mode_skips_every_build_and_uses_exact_images() -> None:
    request = ValidateWorkflowRequest(
        backend="k8s",
        functions=(function(image="localhost:5000/nanofaas/java-word-stats:v0.18.0-amd64-native"),),
        build_images=False,
        control_plane_image="localhost:5000/nanofaas/control-plane:v0.18.0-amd64-native",
    )
    specs = k8s_deployment_specs(request)
    assert not any(spec.task_id.startswith(("build.", "images.build.", "images.push.")) for spec in specs)
    assert "v0.18.0-amd64-native" in " ".join(specs[-1].argv)


def test_default_k8s_task_list_is_unchanged() -> None:
    assert ids(k8s_deployment_specs(default_request())) == EXISTING_EXPECTED_IDS
```

In the controlplane tests, pass a prebuilt control-plane image plus a function-image mapping and assert that the load-test plan deploys/registers those exact references.

**Step 3: Run tests and confirm failure**

Run: `uv run pytest -q tests/workflows/test_validate.py` from `tools/workflow-tasks`
Run: `uv run pytest -q tests/plans/test_loadtest.py` from `tools/controlplane`
Expected: new tests FAIL; old tests PASS.

**Step 4: Add optional, default-compatible fields**

Add only:

```python
@dataclass(frozen=True, slots=True)
class ValidateWorkflowRequest:
    # existing fields...
    build_images: bool = True
    control_plane_image: str | None = None
```

Require `control_plane_image` when `build_images` is false. Wrap the current build/image-push block in `if request.build_images`; use the override in Helm values. Add optional keyword-only `prebuilt_control_plane_image` and `prebuilt_function_images` parameters to `build_loadtest_plan`; defaults preserve every current caller.

**Step 5: Run direct and transitive tests**

Run both focused suites above. Then run all 303 controlplane tests and all 439 workflow-tasks tests.
Expected: PASS with unchanged ordinary plan/TUI behavior.

**Step 6: Commit**

Run staged GitNexus detection and inspect the `plan_command` and `run_current_workflow` flows before committing.

### Task 5: Produce comparable three-run release metrics

**Difficulty:** High
**Commit:** `Add release performance aggregation`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/metrics.py`
- Create: `tools/controlplane/tests/release/test_metrics.py`
- Create: `tools/controlplane/release.yaml`
- Create: `docs/performance/history.md`
- Create: `docs/performance/releases/.gitkeep`
- Modify: `tools/workflow-tasks/src/workflow_tasks/loadtest/tasks.py:25-55`
- Modify: `tools/workflow-tasks/tests/loadtest/test_loadtest_tasks.py`

**Step 1: Make k6 export p99**

Write a failing assertion that `_build_k6_argv` contains:

```text
--summary-trend-stats avg,min,med,max,p(50),p(90),p(95),p(99)
```

Add those two arguments immediately after `--summary-export`. Rerun impact analysis for `_build_k6_argv` (currently LOW) and its direct tests.

**Step 2: Write failing aggregation tests**

Use three small `summary.json` fixtures and assert medians for:

- `http_reqs.values.rate` as throughput;
- `http_req_failed.values.rate` as error rate;
- `http_req_duration.values.p(50)`, `p(95)`, and `p(99)`;
- selected Prometheus point statistics;
- autoscaling peak replicas.

```python
def test_aggregate_release_runs_uses_per_metric_median(tmp_path: Path) -> None:
    record = aggregate_release_runs([run1, run2, run3], release_profile())
    assert record["metrics"]["throughput_rps"] == 120.0
    assert record["metrics"]["latency_ms"]["p95"] == 42.0


def test_regression_gate_compares_only_identical_profiles() -> None:
    with pytest.raises(ValueError, match="profile mismatch"):
        evaluate_regression(candidate, baseline_from_other_provider, policy)
```

**Step 3: Implement a strict release record**

`tools/controlplane/release.yaml` owns the policy, not Python constants:

```yaml
schemaVersion: 1
benchmark:
  scenario: scenarios-v2/loadtest.yaml
  runs: 3
  profile: azure-d4s-v5+d2s-v5-amd64-native-loadtest-v1
  regression:
    throughputMaxLossPercent: 10
    p95MaxIncreasePercent: 15
    errorRateMax: 0.30
```

The first version has no comparative baseline and must still pass the scenario's k6 thresholds. Later versions compare only with the latest record carrying the identical profile identifier. Write JSON atomically to `docs/performance/releases/<version>.json` and regenerate the compact Markdown table deterministically.

**Step 4: Verify**

Run focused metrics and load-test task tests. Expected: PASS.
Run `uv run ruff check src tests` from both Python packages. Expected: PASS.

**Step 5: Commit**

Run staged change detection and commit.

### Task 6: Compose the Azure release workflow and hard guard

**Difficulty:** Very high
**Commit:** `Add gated Azure release workflow`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/plan.py`
- Create: `tools/controlplane/tests/release/test_plan.py`
- Create: `tools/controlplane/src/controlplane_tool/cli/release.py`
- Create: `tools/controlplane/tests/cli/test_release_command.py`
- Modify: `tools/controlplane/src/controlplane_tool/app/main.py`

**Step 1: Write release-guard tests**

Assert rejection before provisioning or task creation for:

- local, Multipass, Proxmox, and generic external providers;
- Azure without both stack and loadgen roles;
- an Azure image URN ending in `:latest`;
- a burstable `Standard_B*` stack or loadgen size;
- a dirty repository;
- a requested version different from the prepared project version;
- a missing `~/.docker/config.json` GHCR login on the stack VM.

The guard's public contract is:

```python
def validate_release_context(
    repo_root: Path,
    version: str,
    environment: EnvironmentConfig,
    provenance: Mapping[str, object],
) -> None: ...
```

**Step 2: Write phase-order tests**

With recording executors, assert the exact high-level order:

```python
assert phases == [
    "release.preflight",
    "release.tests",
    "images.amd64",
    "benchmark.1", "benchmark.2", "benchmark.3",
    "metrics.aggregate", "metrics.gate",
    "qemu.prepare", "images.arm64", "images.arm64.smoke",
    "release.promote", "release.verify", "release.attest",
]
```

Inject a failure at each gate and assert no later phase runs. In particular, ARM64 and every GHCR operation must remain absent after a benchmark failure.

**Step 3: Implement source-test and QEMU preparation tasks**

Source tests run on the Azure stack VM after repository sync. Reuse Gradle and uv already installed by provisioning. Run Go, Node, Rust, and Bash tests in pinned Docker toolchain images rather than installing mutable host toolchains. QEMU preparation is idempotent:

```bash
docker run --privileged --rm tonistiigi/binfmt --install arm64
docker buildx inspect nanofaas-release >/dev/null 2>&1 || \
  docker buildx create --name nanofaas-release --driver docker-container --use
docker buildx inspect --bootstrap nanofaas-release
```

Verify `linux/arm64` appears in the builder platforms before starting ARM work.

**Step 4: Implement three isolated benchmark executions**

Inside one provisioned pinned Azure environment, call `build_loadtest_plan` three times with prebuilt AMD64-native references and run directories:

```text
tools/controlplane/runs/releases/v0.18.0/run-1
tools/controlplane/runs/releases/v0.18.0/run-2
tools/controlplane/runs/releases/v0.18.0/run-3
```

Let each existing workflow uninstall the Helm release and delete the function before the next run. Do not rebuild candidate images. Aggregate only after all three run summaries exist.

**Step 5: Implement ARM64 smoke tasks**

For every ARM64 image, first inspect its architecture. Start service/function images under `--platform linux/arm64`, wait for `/health` (control-plane uses its management health endpoint), then remove the container. For the watchdog scratch image, execute the binary and distinguish an expected missing-child error from an `exec format error`. Any missing cell or smoke failure stops promotion.

**Step 6: Add CLI commands**

`release prepare` delegates to Task 1. `release plan` renders all phases and never provisions. `release run` enters `provision_environment`, rebuilds role bindings after provisioning, binds the existing progress sink, writes release run metadata, and preserves logs on failure. It never commits, tags Git, or pushes source code.

**Step 7: Verify failure barriers**

Run release plan and CLI tests, then both complete Python suites. Expected: PASS.

**Step 8: Commit after review**

Request a dedicated correctness review of phase ordering and negative tests. Run staged change detection and commit.

### Task 7: Promote exact artifacts, manifests, aliases, SBOMs, and attestations

**Difficulty:** High
**Commit:** `Publish verified multi-architecture release images`

**Files:**
- Create: `tools/controlplane/src/controlplane_tool/release/publish.py`
- Create: `tools/controlplane/tests/release/test_publish.py`
- Modify: `tools/controlplane/src/controlplane_tool/release/plan.py`

**Step 1: Write publish-plan tests**

Assert that publication starts only after all matrix cells and gate evidence exist. For a flavor-aware image, expect:

```text
v0.18.0-amd64-native
v0.18.0-arm64-native
v0.18.0-native
v0.18.0
v0.18.0-amd64-jvm
v0.18.0-arm64-jvm
v0.18.0-jvm
```

For default images, expect architecture tags and `v0.18.0`. Assert `v0.18.0` references the same native descriptors as `v0.18.0-native`. Assert mutable aliases are planned last.

**Step 2: Preserve artifacts across registries**

Use `skopeo copy --preserve-digests --src-tls-verify=false` from the Azure-local registry to immutable GHCR architecture tags. Compare source and destination manifest digests before creating indexes. Never rebuild or use `docker tag && docker build` in this phase.

**Step 3: Create and verify indexes**

Use `docker buildx imagetools create` to create flavor/default manifests from immutable architecture tags. Inspect each index and require exactly `linux/amd64` and `linux/arm64`. Only then create `v0.18.0` native aliases. Treat a version as complete only after every expected manifest verifies; do not update `latest` in the first implementation.

**Step 4: Generate and attach supply-chain metadata**

Generate SPDX JSON with syft against each final digest. Create an in-toto release predicate containing source commit, Azure profile, benchmark record, and all digests. Attach SBOM and predicate with cosign using a pre-provisioned operator-controlled key path and password environment on the Azure stack VM. The tool validates their presence but never transports or logs private key material.

**Step 5: Verify with recording executors**

Run: `uv run pytest -q tests/release/test_publish.py tests/release/test_plan.py`
Expected: PASS, including digest mismatch, incomplete matrix, failed manifest, and missing signing credential cases.

**Step 6: Commit**

Run staged change detection and commit.

### Task 8: Pin the Azure release environment and remove GitHub publication

**Difficulty:** Medium
**Commit:** `Make Azure the only image release authority`

**Files:**
- Create: `tools/controlplane/environments/azure-release.yaml.example`
- Modify: `.github/workflows/gitops.yml`
- Modify: `tools/controlplane/tests/config/test_environment.py`
- Create: `scripts/tests/test_release_authority.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/infra/ansible_assets/playbooks/provision-base.yml`
- Modify: `tools/workflow-tasks/tests/infra/test_ansible.py`

**Step 1: Resolve and pin an Azure image URN**

Run:

```bash
az vm image list --location westeurope --publisher Canonical \
  --offer ubuntu-24_04-lts --sku server --all \
  --query "[-1].urn" -o tsv
```

Put the exact returned version in the example; never store `latest`. Configure:

```yaml
provider: azure
roles:
  stack:
    name: nanofaas-release-stack
    disk: 128G
  loadgen:
    name: nanofaas-release-loadgen
    disk: 30G
azure:
  resource_group: nanofaas-release-rg
  location: westeurope
  vm_size: Standard_D4s_v5
  loadgen_vm_size: Standard_D2s_v5
  image_urn: <exact resolved URN>
  ssh_key_path: /absolute/path/to/id_ed25519
```

**Step 2: Add builder utilities to stack provisioning**

Add `qemu-user-static`, `binfmt-support`, `skopeo`, and required transport utilities to the base package list. Do not install k6 or builder-only packages on loadgen through a separate new framework; tolerate the small package overlap for the first version. syft/cosign may be run from pinned container images to avoid mutable host installation.

**Step 3: Remove the GitHub publish job**

Keep GitHub test jobs, but delete the tag-triggered `publish` job and package-write permission. The contract test must assert that `.github/workflows/gitops.yml` contains no `docker push`, `bootBuildImage` publication, or `packages: write`.

**Step 4: Verify**

Run configuration, Ansible, and script contract tests. Run `controlplane-tool release plan` against the example after replacing only the SSH path in a temporary copy. Expected: a pinned Azure plan.

**Step 5: Commit**

Run staged change detection and commit.

### Task 9: Document operation, recovery, and metric history

**Difficulty:** Medium
**Commit:** `Document Azure image releases`

**Files:**
- Create: `docs/operations/image-releases.md`
- Modify: `README.md`
- Modify: `docs/performance/history.md`
- Modify: `tools/controlplane/README.md` if present; otherwise do not create a duplicate tool README

**Step 1: Write operator documentation**

Document:

- prerequisites: Azure login, pinned environment copy, GHCR login on stack, cosign key, expected costs;
- `release prepare`, mandatory review/commit, dry-run, real run, and post-run metric commit;
- the 52-cell matrix and tag policy;
- AMD64 native benchmark semantics and the absence of ARM64 performance claims;
- local/Multipass/Proxmox experimental commands;
- run artifact paths and the difference between `runs/latest` and versioned release runs;
- failure behavior before/after immutable architecture upload;
- manual cleanup and safe rerun using the same source commit/version;
- why GitHub Actions tests remain while image publication is forbidden there.

**Step 2: Add documentation contract tests only where useful**

Extend `scripts/tests/test_release_authority.py` to assert that the documented primary commands and Azure-only warning remain present. Do not snapshot prose.

**Step 3: Verify links and examples**

Run CLI `--help` commands and the script test. Run `git diff --check`.

**Step 4: Commit**

Run staged change detection and commit.

### Task 10: Full verification and controlled dry run

**Difficulty:** High
**Commit:** `Verify Azure release pipeline` only if verification itself requires tracked fixes; otherwise no commit.

**Files:**
- Modify only files required by failures discovered in this task, each through a fresh TDD/impact cycle.

**Step 1: Run static and package checks**

```bash
cd tools/workflow-tasks && uv run ruff check src tests && uv run basedpyright
cd ../controlplane && uv run ruff check src tests && uv run basedpyright
uv run controlplane-quality
```

Expected: all PASS.

**Step 2: Run Python suites from their required directories**

```bash
cd tools/controlplane && uv run pytest -q tests
cd ../workflow-tasks && uv run pytest -q tests
cd ../fn-init && uv run pytest -q tests
```

Expected: all PASS; controlplane baseline is at least 303 and workflow-tasks baseline at least 439 tests, plus the new tests.

**Step 3: Run platform and function tests**

```bash
./gradlew build -PcontrolPlaneModules=all
functions/contract-tests/run.sh
cargo test --manifest-path runtimes/watchdog/Cargo.toml
bash runtimes/watchdog/test-local.sh
```

Expected: all PASS.

**Step 4: Validate the portable image plan without building**

```bash
cd tools/controlplane
uv run controlplane-tool images plan --version v0.18.0 --arch all --flavor all
```

Expected: exactly 52 cells, AMD64 before ARM64, no GHCR push command.

**Step 5: Run a Multipass experiment**

Build a small representative subset only:

```bash
uv run controlplane-tool images build --version v0.18.0 \
  --environment environments/multipass.yaml \
  --only control-plane,watchdog --arch amd64 --flavor native
```

Expected: local/VM artifacts only; GHCR unchanged.

**Step 6: Run Azure release dry-run**

```bash
uv run controlplane-tool release plan v0.18.0 \
  --environment environments/azure-release.yaml
```

Expected: tests → AMD64 → three benchmarks → gate → QEMU/ARM64 → smoke → promotion → verification → attestation.

**Step 7: Final GitNexus and diff audit**

Run `gitnexus_detect_changes(scope="compare", base_ref="main")`. Inspect every affected flow, especially ordinary CLI/TUI load testing. Run `git diff --check` and verify no secrets, generated run artifacts, VM configuration copies, or private key paths are tracked.

**Step 8: Request final code review**

Use `superpowers:requesting-code-review`. Resolve correctness findings through `superpowers:receiving-code-review`, rerun the relevant tests, then use `superpowers:verification-before-completion` before claiming completion.

## Real Azure release acceptance test

This is intentionally not part of routine implementation verification because it provisions paid infrastructure and writes public GHCR artifacts. Run it only after code review and explicit user authorization:

```bash
controlplane-tool release prepare v0.18.0
# review, test, and commit the version change
controlplane-tool release run v0.18.0 \
  --environment tools/controlplane/environments/azure-release.yaml \
  --provision
```

Acceptance requires:

- all source tests pass on the Azure stack;
- the exact AMD64-native candidates complete three benchmark runs;
- the aggregate and regression gates pass;
- all 52 matrix cells exist;
- ARM64 cells pass QEMU smoke tests;
- GHCR architecture digests match the Azure-local source digests;
- every final index contains AMD64 and ARM64;
- `v0.18.0` and `v0.18.0-native` resolve to the same native descriptors;
- SBOM and signed release predicate verification succeeds;
- `docs/performance/releases/v0.18.0.json` and the history row are produced;
- Azure cleanup completes unless `--keep` was explicitly requested.
