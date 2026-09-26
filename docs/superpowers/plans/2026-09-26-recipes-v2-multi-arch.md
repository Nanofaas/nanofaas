# Recipes v2, part 3: multi-architecture images — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A recipe with `registry.platforms` builds its images with `docker buildx` for every listed
platform, with optional BuildKit provenance. `publishRecipe` records the index digest and one
digest per platform in `distribution.json`.

**Architecture:**
- **Pure layer.** A new `RecipeBuildx` class holds the pure functions:
  - the buildx and imagetools commands;
  - parsing the builder's platforms, the metadata file and the pushed index;
  - the host-platform rule.
- **Wiring.** `RecipeArtifacts` branches once on `platforms`:
  - a `checkRecipeBuilder` task runs before `cleanRecipe`;
  - the image tasks run `buildx build` with no output;
  - `publishRecipe` repeats each build with `--push --metadata-file`.
- **Native images.** A container-built native component with an image is compiled and packaged in
  one build, by a new `recipe-native` stage of `deploy/native-java/Dockerfile`, so its provenance
  names the GraalVM builder stage.
- **Unchanged.** A recipe without `platforms` runs exactly the part 1 and part 2 code paths.

**Tech Stack:** Java 25, the Gradle 9.7.1 settings plugin (`platform/gradle-plugin`, an included
build), Jackson, NetworkNT json-schema-validator 2.0.7 (Draft 2020-12), JUnit 5, AssertJ, Gradle
TestKit, docker buildx 0.31, and pytest for `scripts/tests`.

**Spec:** `docs/superpowers/specs/2026-09-26-recipes-v2-multi-arch-design.md`

## Global Constraints

- **v2 only.** `platforms` and `provenance` exist only in `recipe-v2.schema.json`; the v1 schema
  stays frozen byte for byte.
- **`platforms`.** A non-empty list, without duplicates, of `linux/amd64` and `linux/arm64`.
- **`provenance`.** `true` builds with `--provenance=mode=max`; absent or `false`, with
  `--provenance=false`. `provenance: true` without `platforms` is a schema error.
- **A recipe without `platforms` is unchanged:** `docker build`, `image.id`, `docker push`, and
  `RecipeContainerBuild.command` for container-built executables.
- **`-PrecipeBuilder=<name>`** adds `--builder <name>` to every buildx build and inspect. On a
  recipe without `platforms` it is a named error.
- **Host-built native images.** A component with `mode: native`, `builder: host` and an image is
  allowed only when `platforms` is exactly `[<host platform>]`:
  - `aarch64`/`arm64` → `linux/arm64`;
  - `amd64`/`x86_64` → `linux/amd64`;
  - any other architecture has no host platform.
- **The report stays at `schemaVersion: 2`.** A buildx image has `platforms`, `provenance`,
  `status`, and after publication `digest` and `manifests`, but no `id`.
- **Report validation** (`RecipeOutput`): for version 2, an image needs an `id` *or* a non-empty
  `platforms`. A `published` buildx image also needs a `digest`, and a `manifests` map whose keys
  are exactly `platforms`.
- **`deploy/native-java/Dockerfile`:** the runtime stage stays last, so the release's default
  target is unchanged.
- **Project rules:**
  - Java 4-space indentation.
  - Code, comments, docs and commit messages in English.
  - Run GitNexus `impact` before editing any existing symbol, and `detect_changes` on the staged
    diff before every commit:
    ```
    node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
    ```
  - Never stage the user's untracked or modified files under `docs/experiments/` and
    `docs/superpowers/{plans,specs}/2026-09-23-*`, nor `minikube_latest_arm64.deb`. Stage paths
    explicitly.
- **Commit trailer** (every commit; `-m "<trailer>"` in the commit steps below stands for these
  two lines):
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
  ```

GitNexus CLI, used by the impact steps below (bare method names; check the returned `filePath`,
because names collide across the repo):

```
GN=/home/michele/.npm/_npx/5e786f48223a616c/node_modules/gitnexus/dist/cli/index.js
node $GN impact "<method>" --direction upstream --repo .
```

## Review Focus

These are the inputs the spec implies but its test list does not name. Each has a test in the
task shown.

1. **A native service with no runtime files** (warm-echo on `builder: container`) builds from an
   empty staging directory, used as the `recipe` context. That directory must exist. (Task 5)
2. **A recipe with `platforms` and no images** must not require buildx at all: no builder check,
   and no docker call. (Task 5)
3. **A registry with a port** (`registry.example:5000/team/cp:1.0.0`) must be inspected as
   `registry.example:5000/team/cp@<digest>`, stripping only the tag. (Task 2)
4. **An arm64 manifest with `variant: v8`** in the pushed index must still map to `linux/arm64`.
   (Task 2)
5. **A buildx that writes no digest into `--metadata-file`** (an older buildx, or a CLI that
   ignores the flag) must give `published-unverified` and a message saying the digest could not
   be read, never a crash. (Task 6)

---

## File Structure

- **Create** `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeBuildx.java`:
  the pure multi-architecture functions.
- **Create** `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeBuildxTest.java`.
- **Create** `platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeOutputTest.java`.
- **Modify** `platform/gradle-plugin/src/main/resources/recipes/recipe-v2.schema.json`: the
  `registry.platforms` and `registry.provenance` fields.
- **Modify** `RecipeContainerBuild.java`: extract `builderArguments`, which both
  `command` and the `recipe-native` build use.
- **Modify** `RecipeOutput.java`: the report rule for `id` or `platforms`.
- **Modify** `RecipeTasks.java`: the host-platform check and the preview.
- **Modify** `RecipeArtifacts.java`:
  - `-PrecipeBuilder`, `checkRecipeBuilder`, the buildx image tasks and the report fields;
  - `publishBuildx`.
- **Modify** `deploy/native-java/Dockerfile`: the `recipe-native` stage.
- **Modify** `scripts/tests/test_java_container_images.py`.
- **Modify** `RecipeReaderTest.java` and `RecipePluginTest.java` (fake docker buildx).
- **Modify** `docs/recipes.md`.

All Java paths below are relative to `platform/gradle-plugin/src/{main,test}/java/it/unimib/datai/nanofaas/gradle/`.

Plugin test command, from the repository root:
`./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.<Class>'`.

---

### Task 1: Schema fields `registry.platforms` and `registry.provenance`

**Files:**
- Modify: `platform/gradle-plugin/src/main/resources/recipes/recipe-v2.schema.json` (the `registry` property)
- Test: `RecipeReaderTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: recipe data in which `/registry/platforms` is an array of `"linux/amd64"` and
  `"linux/arm64"`, and `/registry/provenance` is a boolean. Task 2 reads both.

- [ ] **Step 1: Write the failing tests**

In `RecipeReaderTest.invalidV2Recipes()`, add these `Arguments.of(...)` entries before the final
`"string version"` entry:

```java
                Arguments.of("v1 with platforms", BASE
                        + "registry: {repository: r.example, tag: t, platforms: [linux/amd64]}\n" + CP, "platforms"),
                Arguments.of("unknown platform", head
                        + "registry: {repository: r.example, tag: t, platforms: [linux/riscv64]}\n" + cp, "platforms"),
                Arguments.of("duplicate platform", head
                        + "registry: {repository: r.example, tag: t, platforms: [linux/arm64, linux/arm64]}\n" + cp, "platforms"),
                Arguments.of("empty platforms", head
                        + "registry: {repository: r.example, tag: t, platforms: []}\n" + cp, "platforms"),
                Arguments.of("provenance without platforms", head
                        + "registry: {repository: r.example, tag: t, provenance: true}\n" + cp, "platforms"),
                Arguments.of("non-boolean provenance", head
                        + "registry: {repository: r.example, tag: t, platforms: [linux/amd64], provenance: max}\n" + cp,
                        "provenance"),
```

Add after `acceptsTheContainerBuilderOnNativeComponents`:

```java
    @Test
    void acceptsPlatformsAndProvenance() throws IOException {
        Path file = write("""
                schemaVersion: 2
                name: demo
                registry: {repository: ghcr.io/my-org, tag: "1.0.0", platforms: [linux/amd64, linux/arm64], provenance: true}
                controlPlane: {modules: [], build: {mode: jvm}}
                """);

        JsonNode data = new RecipeReader().read(file, null).data();

        assertThat(data.at("/registry/platforms").toString()).isEqualTo("[\"linux/amd64\",\"linux/arm64\"]");
        assertThat(data.at("/registry/provenance").asBoolean()).isTrue();
        Path withoutProvenance = write("""
                schemaVersion: 2
                name: demo
                registry: {repository: ghcr.io/my-org, tag: "1.0.0", platforms: [linux/arm64], provenance: false}
                controlPlane: {modules: [], build: {mode: jvm}}
                """);
        assertThat(new RecipeReader().read(withoutProvenance, null).data().at("/registry/provenance").asBoolean()).isFalse();
    }
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeReaderTest'`
Expected:
- FAIL: `acceptsPlatformsAndProvenance`, with an additionalProperties error naming `platforms`;
- FAIL: the five v2 cases, because nothing is thrown;
- PASS: "v1 with platforms", since v1 already rejects unknown properties.

- [ ] **Step 3: Add the fields to the v2 schema**

In `recipe-v2.schema.json`, replace the `registry` property's `"properties"` object so that it
reads as below, and add the `if`/`then` pair next to `"properties"` inside `registry`:

```json
      "properties": {
        "repository": { ...unchanged... },
        "tag": { "$ref": "#/$defs/tag" },
        "platforms": {
          "description": "Platforms of every image. Selects the docker buildx path: images are built for each platform, and only publishRecipe pushes them (see docs/recipes.md).",
          "type": "array",
          "minItems": 1,
          "uniqueItems": true,
          "items": { "enum": ["linux/amd64", "linux/arm64"] }
        },
        "provenance": {
          "description": "Attach BuildKit provenance (mode=max) to every image. Needs platforms.",
          "type": "boolean"
        }
      },
      "if": { "required": ["provenance"], "properties": { "provenance": { "const": true } } },
      "then": { "required": ["platforms"] }
```

Keep `repository` exactly as it is. The file's indentation and key order otherwise stay
unchanged. Check that the v1 schema is untouched:
`git diff --stat -- platform/gradle-plugin/src/main/resources/recipes/recipe-v1.schema.json` prints nothing.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeReaderTest'`
Expected: PASS, including `v2SchemaConformsToBundledDraft202012Metaschema`.

- [ ] **Step 5: Commit**

```bash
git add platform/gradle-plugin/src/main/resources/recipes/recipe-v2.schema.json \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeReaderTest.java
node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
git commit -m "Accept registry platforms and provenance in v2 recipes" -m "<trailer>"
```

---

