# Build Metadata and Modular OpenAPI Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Make every control-plane artifact report its real build/runtime identity and package an OpenAPI contract composed from the modules selected for that artifact.

**Architecture:** The Gradle settings plugin composes a core OpenAPI document with selected module fragments and explicit operation overlays, then packages the result as `/openapi.yaml`. The build-metadata module combines a generated immutable properties resource, Docker-provided base-image references, and JVM runtime inspection into a typed response. Existing core controllers and typed module SPIs remain unchanged.

**Tech Stack:** Java 25, Gradle 9.7, Spring Boot/WebFlux 4.1, SnakeYAML (build-time only), JUnit 5, AssertJ, Gradle TestKit, Python 3.13, pytest.

---

## Scope and invariants

- `offload.mode=always` keeps working without a queue module.
- No generic HTTP router, handler priority, Springdoc dependency, Docker socket, or Kubernetes API access is added.
- New module routes are owned by module controllers. Core routes keep delegating through typed SPIs.
- OpenAPI overlays may add or remove fields explicitly. Two modules writing different values to the same operation leaf fail the build.
- Missing diagnostic data serializes as JSON `null` and never prevents control-plane startup.
- NanoFaaS work uses `.worktrees/build-metadata-openapi` on `feat/build-metadata-openapi`. NanoLab propagation uses a separate worktree and commit because it is another repository.

### Task 1: Implement the deterministic OpenAPI composer

**Files:**
- Modify: `platform/gradle-plugin/build.gradle`
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/OpenApiComposer.java`
- Test: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/OpenApiComposerTest.java`

**Step 1: Write failing tests**

Use `@TempDir` and minimal YAML strings. Cover:

```java
@Test
void addsModulePathsAndComponents() {
    Path result = compose(core(), Map.of("extra", fragmentWithExtraRoute()));
    assertThat(paths(yaml(result))).containsKey("/extra");
    assertThat(schemas(yaml(result))).containsKeys("Core", "Extra");
}

@Test
void appliesDisjointOperationOverlays() {
    Path result = compose(coreWithInvoke(), Map.of(
            "queue", overlay("invokeFunctionSync", "429", "queue full"),
            "offload", overlay("invokeFunctionSync", "502", "remote failed")));
    assertThat(operationResponses(yaml(result), "invokeFunctionSync"))
            .containsKeys("200", "429", "502");
}

@Test
void nullInAnOverlayRemovesAField() {
    Path result = compose(coreWithEnqueue501(), Map.of(
            "async-queue", overlayRemoving501AndAdding202()));
    assertThat(operationResponses(yaml(result), "invokeFunctionAsync"))
            .containsKey("202").doesNotContainKey("501");
}

@Test
void rejectsConflictingOverlayLeaves() {
    assertThatThrownBy(() -> compose(coreWithInvoke(), Map.of(
            "one", overlay("invokeFunctionSync", "429", "first"),
            "two", overlay("invokeFunctionSync", "429", "second"))))
            .hasMessageContaining("invokeFunctionSync")
            .hasMessageContaining("one")
            .hasMessageContaining("two")
            .hasMessageContaining("responses/429/description");
}
```

Also reject duplicate method/path pairs, duplicate component names, unknown overlay targets, and forbidden fragment top-level keys.

**Step 2: Verify red**

```bash
./gradlew -p platform/gradle-plugin test --tests '*OpenApiComposerTest' --no-daemon
```

Expected: compilation fails because `OpenApiComposer` is missing.

**Step 3: Add the build-time parser**

Add only to `platform/gradle-plugin/build.gradle`:

```groovy
implementation 'org.yaml:snakeyaml:2.4'
```

**Step 4: Implement the minimum composer**

`OpenApiComposer.compose(Path core, Map<String, Path> fragments, Path output)` must:

1. preserve insertion order and sort fragments by module ID;
2. accept only `paths`, `components`, and `x-nanofaas-overlays` in fragments;
3. merge new paths/components and reject ordinary duplicates;
4. locate overlay targets by unique `operationId`;
5. apply recursive merge-patch semantics: maps recurse, `null` removes, other values replace;
6. track writes by operation ID and JSON pointer, allowing identical repeated values and rejecting differing ones with both module IDs in the error;
7. emit deterministic YAML without timestamps.

