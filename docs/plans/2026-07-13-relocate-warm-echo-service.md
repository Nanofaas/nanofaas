# Relocate Java Warm Echo Service Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Move the runnable Java echo service from `platform/function-runtime` to `services/java/warm-echo`, make its example role explicit, and preserve every active control-plane Python scenario.

**Architecture:** The reusable Java runtime remains in `sdks/java`; the moved module is a deployable long-running example service. NanoFaaS provisions the example through the normal function registration path, so the standalone `nanofaas-runtime` Helm release is removed. Active `validate` and `loadtest` workflows keep building the warm-echo image where required, but no longer install an unrelated Deployment before registering managed functions.

**Tech Stack:** Java 21, Spring Boot, Gradle, Python 3.13, uv, pytest, Helm, Docker/Testcontainers, Kubernetes/Multipass, GitNexus.

---

## Decisions and invariants

- New source path: `services/java/warm-echo`.
- New Gradle coordinate: `:services:java:warm-echo`.
- New application class: `WarmEchoApplication`.
- New default image: `nanofaas/java-warm-echo`.
- New image override: Gradle property `warmEchoImage`, environment variable `WARM_ECHO_IMAGE`.
- `sdks/java` remains the reusable runtime implementation; the service depends on the SDK, never the reverse.
- Delete `deploy/helm/nanofaas-runtime`. The control plane must create the function Deployment and Service through the selected deployment provider.
- Do not add `services/examples`; `functions` and `services` already contain examples.
- Do not preserve aliases for `:function-runtime`, `functionRuntimeImage`, or the old image name. This is pre-release cleanup, and keeping aliases would perpetuate the ambiguity.
- Do not edit historical material under `docs/plans`, `docs/superpowers`, `issues`, or `experiments` merely to rewrite old paths.
- Preserve the public scenario YAML files and their semantics:
  - `tools/controlplane/scenarios-v2/validate-container.yaml`
  - `tools/controlplane/scenarios-v2/validate-k8s.yaml`
  - `tools/controlplane/scenarios-v2/loadtest.yaml`
  - `tools/controlplane/scenarios-v2/cli.yaml`
- Keep provider portability: generated commands must still run through role bindings for local, Multipass, external SSH, Azure, and Proxmox environments.

## Risk summary

GitNexus reports `LOW` risk for the Java classes (`FunctionRuntimeApplication`, `EchoHandler`) and no application callers. It reports `HIGH` risk for `k8s_deployment_specs` and `validate_cleanup_specs`: they feed both validate and load-test plans and reach the control-plane CLI and TUI. Update tests for the generated workflows before changing those functions.

---

### Task 1: Lock the new repository layout and Gradle identity with failing tests

**Files:**
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/IssueCoverageTest.java`
- Modify: `settings.gradle`

**Step 1: Change the layout assertions first**

Update `IssueCoverageTest` so it requires:

```java
assertTrue(Files.isDirectory(root.resolve("services/java/warm-echo")));
assertTrue(Files.exists(root.resolve("services/java/warm-echo/Dockerfile")));
assertFalse(Files.exists(root.resolve("platform/function-runtime")));
```

Add the `assertFalse` static import. Leave the control-plane and common assertions intact.

**Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :control-plane:test --tests '*IssueCoverageTest'
```

Expected: FAIL because `services/java/warm-echo` does not exist yet.

**Step 3: Declare the new Gradle coordinate**

Replace the old project declaration in `settings.gradle` with:

```groovy
include(':services:java:warm-echo')
project(':services:java:warm-echo').projectDir = file('services/java/warm-echo')
```

Do not keep a `:function-runtime` compatibility project.

**Step 4: Verify the intended Gradle model is not yet buildable**

Run:

```bash
./gradlew projects
```

Expected: the new coordinate is listed; compilation is deferred until the directory move in Task 2.

**Step 5: Do not commit yet**

Task 1 intentionally stays red until the mechanical move in Task 2.

---

### Task 2: Move and rename the Java warm-echo service