### Task 2: `RecipeBuildx`, the pure multi-architecture functions

**Files:**
- Create: `RecipeBuildx.java`
- Test: `RecipeBuildxTest.java`

**Interfaces:**
- Consumes:
  - the Task 1 data (`/registry/platforms`, `/registry/provenance`);
  - `RecipeTasks.Target`, with `mode()`, `image()` and `containerBuilt()`.
- Produces (all `static`, package-private, in `final class RecipeBuildx`):
  - `String NATIVE_TARGET = "recipe-native"`
  - `List<String> platforms(JsonNode data)`: null when absent
  - `boolean provenance(JsonNode data)`
  - `String hostPlatform(String osArch)`: null for other architectures
  - `String platformProblem(String osArch, List<String> platforms, RecipeTasks.Target target)`:
    null when allowed
  - `List<String> inspect(String docker, String builder)`: `builder` may be null
  - `Set<String> builderPlatforms(String inspectOutput)`
  - `List<String> build(String docker, String builder, List<String> platforms, boolean provenance, String reference, List<String> source, Path metadataFile)`:
    `metadataFile` null means no push
  - `List<String> imagetools(String docker, String reference, String digest)`
  - `String pushedDigest(String metadataJson)`: null when unreadable
  - `Map<String, String> manifests(String raw, String digest, List<String> platforms)`: null when
    unreadable
  - `boolean covers(Map<String, String> manifests, List<String> platforms)`

- [ ] **Step 1: Write the failing test**

Create `RecipeBuildxTest.java`:

```java
package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeBuildxTest {

    private static final String INDEX = "sha256:" + "1".repeat(64);
    private static final String AMD = "sha256:" + "a".repeat(64);
    private static final String ARM = "sha256:" + "b".repeat(64);
    private static final String ATTESTATION = "sha256:" + "c".repeat(64);

    @TempDir
    Path dir;

    private JsonNode data(String registry) throws IOException {
        Path file = dir.resolve("recipe.yaml");
        Files.writeString(file, "schemaVersion: 2\nname: demo\n" + registry
                + "controlPlane: {modules: [], build: {mode: jvm}}\n");
        return new RecipeReader().read(file, null).data();
    }

    private static RecipeTasks.Target target(String mode, String image, String builder) {
        return new RecipeTasks.Target("controlPlane", "control-plane", "control-plane", "java", mode,
                ":control-plane:" + (mode.equals("native") ? "nativeCompile" : "bootJar"), null, null, "control-plane/",
                image, null, null, builder == null ? null : new RecipeBuildProperties.NativeOptions("3", "serial",
                        List.of(), builder, builder.equals("container") ? "community" : null));
    }

    @Test
    void platformsAndProvenanceComeFromTheRegistry() throws IOException {
        JsonNode multi = data("registry: {repository: r.example, tag: t, platforms: [linux/amd64, linux/arm64],"
                + " provenance: true}\n");
        assertThat(RecipeBuildx.platforms(multi)).containsExactly("linux/amd64", "linux/arm64");
        assertThat(RecipeBuildx.provenance(multi)).isTrue();

        JsonNode classic = data("registry: {repository: r.example, tag: t}\n");
        assertThat(RecipeBuildx.platforms(classic)).isNull();
        assertThat(RecipeBuildx.provenance(classic)).isFalse();
        assertThat(RecipeBuildx.platforms(data(""))).isNull();
    }

    @Test
    void hostPlatformNamesOnlyTheArchitecturesImagesTarget() {
        assertThat(RecipeBuildx.hostPlatform("aarch64")).isEqualTo("linux/arm64");
        assertThat(RecipeBuildx.hostPlatform("arm64")).isEqualTo("linux/arm64");
        assertThat(RecipeBuildx.hostPlatform("amd64")).isEqualTo("linux/amd64");
        assertThat(RecipeBuildx.hostPlatform("x86_64")).isEqualTo("linux/amd64");
        assertThat(RecipeBuildx.hostPlatform("ppc64le")).isNull();
    }

    @Test
    void hostBuiltNativeImagesMayOnlyTargetTheHostPlatform() {
        RecipeTasks.Target hostNative = target("native", "img", "host");

        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/arm64"), hostNative)).isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), hostNative))
                .contains("linux/arm64").contains("builder: host").contains("use build.builder: container");
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64", "linux/arm64"), hostNative))
                .contains("use build.builder: container");
        assertThat(RecipeBuildx.platformProblem("ppc64le", List.of("linux/arm64"), hostNative)).contains("ppc64le");
        assertThat(RecipeBuildx.platformProblem("aarch64", null, hostNative)).isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), target("native", "img", "container")))
                .isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), target("native", null, "host")))
                .isNull();
        assertThat(RecipeBuildx.platformProblem("aarch64", List.of("linux/amd64"), target("jvm", "img", null))).isNull();
    }

    @Test
    void builderPlatformsJoinEveryNodeAndDropTheConfiguredMark() {
        String output = """
                Name:          multi
                Driver:        docker-container

                Nodes:
                Name:                  multi0
                Platforms:             linux/arm64, linux/arm/v7*
                Name:                  multi1
                Platforms:             linux/amd64*, linux/amd64/v2
                """;

        assertThat(RecipeBuildx.builderPlatforms(output))
                .containsExactly("linux/arm64", "linux/arm/v7", "linux/amd64", "linux/amd64/v2");
        assertThat(RecipeBuildx.builderPlatforms("Name: empty\n")).isEmpty();
    }

    @Test
    void commandsCarryBuilderPlatformsProvenanceAndPush() {
        assertThat(RecipeBuildx.inspect("docker", null)).containsExactly("docker", "buildx", "inspect");
        assertThat(RecipeBuildx.inspect("docker", "multi")).containsExactly("docker", "buildx", "inspect", "--builder", "multi");

        List<String> source = List.of("-f", "/repo/deploy/recipes/Dockerfile.jvm", "/out/control-plane");
        assertThat(RecipeBuildx.build("docker", null, List.of("linux/amd64", "linux/arm64"), true, "r.example/cp:1",
                source, null)).containsExactly("docker", "buildx", "build", "--platform", "linux/amd64,linux/arm64",
                "--provenance=mode=max", "-t", "r.example/cp:1", "-f", "/repo/deploy/recipes/Dockerfile.jvm",
                "/out/control-plane");
        assertThat(RecipeBuildx.build("docker", "multi", List.of("linux/arm64"), false, "r.example/cp:1", source,
                Path.of("/tmp/meta.json"))).containsExactly("docker", "buildx", "build", "--builder", "multi",
                "--platform", "linux/arm64", "--provenance=false", "-t", "r.example/cp:1", "--push", "--metadata-file",
                "/tmp/meta.json", "-f", "/repo/deploy/recipes/Dockerfile.jvm", "/out/control-plane");
    }

    @Test
    void imagetoolsInspectsTheRepositoryByDigestEvenBehindARegistryPort() {
        assertThat(RecipeBuildx.imagetools("docker", "registry.example:5000/team/cp:1.0.0", INDEX))
                .containsExactly("docker", "buildx", "imagetools", "inspect", "registry.example:5000/team/cp@" + INDEX, "--raw");
    }

    @Test
    void pushedDigestReadsTheMetadataFile() {
        assertThat(RecipeBuildx.pushedDigest("{\"containerimage.digest\": \"" + INDEX + "\", \"image.name\": \"r/cp:1\"}"))
                .isEqualTo(INDEX);
        assertThat(RecipeBuildx.pushedDigest("{\"image.name\": \"r/cp:1\"}")).isNull();
        assertThat(RecipeBuildx.pushedDigest("{\"containerimage.digest\": \"latest\"}")).isNull();
        assertThat(RecipeBuildx.pushedDigest("")).isNull();
        assertThat(RecipeBuildx.pushedDigest("not json")).isNull();
    }

    @Test
    void manifestsMapPlatformsIgnoreVariantsAndSkipAttestations() {
        String index = """
                {"schemaVersion": 2, "mediaType": "application/vnd.oci.image.index.v1+json", "manifests": [
                  {"digest": "%s", "platform": {"architecture": "amd64", "os": "linux"}},
                  {"digest": "%s", "platform": {"architecture": "arm64", "os": "linux", "variant": "v8"}},
                  {"digest": "%s", "annotations": {"vnd.docker.reference.type": "attestation-manifest"},
                   "platform": {"architecture": "unknown", "os": "unknown"}}]}
                """.formatted(AMD, ARM, ATTESTATION);

        assertThat(RecipeBuildx.manifests(index, INDEX, List.of("linux/amd64", "linux/arm64")))
                .containsExactly(Map.entry("linux/amd64", AMD), Map.entry("linux/arm64", ARM));
    }

    @Test
    void aSingleManifestIsTheOnlyPlatform() {
        String manifest = "{\"schemaVersion\": 2, \"config\": {\"digest\": \"" + AMD + "\"}, \"layers\": []}";

        assertThat(RecipeBuildx.manifests(manifest, INDEX, List.of("linux/arm64")))
                .containsExactly(Map.entry("linux/arm64", INDEX));
        assertThat(RecipeBuildx.manifests(manifest, INDEX, List.of("linux/amd64", "linux/arm64"))).isNull();
        assertThat(RecipeBuildx.manifests("", INDEX, List.of("linux/arm64"))).isNull();
        assertThat(RecipeBuildx.manifests("{\"errors\": []}", INDEX, List.of("linux/arm64"))).isNull();
    }

    @Test
    void coversNeedsExactlyThePlatformsWithDigests() {
        Map<String, String> both = new LinkedHashMap<>(Map.of("linux/amd64", AMD, "linux/arm64", ARM));
        assertThat(RecipeBuildx.covers(both, List.of("linux/amd64", "linux/arm64"))).isTrue();
        assertThat(RecipeBuildx.covers(Map.of("linux/amd64", AMD), List.of("linux/amd64", "linux/arm64"))).isFalse();
        assertThat(RecipeBuildx.covers(both, List.of("linux/amd64"))).isFalse();
        assertThat(RecipeBuildx.covers(Map.of("linux/amd64", "latest"), List.of("linux/amd64"))).isFalse();
        assertThat(RecipeBuildx.covers(null, List.of("linux/amd64"))).isFalse();
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeBuildxTest'`
Expected: FAIL at compilation, with "cannot find symbol ... RecipeBuildx".

- [ ] **Step 3: Write the implementation**

Create `RecipeBuildx.java`:

