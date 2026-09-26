# Recipes v2, part 2: container builder — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a native Java component declare `build.builder: container`. It is then compiled
inside `deploy/native-java/Dockerfile`'s GraalVM builder instead of on the host, and packaged,
reported and pinned exactly like a host-built one.

**Architecture:**
- **Dockerfile.** The shared builder gains a BuildKit Gradle cache and a `native-executable`
  export stage.
- **Recipe plugin.** It registers one `docker build --target native-executable --output
  type=local,dest=<staging>` task per container-built component, in place of the host
  `nativeCompile`. The Gradle arguments for that build come from a small pure class,
  `RecipeContainerBuild`.
- **`NativeOptions`.** It carries `builder` and `distribution`, which also feed the report.

**Tech Stack:** Gradle 9.7 settings plugin (Java 25), NetworkNT JSON Schema 2.0.7, Gradle
TestKit, BuildKit (Docker 29 here), pytest via `uv`.

**Spec:** `docs/superpowers/specs/2026-09-26-recipes-v2-container-builder-design.md`

## Global Constraints

- Branch `feat/218-recipes-v2-container-builder`.
- `recipe-v1.schema.json` stays byte-for-byte unchanged.
- `builder`:
  - values `host | container`, default `host`;
  - allowed only with `mode: native`;
  - present on `controlPlaneBuild` and `javaBuild` only.
- Distribution: with `container`, `oracle` when the effective `gc` is `G1`, otherwise `community`.
  With `host`, no distribution is recorded.
- Container build command, in exactly this order:
  - `docker build -f <root>/deploy/native-java/Dockerfile --target native-executable`;
  - `--output type=local,dest=<output>/<stagingDir>`;
  - `--build-context containerd_maven_repo=<repo|<root>/deploy/native-java/empty-maven-repo>`;
  - `--build-arg NATIVE_TASK=…`, `--build-arg NATIVE_BINARY=…`, `--build-arg GRAALVM_DISTRIBUTION=…`,
    `--build-arg GRADLE_ARGS=…`;
  - the build context, `<root>`.
- The container build task runs with the environment variable `DOCKER_BUILDKIT=1`.
- No container build argument may contain whitespace: the Dockerfile word-splits `$GRADLE_ARGS`.
- Measured on Docker 29.2.1: the BuildKit `local` exporter overwrites files in the destination and
  keeps the others. So export straight into the staging directory, with no temporary directory.
- `CLAUDE.md` rules:
  - run `impact` before editing a symbol, and `detect_changes` before each commit;
  - write everything in English;
  - use 4-space indentation.
- Commit messages end with:

  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
  ```

- Commands:
  - plugin suite: `./gradlew -p platform/gradle-plugin test`;
  - script tests: `uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py`.

## Review Focus

1. **A pass-through value with whitespace** (`-PnativeBuildMemory="6 g"`) would be split silently
   by the Dockerfile's `$GRADLE_ARGS`. It must fail before any build, naming the value. Tests:
   Task 3 (unit) and Task 4 (TestKit).
2. **A container-built native component without `container.image`** must still be compiled and
   staged. Today only image tasks pull builds in. Test: Task 4.
3. **The control plane's container build** must carry its module selection (`none` for `[]`), its
   build identity and the host's revision/dirty state. Otherwise `/modules/build-metadata` is
   wrong. Tests: Task 3 (unit) and Task 6 (real build).
4. **The containerd module with `builder: container` but no staged repository** must fail at
   configuration with a named error, not ten minutes into a build. Tests: Task 3 (unit) and
   Task 4 (TestKit).
5. **A non-Linux host with `builder: container`** must be accepted, and with `builder: host`
   still rejected. Test: Task 4 (unit, on the extracted host check).

---

### Task 1: `builder` in schema v2, and in `NativeOptions`

**Files:**
- Modify: `platform/gradle-plugin/src/main/resources/recipes/recipe-v2.schema.json`
  (`$defs.javaBuild`, `$defs.controlPlaneBuild`, `$defs.nativeOptionsNeedNativeMode`)
- Modify: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeBuildProperties.java`
  (`NativeOptions`, `effectiveNative`)
- Test: `RecipeReaderTest.java`, `RecipeBuildPropertiesTest.java`

**Interfaces:**
- Produces: `record NativeOptions(String optimization, String gc, List<String> monitoring, String builder, String distribution)`.
  - `builder` is `"host"` or `"container"`.
  - `distribution` is `"community"` or `"oracle"` when `builder` is `container`, else `null`.

- [ ] **Step 1: Write the failing tests**

In `RecipeReaderTest`, add to `invalidV2Recipes()`, before `"string version"`:

```java
                Arguments.of("builder in jvm mode", head
                        + "controlPlane: {modules: [], build: {mode: jvm, builder: container}}\n", "builder"),
                Arguments.of("unknown builder", head
                        + "controlPlane: {modules: [], build: {mode: native, builder: cloud}}\n", "builder"),
```

and add:

```java
    @Test
    void acceptsTheContainerBuilderOnNativeComponents() throws IOException {
        Path file = write("""
                schemaVersion: 2
                name: demo
                controlPlane: {modules: [], build: {mode: native, builder: container}}
                functions: [{name: word-stats, sdk: java, build: {mode: native, builder: host}}]
                services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container}}]
                """);

        JsonNode data = new RecipeReader().read(file, null).data();

        assertThat(data.at("/controlPlane/build/builder").asText()).isEqualTo("container");
        assertThat(data.at("/services/0/build/builder").asText()).isEqualTo("container");
    }
```

