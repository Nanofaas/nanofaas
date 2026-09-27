# Native AOT with the recipe's configuration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A native control-plane build evaluates Spring's property conditions with the same
configuration the runtime reads. That is a recipe's `controlPlane.config`, or a file named with
`-PnanofaasAotConfig`.

**Architecture:**
- **One hook.** `platform/control-plane/build.gradle` passes `-PnanofaasAotConfig=<file>` to
  `processAot`, as `spring.config.additional-location`, and declares the file as a task input.
- **Recipes on the host.** The settings plugin writes `controlPlane.config` to
  `build/recipe-aot/<name>/control-plane.yaml` and sets that property.
- **The container builder.** It receives the YAML as a base64 build argument, decodes it, and
  adds the flag itself.

**Tech Stack:**
- Gradle 9.7.1, Groovy build scripts, and the Java 25 settings plugin (`platform/gradle-plugin`,
  an included build).
- Spring Boot 4.1 AOT and GraalVM 25.2.4 CE.
- BuildKit.
- JUnit 5, AssertJ, Gradle TestKit, pytest.

**Spec:** `docs/superpowers/specs/2026-09-27-native-aot-config-design.md`

## Global Constraints

- **Property.** It is named `nanofaasAotConfig`. A relative path resolves against the repository
  root (`rootProject.file`).
- **`processAot` wiring.** It receives
  `systemProperty 'spring.config.additional-location', 'file:<absolute path>'` (`file:`, never
  `optional:file:`) and `inputs.file(<path>)`.
- **When the hook applies.** Only in a native build; in a JVM build the property has no effect.
  The release and `scripts/native-java-image.sh` do not pass it.
- **Recipe file.** It is `<root>/build/recipe-aot/<recipe name>/control-plane.yaml`, written
  when the recipe is resolved, with the same YAML serialisation as `config/recipe.yaml`. It
  exists only for a native control plane with `controlPlane.config`.
- **Owned flag.** `nanofaasAotConfig` becomes a recipe-owned flag. Passing it together with
  `-Precipe` is rejected.
- **Container builder.**
  - The build argument is `NATIVE_AOT_CONFIG`, base64 of the YAML and empty by default.
  - Only the control plane gets it, and only when it has a configuration.
  - The Dockerfile decodes it to `/tmp/nanofaas-aot-config.yaml` and adds
    `${NATIVE_AOT_CONFIG:+-PnanofaasAotConfig=/tmp/nanofaas-aot-config.yaml}` to the Gradle
    command.
- **Report and preview:** unchanged.
- **Project rules:**
  - Java 4-space indentation; code, comments and commits in English.
  - Run GitNexus `impact` before editing an existing symbol, and confirm UNKNOWN results with a
    text search.
  - Run `detect_changes` on the staged diff before every commit:
    `node /tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad/detect.mjs`.
  - Stage paths explicitly. Never stage `docs/experiments/**`, the untracked
    `docs/superpowers/{plans,specs}/2026-09-23-*` files, or `minikube_latest_arm64.deb`.
- **Commit trailer.** `-m "<trailer>"` in the steps below stands for these two lines:
  ```
  Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
  Claude-Session: https://claude.ai/code/session_019F6VX47yLJ3prsE2mDcaU1
  ```

GitNexus CLI (bare method names; check the returned `filePath`):
`GN=/home/michele/.npm/_npx/5e786f48223a616c/node_modules/gitnexus/dist/cli/index.js; node $GN impact "<name>" --direction upstream --repo .`

GraalVM for native steps: `export JAVA_HOME=$HOME/.sdkman/candidates/java/25.2.4-graalce PATH=$HOME/.sdkman/candidates/java/25.2.4-graalce/bin:$PATH`.
`$SCRATCH` = `/tmp/claude-1000/-home-michele-Documenti-nanofaas/951d8351-fa77-4430-b91a-b6b4fcaf73aa/scratchpad`.

## Review Focus

These are the inputs the spec implies but does not name. Each has a test in the task shown.

1. **A configuration value with quotes, `$`, or several lines** must reach the container builder
   unchanged, because base64 carries it untouched through `--build-arg`. (Task 3, unit test)
2. **A configuration changed between two native assemblies** must redo AOT and change the
   components. That is what `inputs.file` is for: without it, Gradle reuses the stale output and
   the old API stays. (Task 5, step 3)