```java
package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pure parts of the multi-architecture path, which a recipe takes when it sets registry.platforms: the buildx
 * commands, the builder's platforms, and the digests read back after a push. A multi-architecture image cannot live in
 * the classic local image store, so assembly builds into the builder's cache and only publication pushes.
 */
final class RecipeBuildx {

    /** The deploy/native-java/Dockerfile stage that compiles and packages a container-built native image in one build. */
    static final String NATIVE_TARGET = "recipe-native";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIGEST = "sha256:[0-9a-f]{64}";

    private RecipeBuildx() {
    }

    /** registry.platforms, or null when the recipe takes the classic docker path. */
    static List<String> platforms(JsonNode data) {
        JsonNode platforms = data.path("registry").path("platforms");
        if (!platforms.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        platforms.forEach(platform -> values.add(platform.asText()));
        return List.copyOf(values);
    }

    static boolean provenance(JsonNode data) {
        return data.path("registry").path("provenance").asBoolean(false);
    }

    /** The platform of an executable compiled on this host, or null for an architecture no image targets. */
    static String hostPlatform(String osArch) {
        return switch (osArch) {
            case "aarch64", "arm64" -> "linux/arm64";
            case "amd64", "x86_64" -> "linux/amd64";
            default -> null;
        };
    }

    /** GraalVM cannot cross-compile: a host-built native image can target only the host's own platform. */
    static String platformProblem(String osArch, List<String> platforms, RecipeTasks.Target target) {
        if (platforms == null || !target.mode().equals("native") || target.image() == null || target.containerBuilt()) {
            return null;
        }
        String host = hostPlatform(osArch);
        if (host != null && platforms.equals(List.of(host))) {
            return null;
        }
        return "registry.platforms " + platforms + " needs an executable for each platform, but builder: host compiles"
                + " only for " + (host == null ? osArch : host) + "; use build.builder: container";
    }

    static List<String> inspect(String docker, String builder) {
        List<String> command = new ArrayList<>(List.of(docker, "buildx", "inspect"));
        if (builder != null) {
            command.addAll(List.of("--builder", builder));
        }
        return List.copyOf(command);
    }

    /** Every platform on the Platforms: lines of docker buildx inspect, one line per node; '*' marks a configured one. */
    static Set<String> builderPlatforms(String inspectOutput) {
        Set<String> platforms = new LinkedHashSet<>();
        for (String line : inspectOutput.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.startsWith("Platforms:")) {
                Arrays.stream(trimmed.substring("Platforms:".length()).split(","))
                        .map(platform -> platform.strip().replace("*", ""))
                        .filter(platform -> !platform.isEmpty())
                        .forEach(platforms::add);
            }
        }
        return platforms;
    }

    /**
     * @param source       the Dockerfile arguments (-f, --target, --build-context, --build-arg), ending with the context
     * @param metadataFile where --push writes the build metadata, or null to build into the builder's cache only
     */
    static List<String> build(String docker, String builder, List<String> platforms, boolean provenance,
                              String reference, List<String> source, Path metadataFile) {
        List<String> command = new ArrayList<>(List.of(docker, "buildx", "build"));
        if (builder != null) {
            command.addAll(List.of("--builder", builder));
        }
        command.addAll(List.of("--platform", String.join(",", platforms),
                "--provenance=" + (provenance ? "mode=max" : "false"), "-t", reference));
        if (metadataFile != null) {
            command.addAll(List.of("--push", "--metadata-file", metadataFile.toString()));
        }
        command.addAll(source);
        return List.copyOf(command);
    }

    /** Reads back what was pushed, by digest: the reference's repository (a registry port included) without its tag. */
    static List<String> imagetools(String docker, String reference, String digest) {
        return List.of(docker, "buildx", "imagetools", "inspect",
                reference.substring(0, reference.lastIndexOf(':')) + "@" + digest, "--raw");
    }

    /** containerimage.digest from a --metadata-file, or null when it is missing or unreadable. */
    static String pushedDigest(String metadataJson) {
        try {
            JsonNode digest = JSON.readTree(metadataJson).path("containerimage.digest");
            return digest.isTextual() && digest.asText().matches(DIGEST) ? digest.asText() : null;
        } catch (IOException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Platform to manifest digest, from imagetools inspect --raw of what was pushed: an index (attestation manifests
     * skipped) or, for one platform without provenance, a single manifest. Null when the document cannot be read.
     */
    static Map<String, String> manifests(String raw, String digest, List<String> platforms) {
        JsonNode json;
        try {
            json = JSON.readTree(raw);
        } catch (IOException | RuntimeException exception) {
            return null;
        }
        if (json == null || !json.isObject()) {
            return null;
        }
        Map<String, String> manifests = new LinkedHashMap<>();
        JsonNode entries = json.path("manifests");
        if (!entries.isArray()) {
            // A single manifest, not an index: only one platform can have been built, and the digest is its own.
            if (!json.has("config") || platforms.size() != 1) {
                return null;
            }
            manifests.put(platforms.getFirst(), digest);
            return manifests;
        }
        for (JsonNode entry : entries) {
            if (entry.path("annotations").path("vnd.docker.reference.type").asText().equals("attestation-manifest")) {
                continue;
            }
            // os/architecture only: BuildKit may add a variant (arm64 v8) that the recipe's platforms do not name.
            manifests.put(entry.at("/platform/os").asText() + "/" + entry.at("/platform/architecture").asText(),
                    entry.path("digest").asText());
        }
        return manifests;
    }

    /** True when the pushed manifests are exactly the requested platforms, each with a registry digest. */
    static boolean covers(Map<String, String> manifests, List<String> platforms) {
        return manifests != null && manifests.keySet().equals(Set.copyOf(platforms))
                && manifests.values().stream().allMatch(digest -> digest.matches(DIGEST));
    }
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeBuildxTest'`
Expected: PASS, 10 tests.

- [ ] **Step 5: Commit**

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeBuildx.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeBuildxTest.java
node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
git commit -m "Compute buildx commands and read back pushed digests" -m "<trailer>"
```

---

### Task 3: The `recipe-native` stage

**Files:**
- Modify: `deploy/native-java/Dockerfile:49-60`
- Test: `scripts/tests/test_java_container_images.py`

**Interfaces:**
- Consumes: nothing.
- Produces: the build target `recipe-native` in `deploy/native-java/Dockerfile`. It reads the
  named context `recipe`, a staged directory holding only runtime files, plus the builder stage's
  existing contexts and arguments (`containerd_maven_repo`, `NATIVE_TASK`, `NATIVE_BINARY`,
  `GRAALVM_DISTRIBUTION`, `GRADLE_ARGS`). Task 5 builds it.

- [ ] **Step 1: Write the failing test and tighten the runtime-stage helper**

In `scripts/tests/test_java_container_images.py`, make
`_assert_runtime_stage_redeclares_and_reports_base_images` anchor on the **last**
`FROM ${RUNTIME_IMAGE}` line. With `recipe-native` above it, the first match would no longer be
the runtime stage. Replace:

```python
    runtime_from = _line_index(lines, "FROM ${RUNTIME_IMAGE}")
```

with:

```python
    # the last runtime FROM: deploy/native-java/Dockerfile also has a recipe-native stage on the same base.
    runtime_from = max(i for i, line in enumerate(lines) if line.startswith("FROM ${RUNTIME_IMAGE}"))
```

Append:

```python
def _stage(dockerfile_text, name):
    """The lines of stage `name`, from its FROM up to the next FROM."""
    lines = dockerfile_text.splitlines()
    start = next(i for i, line in enumerate(lines) if line.startswith("FROM ") and line.split()[-1] == name)
    end = next((i for i in range(start + 1, len(lines)) if lines[i].startswith("FROM ")), len(lines))
    return lines[start:end]


def test_recipe_native_stage_packages_like_the_recipe_native_dockerfile():
    """The multi-architecture path compiles and packages a container-built native image in one build, so the
    image's provenance names the GraalVM builder stage. It must package exactly as Dockerfile.native does."""
    dockerfile = (REPO_ROOT / "deploy/native-java/Dockerfile").read_text(encoding="utf-8")
    stage = _stage(dockerfile, "recipe-native")
    packaging = (REPO_ROOT / "deploy/recipes/Dockerfile.native").read_text(encoding="utf-8").splitlines()
    runtime = packaging[max(i for i, line in enumerate(packaging) if line.startswith("FROM ${RUNTIME_IMAGE}")):]

    def settings(lines):
        return [line for line in lines if line.split(" ", 1)[0] in {"ARG", "ENV", "WORKDIR", "EXPOSE", "ENTRYPOINT"}]

    assert stage[0] == "FROM ${RUNTIME_IMAGE} AS recipe-native"
    assert settings(stage) == settings(runtime)
    assert "COPY --from=builder --chown=nonroot:nonroot /var/lib/nanofaas /var/lib/nanofaas" in stage
    recipe = stage.index("COPY --from=recipe . /app/")
    assert recipe < stage.index("COPY --from=builder /tmp/application /app/application")
    stages = [line.split() for line in dockerfile.splitlines() if line.startswith("FROM ")]
    assert stages[-1] == ["FROM", "${RUNTIME_IMAGE}"], "the release's default target must stay the runtime image"
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py`
Expected: 1 failed (`test_recipe_native_stage_packages_like_the_recipe_native_dockerfile`, with
`StopIteration` because the stage does not exist) and 7 passed.

- [ ] **Step 3: Add the stage**

In `deploy/native-java/Dockerfile`, replace everything from the line
`# \`cc\` rather than \`base\`...` up to and including the line `FROM ${RUNTIME_IMAGE}` (lines
49-60) with the block below. This also moves the `cc` comment above the stages that use the
runtime base, which fixes part 2's deferred comment-placement minor.

```dockerfile
# assembleRecipe's container builder exports only the executable
# (docker build --target native-executable --output type=local,...) and packages it itself.
FROM scratch AS native-executable
COPY --from=builder /tmp/application /application

# `cc` rather than `base`: it carries libstdc++, which a G1 binary loads at run
# time because the collector itself is C++. A serial binary does not need it and
# only pays the few megabytes, which is cheaper than two runtime images that can
# drift apart — the base image failed at startup, not at build, with
# "libstdc++.so.6: cannot open shared object file".
#
# assembleRecipe's multi-architecture path (registry.platforms) compiles and packages a
# container-built native image in this one build, so the image's provenance names the builder
# stage above. The staged runtime files come from the named context `recipe`; the layout
# follows deploy/recipes/Dockerfile.native.
FROM ${RUNTIME_IMAGE} AS recipe-native
ARG BUILDER_IMAGE
ARG RUNTIME_IMAGE
ENV NANOFAAS_BUILD_BASE_IMAGE=${BUILDER_IMAGE}
ENV NANOFAAS_RUNTIME_BASE_IMAGE=${RUNTIME_IMAGE}
ENV NANOFAAS_REGISTRY_PATH=/var/lib/nanofaas/functions.json
ENV SPRING_CONFIG_ADDITIONALLOCATION=optional:file:/app/config/recipe.yaml
WORKDIR /app
COPY --from=builder --chown=nonroot:nonroot /var/lib/nanofaas /var/lib/nanofaas
COPY --from=recipe . /app/
COPY --from=builder /tmp/application /app/application
EXPOSE 8080 8081
ENTRYPOINT ["/app/application"]

# The release builds the default target, this final stage, which must stay last.
FROM ${RUNTIME_IMAGE}
```