Keep the implementation in one package-private class with one nested `record Write(String module, Object value)`. Do not build a generic merge framework.

**Step 5: Verify green and commit**

```bash
./gradlew -p platform/gradle-plugin test --tests '*OpenApiComposerTest' --no-daemon
git add platform/gradle-plugin
git commit -m "feat: compose modular OpenAPI fragments"
```

### Task 2: Wire composition to resolved Gradle modules

**Files:**
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ComposeOpenApiTask.java`
- Modify: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModulesPlugin.java`
- Test: `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModulesPluginTest.java`

**Step 1: Add a failing TestKit test**

Create fixture modules `alpha` and `beta`, each with an OpenAPI fragment. Select only `alpha`, run `:control-plane:processResources`, and assert:

```java
Path output = projectDir.resolve(
        "control-plane/build/generated/openapi/META-INF/resources/openapi.yaml");
assertThat(output).exists();
assertThat(Files.readString(output)).contains("/alpha").doesNotContain("/beta");
assertThat(result.task(":control-plane:composeControlPlaneOpenApi").getOutcome())
        .isEqualTo(TaskOutcome.SUCCESS);
```

**Step 2: Verify red**

```bash
./gradlew -p platform/gradle-plugin test \
  --tests '*ControlPlaneModulesPluginTest*compose*' --no-daemon
```

Expected: `composeControlPlaneOpenApi` is missing.

**Step 3: Implement a cacheable task**

`ComposeOpenApiTask` exposes:

```java
@InputFile @PathSensitive(PathSensitivity.RELATIVE)
public abstract RegularFileProperty getCoreDocument();
@InputFiles @PathSensitive(PathSensitivity.RELATIVE)
public abstract ConfigurableFileCollection getFragments();
@Input public abstract ListProperty<String> getModuleIds();
@OutputFile public abstract RegularFileProperty getOutputFile();
```

The task pairs sorted IDs with files and delegates to `OpenApiComposer`.

Extend `ControlPlaneModulesPlugin` only for project `:control-plane`: register `composeControlPlaneOpenApi`, select fragments that exist under `platform/modules/<id>/openapi.yaml`, output `build/generated/openapi/META-INF/resources/openapi.yaml`, and make `processResources` include that generated directory. Do not write files during Gradle configuration.

**Step 4: Verify green and commit**

```bash
./gradlew -p platform/gradle-plugin test \
  --tests '*ControlPlaneModulesPluginTest*compose*' --no-daemon
git add platform/gradle-plugin
git commit -m "feat: package the selected OpenAPI contract"
```

### Task 3: Split the API contract into module-owned fragments

**Files:**
- Move: `openapi.yaml` → `openapi/core.yaml`
- Create: `platform/modules/runtime-config/openapi.yaml`
- Create: `platform/modules/build-metadata/openapi.yaml`
- Create: `platform/modules/async-queue/openapi.yaml`
- Create: `platform/modules/sync-queue/openapi.yaml`
- Create: `platform/modules/offload/openapi.yaml`
- Modify: `platform/control-plane/build.gradle`
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/IssueCoverageTest.java`

**Step 1: Move the source contract**

Use `git mv openapi.yaml openapi/core.yaml`. Remove runtime-config paths and its exclusive parameters/schemas from core. Keep shared DTO schemas in core.

**Step 2: Add fragments**

- `runtime-config`: moved `/v1/admin/runtime-config...` paths and exclusive schemas.
- `build-metadata`: `GET /modules/build-metadata` and the approved nullable response schemas.
- `async-queue`: overlay `invokeFunctionAsync`, remove `501`, add `202`, `429`, and `500`.
- `sync-queue`: overlay `invokeFunctionSync` response `429` with `Retry-After` and `X-Queue-Reject-Reason`.
- `offload`: overlay `invokeFunctionSync` with `X-NanoFaaS-Offloaded`, `502`, and `504`; do not alter runtime offload behavior.

Use this format:

```yaml
x-nanofaas-overlays:
  - operationId: invokeFunctionSync
    patch:
      responses:
        '502':
          description: Remote offload failed; no local fallback is attempted.