In `RecipeBuildPropertiesTest`, change the expectation in
`effectiveNativeAppliesDefaultsAndTheG1Rule` to
`new RecipeBuildProperties.NativeOptions("3", "G1", List.of("jvmstat", "jfr"), "host", null)`, and add:

```java
    @Test
    void containerBuilderPicksTheDistributionFromTheCollector() throws IOException {
        JsonNode data = data("""
                controlPlane: {modules: [], build: {mode: native, builder: container, native: {gc: G1}}}
                services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container}}]
                """);

        assertThat(RecipeBuildProperties.effectiveNative(data.get("controlPlane")))
                .isEqualTo(new RecipeBuildProperties.NativeOptions("3", "G1", List.of("jfr"), "container", "oracle"));
        assertThat(RecipeBuildProperties.effectiveNative(data.at("/services/0")))
                .isEqualTo(new RecipeBuildProperties.NativeOptions("3", "serial", List.of(), "container", "community"));
    }
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew -p platform/gradle-plugin test --tests '*RecipeReaderTest' --tests '*RecipeBuildPropertiesTest'`

Expected: compilation fails, because `NativeOptions` has three components.

- [ ] **Step 3: Implement**

In `recipe-v2.schema.json`:
- add to the `properties` of both `javaBuild` and `controlPlaneBuild`:

```json
        "builder": {"description": "Where a native component compiles: host (default) or container (deploy/native-java/Dockerfile's builder).", "enum": ["host", "container"]}
```

- make `nativeOptionsNeedNativeMode`'s `then` read
  `{"properties": {"native": false, "builder": false}}`.

In `RecipeBuildProperties`, replace the record and the end of `effectiveNative`:

```java
    /** {@code distribution} is the GraalVM the container builder installs; null for the host builder, which uses its own. */
    record NativeOptions(String optimization, String gc, List<String> monitoring, String builder, String distribution) {
    }
```

```java
        String builder = component.path("build").path("builder").asText("host");
        // The container builder installs Oracle GraalVM only for G1: Community's Native Image has no G1.
        String distribution = builder.equals("container") ? (gc.equals("G1") ? "oracle" : "community") : null;
        return new NativeOptions(options.path("optimization").asText("3"), gc, List.copyOf(monitoring), builder, distribution);
```

- [ ] **Step 4: Run the plugin suite**

Run: `./gradlew -p platform/gradle-plugin test`

Expected: all PASS. The report's `native` object only reads the first three components, so its
tests are unchanged.

- [ ] **Step 5: Commit** (`detect_changes` first)

```bash
git add platform/gradle-plugin/src/main/resources/recipes/recipe-v2.schema.json \
        platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeBuildProperties.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeReaderTest.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeBuildPropertiesTest.java
git commit -m "Accept build.builder in recipe v2 and derive the container's GraalVM"   # + attribution lines
```

---

### Task 2: The shared builder: Gradle cache and `native-executable` stage

**Files:**
- Modify: `deploy/native-java/Dockerfile`
- Test: `scripts/tests/test_java_container_images.py`

**Interfaces:**
- Produces: the Dockerfile target `native-executable`, holding only `/application`. The final
  stage remains last.

- [ ] **Step 1: Write the failing test**

Append to `scripts/tests/test_java_container_images.py`:

```python
def test_native_builder_exports_the_executable_and_caches_gradle():
    """assembleRecipe's container builder exports only /application from `native-executable`;
    the release keeps building the default (last) stage, so that one must stay the runtime image."""
    dockerfile = (REPO_ROOT / "deploy/native-java/Dockerfile").read_text(encoding="utf-8")
    stages = [line.split() for line in dockerfile.splitlines() if line.startswith("FROM ")]

    assert ["FROM", "scratch", "AS", "native-executable"] in stages
    assert stages[-1] == ["FROM", "${RUNTIME_IMAGE}"], "the release's default target must stay the runtime image"
    assert "COPY --from=builder /tmp/application /application" in dockerfile
    gradle = next(line for line in dockerfile.splitlines() if "./gradlew" in line)
    assert "--mount=type=cache,target=/root/.gradle" in gradle
```

- [ ] **Step 2: Run to see it fail**

Run: `uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py`

Expected: 1 failed (the stage is missing), 6 passed.

- [ ] **Step 3: Implement**

In `deploy/native-java/Dockerfile`, change the Gradle `RUN` line to:

```dockerfile
RUN --mount=type=cache,target=/root/.gradle ./gradlew "$NATIVE_TASK" $GRADLE_ARGS --no-daemon && \
```

Keep the two continuation lines after it unchanged. Put a comment above the `RUN`:

```dockerfile
# The Gradle user home lives in a BuildKit cache mount: dependencies and the Gradle distribution
# survive across builds (nanolab assembles several variants in a row). It is not part of the image.
```

Insert immediately before `FROM ${RUNTIME_IMAGE}`:

```dockerfile
# assembleRecipe's container builder exports only the executable
# (docker build --target native-executable --output type=local,...) and packages it itself.
# The release builds the default target, the final stage below, which must stay last.
FROM scratch AS native-executable
COPY --from=builder /tmp/application /application

```

- [ ] **Step 4: Run the tests**

Run: `uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py`

Expected: 7 passed.

- [ ] **Step 5: Commit** (`detect_changes` first)

```bash
git add deploy/native-java/Dockerfile scripts/tests/test_java_container_images.py
git commit -m "Cache Gradle in the native builder and add an executable-only export stage"   # + attribution lines
```

---

### Task 3: `RecipeContainerBuild`: Gradle arguments, command and containerd check

**Files:**
- Create: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuild.java`
- Test: create `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuildTest.java`

**Interfaces:**
- Consumes:
  - `RecipeBuildProperties.byProject(JsonNode)`;
  - the constants `CONTROL_PLANE = ":control-plane"` and
    `BUILD_METADATA_PROJECT = ":control-plane-modules:build-metadata"`.
- Produces:
  - constants `DOCKERFILE = "deploy/native-java/Dockerfile"`, `TARGET = "native-executable"`,
    `EMPTY_MAVEN_REPOSITORY = "deploy/native-java/empty-maven-repo"`,
    `CONTAINERD_MODULE = "containerd-deployment-provider"` and
    `PASS_THROUGH = List.of("nativeBuildMemory", "nativeParallelism")`;
  - `static List<String> gradleArgs(Path recipeSource, JsonNode data, String projectPath, List<String> modules, JsonNode source, Map<String, String> passThrough)`;
  - `static List<String> command(String docker, Path rootDir, Path destination, String nativeTask, String nativeBinary, String distribution, List<String> gradleArgs, Path containerdRepository)`,
    where a null `containerdRepository` means the empty repository;
  - `static void requireContainerdRepository(Path recipeSource, List<String> modules, Object containerdMavenLocal, String mavenRepoLocal)`.

- [ ] **Step 1: Write the failing tests**

```java
package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import org.gradle.api.GradleException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecipeContainerBuildTest {

    private static final Path RECIPE = Path.of("recipe.yaml");
    private static final String REVISION = "a".repeat(40);

    @TempDir
    Path dir;

    private JsonNode data(String yaml) throws IOException {
        Path file = dir.resolve("recipe.yaml");
        Files.writeString(file, "schemaVersion: 2\nname: demo\n" + yaml);
        return new RecipeReader().read(file, null).data();
    }

    private static JsonNode source(boolean dirty) throws IOException {
        return new ObjectMapper().readTree("{\"revision\": \"" + REVISION + "\", \"dirty\": " + dirty + "}");
    }

    @Test
    void controlPlaneCarriesModulesIdentityAndRevision() throws IOException {
        JsonNode data = data("""
                controlPlane: {modules: [build-metadata], build: {mode: native, builder: container, variant: native-os,
                               native: {optimization: s, gc: G1}}}
                """);

        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, data, ":control-plane", List.of("build-metadata"),
                source(true), Map.of("nativeBuildMemory", "6g"))).containsExactly(
                "-PnanofaasBuildType=native", "-PnativeOptimization=s", "-PnativeGc=G1",
                "-PcontrolPlaneModules=build-metadata",
                "-PnanofaasBuildOptimization=s", "-PnanofaasBuildVariant=native-os",
                "-PnanofaasBuildRevision=" + REVISION, "-PnanofaasBuildDirty=true",
                "-PnativeBuildMemory=6g");
    }

    @Test
    void emptyModulesSelectNoneAndContainerdGetsItsRepository() throws IOException {
        JsonNode data = data("controlPlane: {modules: [], build: {mode: native, builder: container}}\n");
        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, data, ":control-plane", List.of(), NullNode.getInstance(),
                Map.of())).containsExactly("-PnanofaasBuildType=native", "-PcontrolPlaneModules=none");

        JsonNode containerd = data("""
                controlPlane: {modules: [containerd-deployment-provider], build: {mode: native, builder: container}}
                """);
        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, containerd, ":control-plane",
                List.of("containerd-deployment-provider"), NullNode.getInstance(), Map.of()))
                .containsSubsequence("-PcontrolPlaneModules=containerd-deployment-provider",
                        "-PcontainerdMavenLocal=true", "-Dmaven.repo.local=/tmp/containerd-m2");
    }

    @Test
    void functionsAndServicesGetOnlyTheirOwnNativeFlags() throws IOException {
        JsonNode data = data("""
                controlPlane: {modules: [build-metadata], build: {mode: jvm, variant: jvm}}
                services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container, native: {optimization: 2}}}]
                """);

        assertThat(RecipeContainerBuild.gradleArgs(RECIPE, data, ":services:java:warm-echo", List.of("build-metadata"),
                source(false), Map.of("nativeParallelism", "2"))).containsExactly(
                "-PnanofaasBuildType=native", "-PnativeOptimization=2", "-PnativeParallelism=2");
    }

    @Test
    void rejectsArgumentsTheDockerfileWouldSplit() throws IOException {
        JsonNode data = data("controlPlane: {modules: [], build: {mode: native, builder: container}}\n");

        assertThatThrownBy(() -> RecipeContainerBuild.gradleArgs(RECIPE, data, ":control-plane", List.of(),
                NullNode.getInstance(), Map.of("nativeBuildMemory", "6 g")))
                .isInstanceOf(GradleException.class)
                .hasMessageContaining("-PnativeBuildMemory=6 g")
                .hasMessageContaining("whitespace");
    }

    @Test
    void commandTargetsTheExportStage() {
        Path root = Path.of("/repo");

        assertThat(RecipeContainerBuild.command("docker", root, Path.of("/out/control-plane"), ":control-plane:nativeCompile",
                "platform/control-plane/build/native/nativeCompile/control-plane", "oracle",
                List.of("-PnanofaasBuildType=native", "-PcontrolPlaneModules=none"), null)).containsExactly(
                "docker", "build", "-f", "/repo/deploy/native-java/Dockerfile", "--target", "native-executable",
                "--output", "type=local,dest=/out/control-plane",
                "--build-context", "containerd_maven_repo=/repo/deploy/native-java/empty-maven-repo",
                "--build-arg", "NATIVE_TASK=:control-plane:nativeCompile",
                "--build-arg", "NATIVE_BINARY=platform/control-plane/build/native/nativeCompile/control-plane",
                "--build-arg", "GRAALVM_DISTRIBUTION=oracle",
                "--build-arg", "GRADLE_ARGS=-PnanofaasBuildType=native -PcontrolPlaneModules=none",
                "/repo");
    }

    @Test
    void containerdNeedsTheStagedRepository() {
        List<String> modules = List.of("containerd-deployment-provider");

        assertThatThrownBy(() -> RecipeContainerBuild.requireContainerdRepository(RECIPE, modules, null, null))
                .hasMessageContaining("-PcontainerdMavenLocal=true").hasMessageContaining("-Dmaven.repo.local");
        RecipeContainerBuild.requireContainerdRepository(RECIPE, modules, "true", "/staged");
        RecipeContainerBuild.requireContainerdRepository(RECIPE, List.of("async-queue"), null, null);
    }
}
```

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew -p platform/gradle-plugin test --tests '*RecipeContainerBuildTest'`