The final stage's lines after `FROM ${RUNTIME_IMAGE}` stay exactly as they are.

- [ ] **Step 4: Run the tests and the Dockerfile checks**

Run: `uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py`
Expected: 8 passed.

Run:
```bash
docker build --check -f deploy/native-java/Dockerfile .
docker build --check -f deploy/native-java/Dockerfile --target recipe-native \
  --build-context recipe=deploy/native-java/empty-maven-repo .
```
Expected: both print `Check complete, no warnings found.`

- [ ] **Step 5: Commit**

```bash
git add deploy/native-java/Dockerfile scripts/tests/test_java_container_images.py
node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
git commit -m "Add a recipe-native stage that compiles and packages in one build" -m "<trailer>"
```

---

### Task 4: Report validation for buildx images

**Files:**
- Modify: `RecipeOutput.java:95-157` (`validReport`)
- Create: `RecipeOutputTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces: `static boolean validReport(JsonNode json)`, now package-private. The rule: for
  version 2, an image needs an `id` *or* a non-empty `platforms` array. A `published` image with
  `platforms` also needs a `digest` and a `manifests` object whose keys are exactly `platforms`,
  each value a `sha256:` digest.

- [ ] **Step 1: Run impact analysis**

Run: `node $GN impact "validReport" --direction upstream --repo .`
Expected: the only caller is `RecipeOutput.isAssemblyOutput` (or the method at `RecipeOutput.java:89`);
LOW risk. If the result is UNKNOWN or the `filePath` is another file, confirm with
`grep -rn validReport platform/gradle-plugin/src`, which must list only `RecipeOutput.java`.

- [ ] **Step 2: Write the failing test**

Create `RecipeOutputTest.java`:

```java
package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecipeOutputTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String DIGEST = "sha256:" + "a".repeat(64);

    private static boolean valid(ObjectNode image) {
        ObjectNode report = JSON.createObjectNode().put("schemaVersion", 2).put("tag", "1.0.0");
        report.putObject("recipe").put("name", "demo").put("sha256", "0".repeat(64)).put("schemaVersion", 2);
        report.putNull("source");
        report.putArray("modules");
        report.putArray("components").addObject().put("kind", "control-plane").put("name", "control-plane")
                .put("sdk", "java").put("mode", "jvm").put("artifact", "control-plane/").set("image", image);
        return RecipeOutput.validReport(report);
    }

    private static ObjectNode image(String status) {
        return JSON.createObjectNode().put("reference", "r.example/cp:1.0.0").put("status", status);
    }

    private static ObjectNode buildx(String status) {
        ObjectNode image = image(status);
        image.putArray("platforms").add("linux/amd64").add("linux/arm64");
        return image.put("provenance", true);
    }

    @Test
    void aClassicV2ImageNeedsItsId() {
        assertThat(valid(image("built"))).isFalse();
        assertThat(valid(image("built").put("id", DIGEST))).isTrue();
    }

    @Test
    void aBuildxImageNeedsNoIdUntilPublished() {
        assertThat(valid(buildx("built"))).isTrue();
        assertThat(valid(buildx("failed"))).isTrue();
        assertThat(valid(buildx("published-unverified"))).isTrue();
        ObjectNode empty = image("built");
        empty.putArray("platforms");
        assertThat(valid(empty)).as("an empty platforms list is not the buildx path").isFalse();
    }

    @Test
    void aPublishedBuildxImageNeedsItsDigestAndEveryPlatform() {
        ObjectNode complete = buildx("published").put("digest", DIGEST);
        complete.putObject("manifests").put("linux/amd64", DIGEST).put("linux/arm64", DIGEST);
        assertThat(valid(complete)).isTrue();

        assertThat(valid(buildx("published").put("digest", DIGEST))).as("no manifests").isFalse();
        ObjectNode partial = buildx("published").put("digest", DIGEST);
        partial.putObject("manifests").put("linux/amd64", DIGEST);
        assertThat(valid(partial)).as("a platform missing").isFalse();
        ObjectNode other = buildx("published").put("digest", DIGEST);
        other.putObject("manifests").put("linux/amd64", DIGEST).put("linux/riscv64", DIGEST);
        assertThat(valid(other)).as("another platform").isFalse();
        ObjectNode notADigest = buildx("published").put("digest", DIGEST);
        notADigest.putObject("manifests").put("linux/amd64", DIGEST).put("linux/arm64", "latest");
        assertThat(valid(notADigest)).as("a tag instead of a digest").isFalse();
        ObjectNode noDigest = buildx("published");
        noDigest.putObject("manifests").put("linux/amd64", DIGEST).put("linux/arm64", DIGEST);
        assertThat(valid(noDigest)).as("no index digest").isFalse();
    }
}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeOutputTest'`
Expected: FAIL at compilation: `validReport(...) has private access in RecipeOutput`.

Change `private static boolean validReport` to `static boolean validReport`, then run the test again.
Expected: `aClassicV2ImageNeedsItsId` passes, and the two buildx tests FAIL (a built buildx image
without `id` is rejected, so the result is `false`, not `true`).

- [ ] **Step 4: Implement the rule**

In `RecipeOutput.validReport`, replace the image check (the `if (!image.isObject() || ...)` block)
with:

```java
            if (!image.isObject() || !image.path("reference").isTextual() || image.path("reference").asText().isBlank()
                    || !image.path("status").isTextual()
                    || !Set.of("built", "published", "published-unverified", "failed").contains(image.path("status").asText())) {
                return false;
            }
            JsonNode platforms = image.path("platforms");
            // The buildx path keeps no local image, hence no id: its images are pinned by digest once published.
            boolean buildx = platforms.isArray() && !platforms.isEmpty();
            boolean published = image.path("status").asText().equals("published");
            if ((version == 2 && !buildx && !matches(image.path("id"), "sha256:[0-9a-f]{64}"))
                    || (published && !matches(image.path("digest"), "sha256:[0-9a-f]{64}"))
                    || (buildx && published && !coversPlatforms(image.path("manifests"), platforms))) {
                return false;
            }
```

Add after `matches`:

```java
    /** A published multi-architecture image records one digest for exactly each of its platforms. */
    private static boolean coversPlatforms(JsonNode manifests, JsonNode platforms) {
        if (!manifests.isObject() || manifests.size() != platforms.size()) {
            return false;
        }
        for (JsonNode platform : platforms) {
            if (!platform.isTextual() || !matches(manifests.path(platform.asText()), "sha256:[0-9a-f]{64}")) {
                return false;
            }
        }
        return true;
    }
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeOutputTest' --tests 'it.unimib.datai.nanofaas.gradle.RecipePluginTest'`
Expected: PASS. The existing ownership tests in `RecipePluginTest`
(`malformedReportsDoNotAuthorizeDeletion`, `reusesOutputsHoldingACompleteV2OrPublishedReport`)
are still green.

- [ ] **Step 6: Commit**

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeOutput.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeOutputTest.java
node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
git commit -m "Accept buildx images, pinned by platform digests, as recipe output" -m "<trailer>"
```

---

### Task 5: Assembly on the buildx path

**Files:**
- Modify: `RecipeContainerBuild.java:64-75` (extract `builderArguments`)
- Modify: `RecipeTasks.java:181-186` (platform check), `RecipeTasks.java:279-297` (preview)
- Modify: `RecipeArtifacts.java:67-178` (`register`), `RecipeArtifacts.java:339-345`
  (`dockerBuild`), `RecipeArtifacts.java:347-389` (`report`)
- Test: `RecipePluginTest.java`

**Interfaces:**
- Consumes:
  - Task 2: `RecipeBuildx.platforms`, `provenance`, `platformProblem`, `inspect`,
    `builderPlatforms`, `build` and `NATIVE_TARGET`;
  - Task 3: the `recipe-native` target;
  - Task 4: reports without `id` are valid when they carry `platforms`.
- Produces:
  - `static List<String> RecipeContainerBuild.builderArguments(Path rootDir, String nativeTask, String nativeBinary, String distribution, List<String> gradleArgs, Path containerdRepository)`;
  - `static void RecipeArtifacts.requireBuilderPlatforms(ExecOperations exec, String docker, String builder, List<String> platforms)`;
  - the task `checkRecipeBuilder`;
  - in `register`, a `Map<String, Function<Path, List<String>>> buildxCommands` keyed by image
    reference. Applying it to `null` gives the assembly command, and applying it to a metadata
    file gives the push command. Task 6 reads it.

- [ ] **Step 1: Run impact analysis**

Run each command and record the callers and risk:

```
node $GN impact "command" --direction upstream --repo .        # check filePath is RecipeContainerBuild.java
node $GN impact "register" --direction upstream --repo .       # RecipeArtifacts.register (and RecipeTasks.register)
node $GN impact "dockerBuild" --direction upstream --repo .
node $GN impact "report" --direction upstream --repo .         # RecipeArtifacts.report
node $GN impact "printPreview" --direction upstream --repo .
node $GN impact "resolve" --direction upstream --repo .        # RecipeTasks.resolve
```

Expected: all callers are inside `platform/gradle-plugin`. For any result that is UNKNOWN, or
that points to another file, confirm with
`grep -rn "<name>(" platform/gradle-plugin/src/main` and record it. If a HIGH or CRITICAL result
points at these files, report it before editing.

- [ ] **Step 2: Extract `builderArguments` (a pure refactor, existing tests green)**

In `RecipeContainerBuild.java`, replace `command` with:

```java
    static List<String> command(String docker, Path rootDir, Path destination, String nativeTask, String nativeBinary,
                                String distribution, List<String> gradleArgs, Path containerdRepository) {
        List<String> command = new ArrayList<>(List.of(docker, "build", "-f", rootDir.resolve(DOCKERFILE).toString(),
                "--target", TARGET, "--output", "type=local,dest=" + destination));
        command.addAll(builderArguments(rootDir, nativeTask, nativeBinary, distribution, gradleArgs, containerdRepository));
        command.add(rootDir.toString());
        return List.copyOf(command);
    }

    /** The builder stage's inputs, shared by the executable export and the multi-architecture recipe-native build. */
    static List<String> builderArguments(Path rootDir, String nativeTask, String nativeBinary, String distribution,
                                         List<String> gradleArgs, Path containerdRepository) {
        Path repository = containerdRepository != null ? containerdRepository : rootDir.resolve(EMPTY_MAVEN_REPOSITORY);
        return List.of("--build-context", "containerd_maven_repo=" + repository,
                "--build-arg", "NATIVE_TASK=" + nativeTask,
                "--build-arg", "NATIVE_BINARY=" + nativeBinary,
                "--build-arg", "GRAALVM_DISTRIBUTION=" + distribution,
                "--build-arg", "GRADLE_ARGS=" + String.join(" ", gradleArgs));
    }