3. **A relative `-PnanofaasAotConfig`** must resolve against the repository root, not against
   `platform/control-plane/`. (Task 1, step 4)
4. **A native function or service built in the container** must not receive `NATIVE_AOT_CONFIG`:
   only the control plane has a configuration. (Task 3, plugin test)
5. **A JVM build given `-PnanofaasAotConfig` pointing at a missing file** must still build: the
   property has no effect without AOT. (Task 1, step 5)

---

## File Structure

- **Modify** `platform/control-plane/build.gradle`: the `processAot` hook.
- **Modify** `platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeBuildProperties.java`:
  - `AOT_CONFIG_PROPERTY`;
  - `aotConfig(JsonNode)`;
  - the owned flag.
- **Modify** `.../gradle/RecipeArtifacts.java`:
  - extract `configYaml(JsonNode)`;
  - pass the YAML to the container builder.
- **Modify** `.../gradle/ControlPlaneModulesPlugin.java`: write the file and set the property.
- **Modify** `.../gradle/RecipeContainerBuild.java`: `builderArguments` and `command` gain
  `aotConfig`.
- **Modify** `deploy/native-java/Dockerfile`: the builder stage.
- **Modify** these tests:
  - `.../gradle/RecipePluginTest.java`;
  - `.../gradle/RecipeContainerBuildTest.java`;
  - `scripts/tests/test_java_container_images.py`.
- **Modify** `docs/recipes.md`.

Java paths below are relative to `platform/gradle-plugin/src/{main,test}/java/it/unimib/datai/nanofaas/gradle/`.
The plugin test command is
`./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.<Class>'`.

---

### Task 1: The `processAot` hook in the control plane's build

**Files:**
- Modify: `platform/control-plane/build.gradle`, right after the `if (!nativeBuildRequested) { ... }` block, around line 153.

**Interfaces:**
- Consumes: nothing.
- Produces: the project property `nanofaasAotConfig` (a path). In a native build, `processAot`
  runs Spring AOT with that file as `spring.config.additional-location`. Tasks 2 and 3 set this
  property.

This task's test is a real native build, because nothing short of running Spring AOT exercises
the hook. The spike showed that one native build of a control plane with the `runtime-config`
module takes about a minute on this host.

- [ ] **Step 1: Write the configuration used by the test**

```bash
printf 'nanofaas:\n  admin:\n    runtime-config:\n      enabled: true\n' > $SCRATCH/aot-on.yaml
```

- [ ] **Step 2: Run the build to verify the property is ignored today**

```bash
export JAVA_HOME=$HOME/.sdkman/candidates/java/25.2.4-graalce PATH=$HOME/.sdkman/candidates/java/25.2.4-graalce/bin:$PATH
./gradlew :control-plane:nativeCompile -PcontrolPlaneModules=runtime-config -PnanofaasAotConfig=$SCRATCH/aot-on.yaml -q
env -i HOME=$HOME PATH=/usr/bin:/bin SERVER_PORT=18095 MANAGEMENT_SERVER_PORT=18096 \
  platform/control-plane/build/native/nativeCompile/control-plane > $SCRATCH/aot-t1.log 2>&1 & P=$!
for i in $(seq 1 30); do c=$(curl -s -o /dev/null -w '%{http_code}' localhost:18095/v1/admin/runtime-config) && [ "$c" != 000 ] && break; sleep 1; done
echo "HTTP $c"; kill $P
```
Expected: `HTTP 404`. The property is not read yet.

- [ ] **Step 3: Add the hook**

Insert after the closing brace of `if (!nativeBuildRequested) { ... }`:

```groovy
// A native build decides its property-conditional beans (runtime-config's admin API, the soak metrics, the image
// validator) during Spring AOT. Hand AOT the configuration the runtime will read (a recipe's controlPlane.config),
// or it decides with application.yml's defaults. file:, not optional:file:, so a wrong path fails the build.
def aotConfig = findProperty('nanofaasAotConfig')
if (nativeBuildRequested && aotConfig) {
    def aotConfigFile = rootProject.file(aotConfig.toString())
    tasks.named('processAot') {
        inputs.file(aotConfigFile).withPropertyName('nanofaasAotConfig').withPathSensitivity(PathSensitivity.NONE)
        systemProperty 'spring.config.additional-location', 'file:' + aotConfigFile.absolutePath
    }
}
```