**Files:**
- Move: `platform/function-runtime` -> `services/java/warm-echo`
- Rename: `services/java/warm-echo/src/main/java/it/unimib/datai/nanofaas/runtime/FunctionRuntimeApplication.java` -> `services/java/warm-echo/src/main/java/it/unimib/datai/nanofaas/services/warmecho/WarmEchoApplication.java`
- Move: `services/java/warm-echo/src/main/java/it/unimib/datai/nanofaas/runtime/core/EchoHandler.java` -> `services/java/warm-echo/src/main/java/it/unimib/datai/nanofaas/services/warmecho/EchoHandler.java`
- Move and rename matching tests under `services/java/warm-echo/src/test/java/.../services/warmecho/`
- Modify: `services/java/warm-echo/build.gradle`
- Modify: `services/java/warm-echo/Dockerfile`
- Create: `services/java/warm-echo/function.yaml`
- Create: `services/java/warm-echo/README.md`
- Modify: `platform/control-plane/build.gradle`

**Step 1: Re-run mandatory impact checks immediately before editing**

Run GitNexus upstream impact for `FunctionRuntimeApplication`, `EchoHandler`, and every workflow symbol edited in later tasks. If any result changes to `HIGH` or `CRITICAL`, stop and report it before implementation.

**Step 2: Move files with Git-aware operations**

Use `git mv`; do not copy and delete. Preserve `application.yml`, Dockerfile, tests, and the existing AOT/buildpack configuration.

**Step 3: Rename the application safely**

Run a GitNexus rename preview for `FunctionRuntimeApplication` -> `WarmEchoApplication`, review the test edit, then apply it. Move both application classes into:

```java
package it.unimib.datai.nanofaas.services.warmecho;
```

Rename `FunctionRuntimeApplicationTest` to `WarmEchoApplicationTest`. Keep `EchoHandler` behavior unchanged.

**Step 4: Update the module build contract**

Keep `implementation project(':sdks:java')`, `archiveFileName = 'app.jar'`, AOT settings, and the native build settings. Change only the image configuration:

```groovy
imageName = project.findProperty('warmEchoImage') ?:
        (System.getenv('WARM_ECHO_IMAGE') ?: 'nanofaas/java-warm-echo:buildpack')
```

Update `platform/control-plane/build.gradle` from:

```groovy
dependsOn ':function-runtime:bootJar'
```

to:

```groovy
dependsOn ':services:java:warm-echo:bootJar'
```

**Step 5: Add the minimal example manifest**

Create `function.yaml`:

```yaml
name: warm-echo
image: localhost:5000/nanofaas/java-warm-echo:e2e
executionMode: DEPLOYMENT
catalog:
  family: warm-echo
  runtime: java
  description: Long-running Java echo service compatible with NanoFaaS.
  defaultImage: localhost:5000/nanofaas/java-warm-echo:e2e
  defaultPayload: echo-sample.json
```

The control-plane function catalog continues to scan only `functions/`; do not add `services/` discovery in this refactor. The manifest is a runnable example contract, not a new selectable scenario function.

**Step 6: Document the module locally**

The README must state that the service:

- is a WARM reference application;
- embeds `sdks/java`;
- is not shared infrastructure;
- is provisioned through the normal NanoFaaS registration API;
- exposes `/invoke`, health, and metrics via the SDK.

**Step 7: Run focused tests**

Run:

```bash
./gradlew :services:java:warm-echo:test :control-plane:test --tests '*IssueCoverageTest'
```

Expected: PASS.

**Step 8: Verify scope and commit**

Run `gitnexus_detect_changes(scope: all)`, confirm only layout/application symbols are affected, then:

```bash
git add settings.gradle services/java/warm-echo platform/control-plane/build.gradle platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/IssueCoverageTest.java
git commit -m "Move warm echo service out of platform"
```

---

### Task 3: Protect active Python validate and load-test plans before changing them

**Files:**
- Modify: `tools/workflow-tasks/tests/workflows/test_validate.py`
- Modify: `tools/controlplane/tests/plans/test_loadtest.py`
- Modify: `tools/controlplane/tests/plans/test_validate.py`

**Step 1: Update expected K8s task sequence**