```

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeContainerBuildTest' --tests 'it.unimib.datai.nanofaas.gradle.RecipePluginTest'`
Expected: PASS, since the command is byte-identical.

- [ ] **Step 3: Extend the fake docker and write the failing tests**

In `RecipePluginTest.writeFixture()`, inside the `bin/docker` text block, insert the lines below
right after the `if [ -f "$log/fail-$1" ]; ...` line. The block is a Java text block passed
through `.formatted`, so `%%` is a literal `%` and `\\n` is a literal `\n`.

```
                if [ -f "$log/fail-buildx-$2" ]; then echo "fake buildx $2 failed" >&2; exit 1; fi
                if [ "$1" = buildx ] && [ "$2" = inspect ]; then
                  echo "Name: fake"
                  cat "$log/builder-platforms" 2>/dev/null || echo "Platforms: linux/arm64, linux/amd64*"
                fi
                if [ "$1" = buildx ] && [ "$2" = build ]; then
                  prev=""; meta=""; ref=""; plats=""; prov=""
                  for a in "$@"; do
                    case "$prev" in --metadata-file) meta="$a" ;; -t) ref="$a" ;; --platform) plats="$a" ;; esac
                    case "$a" in --provenance=*) prov="${a#--provenance=}" ;; esac
                    prev="$a"
                  done
                  if [ -n "$meta" ]; then
                    n=$(grep -c '^--push$' "$log/docker.log")
                    if [ -f "$log/fail-push-$n" ]; then echo "fake push $n failed" >&2; exit 1; fi
                    d="sha256:$(printf 'index-%%s' "$ref" | sha256sum | cut -c1-64)"
                    [ -f "$log/no-digest" ] || printf '{"containerimage.digest": "%%s"}\\n' "$d" > "$meta"
                    raw="$log/raw-$d"
                    if [ "$prov" = false ] && [ "${plats#*,}" = "$plats" ]; then
                      printf '{"schemaVersion": 2, "config": {"digest": "%%s"}}\\n' "$d" > "$raw"
                    else
                      printf '{"manifests": [' > "$raw"
                      sep=""
                      for p in $(echo "$plats" | tr ',' ' '); do
                        [ -f "$log/drop-$(echo "$p" | tr / -)" ] && continue
                        m="sha256:$(printf '%%s@%%s' "$ref" "$p" | sha256sum | cut -c1-64)"
                        printf '%%s{"digest": "%%s", "platform": {"os": "%%s", "architecture": "%%s"}}' \\
                          "$sep" "$m" "${p%%/*}" "${p#*/}" >> "$raw"
                        sep=","
                      done
                      if [ "$prov" = mode=max ]; then
                        printf '%%s{"digest": "sha256:%%s", "annotations": {"vnd.docker.reference.type": "attestation-manifest"}, "platform": {"os": "unknown", "architecture": "unknown"}}' \\
                          "$sep" "$(printf 'att-%%s' "$ref" | sha256sum | cut -c1-64)" >> "$raw"
                      fi
                      echo ']}' >> "$raw"
                    fi
                  fi
                fi
                if [ "$1" = buildx ] && [ "$2" = imagetools ]; then cat "$log/raw-${4##*@}"; fi
```

Add these helpers next to `containerBuild()`:

```java
    private List<String> buildxBuild(String reference, boolean push) throws IOException {
        List<List<String>> calls = dockerCalls();
        return calls.stream().filter(call -> call.size() > 1 && call.get(0).equals("buildx") && call.get(1).equals("build")
                        && call.contains(reference) && call.contains("--push") == push).findFirst()
                .orElseThrow(() -> new AssertionError("no buildx build of " + reference + " (push " + push + ") in " + calls));
    }

    private static final String MULTI_ARCH = V2_HEADER + """
            registry: {repository: registry.example:5000/team, tag: "1.0.0", platforms: [linux/amd64, linux/arm64], provenance: true}
            controlPlane:
              modules: []
              build: {mode: native, builder: container}
              container: {image: control-plane}
              config: {nanofaas: {metrics: {profile: basic}}}
            functions:
              - {name: word-stats, sdk: java, build: {mode: jvm}, container: {image: ws-java}}
              - {name: word-stats, sdk: python, container: {image: ws-python}}
            services: [{name: warm-echo, sdk: java, build: {mode: native, builder: container}, container: {image: warm-echo}}]
            """;
    private static final String MA_CP = "registry.example:5000/team/control-plane:1.0.0";
```

Add the tests after `skippingCleanRecipeStillRefusesAnUnownedOutputWhenEveryComponentIsContainerBuilt`:

```java
    @Test
    void multiArchAssemblyBuildsEveryImageWithBuildxIntoTheCacheOnly() throws IOException {
        recipe(MULTI_ARCH);

        run("assembleRecipe", "-Precipe=recipe.yaml", docker(), "-PrecipeBuilder=multi");

        Path root = projectDir.toRealPath();
        List<List<String>> calls = dockerCalls();
        assertThat(calls.getFirst()).containsExactly("buildx", "inspect", "--builder", "multi");
        assertThat(buildxBuild(MA_CP, false)).containsExactly("buildx", "build", "--builder", "multi",
                "--platform", "linux/amd64,linux/arm64", "--provenance=mode=max", "-t", MA_CP,
                "-f", root.resolve("deploy/native-java/Dockerfile").toString(), "--target", "recipe-native",
                "--build-context", "recipe=" + root.resolve("build/recipes/demo/control-plane"),
                "--build-context", "containerd_maven_repo=" + root.resolve("deploy/native-java/empty-maven-repo"),
                "--build-arg", "NATIVE_TASK=:control-plane:nativeCompile",
                "--build-arg", "NATIVE_BINARY=platform/control-plane/build/native/nativeCompile/control-plane",
                "--build-arg", "GRAALVM_DISTRIBUTION=community",
                "--build-arg", "GRADLE_ARGS=-PnanofaasBuildType=native -PcontrolPlaneModules=none",
                root.toString());
        assertThat(buildxBuild("registry.example:5000/team/ws-java:1.0.0", false)).endsWith("-f",
                root.resolve("deploy/recipes/Dockerfile.jvm").toString(),
                root.resolve("build/recipes/demo/functions/java/word-stats").toString());
        assertThat(buildxBuild("registry.example:5000/team/ws-python:1.0.0", false)).endsWith("-f",
                root.resolve("functions/python/word-stats/Dockerfile").toString(), root.toString());
        assertThat(buildxBuild("registry.example:5000/team/warm-echo:1.0.0", false)).contains("--target", "recipe-native",
                "recipe=" + root.resolve("build/recipes/demo/services/java/warm-echo"));
        assertThat(calls).noneMatch(call -> call.getFirst().equals("build") || call.getFirst().equals("image")
                || call.contains("--push"));

        Path staged = projectDir.resolve("build/recipes/demo");
        assertThat(projectDir.resolve("markers/control-plane-nativeCompile")).doesNotExist();
        assertThat(staged.resolve("control-plane/config/recipe.yaml")).content().contains("profile: basic");
        assertThat(staged.resolve("control-plane/application")).doesNotExist();
        assertThat(staged.resolve("services/java/warm-echo")).isDirectory();
        JsonNode image = image(report(), MA_CP);
        assertThat(image.get("status").asText()).isEqualTo("built");
        assertThat(image.get("platforms").toString()).isEqualTo("[\"linux/amd64\",\"linux/arm64\"]");
        assertThat(image.get("provenance").asBoolean()).isTrue();
        assertThat(image.has("id")).isFalse();
    }

    @Test
    void multiArchChecksTheBuilderBeforeEmptyingTheOutput() throws IOException {
        recipe(V2_HEADER + "registry: {repository: registry.example/team, tag: t, platforms: [linux/amd64, linux/arm64]}\n"
                + "controlPlane: {modules: [], build: {mode: jvm}, container: {image: cp}}\n");
        run("assembleRecipe", "-Precipe=recipe.yaml", docker());
        Files.writeString(projectDir.resolve("builder-platforms"), "Platforms: linux/arm64\n");
        Files.delete(projectDir.resolve("docker.log"));

        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker()))
                .contains("cannot build [linux/amd64]").contains("-PrecipeBuilder");
        assertThat(projectDir.resolve("build/recipes/demo/distribution.json")).exists();
        assertThat(dockerCalls()).containsExactly(List.of("buildx", "inspect"));
    }

    @Test
    void multiArchRejectsHostBuiltNativeImagesAndAStrayBuilder() throws IOException {
        recipe(V2_HEADER + "registry: {repository: registry.example/team, tag: t, platforms: [linux/amd64, linux/arm64]}\n"
                + "controlPlane: {modules: [], build: {mode: native}, container: {image: cp}}\n");
        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker())).contains("controlPlane.container.image")
                .contains("builder: host").contains("use build.builder: container");

        recipe(V2_HEADER + "registry: {repository: registry.example/team, tag: t}\n"
                + "controlPlane: {modules: [], build: {mode: jvm}, container: {image: cp}}\n");
        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), "-PrecipeBuilder=multi"))
                .contains("-PrecipeBuilder").contains("registry.platforms");
        assertThat(projectDir.resolve("docker.log")).doesNotExist();
    }

    @Test
    void multiArchWithoutImagesNeedsNoBuilder() throws IOException {
        recipe(V2_HEADER + "registry: {repository: registry.example/team, tag: t, platforms: [linux/amd64]}\n" + CP_JVM);

        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        assertThat(projectDir.resolve("docker.log")).as("no image, so no builder to check").doesNotExist();
    }

    @Test
    void hostBuiltNativeImageOnTheHostPlatformPackagesTheStagedExecutable() throws IOException {
        String host = RecipeBuildx.hostPlatform(System.getProperty("os.arch"));
        org.junit.jupiter.api.Assumptions.assumeTrue(host != null, "no image platform for this architecture");
        recipe(V2_HEADER + "registry: {repository: registry.example/team, tag: t, platforms: [" + host + "]}\n"
                + "controlPlane: {modules: [], build: {mode: native}, container: {image: cp}}\n");

        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        Path root = projectDir.toRealPath();
        assertThat(projectDir.resolve("markers/control-plane-nativeCompile")).exists();
        assertThat(projectDir.resolve("build/recipes/demo/control-plane/application")).isExecutable();
        assertThat(buildxBuild("registry.example/team/cp:t", false)).containsSubsequence("--platform", host,
                "--provenance=false", "-f", root.resolve("deploy/recipes/Dockerfile.native").toString(),
                root.resolve("build/recipes/demo/control-plane").toString());
    }

    @Test
    void previewShowsTheBuildxPath() throws IOException {
        recipe(V2_HEADER + "registry: {repository: registry.example/team, tag: t, platforms: [linux/amd64, linux/arm64],"
                + " provenance: true}\ncontrolPlane: {modules: [], build: {mode: native, builder: container},"
                + " container: {image: cp}}\n");

        assertThat(run("validateRecipe", "-Precipe=recipe.yaml").getOutput())
                .contains("docker buildx build -f deploy/native-java/Dockerfile --target recipe-native"
                        + " (container builder, community)")
                .contains("image registry.example/team/cp:t (docker buildx build --platform linux/amd64,linux/arm64,"
                        + " provenance: max)");
    }
```