Expected: compilation fails, because `RecipeContainerBuild` does not exist.

- [ ] **Step 3: Implement**

```java
package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The pure parts of a containerized native build: the Gradle arguments the builder runs with, the docker build command
 * that exports the executable, and the containerd precondition. The container gets explicit -P flags, never -Precipe:
 * the recipe file may live outside the build context.
 */
final class RecipeContainerBuild {

    static final String DOCKERFILE = "deploy/native-java/Dockerfile";
    static final String TARGET = "native-executable";
    static final String EMPTY_MAVEN_REPOSITORY = "deploy/native-java/empty-maven-repo";
    static final String CONTAINERD_MODULE = "containerd-deployment-provider";
    /** Builder-sizing flags of the invocation, passed into the container too. */
    static final List<String> PASS_THROUGH = List.of("nativeBuildMemory", "nativeParallelism");
    private static final List<String> NATIVE_FLAGS = List.of("nativeOptimization", "nativeGc", "nativeMonitoring");

    private RecipeContainerBuild() {
    }

    /** @param source the report's source node (revision and dirty state), or a null node when Git is unavailable */
    static List<String> gradleArgs(Path recipeSource, JsonNode data, String projectPath, List<String> modules,
                                   JsonNode source, Map<String, String> passThrough) {
        Map<String, Map<String, String>> byProject = RecipeBuildProperties.byProject(data);
        List<String> args = new ArrayList<>(List.of("-PnanofaasBuildType=native"));
        Map<String, String> own = byProject.getOrDefault(projectPath, Map.of());
        NATIVE_FLAGS.stream().filter(own::containsKey).forEach(key -> args.add("-P" + key + "=" + own.get(key)));
        if (projectPath.equals(RecipeBuildProperties.CONTROL_PLANE)) {
            // A blank selector falls back to the default modules; none is the explicit empty selection.
            args.add("-PcontrolPlaneModules=" + (modules.isEmpty() ? "none" : String.join(",", modules)));
            new TreeMap<>(byProject.getOrDefault(RecipeBuildProperties.BUILD_METADATA_PROJECT, Map.of()))
                    .forEach((key, value) -> args.add("-P" + key + "=" + value));
            // The build context has no .git: the host's revision and dirty state go in explicitly.
            if (source != null && source.isObject()) {
                args.add("-PnanofaasBuildRevision=" + source.get("revision").asText());
                if (source.path("dirty").isBoolean()) {
                    args.add("-PnanofaasBuildDirty=" + source.get("dirty").asBoolean());
                }
            }
            if (modules.contains(CONTAINERD_MODULE)) {
                args.add("-PcontainerdMavenLocal=true");
                args.add("-Dmaven.repo.local=/tmp/containerd-m2");
            }
        }
        PASS_THROUGH.stream().filter(passThrough::containsKey)
                .forEach(key -> args.add("-P" + key + "=" + passThrough.get(key)));
        for (String arg : args) {
            if (arg.chars().anyMatch(Character::isWhitespace)) {
                throw RecipeReader.failure(recipeSource, "container builder argument '" + arg + "' contains whitespace;"
                        + " the builder's GRADLE_ARGS is word-split, so the value would be broken apart");
            }
        }
        return List.copyOf(args);
    }

    static List<String> command(String docker, Path rootDir, Path destination, String nativeTask, String nativeBinary,
                                String distribution, List<String> gradleArgs, Path containerdRepository) {
        Path repository = containerdRepository != null ? containerdRepository : rootDir.resolve(EMPTY_MAVEN_REPOSITORY);
        return List.of(docker, "build", "-f", rootDir.resolve(DOCKERFILE).toString(), "--target", TARGET,
                "--output", "type=local,dest=" + destination,
                "--build-context", "containerd_maven_repo=" + repository,
                "--build-arg", "NATIVE_TASK=" + nativeTask,
                "--build-arg", "NATIVE_BINARY=" + nativeBinary,
                "--build-arg", "GRAALVM_DISTRIBUTION=" + distribution,
                "--build-arg", "GRADLE_ARGS=" + String.join(" ", gradleArgs),
                rootDir.toString());
    }

    static void requireContainerdRepository(Path recipeSource, List<String> modules, Object containerdMavenLocal,
                                            String mavenRepoLocal) {
        if (modules.contains(CONTAINERD_MODULE)
                && (!"true".equals(String.valueOf(containerdMavenLocal)) || mavenRepoLocal == null)) {
            throw RecipeReader.failure(recipeSource, "controlPlane.modules: " + CONTAINERD_MODULE
                    + " with builder: container needs -PcontainerdMavenLocal=true and -Dmaven.repo.local=<the staged"
                    + " repository from scripts/bootstrap-containerd-dependencies.sh>");
        }
    }
}
```