The K8s validate plan must become:

```python
[
    "stack.preflight",
    "build.jvm",
    "images.build.control-plane",
    "images.push.control-plane",
    "images.build.warm-echo",
    "images.push.warm-echo",
    "images.build.word-stats-java",
    "images.push.word-stats-java",
    "helm.deploy.control-plane",
    "functions.register.word-stats-java",
    "functions.invoke.word-stats-java",
    "resources.inspect.k8s.word-stats-java",
]
```

Assert the JVM build contains `:services:java:warm-echo:bootJar`. Assert it does not contain `:function-runtime:bootJar`.

**Step 2: Lock cleanup semantics**

Change the K8s cleanup expectation to:

```python
[
    "functions.delete.word-stats-java",
    "helm.uninstall.control-plane",
]
```

Add explicit assertions that neither plan nor cleanup contains `function-runtime` or `nanofaas-runtime`.

**Step 3: Protect load-test ordering**

In `test_loadtest_plan_owns_stack_registration_and_cleanup`, assert:

```python
assert workflow.task_ids.index("helm.deploy.control-plane") < workflow.task_ids.index(
    "functions.register.word-stats-java"
)
assert "helm.deploy.function-runtime" not in workflow.task_ids
assert [task.task_id for task in workflow.cleanup_tasks] == [
    "functions.delete.word-stats-java",
    "helm.uninstall.control-plane",
]
```

Retain the provider parameterization covering local, Multipass, external, Azure, and Proxmox.

**Step 4: Add an active-plan path assertion**

In the validate plan tests, assert the generated warm-echo Docker build uses:

```text
services/java/warm-echo/Dockerfile
services/java/warm-echo
localhost:5000/nanofaas/java-warm-echo:e2e
```

**Step 5: Run tests and verify RED**

Run:

```bash
uv run --project tools/workflow-tasks pytest -q tools/workflow-tasks/tests/workflows/test_validate.py
uv run --project tools/controlplane pytest -q tools/controlplane/tests/plans/test_validate.py tools/controlplane/tests/plans/test_loadtest.py
```

Expected: FAIL against the old plan generation.

---

### Task 4: Update active workflow generation and remove the standalone runtime release

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/validate.py`

**Step 1: Update the JVM build target**

For K8s Docker builds, append `:services:java:warm-echo:bootJar` instead of `:function-runtime:bootJar`.

**Step 2: Build and push the renamed test service image**

In `k8s_deployment_specs`, replace the old tuple with:

```python
(
    "warm-echo",
    f"{request.registry}/nanofaas/java-warm-echo:e2e",
    "services/java/warm-echo/Dockerfile",
    "services/java/warm-echo",
)
```

Keep role `stack`, registry behavior, and ordering unchanged.

**Step 3: Remove the redundant Helm deployment**

Delete `function_runtime_helm_values`, its import, and the `helm.deploy.function-runtime` task. Do not replace it with a warm-echo Helm task. The provider creates `fn-<name>` Deployment and Service when the function is registered.

**Step 4: Remove redundant cleanup**

Delete `helm.uninstall.function-runtime` from `validate_cleanup_specs`. Continue deleting registered functions before uninstalling the control plane.

**Step 5: Run the RED tests and verify GREEN**

Run the two commands from Task 3. Expected: PASS.

**Step 6: Render every active scenario without executing infrastructure**

Run:

```bash
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-container.yaml
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/validate-k8s.yaml
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/cli.yaml
```

Expected: all plans render; K8s/load-test plans contain no standalone runtime Helm task.

**Step 7: Verify HIGH-risk dependents and commit**

Run `gitnexus_detect_changes(scope: all)`. Explicitly inspect `build_validate_plan`, `build_loadtest_plan`, `run_command`, `plan_command`, and TUI process impact. Then:

```bash
git add tools/workflow-tasks/src/workflow_tasks/workflows/validate.py tools/workflow-tasks/tests/workflows/test_validate.py tools/controlplane/tests/plans/test_validate.py tools/controlplane/tests/plans/test_loadtest.py
git commit -m "Keep Python scenarios aligned with warm echo"
```

---

### Task 5: Align reusable workflow components and the K8s JUnit bridge

**Files:**
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/images.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/helm.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/cleanup.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/remote_script.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/components/verification.py`
- Modify: corresponding tests under `tools/workflow-tasks/tests/components/`
- Modify: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eTest.java`

**Step 1: Rename image helpers and contracts in tests**

Rename `runtime_image` to `warm_echo_image` and expect:

```python
warm_echo_image("reg:5000") == "reg:5000/nanofaas/java-warm-echo:e2e"
```

Expect component operation IDs `images.build_core.warm_echo_image` and `images.build_core.push_warm_echo_image`.

**Step 2: Remove unused Helm component expectations**

Delete tests for `HELM_DEPLOY_FUNCTION_RUNTIME`, `plan_deploy_function_runtime`, and `function_runtime_helm_values`. Retain all control-plane Helm tests.

Delete tests for `UNINSTALL_FUNCTION_RUNTIME`; retain control-plane and VM lifecycle cleanup tests.

**Step 3: Rename the JUnit bridge input**

Change `k8s_e2e_test_vm_script` from `runtime_image`/`FUNCTION_RUNTIME_IMAGE` to `warm_echo_image`/`WARM_ECHO_IMAGE`. Update verification component tests to assert the new environment variable and image.

**Step 4: Apply the minimal implementation**

- Update build target, Dockerfile path, context, summaries, and image name in `components/images.py`.
- Delete only the runtime-specific Helm and cleanup component code; do not refactor unrelated component APIs.
- Update `verification.py` and `remote_script.py` to pass the renamed image variable.

**Step 5: Update K8s E2E semantics**

In `K8sE2eTest`:

```java
private static final String WARM_ECHO_IMAGE = System.getenv()
        .getOrDefault("WARM_ECHO_IMAGE", "nanofaas/java-warm-echo:e2e");