- [ ] **Step 4: Run the tests to verify they fail**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipePluginTest'`
Expected: the six new tests FAIL. For example, `multiArchAssemblyBuildsEveryImageWithBuildxIntoTheCacheOnly`
fails because the first call is a classic `build`, and `multiArchRejects...` because nothing
rejects the recipe. Every existing test PASSES.

- [ ] **Step 5: The host-platform check and the preview in `RecipeTasks`**

In `RecipeTasks.resolve()`, replace the host-check loop with:

```java
        List<String> platforms = RecipeBuildx.platforms(data);
        for (Target target : resolved) {
            String problem = hostProblem(System.getProperty("os.name"), target);
            if (problem == null) {
                problem = RecipeBuildx.platformProblem(System.getProperty("os.arch"), platforms, target);
            }
            if (problem != null) {
                throw fail(target.field() + ".container.image: " + problem);
            }
        }
```

In `printPreview()`, replace the `for (Target target : targets)` loop with:

```java
        List<String> platforms = RecipeBuildx.platforms(recipe.data());
        String buildx = platforms == null ? "" : " (docker buildx build --platform " + String.join(",", platforms)
                + ", provenance: " + (RecipeBuildx.provenance(recipe.data()) ? "max" : "off") + ")";
        for (Target target : targets) {
            String build = platforms != null && target.containerBuilt() && target.image() != null
                    ? "docker buildx build -f " + RecipeContainerBuild.DOCKERFILE + " --target " + RecipeBuildx.NATIVE_TARGET
                            + " (container builder, " + target.nativeOptions().distribution() + ")"
                    : target.containerBuilt()
                    ? "docker build -f " + RecipeContainerBuild.DOCKERFILE + " --target " + RecipeContainerBuild.TARGET
                            + " (container builder, " + target.nativeOptions().distribution() + ")"
                    : target.task() != null ? target.task()
                    : "docker build -f " + slash(target.dockerfile()) + " "
                            + (target.contextDir().toString().isEmpty() ? "." : slash(target.contextDir()));
            System.out.printf("  %-20s %-11s %-10s %s%s%s%n", target.name(), target.sdk(), target.mode(), build,
                    target.stagingDir() == null ? "" : " -> " + target.stagingDir(),
                    target.image() == null ? "" : "  image " + target.image() + buildx);
        }
```

- [ ] **Step 6: Wire the buildx path in `RecipeArtifacts.register`**

1. **Imports.** Add `java.util.LinkedHashMap`, `java.util.Set` (used by `requireBuilderPlatforms`)
   and `java.util.function.Function`.

2. **`-PrecipeBuilder`.** Right after the `docker` variable is computed, add:

   ```java
           Services services = root.getObjects().newInstance(Services.class);
           List<String> platforms = RecipeBuildx.platforms(recipe.data());
           boolean provenance = RecipeBuildx.provenance(recipe.data());
           Object builderProperty = root.findProperty("recipeBuilder");
           String builder = builderProperty == null ? null : builderProperty.toString();
           if (builder != null && platforms == null) {
               throw RecipeReader.failure(recipe.source(), "-PrecipeBuilder selects the buildx builder of"
                       + " registry.platforms, which this recipe does not set");
           }
   ```

   Then delete the later line `Services services = root.getObjects().newInstance(Services.class);`.

3. **`checkRecipeBuilder`.** Right after the `clean` task registration, add:

   ```java
           if (platforms != null && targets.stream().anyMatch(target -> target.image() != null)) {
               TaskProvider<Task> check = root.getTasks().register("checkRecipeBuilder", task -> {
                   task.setDescription("Checks that the buildx builder can build every platform of registry.platforms.");
                   task.doLast(ignored -> requireBuilderPlatforms(services.getExec(), docker, builder, platforms));
               });
               // Before the output is emptied, and so before anything compiles (compiles run after cleanRecipe).
               clean.configure(task -> task.dependsOn(check));
           }
   ```

4. **Skip the separate executable build.** In the `containerBuilds` loop, right after the
   `RecipeContainerBuild.gradleArgs(...)` pre-check call, add:

   ```java
               if (platforms != null && target.image() != null) {
                   continue; // recipe-native compiles it inside the image build, once per platform
               }
   ```

5. **Image tasks.** Replace the image loop, from `List<TaskProvider<Exec>> images = new ArrayList<>();`
   to the loop's closing brace, with:

   ```java
           List<TaskProvider<Exec>> images = new ArrayList<>();
           Map<String, Function<Path, List<String>>> buildxCommands = new LinkedHashMap<>();
           for (RecipeTasks.Target target : targets) {
               if (target.image() == null) {
                   continue;
               }
               if (platforms == null) {
                   images.add(root.getTasks().register("recipeImage" + images.size(), Exec.class, exec -> {
                       exec.setDescription("Builds " + target.image());
                       exec.dependsOn(runtimeFiles);
                       if (containerBuilds.containsKey(target)) {
                           exec.dependsOn(containerBuilds.get(target));
                       }
                       exec.commandLine(dockerBuild(docker, rootDir, output, target));
                       exec.doLast(ignored -> imageIds.put(target.image(),
                               imageId(services.getExec(), docker, target.image())));
                   }));
                   continue;
               }
               Function<Path, List<String>> command = metadata -> RecipeBuildx.build(docker, builder, platforms,
                       provenance, target.image(), buildxSource(root, recipe, modules, services, rootDir, output, target,
                               passThrough, containerdRepository), metadata);
               buildxCommands.put(target.image(), command);
               images.add(root.getTasks().register("recipeImage" + images.size(), Exec.class, exec -> {
                   exec.setDescription("Builds " + target.image() + " for " + String.join(", ", platforms));
                   exec.dependsOn(runtimeFiles);
                   exec.setWorkingDir(rootDir.toFile());
                   // Set at execution: a container-built native image takes the source revision then.
                   exec.doFirst(ignored -> exec.commandLine(command.apply(null)));
               }));
           }
   ```

6. **New helpers.** Replace `dockerBuild` with the three methods below, and add
   `buildxSource` and `requireBuilderPlatforms` after it:

   ```java
       /** Java images build from their staged directory (the repository .dockerignore hides build/); others from the repository. */
       static List<String> dockerBuild(String docker, Path rootDir, Path output, RecipeTasks.Target target) {
           return List.of(docker, "build", "-f", dockerfile(rootDir, target).toString(), "-t", target.image(),
                   context(rootDir, output, target).toString());
       }

       private static Path dockerfile(Path rootDir, RecipeTasks.Target target) {
           return target.dockerfile() != null ? rootDir.resolve(target.dockerfile())
                   : rootDir.resolve("deploy/recipes/Dockerfile." + target.mode());
       }

       private static Path context(Path rootDir, Path output, RecipeTasks.Target target) {
           return target.dockerfile() != null ? rootDir.resolve(target.contextDir()) : output.resolve(target.stagingDir());
       }

       /** What follows -t in a buildx image build: the Dockerfile arguments, ending with the build context. */
       private static List<String> buildxSource(Project root, RecipeReader.Document recipe, List<String> modules,
                                                Services services, Path rootDir, Path output, RecipeTasks.Target target,
                                                Map<String, String> passThrough, Path containerdRepository) {
           if (!target.containerBuilt()) {
               return List.of("-f", dockerfile(rootDir, target).toString(), context(rootDir, output, target).toString());
           }
           String projectPath = target.task().substring(0, target.task().lastIndexOf(':'));
           List<String> arguments = new ArrayList<>(List.of("-f", rootDir.resolve(RecipeContainerBuild.DOCKERFILE).toString(),
                   "--target", RecipeBuildx.NATIVE_TARGET, "--build-context", "recipe=" + output.resolve(target.stagingDir())));
           arguments.addAll(RecipeContainerBuild.builderArguments(rootDir, target.task(), nativeBinary(root, target),
                   target.nativeOptions().distribution(), RecipeContainerBuild.gradleArgs(recipe.source(), recipe.data(),
                           projectPath, modules, source(services.getExec(), rootDir, output), passThrough),
                   containerdRepository));
           arguments.add(rootDir.toString());
           return arguments;
       }

       /** Fails unless the buildx builder lists every requested platform: before the output is emptied, nothing built. */
       static void requireBuilderPlatforms(ExecOperations exec, String docker, String builder, List<String> platforms) {
           ByteArrayOutputStream output = new ByteArrayOutputStream();
           int exit = exec.exec(spec -> {
               spec.commandLine(RecipeBuildx.inspect(docker, builder));
               spec.setStandardOutput(output);
               spec.setIgnoreExitValue(true);
           }).getExitValue();
           String name = builder == null ? "the current buildx builder" : "buildx builder " + builder;
           if (exit != 0) {
               throw new GradleException("docker buildx inspect of " + name + " failed (exit " + exit
                       + "); registry.platforms needs docker buildx");
           }
           Set<String> available = RecipeBuildx.builderPlatforms(output.toString(StandardCharsets.UTF_8));
           List<String> missing = platforms.stream().filter(platform -> !available.contains(platform)).toList();
           if (!missing.isEmpty()) {
               throw new GradleException(name + " cannot build " + missing + " (it lists " + available + "). Select"
                       + " another with -PrecipeBuilder=<name>, add a node for them (docker buildx create --append), or"
                       + " install QEMU emulation (docker run --privileged --rm tonistiigi/binfmt --install all);"
                       + " native images under emulation are very slow");
           }
       }
   ```