- [ ] **Step 4: Rerun the build to verify it passes, with a relative path**

Use a path relative to the repository root, to also pin Review Focus 3:

```bash
cp $SCRATCH/aot-on.yaml build/aot-on.yaml
./gradlew :control-plane:nativeCompile -PcontrolPlaneModules=runtime-config -PnanofaasAotConfig=build/aot-on.yaml -q
# same run and curl as in step 2
```
Expected: `HTTP 200`, with a JSON body that starts `{"revision":0,"namespaces":`.

- [ ] **Step 5: The error and no-effect cases**

```bash
./gradlew :control-plane:nativeCompile -PcontrolPlaneModules=runtime-config -PnanofaasAotConfig=build/missing.yaml 2>&1 | grep -i -m2 'nanofaasAotConfig\|does not exist'
./gradlew :control-plane:bootJar -PnanofaasAotConfig=build/missing.yaml -q && echo "jvm build ok"
rm build/aot-on.yaml
```
Expected:
- the native build fails, with a message that names `nanofaasAotConfig` and a file that does not
  exist;
- the JVM `bootJar` prints `jvm build ok` (Review Focus 5).

- [ ] **Step 6: Commit**

```bash
git add platform/control-plane/build.gradle
node $SCRATCH/detect.mjs
git commit -m "Let native builds run Spring AOT with a given configuration" -m "<trailer>"
```

---

### Task 2: Recipes write the configuration for AOT

**Files:**
- Modify: `RecipeArtifacts.java`, `writeRuntimeFiles` (lines 447-454): extract `configYaml`.
- Modify: `RecipeBuildProperties.java`: `AOT_CONFIG_PROPERTY`, `aotConfig`, and the `OWNED_FLAGS` entry.
- Modify: `ControlPlaneModulesPlugin.java`, `apply` (lines 48-49): write the file and set the property.
- Test: `RecipePluginTest.java`.

**Interfaces:**
- Consumes: Task 1's property name, `nanofaasAotConfig`.
- Produces:
  - `static String RecipeArtifacts.configYaml(JsonNode config)`, the YAML that
    `config/recipe.yaml` holds;
  - `static final String RecipeBuildProperties.AOT_CONFIG_PROPERTY = "nanofaasAotConfig"`;
  - `static String RecipeBuildProperties.aotConfig(JsonNode data)`: the YAML, or `null` unless the
    control plane is native and has `controlPlane.config`.

  Task 3 uses `aotConfig`.

- [ ] **Step 1: Run impact analysis**