```

Replace uses of `RUNTIME_IMAGE`. Remove `awaitDeploymentReady("function-runtime")` from setup: there is no preinstalled example Deployment. Keep all registrations using the image; NanoFaaS must create and await the managed `fn-*` Deployment itself.

**Step 6: Run focused tests**

Run:

```bash
uv run --project tools/workflow-tasks pytest -q tools/workflow-tasks/tests/components
./gradlew :control-plane-modules:k8s-deployment-provider:test
```

Expected: PASS.

**Step 7: Verify scope and commit**

Run `gitnexus_detect_changes(scope: all)`, then:

```bash
git add tools/workflow-tasks platform/modules/k8s-deployment-provider
git commit -m "Use warm echo in reusable K8s workflows"
```

---

### Task 6: Update local E2E tests and semantic image fixtures

**Files:**
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/BuildpackE2eTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/E2eFlowTest.java`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/e2e/E2eApiSupportTest.java`
- Modify: K8s provider tests containing `nanofaas/function-runtime:0.5.0`

**Step 1: Update expected E2E coordinates first**

Use:

```text
nanofaas/java-warm-echo:buildpack
:services:java:warm-echo:bootBuildImage
-PwarmEchoImage=...
services/java/warm-echo/Dockerfile
services/java/warm-echo/build/libs
```

Use network alias `warm-echo` and endpoint `http://warm-echo:8080/invoke` in local pool E2E tests.

**Step 2: Update neutral K8s fixture image strings**

Replace `nanofaas/function-runtime:0.5.0` with `nanofaas/java-warm-echo:0.5.0` in provider tests. Do not change provider logic.

**Step 3: Run non-container tests**

Run:

```bash
./gradlew :control-plane:test :control-plane-modules:k8s-deployment-provider:test
```

Expected: PASS.

**Step 4: Run local container E2E when Docker is available**

Run:

```bash
./gradlew :services:java:warm-echo:bootJar :control-plane:test -PrunE2e --tests '*E2eFlowTest'
```

Expected: PASS. If Docker is unavailable, report this as an unverified gate; do not claim E2E completion.

**Step 5: Verify scope and commit**

Run `gitnexus_detect_changes(scope: all)`, then commit:

```bash
git add platform/control-plane platform/modules/k8s-deployment-provider
git commit -m "Repoint E2E tests to warm echo service"
```

---

### Task 7: Remove the obsolete Helm chart and update build/publish automation

**Files:**
- Delete: `deploy/helm/nanofaas-runtime/`
- Modify: `platform/modules/k8s-deployment-provider/src/test/java/it/unimib/datai/nanofaas/modules/k8s/e2e/K8sE2eDeploymentSpecTest.java`
- Modify: `.github/workflows/gitops.yml`
- Modify: `scripts/native-build.sh`
- Modify: `scripts/tests/test_native_build_wrapper.py`
- Modify or replace stale assertions in: `scripts/tests/test_gitops_workflow_control_plane_build.py`

**Step 1: Change tests before deleting the chart**

Remove the test that renders `deploy/helm/nanofaas-runtime`. Keep control-plane Deployment and health-probe rendering tests. Add an assertion that the obsolete chart directory does not exist in the repository-layout test.

**Step 2: Delete the chart**

Delete `deploy/helm/nanofaas-runtime`; do not create a replacement warm-echo chart.

**Step 3: Update release publishing**

Change the tag-triggered workflow to build and push:

```text
:services:java:warm-echo:bootBuildImage
-PwarmEchoImage=ghcr.io/<repo>/java-warm-echo:<tag>
ghcr.io/<repo>/java-warm-echo:<tag>
```

Keep the control-plane image flow unchanged.

Update the GitOps workflow test to reflect the workflow that actually exists; remove stale expectations for the deleted `controlplane-tool images` command rather than introducing compatibility code.

**Step 4: Update native build paths**

Use Gradle target `:services:java:warm-echo:nativeCompile` and binary path:

```text
services/java/warm-echo/build/native/nativeCompile/warm-echo
```

Rename local shell variables and error messages from runtime to warm echo.

**Step 5: Run automation tests and Helm validation**

Run:

```bash
uv run pytest -q scripts/tests/test_native_build_wrapper.py scripts/tests/test_gitops_workflow_control_plane_build.py
helm lint deploy/helm/nanofaas
helm template nanofaas deploy/helm/nanofaas >/dev/null
```

Expected: PASS.

**Step 6: Verify scope and commit**

Run `gitnexus_detect_changes(scope: all)`, then:

```bash
git add .github/workflows/gitops.yml deploy/helm scripts platform/modules/k8s-deployment-provider/src/test
git commit -m "Remove standalone function runtime release"
```

---

### Task 8: Correct active documentation and repository guidance

**Files:**
- Modify: `AGENTS.md`
- Modify: `CLAUDE.md`
- Modify: `GEMINI.md`
- Modify: `docs/architecture.md`
- Modify: `docs/example-function.md`
- Modify: `docs/function-pod-architecture.md`
- Delete: `docs/function-runtime.md`
- Modify only current statements in: `docs/feature-roadmap.md`
- Modify: `services/java/warm-echo/README.md`

**Step 1: Establish consistent terminology**

Documentation must say:

- `sdks/java` implements the reusable Java function runtime;
- `services/java/warm-echo` is a runnable long-running example;
- the control plane invokes it through the same `/invoke` contract as other function images;
- it is not installed as shared platform infrastructure;
- NanoFaaS creates its Deployment/Service in WARM mode.

**Step 2: Remove the misleading standalone document**

Move any still-useful service-specific content from `docs/function-runtime.md` into the service README, then delete the old document. Do not duplicate SDK documentation already present elsewhere.

**Step 3: Update commands and paths**

Replace active commands with:

```bash
./gradlew :services:java:warm-echo:bootRun
./gradlew :services:java:warm-echo:bootBuildImage
```

Use `nanofaas/java-warm-echo` in current examples.

**Step 4: Check for forbidden active references**

Run:

```bash
git grep -n -I -E 'platform/function-runtime|:function-runtime|nanofaas/function-runtime|nanofaas-runtime|FUNCTION_RUNTIME_IMAGE' -- \
  ':!docs/plans/**' ':!docs/superpowers/**' ':!issues/**' ':!experiments/**'
```