7. **Report.** In `report(...)`, replace the `else` branch of `if (target.image() == null)` with:

   ```java
               } else {
                   ObjectNode image = component.putObject("image").put("reference", target.image()).put("status", "built");
                   List<String> platforms = RecipeBuildx.platforms(recipe.data());
                   if (platforms == null) {
                       image.put("id", imageIds.get(target.image()));
                   } else {
                       // No local image on the buildx path: publication records the digests instead.
                       ArrayNode platformNodes = image.putArray("platforms");
                       platforms.forEach(platformNodes::add);
                       image.put("provenance", RecipeBuildx.provenance(recipe.data()));
                   }
               }
   ```

   Leave `publishRecipe`'s `doLast` unchanged in this task (Task 6 branches it).

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./gradlew -p platform/gradle-plugin test`
Expected: PASS for the whole plugin suite (191 + the new tests, 0 failures).

`hostBuiltNativeImageOnTheHostPlatformPackagesTheStagedExecutable` runs on arm64 and amd64 hosts
and is skipped elsewhere.

- [ ] **Step 8: Commit**

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuild.java \
        platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeTasks.java \
        platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipePluginTest.java
node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
git commit -m "Assemble multi-architecture recipe images with buildx" -m "<trailer>"
```

---

### Task 6: Publication on the buildx path

**Files:**
- Modify: `RecipeArtifacts.java`: the `publishRecipe` registration, `publish`, and the new
  `publishBuildx` and `inspectRaw`
- Test: `RecipePluginTest.java`

**Interfaces:**
- Consumes:
  - Task 5's `buildxCommands` map, and the report written by the assembly (images with
    `platforms`);
  - Task 2: `RecipeBuildx.pushedDigest`, `imagetools`, `manifests` and `covers`.
- Produces: `static void RecipeArtifacts.publishBuildx(ExecOperations exec, String docker, Path reportFile, Map<String, Function<Path, List<String>>> commands)`.

- [ ] **Step 1: Run impact analysis**

Run: `node $GN impact "publish" --direction upstream --repo .`. Check that the `filePath` is
`RecipeArtifacts.java`; the name collides across the repo. Otherwise confirm with
`grep -rn "publish(" platform/gradle-plugin/src/main`.
Expected: the only caller is the `publishRecipe` doLast.

- [ ] **Step 2: Write the failing tests**

Add after the Task 5 tests in `RecipePluginTest`:

```java
    private static final String MULTI_ARCH_PUBLISH = V2_HEADER + """
            registry: {repository: registry.example:5000/team, tag: "1.0.0", platforms: [linux/amd64, linux/arm64], provenance: true}
            controlPlane: {modules: [], build: {mode: jvm}, container: {image: control-plane}}
            functions: [{name: word-stats, sdk: python, container: {image: ws-python}}]
            """;
    private static final String MA_PY = "registry.example:5000/team/ws-python:1.0.0";

    @Test
    void multiArchPublishPushesTheAssembledBuildsAndRecordsIndexAndPlatformDigests() throws IOException {
        recipe(MULTI_ARCH_PUBLISH);

        run("publishRecipe", "-Precipe=recipe.yaml", docker());

        List<List<String>> calls = dockerCalls();
        List<String> assembled = buildxBuild(MA_CP, false);
        List<String> pushed = buildxBuild(MA_CP, true);
        assertThat(calls.lastIndexOf(buildxBuild(MA_PY, false))).isLessThan(calls.indexOf(pushed));
        List<String> withoutPush = new ArrayList<>(pushed);
        withoutPush.subList(withoutPush.indexOf("--push"), withoutPush.indexOf("--push") + 3).clear();
        assertThat(withoutPush).as("the push repeats the assembled build").isEqualTo(assembled);
        assertThat(pushed.get(pushed.indexOf("--push") + 1)).isEqualTo("--metadata-file");
        assertThat(calls).contains(List.of("buildx", "imagetools", "inspect",
                "registry.example:5000/team/control-plane@" + digestOf("index-" + MA_CP), "--raw"));
        assertThat(calls).noneMatch(call -> call.getFirst().equals("push"));

        JsonNode image = image(report(), MA_CP);
        assertThat(image.get("status").asText()).isEqualTo("published");
        assertThat(image.get("digest").asText()).isEqualTo(digestOf("index-" + MA_CP));
        assertThat(image.get("manifests").toString()).isEqualTo("{\"linux/amd64\":\"" + digestOf(MA_CP + "@linux/amd64")
                + "\",\"linux/arm64\":\"" + digestOf(MA_CP + "@linux/arm64") + "\"}");
        assertThat(image(report(), MA_PY).get("status").asText()).isEqualTo("published");
    }

    @Test
    void multiArchPublishOfOnePlatformWithoutProvenanceRecordsItsManifest() throws IOException {
        recipe(V2_HEADER + "registry: {repository: registry.example:5000/team, tag: \"1.0.0\", platforms: [linux/arm64]}\n"
                + "controlPlane: {modules: [], build: {mode: jvm}, container: {image: control-plane}}\n");

        run("publishRecipe", "-Precipe=recipe.yaml", docker());

        assertThat(buildxBuild(MA_CP, true)).contains("--provenance=false");
        JsonNode image = image(report(), MA_CP);
        assertThat(image.get("provenance").asBoolean()).isFalse();
        assertThat(image.get("manifests").toString()).isEqualTo("{\"linux/arm64\":\"" + digestOf("index-" + MA_CP) + "\"}");
    }

    @Test
    void multiArchPublishWithAMissingPlatformIsUnverified() throws IOException {
        recipe(MULTI_ARCH_PUBLISH);
        Files.writeString(projectDir.resolve("drop-linux-arm64"), "");

        assertThat(fails("publishRecipe", "-Precipe=recipe.yaml", docker())).contains(MA_CP
                + " was pushed, but its platforms [linux/amd64] do not match [linux/amd64, linux/arm64]");
        JsonNode image = image(report(), MA_CP);
        assertThat(image.get("status").asText()).isEqualTo("published-unverified");
        assertThat(image.get("digest").asText()).isEqualTo(digestOf("index-" + MA_CP));
        assertThat(image.has("manifests")).isFalse();
        assertThat(image(report(), MA_PY).get("status").asText()).isEqualTo("built");
    }

    @Test
    void multiArchPublishWithoutBuildMetadataIsUnverified() throws IOException {
        recipe(MULTI_ARCH_PUBLISH);
        Files.writeString(projectDir.resolve("no-digest"), "");

        assertThat(fails("publishRecipe", "-Precipe=recipe.yaml", docker()))
                .contains(MA_CP + " was pushed, but its digest could not be read from the build metadata");
        JsonNode image = image(report(), MA_CP);
        assertThat(image.get("status").asText()).isEqualTo("published-unverified");
        assertThat(image.get("digest").isNull()).isTrue();
        assertThat(dockerCalls()).noneMatch(call -> call.contains("imagetools"));
    }

    @Test
    void multiArchFailedSecondPushKeepsTheFirstSuccess() throws IOException {
        recipe(MULTI_ARCH_PUBLISH);
        Files.writeString(projectDir.resolve("fail-push-2"), "");

        assertThat(fails("publishRecipe", "-Precipe=recipe.yaml", docker()))
                .contains("docker buildx build --push " + MA_PY + " failed")
                .contains("already published: [" + MA_CP + "@" + digestOf("index-" + MA_CP) + "]");
        assertThat(image(report(), MA_CP).get("status").asText()).isEqualTo("published");
        assertThat(image(report(), MA_PY).get("status").asText()).isEqualTo("failed");
    }
```

`MA_CP` is Task 5's constant. `digestOf(text)` is the existing helper: `sha256:` followed by the
SHA-256 of the text, which is what the fake docker computes.

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipePluginTest'`
Expected: the five new tests FAIL. The classic `publish` runs `docker push`, so the report has no
`manifests`, and `buildxBuild(MA_CP, true)` throws "no buildx build ... (push true)".

- [ ] **Step 4: Implement `publishBuildx`**

In `RecipeArtifacts.publish`, replace the report-reading block at its top with
`ObjectNode report = readReport(reportFile);`, and add:

```java
    private static ObjectNode readReport(Path reportFile) {
        try {
            return (ObjectNode) JSON.readTree(reportFile.toFile());
        } catch (IOException exception) {
            throw new GradleException("Cannot read " + reportFile + " (" + exception + ")", exception);
        }
    }

    /**
     * Pushes each image by repeating the build that assembled it, which the builder's cache answers, then records the
     * pushed digest (the index, or the single manifest) and one manifest digest per platform. As for publish, the
     * report is rewritten after every image, with no retry and no rollback.
     */
    static void publishBuildx(ExecOperations exec, String docker, Path reportFile,
                              Map<String, Function<Path, List<String>>> commands) {
        ObjectNode report = readReport(reportFile);
        List<String> published = new ArrayList<>();
        for (JsonNode component : report.get("components")) {
            if (!(component.get("image") instanceof ObjectNode image)) {
                continue;
            }
            String reference = image.get("reference").asText();
            List<String> platforms = new ArrayList<>();
            image.get("platforms").forEach(platform -> platforms.add(platform.asText()));
            String metadataJson;
            int exit;
            try {
                Path metadata = Files.createTempFile("recipe-buildx-", ".json");
                try {
                    exit = exec.exec(spec -> {
                        spec.commandLine(commands.get(reference).apply(metadata));
                        spec.setIgnoreExitValue(true);
                    }).getExitValue();
                    metadataJson = Files.readString(metadata);
                } finally {
                    Files.deleteIfExists(metadata);
                }
            } catch (IOException exception) {
                throw new GradleException("Cannot use a temporary buildx metadata file (" + exception + ")", exception);
            }
            if (exit != 0) {
                image.put("status", "failed");
                writeReport(reportFile, report);
                throw new GradleException("docker buildx build --push " + reference + " failed (exit " + exit
                        + "); already published: " + published);
            }
            String digest = RecipeBuildx.pushedDigest(metadataJson);
            Map<String, String> manifests = digest == null ? null
                    : RecipeBuildx.manifests(inspectRaw(exec, docker, reference, digest), digest, platforms);
            boolean verified = RecipeBuildx.covers(manifests, platforms);
            image.put("status", verified ? "published" : "published-unverified");
            image.put("digest", digest);
            if (verified) {
                ObjectNode manifestNodes = image.putObject("manifests");
                manifests.forEach(manifestNodes::put);
            }
            try {
                writeReport(reportFile, report);
            } catch (GradleException exception) {
                throw new GradleException(reference + " was pushed, but " + reportFile + " could not record it; "
                        + "already published before it: " + published, exception);
            }
            if (!verified) {
                String problem = digest == null ? "its digest could not be read from the build metadata"
                        : manifests == null ? "its pushed index could not be read"
                        : "its platforms " + manifests.keySet() + " do not match " + platforms;
                throw new GradleException(reference + " was pushed, but " + problem
                        + " (recorded as published-unverified); already published before it: " + published);
            }
            published.add(reference + "@" + digest);
        }
    }

    /** imagetools inspect --raw of a pushed digest; empty when the registry cannot be read. */
    private static String inspectRaw(ExecOperations exec, String docker, String reference, String digest) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int exit = exec.exec(spec -> {
            spec.commandLine(RecipeBuildx.imagetools(docker, reference, digest));
            spec.setStandardOutput(output);
            spec.setIgnoreExitValue(true);
        }).getExitValue();
        return exit == 0 ? output.toString(StandardCharsets.UTF_8) : "";
    }
```