```

**Step 3: Update inputs and existence checks**

In `platform/control-plane/build.gradle`, replace root `openapi.yaml` with `openapi/core.yaml` plus `fileTree('platform/modules') { include '*/openapi.yaml' }`. Update `IssueCoverageTest` to require `openapi/core.yaml`.

**Step 4: Verify default and core-only contracts**

```bash
./gradlew :control-plane:composeControlPlaneOpenApi --no-daemon
rg -n '/modules/build-metadata|/v1/admin/runtime-config|502|504' \
  platform/control-plane/build/generated/openapi/META-INF/resources/openapi.yaml

./gradlew :control-plane:composeControlPlaneOpenApi \
  -PcontrolPlaneModules=none --rerun-tasks --no-daemon
```

Expected: the default contract has selected module contributions; core-only has no module routes and retains async response `501`.

**Step 5: Commit**

```bash
git add openapi platform/modules/*/openapi.yaml platform/control-plane/build.gradle \
  platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/IssueCoverageTest.java
git commit -m "docs: split OpenAPI by control-plane module"
```

### Task 4: Verify selected module routes against the composed contract

**Files:**
- Modify: `platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/OpenApiRouteCoverageTest.java`

**Step 1: Add failing assertions**

Scan `it.unimib.datai.nanofaas`, not only the core API package, and require:

```java
assertThat(declaredRoutes()).contains(
        "get /modules/build-metadata",
        "get /v1/admin/runtime-config");
assertThat(documentedOperations()).containsAll(declaredRoutes());
```

Load `/openapi.yaml` from the test classpath rather than the repository root.

**Step 2: Verify red**

```bash
./gradlew :control-plane:test --tests '*OpenApiRouteCoverageTest' --no-daemon
```

Expected: old discovery/resource loading does not satisfy the new assertions.

**Step 3: Implement and verify both selections**

Continue excluding `/v1/internal/`; normalize method/path pairs. Annotation scanning is sufficient here—composer tests own schema merge behavior and endpoint tests own payload shape.

```bash
./gradlew :control-plane:test --tests '*OpenApiRouteCoverageTest' --no-daemon
./gradlew :control-plane:test --tests '*OpenApiRouteCoverageTest' \
  -PcontrolPlaneModules=none --rerun-tasks --no-daemon
```

Expected: both pass against their generated contract.

**Step 4: Commit**

```bash
git add platform/control-plane/src/test/java/it/unimib/datai/nanofaas/controlplane/api/OpenApiRouteCoverageTest.java
git commit -m "test: cover selected module API routes"
```

### Task 5: Implement the typed build-metadata response

**Files:**
- Create: `platform/modules/build-metadata/src/main/java/it/unimib/datai/nanofaas/modules/buildmetadata/BuildMetadata.java`
- Create: `platform/modules/build-metadata/src/main/java/it/unimib/datai/nanofaas/modules/buildmetadata/BuildMetadataProvider.java`
- Modify: `platform/modules/build-metadata/src/main/java/it/unimib/datai/nanofaas/modules/buildmetadata/BuildMetadataController.java`
- Modify: `platform/modules/build-metadata/src/main/java/it/unimib/datai/nanofaas/modules/buildmetadata/BuildMetadataConfiguration.java`
- Test: `platform/modules/build-metadata/src/test/java/it/unimib/datai/nanofaas/modules/buildmetadata/BuildMetadataProviderTest.java`
- Test: `platform/modules/build-metadata/src/test/java/it/unimib/datai/nanofaas/modules/buildmetadata/BuildMetadataControllerTest.java`

**Step 1: Write failing provider tests**

Use a package-private constructor accepting `Properties`, environment values, system properties, and GC names. Assert:

```java
assertThat(provider(props, env, Map.of("os.arch", "aarch64"), List.of("G1 GC"))
        .get().runtime().architecture()).isEqualTo("arm64");
assertThat(provider(props, env, Map.of("os.arch", "amd64"), List.of())
        .get().runtime().architecture()).isEqualTo("x86_64");
assertThat(provider(new Properties(), Map.of(), Map.of(), List.of()).get().revision())
        .isNull();
```

Also test sorted modules/GC names, kernel from `os.version`, native detection from `org.graalvm.nativeimage.imagecode`, and base images from `NANOFAAS_BUILD_BASE_IMAGE` and `NANOFAAS_RUNTIME_BASE_IMAGE`.

**Step 2: Write a failing controller test**

Instantiate the controller with a provider returning a fixed record and assert `describe()` returns it. Add a `WebTestClient` slice test for exact JSON field names and nullable values.

**Step 3: Verify red**

```bash
./gradlew :control-plane-modules:build-metadata:test --no-daemon
```

Expected: provider/records do not exist.

**Step 4: Implement records and provider**

Use these records:

```java
public record BuildMetadata(
        String version,
        String revision,
        Boolean dirty,
        List<String> modules,
        Build build,
        Runtime runtime) {
    public record Build(String type, String variant, String optimization,
                        BaseImages baseImages) {}
    public record BaseImages(String builder, String runtime) {}
    public record Runtime(String architecture, String kernelVersion,
                          String javaVersion, String vm,
                          List<String> garbageCollectors) {}
}
```

`BuildMetadataProvider` loads `META-INF/nanofaas-build.properties`. Catch malformed/missing optional data, log once, and leave affected fields `null`. Build one immutable response in the constructor. Never expose raw JVM arguments.

Normalize architecture exactly:

```java
return switch (raw.toLowerCase(Locale.ROOT)) {
    case "aarch64", "arm64" -> "arm64";
    case "amd64", "x86_64", "x64" -> "x86_64";
    default -> null;
};
```

The production factory uses `System.getenv()`, `System.getProperties()`, and `ManagementFactory.getGarbageCollectorMXBeans()`.

**Step 5: Wire controller and verify green**

`BuildMetadataConfiguration` creates one provider and injects it into `BuildMetadataController`. Replace the old placeholder map with `provider.get()`.

```bash
./gradlew :control-plane-modules:build-metadata:test --no-daemon
git add platform/modules/build-metadata/src
git commit -m "feat: report control-plane build identity"
```

### Task 6: Generate immutable build properties

**Files:**
- Modify: `platform/modules/build-metadata/build.gradle`
- Test: `platform/modules/build-metadata/src/test/java/it/unimib/datai/nanofaas/modules/buildmetadata/GeneratedBuildMetadataTest.java`

**Step 1: Add a failing resource test**

Load `META-INF/nanofaas-build.properties` and assert:

```java
assertThat(properties.getProperty("version"))
        .isEqualTo(System.getProperty("project.version"));
assertThat(properties.getProperty("revision")).matches("[0-9a-f]{40}");
assertThat(properties.getProperty("dirty")).isIn("true", "false");
assertThat(properties.getProperty("modules").split(","))
        .contains("build-metadata").isSorted();
assertThat(properties.getProperty("type")).isEqualTo("jvm");
```

Pass `project.version` to this module's test task.

**Step 2: Verify red**

Run the build-metadata tests. Expected: resource not found.

**Step 3: Register `generateBuildMetadata`**

Write sorted `key=value` lines to:

```text
build/generated/build-metadata/META-INF/nanofaas-build.properties
```

Sources:

```text
version      project.version
revision     git rev-parse HEAD
dirty        git status --porcelain --untracked-files=no is non-empty
modules      gradle.ext.nanofaasSelectedControlPlaneModules, sorted
type         -PnanofaasBuildType; else native task detection; else jvm
variant      -PnanofaasBuildVariant, optional
optimization -PnanofaasBuildOptimization; else nativeOptimization; optional
```

Declare every value as a task input, the file as output, and add the generated directory to `sourceSets.main.output` with `builtBy`. Do not use `Properties.store`, which inserts a timestamp. Git failures omit revision/dirty rather than failing compilation. Ignore untracked files when calculating dirty state.

**Step 4: Verify JVM/native metadata without native compilation**

```bash
./gradlew :control-plane-modules:build-metadata:processResources \
  --rerun-tasks --no-daemon
rg -n 'type=jvm|modules=.*build-metadata' \
  platform/modules/build-metadata/build/generated/build-metadata/META-INF/nanofaas-build.properties

./gradlew :control-plane-modules:build-metadata:processResources \
  -PnanofaasBuildType=native -PnanofaasBuildVariant=native-o3 \
  -PnanofaasBuildOptimization=3 --rerun-tasks --no-daemon
rg -n 'type=native|variant=native-o3|optimization=3' \
  platform/modules/build-metadata/build/generated/build-metadata/META-INF/nanofaas-build.properties
```

**Step 5: Test and commit**

```bash
./gradlew :control-plane-modules:build-metadata:test --no-daemon
git add platform/modules/build-metadata
git commit -m "build: embed resolved control-plane metadata"
```

### Task 7: Record Docker base images and native build dimensions

**Files:**
- Modify: `platform/control-plane/Dockerfile`
- Modify: `deploy/compose/Dockerfile`
- Modify: `deploy/native-java/Dockerfile`
- Modify: `scripts/native-java-image.sh`
- Test: extend the existing Dockerfile/script contract tests located with `rg 'distroless/base-debian13|native-java/Dockerfile' --glob '*Test*' --glob 'test_*'`.

**Step 1: Add failing static contract tests**

Assert each control-plane Dockerfile declares and uses:

```text
ARG BUILDER_IMAGE=<current builder>
ARG RUNTIME_IMAGE=<current runtime>
FROM ${BUILDER_IMAGE} AS ...
FROM ${RUNTIME_IMAGE}
ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}
ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}
```

Assert `native-java-image.sh` passes `-PnanofaasBuildType=native`, optional `-PnanofaasBuildVariant`, and normalized optimization through `GRADLE_ARGS`.

**Step 2: Verify red with the focused tests**

Expected: ARG/ENV and metadata-property assertions fail.

**Step 3: Parameterize without changing defaults**

Defaults remain:

```text
JVM builder:    eclipse-temurin:25-jdk
JVM runtime:    gcr.io/distroless/base-debian13:nonroot
native builder: oraclelinux:9-slim
native runtime: gcr.io/distroless/cc-debian13:nonroot
```

Declare global ARGs before the first `FROM`, redeclare them where Docker requires, and set final ENV values. Do not change users, packages, entrypoints, or tuning.

Append metadata Gradle properties in `native-java-image.sh`, omitting optional empty values.

**Step 4: Verify Dockerfiles and commit**

```bash
docker build --check -f platform/control-plane/Dockerfile platform/control-plane
docker build --check -f deploy/native-java/Dockerfile .
git add platform/control-plane/Dockerfile deploy/compose/Dockerfile \
  deploy/native-java/Dockerfile scripts/native-java-image.sh
git commit -m "build: expose control-plane image provenance"
```

### Task 8: Pass variant identity from NanoLab

**Repository:** `/Users/micheleciavotta/Downloads/nanolab`

**Files:**
- Modify: `packages/nanolab/src/nanolab/images/control_plane_variants.py`
- Modify: `packages/nanolab/src/nanolab/images/plan.py`
- Test: `packages/nanolab/tests/images/test_control_plane_variants.py`
- Test: `packages/nanolab/tests/images/test_plan.py`
- Test: `packages/nanolab/tests/images/test_bake.py`

**Step 1: Create a NanoLab worktree**

Use `superpowers:using-git-worktrees` and branch `feat/build-metadata-propagation`. Do not edit NanoLab `main`.

**Step 2: Write failing propagation tests**

For each comparison variant, require its build operations to carry its key:

```python
ops = build_operations(variant, registry=REGISTRY, modules=MODULES)
values = [value for op in ops for value in (*op.argv, *op.env.values())]
assert any(variant.key in value for value in values)
```

For release Bake control-plane cells, assert JVM/native build arguments include build type, the release profile key, and normalized optimization. Ensure function images do not receive control-plane metadata.

**Step 3: Verify red**

```bash
UV_CACHE_DIR=/tmp/nanolab-uv-cache NANOFAAS_ROOT=<nanofaas-worktree> \
  uv run pytest packages/nanolab/tests/images/test_control_plane_variants.py \
  packages/nanolab/tests/images/test_plan.py \
  packages/nanolab/tests/images/test_bake.py -q
```

**Step 4: Propagate explicit identity**

- JVM comparison bootJar: append `-PnanofaasBuildType=jvm`, `-PnanofaasBuildVariant=<key>`, and `-PnanofaasBuildOptimization=c1|c2` derived once from the existing catalogue.
- Native comparison environment: add `NANOFAAS_BUILD_VARIANT=<key>`; the native script translates optimization.
- Release JVM control plane: identify the existing `jvm-c2` profile.
- Release native control plane: identify the existing `native-o3-g1` profile.

NanoLab owns campaign aliases; NanoFaaS must not infer them from image tags.

**Step 5: Verify and commit in NanoLab**

Run Step 3 and expect all tests to pass.

```bash
git add packages/nanolab/src/nanolab/images packages/nanolab/tests/images
git commit -m "build: propagate control-plane build identity"
```

### Task 9: Document and verify the complete feature

**Files:**
- Modify: `platform/modules/build-metadata/README.md`
- Modify: `docs/control-plane.md`
- Modify: `paper/cap/16-moduli-diagnostici.tex`
- Modify: `paper/cap/A-api.tex`

**Step 1: Update documentation**

Document the exact JSON, nullable fields, normalized architectures, base-image meaning, generated `/openapi.yaml`, and the rule that runtime-conditional APIs remain in the artifact contract.

**Step 2: Run focused verification**

```bash
./gradlew -p platform/gradle-plugin test --no-daemon
./gradlew :control-plane-modules:build-metadata:test \
  :control-plane:test --tests '*OpenApiRouteCoverageTest' --no-daemon
./gradlew :control-plane:bootJar --no-daemon
jar tf platform/control-plane/build/libs/app.jar | rg \
  'META-INF/nanofaas-build.properties|META-INF/resources/openapi.yaml'
```

Expected: tests pass and both generated resources are packaged.

**Step 3: Verify module selections**

```bash
./gradlew :control-plane:test --tests '*OpenApiRouteCoverageTest' \
  -PcontrolPlaneModules=none --rerun-tasks --no-daemon
./gradlew :control-plane:test --tests '*OpenApiRouteCoverageTest' \
  -PcontrolPlaneModules=build-metadata --rerun-tasks --no-daemon
```

Expected: each passes against its contract; only the second includes `/modules/build-metadata`.

**Step 4: Run repository checks**

```bash
./gradlew test --no-daemon
git diff --check
git status --short
```

Before merge, run one native smoke build where GraalVM/container resources are available:

```bash
NANOFAAS_BUILD_VARIANT=native-o3 NATIVE_OPTIMIZATION=3 \
  ./scripts/native-java-image.sh control-plane nanofaas/control-plane:metadata-smoke
```

Start it and verify type, variant, base images, architecture, and kernel from `/modules/build-metadata`.

**Step 5: Commit docs**

```bash
git add platform/modules/build-metadata/README.md docs/control-plane.md \
  paper/cap/16-moduli-diagnostici.tex paper/cap/A-api.tex
git commit -m "docs: describe artifact-specific API metadata"
```

**Step 6: Review before integration**

Use `superpowers:requesting-code-review`, then `superpowers:verification-before-completion`. Review specifically for runtime SnakeYAML leakage, nondeterministic resources, secret-bearing JVM arguments, module/fragment mismatch, silent overlay conflicts, changed offload behavior, and requested values reported as observed runtime facts.
