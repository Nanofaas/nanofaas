# Backend-neutral native CLI implementation plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Produce a small, backend-neutral Java CLI whose GraalVM executable supports the complete retained command surface without Kubernetes runtime dependencies.

**Architecture:** Remove backend provisioning and Kubernetes diagnostics from the Java CLI. Keep all retained commands on the control-plane HTTP contract, share reconciliation and JSON-input code, and validate both JVM and native execution.

**Tech Stack:** Java 21, Picocli, Jackson, Java `HttpClient`, JUnit 5, MockWebServer, Gradle GraalVM Native Build Tools, Python/pytest for scenario integration.

---

### Task 1: Remove the Kubernetes-only command surface

**Files:**
- Modify: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/RootCommand.java`
- Modify: `clients/cli/build.gradle`
- Modify: `clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java`
- Delete: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/k8s/`
- Delete: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/platform/`
- Delete: corresponding `clients/cli/src/test/.../commands/k8s/` and `commands/platform/`

1. Add a failing root-command test asserting that `k8s` and `platform` are absent while the retained commands remain.
2. Run `./gradlew :nanofaas-cli:test --tests '*RootCommandTest' --rerun-tasks` and confirm failure.
3. Remove the command registrations, sources, tests, Fabric8 dependencies, and Kubernetes mock dependency.
4. Run the focused test and `./gradlew :nanofaas-cli:dependencies --configuration runtimeClasspath`; confirm the test passes and Fabric8/Vert.x/Netty are absent.

### Task 2: Normalize help, version, inherited options, and errors

**Files:**
- Modify: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/NanofaasCli.java`
- Modify: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/RootCommand.java`
- Modify: retained command annotations under `clients/cli/src/main/java/.../commands/`
- Create: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/CliVersionProvider.java`
- Modify: `clients/cli/build.gradle`
- Modify: `clients/cli/src/test/java/it/unimib/datai/nanofaas/cli/commands/RootCommandTest.java`

1. Add failing tests for leaf `--help`, global options after subcommands, non-empty `--version`, and concise errors without stack traces.
2. Verify the focused tests fail for the expected reasons.
3. Enable standard help on retained commands, inherit root options, generate/read the project version, and install one Picocli execution exception handler.
4. Re-run focused and full CLI tests.

### Task 3: Match the function response contract and share apply logic

**Files:**
- Create: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/FunctionDetails.java`
- Create: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FunctionApplier.java`
- Modify: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/http/ControlPlaneClient.java`
- Modify: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/fn/FnApplyCommand.java`
- Modify: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/commands/deploy/DeployCommand.java`
- Modify: `FnGetCommand.java`, `FnListCommand.java`, and their tests
- Modify: HTTP/apply/deploy tests

1. Add a failing HTTP test using the real `FunctionResponse` fields `requestedExecutionMode` and `effectiveExecutionMode`.
2. Add a failing apply regression test proving an unchanged DEPLOYMENT manifest does not issue DELETE or a second POST.
3. Introduce the exact response model and one shared reconciliation implementation used by apply and deploy.
4. Render `fn get` as JSON and retain the compact list output.
5. Run all HTTP, apply, deploy, get, and list tests.

### Task 4: Correct invocation payloads and E2E assertions

**Files:**
- Create: `clients/cli/src/main/java/it/unimib/datai/nanofaas/cli/io/JsonInput.java`
- Modify: `InvokeCommand.java` and `EnqueueCommand.java`
- Modify: their tests
- Modify: `tools/controlplane/src/controlplane_tool/plans/cli.py`
- Modify: `tools/workflow-tasks/src/workflow_tasks/workflows/cli.py`
- Modify: corresponding Python tests
- Modify: `docs/tutorial-function.md`
- Modify: `tools/fn-init/src/fn_init/wizard.py`

1. Add failing Java tests defining raw input semantics and shared file/stdin parsing.
2. Add failing Python tests proving the CLI workflow passes raw function input and validates the response payload.
3. Extract the duplicated JSON reader, stop double-wrapping E2E payloads, and add an output assertion task.
4. Change tutorial and generated next steps to pass raw JSON to invoke/enqueue while keeping contract files for `fn test`.
5. Run focused Java, controlplane, workflow-tasks, and fn-init tests.

### Task 5: Make deploy paths and local images reliable

**Files:**
- Modify: `BuildSpecLoader.java`, `DockerBuildx.java`, and their tests
- Modify: function manifests/templates whose build context still assumes the old directory depth

1. Add failing tests showing paths resolve from the manifest directory and `push: false` adds `--load`.
2. Verify both failures.
3. Resolve context from the manifest parent and Dockerfile from the resolved context; normalize paths.
4. Add `--load` when push is disabled and update affected templates/manifests.
5. Run CLI image tests and fn-init tests.

### Task 6: Record future status and document the reduced CLI

**Files:**
- Modify: `docs/feature-roadmap.md`
- Modify: `docs/nanofaas-cli.md`
- Modify: `README.md` if it lists removed commands

1. Document the retained backend-neutral surface and ownership of provisioning.
2. Add a roadmap item for an HTTP-only `nanofaas status` reporting health, version, modules, and capabilities.
3. Run documentation-link and product-document tests.

### Task 7: Add and run native verification

**Files:**
- Modify: `clients/cli/build.gradle`
- Modify: `scripts/native-build.sh` if it remains the canonical native entry point
- Add or modify tests/scripts for native smoke coverage

1. Add a native smoke task/test that requires non-empty help and version output and exercises a retained HTTP command against a local stub.
2. Verify the smoke check fails before wiring the native executable.
3. Wire the task to `nativeCompile` and include the CLI in the canonical native workflow.
4. Run `./gradlew :nanofaas-cli:test --rerun-tasks`.
5. Run affected Python suites.
6. Run `./gradlew :nanofaas-cli:nativeCompile --no-daemon` and the native smoke test.
7. Confirm the executable works and compare its size/startup with the 68 MB/20 ms baseline.
8. Run `gitnexus_detect_changes`, `git diff --check`, and final repository status verification before commit.