- [ ] **Step 4: Run the tests**

Run: `./gradlew -p platform/gradle-plugin test --tests '*RecipeContainerBuildTest'`

Expected: PASS (6 tests).

- [ ] **Step 5: Commit** (`detect_changes` first)

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuild.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuildTest.java
git commit -m "Compute the container builder's Gradle arguments and command"   # + attribution lines
```

---

### Task 4: Build container-built components through the builder

**Files:**
- Modify: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeTasks.java`
  (`Target.containerBuilt`, the host check, the preview)
- Modify: `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java`
  (`register`: staging filter, container build tasks, image dependencies, report `native` fields)
- Test: `RecipePluginTest.java`

**Interfaces:**
- Consumes: `NativeOptions.builder()/distribution()` (Task 1) and every `RecipeContainerBuild`
  member (Task 3).
- Produces:
  - `boolean Target.containerBuilt()`;
  - `static String hostProblem(String osName, Target target)` in `RecipeTasks`;
  - tasks named `recipeNativeBuild<N>`;
  - the report's `native.builder`, and `native.distribution` when non-null.

- [ ] **Step 1: Teach the fake Docker `--output`, then write the failing tests**

In `writeFixture()`'s `bin/docker` script, add after the `{ printf ... } >> "$log/docker.log"`
line:

```sh
                if [ "$1" = build ]; then
                  prev=""
                  for a in "$@"; do
                    if [ "$prev" = --output ]; then
                      d="${a#type=local,dest=}"; mkdir -p "$d"; printf '#!/bin/sh\\n' > "$d/application"; chmod +x "$d/application"
                    fi
                    prev="$a"
                  done
                fi
```

Put that block after the existing `if [ -f "$log/fail-$1" ]` line, so a `fail-build` file still
fails every build. Then add these tests:

```java
    private List<String> containerBuild() throws IOException {
        return dockerCalls().stream().filter(call -> call.contains("native-executable")).findFirst()
                .orElseThrow(() -> new AssertionError("no container build in " + dockerCalls()));
    }

    @Test
    void containerBuilderCompilesInsideTheBuilderAndPackagesAsUsual() throws IOException {
        recipe(V2_HEADER + """
                controlPlane:
                  modules: []
                  build: {mode: native, builder: container, native: {gc: G1}}
                  container: {image: control-plane}
                  config: {nanofaas: {metrics: {profile: basic}}}
                """);

        run("assembleRecipe", "-Precipe=recipe.yaml", docker(), "-PnativeParallelism=2");

        Path root = projectDir.toRealPath();
        Path staged = projectDir.resolve("build/recipes/demo/control-plane");
        assertThat(projectDir.resolve("markers/control-plane-nativeCompile")).doesNotExist();
        assertThat(staged.resolve("application")).isExecutable();
        assertThat(staged.resolve("config/recipe.yaml")).content().contains("profile: basic");
        assertThat(containerBuild()).containsExactly("build", "-f", root.resolve("deploy/native-java/Dockerfile").toString(),
                "--target", "native-executable", "--output", "type=local,dest=" + root.resolve("build/recipes/demo/control-plane"),
                "--build-context", "containerd_maven_repo=" + root.resolve("deploy/native-java/empty-maven-repo"),
                "--build-arg", "NATIVE_TASK=:control-plane:nativeCompile",
                "--build-arg", "NATIVE_BINARY=platform/control-plane/build/native/nativeCompile/control-plane",
                "--build-arg", "GRAALVM_DISTRIBUTION=oracle",
                "--build-arg", "GRADLE_ARGS=-PnanofaasBuildType=native -PnativeGc=G1 -PcontrolPlaneModules=none -PnativeParallelism=2",
                root.toString());
        List<String> commands = dockerCalls().stream().map(call -> String.join(" ", call)).toList();
        assertThat(commands.indexOf(String.join(" ", containerBuild())))
                .isLessThan(commands.indexOf(commands.stream().filter(c -> c.contains("Dockerfile.native")).findFirst().orElseThrow()));
        assertThat(report().at("/components/0/native").toString()).isEqualTo(
                "{\"optimization\":\"3\",\"gc\":\"G1\",\"monitoring\":[\"jfr\"],\"builder\":\"container\",\"distribution\":\"oracle\"}");
    }

    @Test
    void hostAndContainerBuildersMixAndImagelessComponentsStillBuild() throws IOException {
        recipe(V2_HEADER + CP_JVM + """
                functions: [{name: word-stats, sdk: java, build: {mode: native}}]
                services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container}}]
                """);

        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        assertThat(projectDir.resolve("markers/word-stats-nativeCompile")).exists();
        assertThat(projectDir.resolve("markers/warm-echo-nativeCompile")).doesNotExist();
        assertThat(projectDir.resolve("build/recipes/demo/services/java/warm-echo/application")).isExecutable();
        assertThat(containerBuild()).contains("NATIVE_TASK=:services:java:warm-echo:nativeCompile",
                "GRAALVM_DISTRIBUTION=community", "GRADLE_ARGS=-PnanofaasBuildType=native");
        assertThat(report().at("/components/1/native/builder").asText()).isEqualTo("host");
        assertThat(report().at("/components/1/native").has("distribution")).isFalse();
    }

    @Test
    void containerBuilderFailsEarlyOnSplittableArgumentsAndMissingContainerdRepository() throws IOException {
        recipe(V2_HEADER + "controlPlane: {modules: [], build: {mode: native, builder: container}}\n");
        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), "-PnativeBuildMemory=6 g"))
                .contains("-PnativeBuildMemory=6 g").contains("whitespace");

        writeModule("containerd-deployment-provider", "");
        recipe(V2_HEADER + "controlPlane: {modules: [containerd-deployment-provider], build: {mode: native, builder: container}}\n");
        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker()))
                .contains("needs -PcontainerdMavenLocal=true");
        assertThat(projectDir.resolve("docker.log")).doesNotExist();
    }

    @Test
    void previewShowsTheContainerBuilder() throws IOException {
        recipe(V2_HEADER + "controlPlane: {modules: [], build: {mode: native, builder: container, native: {gc: G1}}}\n");

        assertThat(run("validateRecipe", "-Precipe=recipe.yaml").getOutput()).contains(
                "docker build -f deploy/native-java/Dockerfile --target native-executable (container builder, oracle)");
    }

    @Test
    void onlyHostBuiltNativeImagesNeedALinuxHost() {
        RecipeTasks.Target host = new RecipeTasks.Target("controlPlane", "control-plane", "control-plane", "java", "native",
                ":control-plane:nativeCompile", null, null, "control-plane/", "img", null, null,
                new RecipeBuildProperties.NativeOptions("3", "serial", List.of(), "host", null));
        RecipeTasks.Target container = new RecipeTasks.Target("controlPlane", "control-plane", "control-plane", "java",
                "native", ":control-plane:nativeCompile", null, null, "control-plane/", "img", null, null,
                new RecipeBuildProperties.NativeOptions("3", "serial", List.of(), "container", "community"));

        assertThat(RecipeTasks.hostProblem("Mac OS X", host)).contains("Linux host");
        assertThat(RecipeTasks.hostProblem("Mac OS X", container)).isNull();
        assertThat(RecipeTasks.hostProblem("Linux", host)).isNull();
    }
```

About the whitespace case: `"-PnativeBuildMemory=6 g"` is one TestKit argument, so Gradle
receives the value `6 g`.

The containerd case fails at configuration. It needs `-PcontainerdMavenLocal` to be absent, and
the fixture does not set it.

- [ ] **Step 2: Run to see them fail**

Run: `./gradlew -p platform/gradle-plugin test --tests '*RecipePluginTest'`

Expected: the five new tests FAIL. The control plane is still compiled on the host,
`hostProblem` does not exist, and the preview shows the Gradle task.

- [ ] **Step 3: Implement it in `RecipeTasks`**

Run impact first on `Target`, `resolve` and `printPreview`.

1. **`Target`.** Add, beside `controlPlane()`:

```java
        boolean containerBuilt() {
            return nativeOptions != null && nativeOptions.builder().equals("container");
        }
```

2. **Host check.** Replace `nativeImageHostProblem` and its loop in `resolve()`:

```java
    /** GraalVM cannot cross-compile: a host-built executable is copied as-is into a Linux image. The container builder is Linux. */
    static String hostProblem(String osName, Target target) {
        if (osName.startsWith("Linux") || !target.mode().equals("native") || target.image() == null || target.containerBuilt()) {
            return null;
        }
        return "a native image needs a Linux host, but the executable would be compiled on " + osName
                + "; use build.builder: container";
    }
```

```java
        for (Target target : resolved) {
            String problem = hostProblem(System.getProperty("os.name"), target);
            if (problem != null) {
                throw fail(target.field() + ".container.image: " + problem);
            }
        }
```

   Update `RecipePluginTest.nativeImagesNeedALinuxHost`, which calls the removed
   `nativeImageHostProblem`. Delete it: `onlyHostBuiltNativeImagesNeedALinuxHost` replaces it.