Expected: no matches.

**Step 5: Commit documentation**

```bash
git add AGENTS.md CLAUDE.md GEMINI.md docs services/java/warm-echo/README.md
git commit -m "Document warm echo as an example service"
```

---

### Task 9: Run full regression and real scenario gates

**Files:** none unless a test exposes a defect.

**Step 1: Verify all Gradle modules and tests**

Run:

```bash
./gradlew projects
./gradlew build
```

Expected: `:services:java:warm-echo` is listed and `BUILD SUCCESSFUL`.

**Step 2: Run both Python suites in full**

Run:

```bash
uv run --project tools/workflow-tasks pytest -q
uv run --project tools/controlplane pytest -q
```

Baseline expectations before implementation were 444 workflow-task tests and 176 control-plane-tool tests; the final counts may increase, but failures must remain zero.

**Step 3: Run script-level regression tests**

Run:

```bash
uv run pytest -q scripts/tests
```

Expected: PASS, including native and GitOps path assertions.

**Step 4: Render scenarios for every provider class**

Run:

```bash
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/multipass.yaml
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/external.yaml.example
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/azure.yaml.example
./scripts/controlplane.sh plan tools/controlplane/scenarios-v2/loadtest.yaml \
  --environment tools/controlplane/environments/proxmox.yaml.example
```

Expected: all four plans render. No command may reference the old Gradle project, path, image, environment variable, or Helm release.

Azure and Proxmox require unit/plan verification only unless credentials and infrastructure are explicitly available.

**Step 5: Run the local container scenario**

With Docker running:

```bash
./scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-container.yaml
```

Expected: build, registration, invocation, resource inspection, and cleanup succeed.

**Step 6: Run the real Multipass K8s scenario**

With Multipass available:

```bash
./scripts/controlplane.sh run tools/controlplane/scenarios-v2/validate-k8s.yaml \
  --environment tools/controlplane/environments/multipass.yaml \
  --provision \
  --keep
```

Expected: VM provisioning, k3s bootstrap, image build/push, Helm control-plane deployment, and managed function registration/invocation succeed. Confirm there is no standalone `function-runtime` or `warm-echo` Helm release; only provider-managed `fn-*` workloads may exist. `--keep` intentionally leaves the prepared VM available for Step 7.

**Step 7: Run the K8s JUnit suite against a live prepared cluster**

Run:

```bash
multipass exec nanofaas-stack -- bash -lc \
  'cd /home/ubuntu/nanofaas && \
   KUBECONFIG=/home/ubuntu/.kube/config \
   WARM_ECHO_IMAGE=localhost:5000/nanofaas/java-warm-echo:e2e \
   NANOFAAS_E2E_NAMESPACE=nanofaas-e2e \
   ./gradlew :control-plane-modules:k8s-deployment-provider:test \
     --tests "*K8sE2eTest" -PrunE2e --no-daemon'
```

Expected: sync-queue, cold/warm metrics, resources, registration, invocation, and deletion tests all pass.

**Step 8: Tear down the retained test VM**

Run:

```bash
multipass delete --purge nanofaas-stack
```

Expected: the VM created in Step 6 is removed. Do this even when Step 7 fails, unless the user explicitly asks to retain it for diagnosis.

**Step 9: Final graph and repository checks**

Run:

```bash
git status --short
git diff --check
```

Run `gitnexus_detect_changes(scope: compare, base_ref: main)`. Review all d=1 dependents from the earlier `HIGH` impact report and confirm that validate, load-test, CLI, and TUI flows are covered.

**Step 10: Final commit only if verification produced fixes**

If no fixes were needed, do not create an empty commit. Otherwise commit only the verified fixes with an imperative message.

---

## Out of scope

- Adding service discovery to the control-plane function catalog.
- Adding a generic `services/` framework or abstraction.
- Keeping compatibility aliases for the old module/image/chart names.
- Rewriting historical plans, archived issue reports, or experiment snapshots.
- Publishing a new version or tag; release work happens only after this refactor is merged and verified.