Run `node $GN impact "writeRuntimeFiles" …`, `impact "byProject" …` and `impact "apply" …`
(check that the last one's `filePath` is `ControlPlaneModulesPlugin.java`).
Expected: callers inside `platform/gradle-plugin` only. Confirm UNKNOWN results with
`grep -rn "<name>(" platform/gradle-plugin/src/main`.

- [ ] **Step 2: Write the failing tests**

In `RecipePluginTest.writeFixture()`'s root `build.gradle`, append `'nanofaasAotConfig'` to the
end of the `keys` list in `printRecipeProps`. Existing assertions match prefixes of that line, so
they keep passing. Then add after `recipeOwnedFlagsAreRejectedAndBuilderFlagsAccepted`:

```java
    @Test
    void aNativeControlPlaneHandsItsConfigurationToSpringAot() throws IOException {
        recipe(V2_HEADER + """
                controlPlane:
                  modules: []
                  build: {mode: native}
                  config: {nanofaas: {admin: {runtime-config: {enabled: true}}}}
                """);

        String output = run("printRecipeProps", "-Precipe=recipe.yaml").getOutput();

        String line = output.lines().filter(l -> l.startsWith("props :control-plane ")).findFirst().orElseThrow();
        String path = line.substring(line.indexOf("nanofaasAotConfig=") + "nanofaasAotConfig=".length());
        assertThat(path).endsWith("build/recipe-aot/demo/control-plane.yaml");
        assertThat(Files.readString(Path.of(path))).isEqualTo("nanofaas:\n  admin:\n    runtime-config:\n      enabled: true\n");
        assertThat(output.lines().filter(l -> l.contains("nanofaasAotConfig=/"))).as("only the control plane").hasSize(1);
    }

    @Test
    void onlyANativeControlPlaneWithAConfigurationGetsAnAotConfiguration() throws IOException {
        recipe(V2_HEADER + "controlPlane: {modules: [], build: {mode: jvm}, config: {nanofaas: {metrics: {profile: basic}}}}\n");
        assertThat(run("printRecipeProps", "-Precipe=recipe.yaml").getOutput()).contains("nanofaasAotConfig=null")
                .doesNotContain("recipe-aot");

        recipe(V2_HEADER + "controlPlane: {modules: [], build: {mode: native}}\n");
        assertThat(run("printRecipeProps", "-Precipe=recipe.yaml").getOutput()).doesNotContain("recipe-aot");
        assertThat(projectDir.resolve("build/recipe-aot")).doesNotExist();
    }

    @Test
    void theAotConfigurationFlagBelongsToTheRecipe() throws IOException {
        recipe(V2_HEADER + CP_JVM);

        assertThat(fails("printRecipeProps", "-Precipe=recipe.yaml", "-PnanofaasAotConfig=aot.yaml"))
                .contains("-PnanofaasAotConfig cannot be combined with -Precipe").contains("controlPlane.config");
    }
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipePluginTest'`
Expected:
- FAIL: `aNativeControlPlaneHandsItsConfigurationToSpringAot`, because the line ends
  `nanofaasAotConfig=null`;
- FAIL: `theAotConfigurationFlagBelongsToTheRecipe`, because nothing is rejected;
- PASS: `onlyANativeControlPlaneWithAConfigurationGetsAnAotConfiguration`, which is a guard;
- every existing test still passes.

- [ ] **Step 4: Implement**

In `RecipeArtifacts.writeRuntimeFiles`, replace the dump:

```java
                Files.writeString(directory.resolve("config/recipe.yaml"), configYaml(config));
```

and add, next to `argfile`:

```java
    /** The YAML config/recipe.yaml holds; Spring AOT reads the same text for a native control plane. */
    static String configYaml(JsonNode config) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        return new Yaml(options).dump(JSON.convertValue(config, Map.class));
    }
```

Remove the two `DumperOptions` lines that are left over in `writeRuntimeFiles`.

In `RecipeBuildProperties`, add next to `SERVICE_MODE_PROPERTY`:

```java
    /** Where platform/control-plane/build.gradle finds the configuration Spring AOT evaluates conditions with. */
    static final String AOT_CONFIG_PROPERTY = "nanofaasAotConfig";
```

In `ownedFlags()`, after the `nanofaasBuildOptimization` entry:

```java
        flags.put(AOT_CONFIG_PROPERTY, "controlPlane.config");
```

and after `byProject`:

```java
    /**
     * The control plane's configuration as the YAML Spring AOT reads, or null unless the control plane is native and
     * configured: a native build decides its property-conditional beans at build time, with this configuration.
     */
    static String aotConfig(JsonNode data) {
        JsonNode controlPlane = data.path("controlPlane");
        JsonNode config = controlPlane.path("config");
        if (!controlPlane.at("/build/mode").asText().equals("native") || config.isMissingNode()) {
            return null;
        }
        return RecipeArtifacts.configYaml(config);
    }
```

In `ControlPlaneModulesPlugin.apply`, replace

```java
        Map<String, Map<String, String>> projectProperties =
                recipe == null ? Map.of() : RecipeBuildProperties.byProject(recipe.data());
```

with

```java
        Map<String, Map<String, String>> projectProperties =
                recipe == null ? Map.of() : withAotConfig(settings, recipe, RecipeBuildProperties.byProject(recipe.data()));
```

and add the method:

```java
    /**
     * Writes a native control plane's configuration under build/ now, when path and content are known, and hands its
     * path to :control-plane; processAot, which reads it, runs long after. Rewritten on every invocation: harmless.
     */
    private static Map<String, Map<String, String>> withAotConfig(Settings settings, RecipeReader.Document recipe,
                                                                  Map<String, Map<String, String>> byProject) {
        String yaml = RecipeBuildProperties.aotConfig(recipe.data());
        if (yaml == null) {
            return byProject;
        }
        Path file = settings.getSettingsDir().toPath().resolve("build/recipe-aot")
                .resolve(recipe.data().get("name").asText()).resolve("control-plane.yaml");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, yaml);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        Map<String, Map<String, String>> result = new LinkedHashMap<>(byProject);
        Map<String, String> controlPlane =
                new LinkedHashMap<>(result.getOrDefault(RecipeBuildProperties.CONTROL_PLANE, Map.of()));
        controlPlane.put(RecipeBuildProperties.AOT_CONFIG_PROPERTY, file.toString());
        result.put(RecipeBuildProperties.CONTROL_PLANE, controlPlane);
        return result;
    }
```

Add the imports that are missing: `java.io.IOException`, `java.io.UncheckedIOException`,
`java.nio.file.Files`, `java.util.LinkedHashMap`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./gradlew -p platform/gradle-plugin test`
Expected: PASS, the whole plugin suite. `config/recipe.yaml` is unchanged, so every existing
`profile: basic` assertion is still green.

- [ ] **Step 6: Commit**

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java \
        platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeBuildProperties.java \
        platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/ControlPlaneModulesPlugin.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipePluginTest.java
node $SCRATCH/detect.mjs
git commit -m "Hand a native control plane's recipe configuration to Spring AOT" -m "<trailer>"
```

---

### Task 3: The container builder

**Files:**
- Modify: `RecipeContainerBuild.java`: `command` and `builderArguments` (lines 64-85).
- Modify: `RecipeArtifacts.java`: the `RecipeContainerBuild.command(...)` call (line ~156) and
  the `builderArguments(...)` call in `buildxSource` (line ~493).
- Modify: `deploy/native-java/Dockerfile`: the builder stage (lines 40-48).
- Test:
  - `RecipeContainerBuildTest.java`;
  - `RecipePluginTest.java`: `containerBuilderCompilesInsideTheBuilderAndPackagesAsUsual`,
    `multiArchAssemblyBuildsEveryImageWithBuildxIntoTheCacheOnly`,
    `hostAndContainerBuildersMixAndImagelessComponentsStillBuild`;
  - `scripts/tests/test_java_container_images.py`.

**Interfaces:**
- Consumes: `RecipeBuildProperties.aotConfig(JsonNode)` from Task 2, and Task 1's property.
- Produces:
  - `static List<String> builderArguments(Path rootDir, String nativeTask, String nativeBinary, String distribution, List<String> gradleArgs, Path containerdRepository, String aotConfig)`;
  - `static List<String> command(String docker, Path rootDir, Path destination, String nativeTask, String nativeBinary, String distribution, List<String> gradleArgs, Path containerdRepository, String aotConfig)`.

  In both, `aotConfig` is the YAML or `null`.

- [ ] **Step 1: Run impact analysis**

Run `impact "builderArguments"` and `impact "command"`, checking that the `filePath` is
`RecipeContainerBuild.java`.
Expected: callers in `RecipeArtifacts`, plus the tests.

- [ ] **Step 2: Write the failing tests**

In `RecipeContainerBuildTest`, add `, null` as a final argument to the two existing
`RecipeContainerBuild.command(...)` calls (in `commandTargetsTheExportStage` and
`anOutputPathWithACommaIsQuotedForTheCsvOutputOption`). Then add:

```java
    @Test
    void theAotConfigurationTravelsAsABase64BuildArgument() {
        // Quotes, $ and line breaks would not survive a plain build argument (Review Focus 1).
        String yaml = "nanofaas:\n  admin:\n    runtime-config:\n      enabled: true\n  note: \"it's $HOME\\n\"\n";
        List<String> with = RecipeContainerBuild.builderArguments(Path.of("/repo"), ":control-plane:nativeCompile", "bin",
                "community", List.of("-PnanofaasBuildType=native"), null, yaml);
        String argument = with.stream().filter(a -> a.startsWith("NATIVE_AOT_CONFIG=")).findFirst().orElseThrow();

        assertThat(with.get(with.indexOf(argument) - 1)).isEqualTo("--build-arg");
        assertThat(new String(java.util.Base64.getDecoder().decode(argument.substring("NATIVE_AOT_CONFIG=".length())),
                java.nio.charset.StandardCharsets.UTF_8)).isEqualTo(yaml);
        assertThat(RecipeContainerBuild.builderArguments(Path.of("/repo"), ":control-plane:nativeCompile", "bin",
                "community", List.of("-PnanofaasBuildType=native"), null, null))
                .noneMatch(a -> a.startsWith("NATIVE_AOT_CONFIG="));
    }
```

In `RecipePluginTest`, add a helper next to `digestOf`:

```java
    private static String base64(String text) {
        return java.util.Base64.getEncoder().encodeToString(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
```

Then make these three changes:
- In `containerBuilderCompilesInsideTheBuilderAndPackagesAsUsual`, insert
  `"--build-arg", "NATIVE_AOT_CONFIG=" + base64("nanofaas:\n  metrics:\n    profile: basic\n"),`
  right after the `GRADLE_ARGS=…` element of the expected command.
- In `multiArchAssemblyBuildsEveryImageWithBuildxIntoTheCacheOnly`, insert the same element
  right after its `"GRADLE_ARGS=-PnanofaasBuildType=native -PcontrolPlaneModules=none",`
  element.
- In `hostAndContainerBuildersMixAndImagelessComponentsStillBuild`, append (Review Focus 4):
  ```java
          assertThat(containerBuild()).noneMatch(argument -> argument.startsWith("NATIVE_AOT_CONFIG="));
  ```

In `scripts/tests/test_java_container_images.py`, append:

```python
def test_native_builder_hands_the_recipe_configuration_to_spring_aot():
    """A recipe's control-plane configuration arrives base64 in NATIVE_AOT_CONFIG (empty for the release). The
    builder decodes it and adds -PnanofaasAotConfig itself, so the path lives in one place."""
    dockerfile = (REPO_ROOT / "deploy/native-java/Dockerfile").read_text(encoding="utf-8")
    stage = _stage(dockerfile, "builder")

    assert "ARG NATIVE_AOT_CONFIG" in stage
    decode = next(i for i, line in enumerate(stage) if "base64 -d" in line)
    gradle = next(i for i, line in enumerate(stage) if "./gradlew" in line)
    assert decode < gradle
    assert '[ -n "$NATIVE_AOT_CONFIG" ]' in stage[decode]
    assert "/tmp/nanofaas-aot-config.yaml" in stage[decode]
    assert "${NATIVE_AOT_CONFIG:+-PnanofaasAotConfig=/tmp/nanofaas-aot-config.yaml}" in stage[gradle]
```

- [ ] **Step 3: Run the tests to verify they fail**

Run:
- `./gradlew -p platform/gradle-plugin test --tests 'it.unimib.datai.nanofaas.gradle.RecipeContainerBuildTest' --tests 'it.unimib.datai.nanofaas.gradle.RecipePluginTest'`
- `uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py`

Expected:
- the plugin tests fail at compilation, because `builderArguments` and `command` take no
  `aotConfig` yet;
- pytest has 1 failure (`StopIteration`, no `base64 -d` line), with the rest passing.

- [ ] **Step 4: Implement**

In `RecipeContainerBuild`, replace `command` and `builderArguments` with:

```java
    static List<String> command(String docker, Path rootDir, Path destination, String nativeTask, String nativeBinary,
                                String distribution, List<String> gradleArgs, Path containerdRepository,
                                String aotConfig) {
        List<String> command = new ArrayList<>(List.of(docker, "build", "-f", rootDir.resolve(DOCKERFILE).toString(),
                "--target", TARGET, "--output", "type=local," + csvField("dest=" + destination)));
        command.addAll(builderArguments(rootDir, nativeTask, nativeBinary, distribution, gradleArgs,
                containerdRepository, aotConfig));
        command.add(rootDir.toString());
        return List.copyOf(command);
    }

    /**
     * The builder stage's inputs, shared by the executable export and the multi-architecture recipe-native build.
     * @param aotConfig the control plane's configuration for Spring AOT, or null: base64, because a build argument
     *                  would not keep its quotes and line breaks
     */
    static List<String> builderArguments(Path rootDir, String nativeTask, String nativeBinary, String distribution,
                                         List<String> gradleArgs, Path containerdRepository, String aotConfig) {
        Path repository = containerdRepository != null ? containerdRepository : rootDir.resolve(EMPTY_MAVEN_REPOSITORY);
        List<String> arguments = new ArrayList<>(List.of("--build-context", "containerd_maven_repo=" + repository,
                "--build-arg", "NATIVE_TASK=" + nativeTask,
                "--build-arg", "NATIVE_BINARY=" + nativeBinary,
                "--build-arg", "GRAALVM_DISTRIBUTION=" + distribution,
                "--build-arg", "GRADLE_ARGS=" + String.join(" ", gradleArgs)));
        if (aotConfig != null) {
            arguments.addAll(List.of("--build-arg", "NATIVE_AOT_CONFIG="
                    + Base64.getEncoder().encodeToString(aotConfig.getBytes(StandardCharsets.UTF_8))));
        }
        return List.copyOf(arguments);
    }
```

Add the imports `java.nio.charset.StandardCharsets` and `java.util.Base64`. Keep the existing
`csvField` helper, and keep the order of the command's first part exactly as it is.

In `RecipeArtifacts`, pass the configuration to both call sites:
- **The `RecipeContainerBuild.command(...)` call.** After `containerdRepository`, add the
  argument
  `target.controlPlane() ? RecipeBuildProperties.aotConfig(recipe.data()) : null`.
- **The `RecipeContainerBuild.builderArguments(...)` call in `buildxSource`.** After
  `containerdRepository`, add the same argument.

In `deploy/native-java/Dockerfile`, replace

```dockerfile
ARG NATIVE_TASK
ARG NATIVE_BINARY
ARG GRADLE_ARGS
```

with

```dockerfile
ARG NATIVE_TASK
ARG NATIVE_BINARY
ARG GRADLE_ARGS
# A recipe's control-plane configuration, base64, for Spring AOT to evaluate its conditions with
# (it decides which beans a native executable contains). Empty for the release and every other caller.
ARG NATIVE_AOT_CONFIG
RUN if [ -n "$NATIVE_AOT_CONFIG" ]; then \
      printf '%s' "$NATIVE_AOT_CONFIG" | base64 -d > /tmp/nanofaas-aot-config.yaml; \
    fi
```

and, in the Gradle `RUN` below it, replace `./gradlew "$NATIVE_TASK" $GRADLE_ARGS --no-daemon` with:

```dockerfile
./gradlew "$NATIVE_TASK" $GRADLE_ARGS ${NATIVE_AOT_CONFIG:+-PnanofaasAotConfig=/tmp/nanofaas-aot-config.yaml} --no-daemon
```

Keep the rest of that `RUN` line, including its `--mount=type=cache,target=/root/.gradle,sharing=locked`.

- [ ] **Step 5: Run the tests to verify they pass**

Run:
- `./gradlew -p platform/gradle-plugin test`
- `uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py`
- `E=deploy/native-java/empty-maven-repo; docker build --check -f deploy/native-java/Dockerfile --build-context containerd_maven_repo=$E --build-context recipe=$E .`

Expected:
- the plugin suite passes;
- pytest passes every test (one more than before);
- the check prints `Check complete, no warnings found.`

- [ ] **Step 6: Commit**

```bash
git add platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuild.java \
        platform/gradle-plugin/src/main/java/it/unimib/datai/nanofaas/gradle/RecipeArtifacts.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipeContainerBuildTest.java \
        platform/gradle-plugin/src/test/java/it/unimib/datai/nanofaas/gradle/RecipePluginTest.java \
        deploy/native-java/Dockerfile scripts/tests/test_java_container_images.py
node $SCRATCH/detect.mjs
git commit -m "Hand the control plane's configuration to Spring AOT in the container builder" -m "<trailer>"
```

---

### Task 4: Documentation

**Files:**
- Modify: `docs/recipes.md`, section "Native builds".

**Interfaces:** none.

- [ ] **Step 1: Add the paragraph**

At the end of the "Native builds" section, just before `## Multi-architecture images`, add:

```markdown
In native, Spring decides which components exist while the executable is built (Spring AOT),
not at startup. For example, the runtime-config admin API needs
`nanofaas.admin.runtime-config.enabled=true`, the soak gauges need
`nanofaas.metrics.profile=soak`, and each image validator needs its `default-backend`.

- **What the build reads.** A native control plane's build hands `controlPlane.config` to that
  step. The executable therefore contains the components the recipe's configuration selects,
  as it would on the JVM.
- **Changing the configuration after the build.** Values change, but the components do not.
  Environment variables at run time cannot switch a component on or off either. Assemble again.
- **Without a recipe.** Pass the same file to a direct build:
  `./gradlew :control-plane:nativeCompile -PnanofaasAotConfig=<file>`. A relative path resolves
  against the repository root. A missing file fails the build.
- **The published native image** is built with the defaults: its runtime-config admin API is
  off. Build your own image for other choices.
- **In the container builder**, the configuration travels as the build argument
  `NATIVE_AOT_CONFIG`, so it also appears in the image's BuildKit provenance. The image already
  ships it as `config/recipe.yaml`.
```

- [ ] **Step 2: Check and commit**

Run: `grep -n "nanofaasAotConfig\|NATIVE_AOT_CONFIG" docs/recipes.md`
Expected: 2 matches, in the new paragraph.

```bash
git add docs/recipes.md
node $SCRATCH/detect.mjs
git commit -m "Document that a native build reads the recipe's configuration" -m "<trailer>"
```

---

### Task 5: Full suite and end-to-end verification

**Files:** none are committed.

**Interfaces:** consumes everything above.

- [ ] **Step 1: Full suite**

```bash
./gradlew test --no-parallel --continue -PcontainerdMavenLocal=true \
  -Dmaven.repo.local=$PWD/.gradle/containerd-m2 -PcontrolPlaneModules=all > $SCRATCH/full-suite-aot.log 2>&1; echo exit $?
uv run --no-project --with pytest pytest -q scripts/tests/test_java_container_images.py
```
Expected: exit 0 and 0 failures. The scripts tests all pass.

- [ ] **Step 2: A recipe on the host**

Write `$SCRATCH/e2e-aot.yaml`:

```yaml
schemaVersion: 2
name: e2e-aot
controlPlane:
  modules: [runtime-config]
  build: {mode: native}
  config: {nanofaas: {admin: {runtime-config: {enabled: true}}}}
```

Run it with GraalVM on the `PATH`:

```bash
./gradlew assembleRecipe -Precipe=$SCRATCH/e2e-aot.yaml -PrecipeOutput=$SCRATCH/out-aot
env -i HOME=$HOME PATH=/usr/bin:/bin SERVER_PORT=18095 MANAGEMENT_SERVER_PORT=18096 \
  $SCRATCH/out-aot/control-plane/application > $SCRATCH/aot-host.log 2>&1 & P=$!
# poll GET localhost:18095/v1/admin/runtime-config as in Task 1, then kill $P
```
Expected: `HTTP 200`. `env -i` means the executable received no configuration.

- [ ] **Step 3: Change the configuration, reassemble (Review Focus 2)**

Edit `$SCRATCH/e2e-aot.yaml` so that `enabled: false`, rerun the same `assembleRecipe`, and run
the same check.
Expected: `HTTP 404`. AOT reran, because its input file changed.

- [ ] **Step 4: The container builder**

Restore `enabled: true` and add `builder: container` to `controlPlane.build`. Add
`container: {image: control-plane}` to `controlPlane`. Then:

```bash
./gradlew assembleRecipe -Precipe=$SCRATCH/e2e-aot.yaml -PrecipeOutput=$SCRATCH/out-aot-container
docker run -d --rm --name aot-e2e -p 18097:8080 \
  -e SPRING_CONFIG_ADDITIONALLOCATION=optional:file:/nonexistent.yaml \
  nanofaas/e2e-aot/control-plane:local
# poll GET localhost:18097/v1/admin/runtime-config, then: docker rm -f aot-e2e
```
Expected: `HTTP 200`.
- Pointing `SPRING_CONFIG_ADDITIONALLOCATION` at a missing optional file stops the shipped
  `config/recipe.yaml` from being read. The spec's "set empty" means this.
- If the image reference differs, use the one in `$SCRATCH/out-aot-container/distribution.json`.

- [ ] **Step 5: Report**

Record every result in the final summary. There is nothing to commit.

---

## Self-review notes

- **Spec coverage:**

  | Spec requirement | Task |
  | --- | --- |
  | Build hook, `file:`, input, relative path, JVM no effect | 1 |
  | Recipe file written when the recipe is resolved, same serialisation, owned flag, no file without native or config | 2 |
  | Container build argument, Dockerfile decode and flag, only for the control plane | 3 |
  | Report and preview unchanged | No task, by design |
  | Errors (owned flag, missing file) | 2, 1 |
  | Documentation | 4 |
  | Manual end-to-end (host, container, direct, baseline 404) | 5, 1 |

- **Deviation from the spec's wording.** The container end-to-end check points
  `SPRING_CONFIG_ADDITIONALLOCATION` at a missing optional file instead of setting it empty.
  Spring's handling of an empty location is not specified, whereas a missing optional file is
  plainly "no file".