3. **Preview.** In `printPreview`, compute `build` as:

```java
            String build = target.containerBuilt()
                    ? "docker build -f " + RecipeContainerBuild.DOCKERFILE + " --target " + RecipeContainerBuild.TARGET
                            + " (container builder, " + target.nativeOptions().distribution() + ")"
                    : target.task() != null ? target.task()
                    : "docker build -f " + slash(target.dockerfile()) + " "
                            + (target.contextDir().toString().isEmpty() ? "." : slash(target.contextDir()));
```

- [ ] **Step 4: Implement it in `RecipeArtifacts.register`**

Run impact on `register` and `report` first.

1. **Staging.** In the `stageRecipe` configuration, stage only host-built Java outputs:
   `targets.stream().filter(target -> target.task() != null && !target.containerBuilt()).forEach(target -> stageJava(root, sync, target));`.

   Apply the same filter to the `producer(...).configure(task -> task.mustRunAfter(clean))` line.

   Keep `writeRuntimeFiles` for every Java target, so the control plane's `config/recipe.yaml`
   is still staged.
2. **Container build tasks.** Right after `Services services = ...` and `imageIds`, add:

```java
        Map<String, String> passThrough = new java.util.LinkedHashMap<>();
        for (String key : RecipeContainerBuild.PASS_THROUGH) {
            Object value = root.findProperty(key);
            if (value != null) {
                passThrough.put(key, value.toString());
            }
        }
        String mavenRepoLocal = System.getProperty("maven.repo.local");
        if (targets.stream().anyMatch(target -> target.controlPlane() && target.containerBuilt())) {
            RecipeContainerBuild.requireContainerdRepository(recipe.source(), modules,
                    root.findProperty("containerdMavenLocal"), mavenRepoLocal);
        }
        Path containerdRepository = modules.contains(RecipeContainerBuild.CONTAINERD_MODULE) && mavenRepoLocal != null
                ? Path.of(mavenRepoLocal) : null;
        Map<RecipeTasks.Target, TaskProvider<Exec>> containerBuilds = new java.util.LinkedHashMap<>();
        for (RecipeTasks.Target target : targets) {
            if (!target.containerBuilt()) {
                continue;
            }
            String projectPath = target.task().substring(0, target.task().lastIndexOf(':'));
            // Checked now: a whitespace value must fail before anything is built, not inside the builder.
            RecipeContainerBuild.gradleArgs(recipe.source(), recipe.data(), projectPath, modules, NullNode.getInstance(),
                    passThrough);
            containerBuilds.put(target, root.getTasks().register("recipeNativeBuild" + containerBuilds.size(), Exec.class,
                    exec -> {
                        exec.setDescription("Compiles " + target.name() + " natively inside the builder container");
                        exec.dependsOn(stage);
                        exec.environment("DOCKER_BUILDKIT", "1");
                        exec.setWorkingDir(rootDir.toFile());
                        exec.doFirst(ignored -> exec.commandLine(RecipeContainerBuild.command(docker, rootDir,
                                output.resolve(target.stagingDir()), target.task(), nativeBinary(root, target),
                                target.nativeOptions().distribution(),
                                RecipeContainerBuild.gradleArgs(recipe.source(), recipe.data(), projectPath, modules,
                                        source(services.getExec(), rootDir, output), passThrough),
                                containerdRepository)));
                    }));
        }
```

   with the helper:

```java
    /** The executable the builder copies out: the host project's nativeCompile output, relative to the repository. */
    private static String nativeBinary(Project root, RecipeTasks.Target target) {
        RegularFile file = (RegularFile) ((Provider<?>) producer(root, target).get().property("outputFile")).get();
        return root.getRootDir().toPath().relativize(file.getAsFile().toPath()).toString().replace('\\', '/');
    }
```

3. **Image tasks.** Add `if (containerBuilds.containsKey(target)) exec.dependsOn(containerBuilds.get(target));`
   next to `exec.dependsOn(stage);`.
4. **`assembleRecipe`.** Change `task.dependsOn(stage, images);` to
   `task.dependsOn(stage, images, containerBuilds.values());`.
5. **Report.** In `report(...)`, after `.put("gc", target.nativeOptions().gc());`, record the
   builder:

```java
                options.put("builder", target.nativeOptions().builder());
                if (target.nativeOptions().distribution() != null) {
                    options.put("distribution", target.nativeOptions().distribution());
                }
```

   Put these after the `monitoring` array, so the key order is optimization, gc, monitoring,
   builder, distribution.

   Update the exact-string expectation in `reportRecordsKindsIdentityNativeOptionsAndImageIds` to
   `{"optimization":"3","gc":"G1","monitoring":["jvmstat","jfr"],"builder":"host"}`.

- [ ] **Step 5: Run the plugin suite**

Run: `./gradlew -p platform/gradle-plugin test`

Expected: all PASS.

- [ ] **Step 6: Commit** (`detect_changes` first)

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeTasks.java \
        platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipePluginTest.java