In `register`, change the `publishRecipe` doLast to branch:

```java
            task.doLast(ignored -> {
                if (platforms == null) {
                    publish(services.getExec(), docker, output.resolve(REPORT));
                } else {
                    publishBuildx(services.getExec(), docker, output.resolve(REPORT), buildxCommands);
                }
            });
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew -p platform/gradle-plugin test`
Expected: PASS for the whole plugin suite, 0 failures. The existing publish tests
(`publishPushesAfterAllBuildsAndRecordsRegistryDigests`, `failedSecondPushKeepsTheFirstSuccessInTheReport`, ...)
are unchanged and green.

- [ ] **Step 6: Commit**

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipePluginTest.java
node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
git commit -m "Publish multi-architecture images and record per-platform digests" -m "<trailer>"
```

---

### Task 7: Documentation

**Files:**
- Modify: `docs/recipes.md`: the recipe format block, a new section after "Native builds", and
  "The report"

**Interfaces:**
- Consumes: the behaviour of Tasks 1-6.
- Produces: nothing in code.

- [ ] **Step 1: Recipe format block**

In the YAML block of "Recipe format", after the `tag: "1.0.0"` line of `registry`, add:

```yaml
  platforms: [linux/amd64, linux/arm64]   # optional; selects docker buildx (see Multi-architecture images)
  provenance: true                 # optional, needs platforms; BuildKit provenance, mode=max
```

- [ ] **Step 2: New section after "Native builds"**

Insert the section below just before `## Output and runtime configuration`:

````markdown
## Multi-architecture images

```yaml
registry:
  repository: ghcr.io/my-org
  tag: "1.0.0"
  platforms: [linux/amd64, linux/arm64]
  provenance: true
```

- **The buildx path.** With `registry.platforms`, even with one platform, every image is built
  with `docker buildx build --platform <list>`. `provenance: true` attaches BuildKit provenance
  with `mode=max`. Without it, images carry none (`--provenance=false`). Without `platforms`,
  everything works as described above.
- **Assembly keeps no local image.** A multi-architecture image cannot live in the classic
  Docker image store, and provenance does not survive `--load`. So `assembleRecipe` builds every
  platform into the builder's cache only, and `publishRecipe` repeats each build with `--push`.
  The repeated build comes from that cache, so it takes a fraction of the first.
- **The builder.** `-PrecipeBuilder=<name>` selects the buildx builder; without it, the current
  one is used. Before anything is emptied or built, the `checkRecipeBuilder` task requires
  `docker buildx inspect` to list every platform.
  - A builder reaches another architecture through QEMU emulation
    (`docker run --privileged --rm tonistiigi/binfmt --install all`) or through a node running on
    it (`docker buildx create --append`).
  - The default `docker` driver cannot build several platforms at once; use a
    `docker-container` builder.
  - This path needs `docker buildx`: podman is not supported here.
- **Native components.**
  - With `builder: container` and an image, the component is compiled and packaged in one build,
    by the `recipe-native` stage of `deploy/native-java/Dockerfile`, once per platform. The
    image's provenance therefore names the GraalVM builder stage. Its staging directory holds
    only the runtime files, with no `application`.
  - With `builder: host`, the executable has the host's architecture, so `platforms` must be
    exactly the host's platform.
  - native-image under QEMU emulation is very slow: in practice, each architecture needs a
    native node.
- **Other images.** JVM images package the same jar for every platform. Dockerfile components
  build from their own Dockerfile, and their `RUN` steps need emulation or a native node.
- **Signing stays outside the recipe.** nanolab and the release sign the digests the report
  records.
````

- [ ] **Step 3: The report**

In "The report", after the bullet that begins ``- for each image, `id`:``, add:

```markdown
- on the buildx path, instead of `id`: `platforms` and `provenance` for each image, and after
  publication `digest` (the multi-architecture index, or the manifest itself for one platform
  without provenance) and `manifests`, mapping each platform to its manifest digest. Nothing
  else distinguishes the two paths: a consumer checks for `platforms`.
```

In the status table, replace the `published` row with:

```markdown
| `published` | Pushed; `digest` is the registry digest (`docker push`'s manifest digest, the matching `RepoDigests` entry, or on the buildx path the pushed index), never the local image ID |
| `published-unverified` | The push succeeded but the digest, or on the buildx path one platform's manifest, could not be determined; the task fails |
```

Replace the existing `published-unverified` row with the second line above; do not add a second
row. In the paragraph that begins "The Docker CLI and its configured credentials", add this
sentence after its first sentence: "On the buildx path, `-PrecipeBuilder=<name>` selects the
builder."

- [ ] **Step 4: Check and commit**

Run: `grep -n "recipe-native\|PrecipeBuilder\|platforms" docs/recipes.md`
Expected: matches in the format block, the new section and the report section.

```bash
git add docs/recipes.md
node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs
git commit -m "Document multi-architecture recipe images" -m "<trailer>"
```

---

### Task 8: Full suite and end-to-end verification

**Files:** none are committed. Scratch files go in
`/tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/`,
referred to below as `$SCRATCH`.

**Interfaces:**
- Consumes: everything above.
- Produces: evidence for the final report.

- [ ] **Step 1: Full test suite**

Run:
```bash
./gradlew test --no-parallel --continue -PcontainerdMavenLocal=true \
  -Dmaven.repo.local=$PWD/.gradle/containerd-m2 -PcontrolPlaneModules=all > $SCRATCH/full-suite.log 2>&1; echo exit $?
uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py
```
Expected:
- the Gradle suite exits 0 with 0 failures (it had 2386 tests before this branch);
- the pytest file has 8 passed.

A single failure in `sdks/java`'s `SharedSaturationWireCorpusTest` is the known intermittent one
(#216). Re-run it alone and record it; it is not a regression of this branch.

- [ ] **Step 2: A local registry and a dedicated builder**

```bash
docker run -d --rm --name recipe-e2e-registry -p 5055:5000 registry:2
docker buildx create --name recipe-e2e --driver docker-container --driver-opt network=host
docker buildx inspect recipe-e2e --bootstrap | grep Platforms
```
Expected: `Platforms:` lists `linux/arm64` on this host.

- [ ] **Step 3: Publish a native control plane and a JVM service for linux/arm64 with provenance**

Write `$SCRATCH/e2e-multiarch.yaml`:

```yaml
schemaVersion: 2
name: e2e-multiarch
registry: {repository: localhost:5055/nanofaas, tag: e2e, platforms: [linux/arm64], provenance: true}
controlPlane:
  modules: [build-metadata]
  build: {mode: native, builder: container, variant: native-e2e}
  container: {image: control-plane}
services:
  - {name: warm-echo, sdk: java, build: {mode: jvm}, container: {image: warm-echo}}
```

Run:
```bash
./gradlew publishRecipe -Precipe=$SCRATCH/e2e-multiarch.yaml -PrecipeBuilder=recipe-e2e \
  -PrecipeOutput=$SCRATCH/out-multiarch
```

Check:
- `$SCRATCH/out-multiarch/distribution.json`: both images are `published`, with a `digest` and
  `manifests` equal to `{"linux/arm64": ...}`, and there is no `id`;
- `$SCRATCH/out-multiarch/control-plane/` has no `application`;
- the provenance names the builder stage:
  ```bash
  docker buildx imagetools inspect localhost:5055/nanofaas/control-plane@<digest> --format '{{json .Provenance}}' \
    > $SCRATCH/provenance.json
  grep -o 'oraclelinux@sha256:[0-9a-f]*\|build-arg:GRAALVM_DISTRIBUTION[^,]*' $SCRATCH/provenance.json
  ```
  Expected: an `oraclelinux` material with a digest, and `build-arg:GRAALVM_DISTRIBUTION`;
- the published image runs:
  ```bash
  docker run -d --rm --name recipe-e2e-cp -p 18080:8080 -p 18081:8081 \
    localhost:5055/nanofaas/control-plane@<linux/arm64 manifest digest>
  curl -s localhost:18080/modules/build-metadata
  ```
  Expected: the JSON reports `variant: native-e2e` and a native (Substrate VM) runtime. If the
  endpoint is on 18081 instead, use that port and record which it was.

- [ ] **Step 4: Two platforms (only with the user's consent)**

Installing QEMU (`docker run --privileged --rm tonistiigi/binfmt --install all`) changes the
host's binfmt configuration. Ask the user first.
- **If they agree:** re-run Step 3 with `platforms: [linux/amd64, linux/arm64]`, and with the
  control plane on `mode: jvm` so as not to compile native-image under emulation. Check that
  `manifests` has both platforms and that
  `docker buildx imagetools inspect localhost:5055/nanofaas/control-plane:e2e` lists both.
- **If they decline:** record "two-platform publication not run on this host" for the final
  report.

- [ ] **Step 5: Clean up and report**

```bash
docker rm -f recipe-e2e-cp; docker rm -f recipe-e2e-registry; docker buildx rm recipe-e2e
```

Record every result in the final summary. There is nothing to commit.

---

## Self-review notes

- **Spec coverage:**

  | Spec requirement | Task |
  | --- | --- |
  | Schema rules (v2 only, enum, unique, non-empty, provenance needs platforms) | 1 |
  | Host platform rule and error | 2, 5 |
  | `-PrecipeBuilder` and its error without platforms | 5 |
  | Preview | 5 |
  | `checkRecipeBuilder` before `cleanRecipe` | 5 |
  | Image commands per kind | 2, 5 |
  | `recipe-native` stage; runtime stage last | 3 |
  | No `recipeNativeBuild` for a container-built native component with an image; staging holds only runtime files | 5 |
  | Assembly without output; report `built`, `platforms`, `provenance`, no `id` | 5 |
  | Publication: push, metadata digest, imagetools manifests, verification, report rewrite, failures | 6 |
  | Report validation | 4 |
  | A recipe without `platforms` unchanged | 5, 6 (existing tests), 8 |
  | Documentation | 7 |
  | Manual end-to-end and provenance check | 8 |

- **Deviation from the spec's test list.** The builder check has its own test,
  `multiArchChecksTheBuilderBeforeEmptyingTheOutput`, which proves the old report survives.
  Running the check only when an image exists is a Review Focus addition (item 2).