git commit -m "Compile container-built recipe components inside the native builder"   # + attribution lines
```

---

### Task 5: Document the container builder

**Files:**
- Modify: `docs/recipes.md` (the "Native builds" section, and the `build` line of the example)

- [ ] **Step 1: Write the section**

Replace the second paragraph of "## Native builds", from "The native executable is compiled on
the host…" to "…must not be newer than Debian 13's.", with:

```markdown
`build.builder` chooses where a native component compiles:

- `host` (the default): `nativeCompile` on this machine, with its GraalVM. The executable is
  copied as-is into a Linux image, so the host must be Linux on the image's architecture, and
  its glibc must not be newer than Debian 13's (the runtime image is `distroless/cc-debian13`).
- `container`: inside the builder of
  [`deploy/native-java/Dockerfile`](../deploy/native-java/Dockerfile), the one the release uses.
  The host needs only a Docker-compatible CLI with BuildKit (Docker 23+, or podman through
  `-PrecipeDocker`), so it also works on macOS and Windows. The builder installs GraalVM
  Community, or Oracle GraalVM when the component's `gc` is `G1`: Community has no G1. Oracle
  GraalVM is GFTC-licensed, not GPL, so asking for G1 is also a licensing choice. The report
  records the distribution. Only the executable comes back
  (`docker build --target native-executable --output type=local,...`), into the component's
  staging directory. From there the image is packaged exactly as for `host`. Gradle's
  dependencies stay in a BuildKit cache between builds. `-PnativeBuildMemory` and
  `-PnativeParallelism` are passed into the builder. With the containerd module, the builder
  needs the staged repository: `-PcontainerdMavenLocal=true -Dmaven.repo.local=<dir>`.
```

In the "Recipe format" example, change
`native: {optimization: s}      # native mode only; see "Native options"` so that the line
above it reads `builder: container             # host (default) | container; see "Native builds"`.
The `build:` block becomes:

```yaml
  build:
    mode: native                   # jvm | native, required
    builder: container             # host (default) | container; see "Native builds"
    variant: native-os             # optional build identity; needs build-metadata
    native: {optimization: s}      # native mode only; see "Native options"
```

In "The report", extend the `native` bullet with `, plus builder and, for the container
builder, distribution`.

- [ ] **Step 2: Check**

Run: `grep -n "builder: container\|native-executable\|GFTC" docs/recipes.md`

Expected: each appears.

- [ ] **Step 3: Commit** (`detect_changes` first)

```bash
git add docs/recipes.md
git commit -m "Document the recipe container builder"   # + attribution lines
```

---

### Task 6: End-to-end on the real build

**Files:** none in the repository. Recipes are written to the scratchpad.

- [ ] **Step 1: Full suites**

Run:

```bash
./gradlew test --no-parallel --continue -PcontainerdMavenLocal=true -Dmaven.repo.local=$PWD/.gradle/containerd-m2 -PcontrolPlaneModules=all
uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py
```

Expected: 0 failures in both.

- [ ] **Step 2: The release path still builds its default target, and the cache pays**

Run twice and time both:

```bash
time scripts/native-java-image.sh warm-echo nanofaas/java-warm-echo:e2e-cache
```

Then run the image and call `/invoke` with an `X-Execution-Id` header.

Expected:
- both builds succeed;
- the second is shorter, because the Gradle downloads are cached;
- the image answers.

- [ ] **Step 3: Control plane in the container builder with G1**

Write `$SCRATCH/e2e-container.yaml`:

```yaml
schemaVersion: 2
name: e2e-container
controlPlane:
  modules: [build-metadata]
  build: {mode: native, builder: container, variant: native-o3-g1, native: {gc: G1}}
  container: {image: control-plane}
```

Run it without GraalVM on the `PATH`, to prove the host's GraalVM is not used. Then run it again
and time it:

```bash
env -u JAVA_HOME ./gradlew assembleRecipe -Precipe=$SCRATCH/e2e-container.yaml -PrecipeOutput=$SCRATCH/out-container
```

Check:
- `distribution.json` has `native.builder = container` and `native.distribution = oracle`;
- the image starts;
- `/modules/build-metadata` reports `variant: native-o3-g1`, `optimization: 3`, and a garbage
  collector list that names G1;
- the second assembly is faster than the first.

- [ ] **Step 4: Report**

Record every output in the final summary. There is nothing to commit.

---

## Self-review notes

- **Spec coverage:**

  | Spec requirement | Task |
  | --- | --- |
  | Schema and rules | 1 |
  | Distribution | 1 |
  | Dockerfile cache and stage | 2 |
  | `GRADLE_ARGS` | 3 |
  | Containerd | 3 |
  | Whitespace guard | 3 |
  | Command | 3 |
  | Task ordering | 4 |
  | Staging | 4 |
  | Preview | 4 |
  | Host check | 4 |
  | Report fields | 4 |
  | Errors | 3, 4 |
  | Docs | 5 |
  | Real verification | 6 |

  The spec's temporary-directory fallback is not needed: the exporter keeps staged files
  (measured), which Task 4's `config/recipe.yaml` assertion pins.
- **Type consistency:**
  - `NativeOptions` has 5 components everywhere: in Task 1, and in the Task 4 test and report.
  - `gradleArgs(Path, JsonNode, String, List, JsonNode, Map)` has the same signature in Tasks 3
    and 4.
  - `hostProblem(String, Target)` replaces `nativeImageHostProblem`, and its only test caller is
    updated in Task 4.
