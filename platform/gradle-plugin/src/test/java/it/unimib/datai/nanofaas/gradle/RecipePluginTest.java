package it.unimib.datai.nanofaas.gradle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecipePluginTest {

    private static final String HEADER = "schemaVersion: 1\nname: demo\n";

    @TempDir
    Path projectDir;

    @TempDir
    Path outsideDir;

    @BeforeEach
    void writeFixture() throws IOException {
        write("settings.gradle", """
                plugins { id 'it.unimib.datai.nanofaas.control-plane-modules' }
                include ':control-plane', ':sdks:java', ':sdks:java-lite'
                include ':functions:java:word-stats', ':functions:java:word-stats-lite', ':functions:java:jvm-only', ':services:java:warm-echo'
                project(':control-plane').projectDir = file('platform/control-plane')
                """);
        write("build.gradle", """
                tasks.register('printNative') { doLast { println "native=${gradle.ext.nanofaasNativeBuildRequested}" } }
                tasks.register('printSelection') { doLast { println "modules=${gradle.ext.nanofaasSelectedControlPlaneModules}" } }
                allprojects {
                    tasks.register('printRecipeProps') {
                        doLast {
                            def keys = ['nativeOptimization', 'nativeGc', 'nativeMonitoring', 'nanofaasBuildVariant',
                                        'nanofaasBuildOptimization', 'nanofaasRecipeBuildMode']
                            println "props ${project.path} " + keys.collect { "${it}=${project.findProperty(it)}" }.join(' ')
                        }
                    }
                }
                """);
        // Fake build tasks produce real outputs and leave a marker, so tests can prove what did (not) run.
        write("marker.gradle", """
                ext.mark = { String name ->
                    def file = rootProject.file("markers/${project.name}-${name}")
                    file.parentFile.mkdirs(); file.text = 'ran'
                }
                ext.fakeBootJar = { String fileName -> tasks.register('bootJar', Jar) {
                    archiveFileName = fileName
                    destinationDirectory = layout.buildDirectory.dir('libs')
                    from(rootProject.file('marker.gradle'))
                    doLast { mark('bootJar') }
                } }
                ext.fakeNative = { String binary -> tasks.register('nativeCompile') {
                    ext.outputFile = layout.buildDirectory.file("native/nativeCompile/${binary}")
                    outputs.dir(layout.buildDirectory.dir('native/nativeCompile'))
                    doLast {
                        def file = outputFile.get().asFile
                        file.parentFile.mkdirs(); file.text = '#!/bin/sh\\n'; file.setExecutable(true)
                        new File(file.parentFile, 'unrelated.txt').text = 'not staged'
                        mark('nativeCompile')
                    }
                } }
                """);
        write("platform/control-plane/build.gradle", """
                plugins { id 'java' }
                apply from: rootProject.file('marker.gradle')
                fakeBootJar('app.jar'); fakeNative('control-plane')
                """);
        write("deploy/recipes/Dockerfile.jvm", "FROM scratch\n");
        write("deploy/recipes/Dockerfile.native", "FROM scratch\n");
        write("bin/docker", """
                #!/bin/sh
                log=%s
                { printf '%%s\\n' "$@"; echo '--'; } >> "$log/docker.log"
                if [ -f "$log/fail-$1" ]; then echo "fake docker $1 failed" >&2; exit 1; fi
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
                if [ "$1" = build ]; then
                  prev=""
                  for a in "$@"; do
                    if [ "$prev" = --output ]; then
                      d="${a#type=local,dest=}"; mkdir -p "$d"; printf '#!/bin/sh\\n' > "$d/application"; chmod +x "$d/application"
                    fi
                    prev="$a"
                  done
                fi
                if [ "$1" = push ]; then
                  n=$(grep -c '^push$' "$log/docker.log")
                  if [ -f "$log/fail-push-$n" ]; then echo "fake push $n failed" >&2; exit 1; fi
                  echo "The push refers to repository [${2%%:*}]"
                  [ -f "$log/no-digest" ] || echo "${2##*:}: digest: sha256:$(printf %%s "$2" | sha256sum | cut -c1-64) size: 528"
                fi
                if [ "$1" = image ] && [ "$4" = '{{.Id}}' ]; then
                  [ -f "$log/fail-inspect-id" ] && { echo "fake inspect failed" >&2; exit 1; }
                  echo "sha256:$(printf 'id-%%s' "$5" | sha256sum | cut -c1-64)"; exit 0
                fi
                if [ "$1" = image ]; then cat "$log/repo-digests" 2>/dev/null || echo '[]'; fi
                """.formatted(projectDir));
        projectDir.resolve("bin/docker").toFile().setExecutable(true);
        write("sdks/java/build.gradle", "plugins { id 'java' }\n");
        write("sdks/java-lite/build.gradle", "plugins { id 'java' }\n");
        writeJavaFunction("word-stats", ":sdks:java", "fakeBootJar('word-stats.jar'); fakeNative('word-stats')");
        write("functions/java/word-stats-lite/build.gradle", """
                plugins { id 'java'; id 'application' }
                apply from: rootProject.file('marker.gradle')
                dependencies { implementation project(':sdks:java-lite') }
                application { mainClass = 'demo.WordStatsLite' }
                fakeNative('word-stats-lite')
                """);
        writeJavaFunction("jvm-only", ":sdks:java", "fakeBootJar('jvm-only.jar')");
        write("services/java/warm-echo/build.gradle", """
                plugins { id 'java' }
                apply from: rootProject.file('marker.gradle')
                dependencies { implementation project(':sdks:java') }
                fakeBootJar('warm-echo.jar'); fakeNative('warm-echo')
                """);
        write("runtimes/watchdog/Dockerfile", "FROM scratch\n");
        write("functions/bash/word-stats/Dockerfile", "FROM scratch\n");
        write("functions/python/word-stats/Dockerfile", "FROM scratch\n");
        write("functions/go/qr-code/Dockerfile", "FROM scratch\n");
        Files.createDirectories(projectDir.resolve("functions/javascript/no-dockerfile"));
    }

    @Test
    void listsCatalogWithoutRecipe() {
        BuildResult result = run("listRecipeFunctions");

        assertThat(result.getOutput())
                .containsSubsequence(
                        "jvm-only", "java", "functions/java/jvm-only", "jvm",
                        "qr-code", "go", "functions/go/qr-code", "container",
                        "word-stats", "java", "functions/java/word-stats", "jvm, native",
                        "word-stats", "java-lite", "functions/java/word-stats-lite", "jvm, native",
                        "word-stats", "python", "functions/python/word-stats", "container")
                .doesNotContain("no-dockerfile")
                .doesNotContainPattern("word-stats-lite\\s+java\\s");
        assertThat(projectDir.resolve("markers")).doesNotExist();
    }

    @Test
    void validateRequiresRecipe() {
        BuildResult result = runner("validateRecipe").buildAndFail();

        assertThat(result.getOutput()).contains("validateRecipe requires -Precipe=<file>");
    }

    @Test
    void previewsTwoSdksOfTheSameFunctionWithoutBuilding() throws IOException {
        recipe(HEADER + """
                registry: {repository: registry.example:5000/team, tag: "1.0.0"}
                controlPlane: {modules: [], build: {mode: jvm}, container: {image: control-plane}}
                functions:
                  - {name: word-stats, sdk: java-lite, build: {mode: native}, container: {image: ws-lite}}
                  - {name: word-stats, sdk: python, container: {image: ws-python}}
                """);

        BuildResult result = run("validateRecipe", "-Precipe=recipe.yaml", "-PrecipeTag=1.0.1");

        assertThat(result.getOutput())
                .contains("Tag: 1.0.1")
                .contains("Control-plane modules: (core only)")
                .containsSubsequence("control-plane", "java", "jvm", ":control-plane:bootJar",
                        "control-plane/", "registry.example:5000/team/control-plane:1.0.1")
                .containsSubsequence("word-stats", "java-lite", "native", ":functions:java:word-stats-lite:nativeCompile",
                        "functions/java-lite/word-stats/", "registry.example:5000/team/ws-lite:1.0.1")
                .containsSubsequence("word-stats", "python", "container", "functions/python/word-stats/Dockerfile",
                        "registry.example:5000/team/ws-python:1.0.1");
        assertThat(projectDir.resolve("markers")).doesNotExist();
        assertThat(projectDir.resolve("build/recipes")).doesNotExist();
        assertThat(projectDir.resolve("docker.log")).doesNotExist();
    }

    @Test
    void previewUsesLocalReferencesWithoutRegistry() throws IOException {
        recipe(HEADER + """
                controlPlane: {modules: [], build: {mode: jvm}, container: {image: control-plane}}
                """);

        assertThat(run("validateRecipe", "-Precipe=recipe.yaml").getOutput())
                .contains("nanofaas/demo/control-plane:local");
    }

    @Test
    void rejectsMissingImplementation() throws IOException {
        recipe(HEADER + CP_JVM + "functions: [{name: figlet, sdk: python, container: {image: figlet}}]\n");

        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("recipe.yaml: functions[0]: python implementation of figlet is not available");
    }

    @Test
    void rejectsUnavailableMode() throws IOException {
        recipe(HEADER + CP_JVM + "functions: [{name: jvm-only, sdk: java, build: {mode: native}}]\n");

        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("functions[0].build.mode: java implementation of jvm-only does not support native");
    }

    @Test
    void rejectsDuplicateImplementationAndImage() throws IOException {
        recipe(HEADER + CP_JVM + """
                functions:
                  - {name: word-stats, sdk: python, container: {image: ws}}
                  - {name: word-stats, sdk: python, container: {image: other}}
                """);
        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("functions[1]: word-stats (python) is already declared");

        recipe(HEADER + CP_JVM + """
                functions:
                  - {name: word-stats, sdk: python, container: {image: ws}}
                  - {name: qr-code, sdk: go, container: {image: ws}}
                """);
        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("functions[1].container.image: image nanofaas/demo/ws:local is already used");
    }

    @Test
    void rejectsSourcesOutsideTheRepository() throws IOException {
        Files.writeString(outsideDir.resolve("Dockerfile"), "FROM scratch\n");
        Files.createSymbolicLink(projectDir.resolve("functions/python/escape"), outsideDir);
        recipe(HEADER + CP_JVM + "functions: [{name: escape, sdk: python, container: {image: escape}}]\n");

        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("functions[0]: python implementation of escape is not available");
        assertThat(run("listRecipeFunctions").getOutput()).doesNotContain("escape");
    }

    @Test
    void recipeModulesDriveSelectionAndIgnoreEnvironment() throws IOException {
        writeModule("alpha", "");
        writeModule("beta", "");
        recipe(HEADER + "controlPlane: {modules: [beta], build: {mode: jvm}}\n");

        BuildResult result = runner("printSelection", "validateRecipe", "-Precipe=recipe.yaml")
                .withEnvironment(Map.of("NANOFAAS_CONTROL_PLANE_MODULES", "alpha")).build();

        assertThat(result.getOutput()).contains("modules=[beta]").contains("Control-plane modules: beta");
    }

    @Test
    void rejectsModuleSelectorTogetherWithRecipe() throws IOException {
        recipe(HEADER + CP_JVM);

        assertThat(fails("printSelection", "-Precipe=recipe.yaml", "-PcontrolPlaneModules=all"))
                .contains("-PcontrolPlaneModules cannot be combined with -Precipe");
    }

    @Test
    void rejectsIncompatibleAndUnknownModules() throws IOException {
        writeModule("alpha", "beta");
        writeModule("beta", "");
        recipe(HEADER + "controlPlane: {modules: [alpha, beta], build: {mode: jvm}}\n");
        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("recipe.yaml: controlPlane.modules:").contains("conflict");

        recipe(HEADER + "controlPlane: {modules: [gamma], build: {mode: jvm}}\n");
        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("recipe.yaml: controlPlane.modules: Unknown control-plane module(s): [gamma]");
    }

    @Test
    void nativeFunctionKeepsJvmControlPlane() throws IOException {
        recipe(HEADER + CP_JVM + "functions: [{name: word-stats, sdk: java, build: {mode: native}}]\n");

        assertThat(run("printNative", "-Precipe=recipe.yaml").getOutput()).contains("native=false");
    }

    @Test
    void nativeControlPlaneWithJvmFunction() throws IOException {
        recipe(HEADER + "controlPlane: {modules: [], build: {mode: native}}\n"
                + "functions: [{name: word-stats, sdk: java, build: {mode: jvm}}]\n");

        assertThat(run("printNative", "-Precipe=recipe.yaml").getOutput()).contains("native=true");
    }

    @Test
    void rejectsDirectNativeRequestAgainstJvmControlPlane() throws IOException {
        recipe(HEADER + CP_JVM);

        assertThat(fails("nativeCompile", "-Precipe=recipe.yaml"))
                .contains("declares a jvm control plane");
        assertThat(fails("printNative", "-Precipe=recipe.yaml", "-PnanofaasBuildType=native"))
                .contains("-PnanofaasBuildType=native contradicts controlPlane.build.mode jvm");
    }

    @Test
    void reportsInvalidRecipeAtConfiguration() throws IOException {
        recipe(HEADER + "controlPlane: {modules: [], build: {mode: jvm}, typo: 1}\n");

        assertThat(fails("listRecipeFunctions", "-Precipe=recipe.yaml")).contains("typo");
    }

    @Test
    void withoutRecipeNativeFlagStillFollowsTaskNames() {
        assertThat(run("printNative").getOutput()).contains("native=false");
    }

    private static final String V2_HEADER = "schemaVersion: 2\nname: demo\n";

    @Test
    void recipeNativeOptionsReachEachProject() throws IOException {
        writeModule("build-metadata", "");
        recipe(V2_HEADER + """
                controlPlane: {modules: [build-metadata], build: {mode: native, variant: native-os, native: {optimization: s}}}
                functions: [{name: word-stats, sdk: java, build: {mode: native, native: {gc: G1}}}]
                """);

        String output = run("printRecipeProps", "-Precipe=recipe.yaml").getOutput();

        assertThat(output)
                .contains("props :control-plane nativeOptimization=s nativeGc=null")
                .contains("props :functions:java:word-stats nativeOptimization=null nativeGc=G1")
                .contains("props :control-plane-modules:build-metadata nativeOptimization=null nativeGc=null"
                        + " nativeMonitoring=null nanofaasBuildVariant=native-os nanofaasBuildOptimization=s")
                .contains("props :functions:java:word-stats-lite nativeOptimization=null");
    }

    @Test
    void recipeOwnedFlagsAreRejectedAndBuilderFlagsAccepted() throws IOException {
        recipe(V2_HEADER + CP_JVM);

        assertThat(fails("printRecipeProps", "-Precipe=recipe.yaml", "-PnativeOptimization=s"))
                .contains("-PnativeOptimization cannot be combined with -Precipe");
        run("printRecipeProps", "-Precipe=recipe.yaml", "-PnativeBuildMemory=6g", "-PnativeParallelism=2",
                "-PcontainerdMavenLocal=true", "-Dmaven.repo.local=" + outsideDir);
    }

    @Test
    void buildIdentityWithoutBuildMetadataFailsBeforeBuilding() throws IOException {
        recipe(V2_HEADER + "controlPlane: {modules: [], build: {mode: jvm, variant: jvm}}\n");

        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker()))
                .contains("controlPlane.build.variant requires the build-metadata module");
        assertThat(projectDir.resolve("markers")).doesNotExist();
    }

    @Test
    void catalogListsServicesAndBashFunctions() {
        assertThat(run("listRecipeFunctions").getOutput())
                .containsSubsequence("word-stats", "bash", "functions/bash/word-stats", "container")
                .containsSubsequence("Services:", "warm-echo", "java", "services/java/warm-echo", "jvm, native",
                        "watchdog", "dockerfile", "runtimes/watchdog", "container");
    }

    @Test
    void servicesAndBashBuildWithTheirOwnContexts() throws IOException {
        recipe(V2_HEADER + CP_JVM + """
                functions: [{name: word-stats, sdk: bash, container: {image: ws-bash}}]
                services:
                  - {name: warm-echo, sdk: java, build: {mode: native, native: {optimization: s}}, container: {image: echo}}
                  - {name: watchdog, sdk: dockerfile, container: {image: watchdog}}
                """);

        BuildResult result = run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        assertThat(result.task(":services:java:warm-echo:nativeCompile").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        Path root = projectDir.toRealPath();
        assertThat(projectDir.resolve("build/recipes/demo/services/java/warm-echo/application")).isExecutable();
        assertThat(dockerCalls().stream().filter(call -> call.getFirst().equals("build")).toList()).containsExactlyInAnyOrder(
                List.of("build", "-f", root.resolve("functions/bash/word-stats/Dockerfile").toString(),
                        "-t", "nanofaas/demo/ws-bash:local", root.toString()),
                List.of("build", "-f", root.resolve("deploy/recipes/Dockerfile.native").toString(),
                        "-t", "nanofaas/demo/echo:local", root.resolve("build/recipes/demo/services/java/warm-echo").toString()),
                List.of("build", "-f", root.resolve("runtimes/watchdog/Dockerfile").toString(),
                        "-t", "nanofaas/demo/watchdog:local", root.resolve("runtimes/watchdog").toString()));
    }

    @Test
    void previewShowsServices() throws IOException {
        recipe(V2_HEADER + CP_JVM + "services: [{name: watchdog, sdk: dockerfile, container: {image: watchdog}}]\n");

        assertThat(run("validateRecipe", "-Precipe=recipe.yaml").getOutput())
                .containsSubsequence("watchdog", "dockerfile", "container",
                        "docker build -f runtimes/watchdog/Dockerfile runtimes/watchdog", "nanofaas/demo/watchdog:local");
    }

    @Test
    void rejectsUnknownServiceAndImagesSharedAcrossKinds() throws IOException {
        recipe(V2_HEADER + CP_JVM + "services: [{name: echo-server, sdk: dockerfile, container: {image: x}}]\n");
        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("services[0]: dockerfile implementation of echo-server is not available");

        recipe(V2_HEADER + CP_JVM + """
                functions: [{name: word-stats, sdk: bash, container: {image: shared}}]
                services: [{name: watchdog, sdk: dockerfile, container: {image: shared}}]
                """);
        assertThat(fails("validateRecipe", "-Precipe=recipe.yaml"))
                .contains("services[0].container.image: image nanofaas/demo/shared:local is already used");
    }

    private String output(Path dir) {
        return "-PrecipeOutput=" + dir;
    }

    @Test
    void assemblesIntoAnOutputOutsideTheCheckout() throws IOException {
        recipe(HEADER + CP_JVM);
        Path out = outsideDir.resolve("run-1");

        run("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(out));

        assertThat(out.resolve("distribution.json")).isRegularFile();
        assertThat(Files.readString(out.resolve(".nanofaas-recipe-output"))).isEqualTo("nanofaas-recipe-output-v1\n");
        assertThat(projectDir.resolve("build/recipes")).doesNotExist();
    }

    @Test
    void refusesOutputsItDoesNotOwn() throws IOException {
        recipe(HEADER + CP_JVM);
        Path unrelated = Files.createDirectories(outsideDir.resolve("unrelated"));
        Files.writeString(unrelated.resolve("keep.txt"), "user data");
        Path malformed = Files.createDirectories(outsideDir.resolve("malformed"));
        Files.writeString(malformed.resolve(".nanofaas-recipe-output"), "nanofaas-recipe-output-v2\n");
        Files.writeString(malformed.resolve("keep.txt"), "user data");
        Path file = Files.writeString(outsideDir.resolve("a-file"), "user data");
        Path link = Files.createSymbolicLink(outsideDir.resolve("link-to-repo"), projectDir);

        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(unrelated)))
                .contains("is not empty and holds no recipe output");
        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(malformed)))
                .contains("is not empty and holds no recipe output");
        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(file)))
                .contains("is not a directory");
        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(link)))
                .contains("is the repository or one of its ancestors");
        assertThat(fails("cleanRecipe", "-Precipe=recipe.yaml", output(unrelated)))
                .contains("is not empty and holds no recipe output");
        assertThat(fails("stageRecipe", "-Precipe=recipe.yaml", output(unrelated)))
                .contains("is not empty and holds no recipe output");

        assertThat(unrelated.resolve("keep.txt")).hasContent("user data");
        assertThat(malformed.resolve("keep.txt")).hasContent("user data");
        assertThat(file).hasContent("user data");
        assertThat(projectDir.resolve("recipe.yaml")).isRegularFile();
    }

    @Test
    void reusesAnOutputHoldingALegacyReport() throws IOException {
        recipe(HEADER + CP_JVM);
        Path legacy = Files.createDirectories(outsideDir.resolve("legacy"));
        Files.writeString(legacy.resolve("distribution.json"), legacyReport().toPrettyString());
        Files.writeString(Files.createDirectories(legacy.resolve("control-plane")).resolve("stale.txt"), "old");

        run("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(legacy));

        assertThat(legacy.resolve("control-plane/stale.txt")).doesNotExist();
        assertThat(legacy.resolve(".nanofaas-recipe-output")).isRegularFile();
    }

    /** Complete report emitted by the v1 writer for HEADER + CP_JVM in this fixture (no Git repository). */
    private JsonNode legacyReport() throws IOException {
        return new ObjectMapper().readTree("""
                {
                  "schemaVersion": 1,
                  "recipe": {"name": "demo", "sha256": "%s"},
                  "tag": "local",
                  "source": null,
                  "modules": [],
                  "components": [{"name": "control-plane", "sdk": "java", "mode": "jvm",
                                  "artifact": "control-plane/", "image": null}]
                }
                """.formatted(sha256(projectDir.resolve("recipe.yaml"))));
    }

    @Test
    void incompleteLegacyReportsDoNotAuthorizeDeletion() throws IOException {
        recipe(HEADER + CP_JVM);
        Path out = Files.createDirectories(outsideDir.resolve("incomplete-report"));
        Files.writeString(out.resolve("keep.txt"), "user data");
        List<JsonNode> invalid = new ArrayList<>();
        invalid.add(new ObjectMapper().readTree("{\"schemaVersion\":1,\"recipe\":{\"name\":\"\"}}"));
        for (String field : List.of("recipe", "tag", "source", "modules", "components")) {
            ObjectNode report = (ObjectNode) legacyReport();
            report.remove(field);
            invalid.add(report);
        }
        ObjectNode wrongVersion = (ObjectNode) legacyReport();
        wrongVersion.put("schemaVersion", "1");
        invalid.add(wrongVersion);
        ObjectNode incompleteComponent = (ObjectNode) legacyReport();
        ((ObjectNode) incompleteComponent.at("/components/0")).remove("artifact");
        invalid.add(incompleteComponent);
        ObjectNode incompleteImage = (ObjectNode) legacyReport();
        ((ObjectNode) incompleteImage.at("/components/0")).putObject("image")
                .put("reference", "nanofaas/demo/control-plane:local");
        invalid.add(incompleteImage);

        for (JsonNode report : invalid) {
            String contents = report.toPrettyString();
            Files.writeString(out.resolve("distribution.json"), contents);
            assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(out)))
                    .contains("is not empty and holds no recipe output");
            assertThat(out.resolve("keep.txt")).hasContent("user data");
            assertThat(out.resolve("distribution.json")).hasContent(contents);
        }
    }

    @Test
    void failedAssemblyCanBeRetriedInTheSameOutput() throws IOException {
        recipe(PUBLISH_CP_ONLY);
        Path out = outsideDir.resolve("retry");
        Files.writeString(projectDir.resolve("fail-build"), "");

        fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(out));

        assertThat(out.resolve(".nanofaas-recipe-output")).isRegularFile();
        assertThat(out.resolve("distribution.json")).doesNotExist();
        Files.delete(projectDir.resolve("fail-build"));
        run("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(out));
        assertThat(out.resolve("distribution.json")).isRegularFile();
    }

    private static String hex64(char c) {
        return String.valueOf(c).repeat(64);
    }

    @Test
    void reusesOutputsHoldingACompleteV2OrPublishedReport() throws IOException {
        recipe(HEADER + CP_JVM);
        ObjectNode v2 = (ObjectNode) legacyReport();
        v2.put("schemaVersion", 2);
        ((ObjectNode) v2.at("/components/0")).put("kind", "control-plane").putObject("image")
                .put("reference", "nanofaas/demo/control-plane:local").put("status", "built").put("id", "sha256:" + hex64('a'));
        ObjectNode published = (ObjectNode) legacyReport();
        ((ObjectNode) published.at("/components/0")).putObject("image")
                .put("reference", "nanofaas/demo/control-plane:local").put("status", "published")
                .put("digest", "sha256:" + hex64('b'));

        for (Map.Entry<String, JsonNode> report : Map.of("v2", (JsonNode) v2, "published", published).entrySet()) {
            Path out = Files.createDirectories(outsideDir.resolve("reuse-" + report.getKey()));
            Files.writeString(out.resolve("distribution.json"), report.getValue().toPrettyString());
            Files.writeString(Files.createDirectories(out.resolve("control-plane")).resolve("stale.txt"), "old");

            run("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(out));

            assertThat(out.resolve("control-plane/stale.txt")).as(report.getKey()).doesNotExist();
            assertThat(out.resolve(".nanofaas-recipe-output")).as(report.getKey()).isRegularFile();
        }
    }

    @Test
    void malformedReportsDoNotAuthorizeDeletion() throws IOException {
        recipe(HEADER + CP_JVM);
        Path out = Files.createDirectories(outsideDir.resolve("malformed-report"));
        Files.writeString(out.resolve("keep.txt"), "user data");
        List<JsonNode> invalid = new ArrayList<>();
        ObjectNode noHash = (ObjectNode) legacyReport();
        ((ObjectNode) noHash.get("recipe")).remove("sha256");
        invalid.add(noHash);
        ObjectNode badSource = (ObjectNode) legacyReport();
        badSource.putObject("source").put("revision", "abc").put("dirty", false);
        invalid.add(badSource);
        ObjectNode badModule = (ObjectNode) legacyReport();
        badModule.putArray("modules").add("Not A Slug");
        invalid.add(badModule);
        ObjectNode noComponents = (ObjectNode) legacyReport();
        noComponents.putArray("components");
        invalid.add(noComponents);
        ObjectNode v2WithoutId = (ObjectNode) legacyReport();
        v2WithoutId.put("schemaVersion", 2);
        ((ObjectNode) v2WithoutId.at("/components/0")).put("kind", "control-plane").putObject("image")
                .put("reference", "nanofaas/demo/control-plane:local").put("status", "built");
        invalid.add(v2WithoutId);
        ObjectNode publishedWithoutDigest = (ObjectNode) legacyReport();
        ((ObjectNode) publishedWithoutDigest.at("/components/0")).putObject("image")
                .put("reference", "nanofaas/demo/control-plane:local").put("status", "published");
        invalid.add(publishedWithoutDigest);

        for (JsonNode report : invalid) {
            String contents = report.toPrettyString();
            Files.writeString(out.resolve("distribution.json"), contents);
            assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(out)))
                    .contains("is not empty and holds no recipe output");
            assertThat(out.resolve("keep.txt")).hasContent("user data");
            assertThat(out.resolve("distribution.json")).hasContent(contents);
        }
    }

    @Test
    void reportRecordsKindsIdentityNativeOptionsAndImageIds() throws IOException {
        writeModule("build-metadata", "");
        recipe(V2_HEADER + """
                registry: {repository: registry.example:5000/team, tag: "1.0.0"}
                controlPlane:
                  modules: [build-metadata]
                  build: {mode: native, variant: native-o3-g1, native: {optimization: 3.0, gc: G1, monitoring: [jvmstat]}}
                  container: {image: control-plane}
                services: [{name: watchdog, sdk: dockerfile, container: {image: watchdog}}]
                """);

        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        JsonNode report = report();
        JsonNode controlPlane = report.get("components").get(0);
        assertThat(report.get("schemaVersion").asInt()).isEqualTo(2);
        assertThat(report.at("/recipe/schemaVersion").asInt()).isEqualTo(2);
        assertThat(controlPlane.get("kind").asText()).isEqualTo("control-plane");
        assertThat(controlPlane.get("variant").asText()).isEqualTo("native-o3-g1");
        assertThat(controlPlane.get("optimization").asText()).isEqualTo("3");
        assertThat(controlPlane.get("native").toString())
                .isEqualTo("{\"optimization\":\"3\",\"gc\":\"G1\",\"monitoring\":[\"jvmstat\",\"jfr\"],\"builder\":\"host\"}");
        assertThat(report.get("components").get(1).get("kind").asText()).isEqualTo("service");
        String reference = "registry.example:5000/team/control-plane:1.0.0";
        assertThat(image(report, reference).get("id").asText()).isEqualTo(imageIdOf(reference));
        assertThat(dockerCalls()).contains(List.of("image", "inspect", "--format", "{{.Id}}", reference));
    }

    @Test
    void imageIdSurvivesPublication() throws IOException {
        recipe(PUBLISH_CP_ONLY);

        run("publishRecipe", "-Precipe=recipe.yaml", docker());

        JsonNode image = image(report(), "registry.example:5000/team/control-plane:1.0.0");
        assertThat(image.get("status").asText()).isEqualTo("published");
        assertThat(image.get("id").asText()).isEqualTo(imageIdOf("registry.example:5000/team/control-plane:1.0.0"));
    }

    @Test
    void failedInspectFailsTheAssemblyAndCanBeRetried() throws IOException {
        recipe(PUBLISH_CP_ONLY);
        Files.writeString(projectDir.resolve("fail-inspect-id"), "");

        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker()))
                .contains("docker image inspect registry.example:5000/team/control-plane:1.0.0 did not return an image ID");
        assertThat(projectDir.resolve("build/recipes/demo/distribution.json")).doesNotExist();
        assertThat(projectDir.resolve("build/recipes/demo/.nanofaas-recipe-output")).isRegularFile();

        Files.delete(projectDir.resolve("fail-inspect-id"));
        run("assembleRecipe", "-Precipe=recipe.yaml", docker());
        assertThat(projectDir.resolve("build/recipes/demo/distribution.json")).isRegularFile();
    }

    @Test
    void v1RecipeReportsAdditiveFieldsOnly() throws IOException {
        recipe(FULL_RECIPE);

        run("assembleRecipe", "-Precipe=recipe.yaml", "-PrecipeTag=2.0.0", docker());

        assertThat(report().at("/recipe/schemaVersion").asInt()).isEqualTo(1);
        JsonNode component = report().get("components").get(0);
        assertThat(component.get("kind").asText()).isEqualTo("control-plane");
        assertThat(component.has("variant")).isFalse();
        assertThat(component.has("native")).isFalse();
        assertThat(component.get("image").get("status").asText()).isEqualTo("built");
    }


    @Test
    void skippingCleanRecipeStillRefusesAnUnownedOutput() throws IOException {
        recipe(HEADER + CP_JVM);
        Path unrelated = Files.createDirectories(outsideDir.resolve("skip-clean"));
        Files.writeString(unrelated.resolve("keep.txt"), "user data");

        assertThat(fails("assembleRecipe", "-x", "cleanRecipe", "-Precipe=recipe.yaml", docker(), output(unrelated)))
                .contains("is not empty and holds no recipe output");
        assertThat(unrelated.resolve("keep.txt")).hasContent("user data");
    }

    @Test
    void aCopiedReportDoesNotMakeADirectoryOwned() throws IOException {
        recipe(HEADER + CP_JVM);
        Path evidence = Files.createDirectories(outsideDir.resolve("evidence"));
        String report = legacyReport().toPrettyString();
        Files.writeString(evidence.resolve("distribution.json"), report);
        Files.writeString(evidence.resolve("soak-metrics.csv"), "user data");

        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker(), output(evidence)))
                .contains("is not empty and holds no recipe output");
        assertThat(evidence.resolve("soak-metrics.csv")).hasContent("user data");
        assertThat(evidence.resolve("distribution.json")).hasContent(report);
    }


    @Test
    void reportOmitsVariantWhenOnlyTheOptimizationIsRecorded() throws IOException {
        writeModule("build-metadata", "");
        recipe(V2_HEADER + "controlPlane: {modules: [build-metadata], build: {mode: native, native: {optimization: s}}}\n");

        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        JsonNode controlPlane = report().get("components").get(0);
        assertThat(controlPlane.get("optimization").asText()).isEqualTo("s");
        assertThat(controlPlane.has("variant")).isFalse();
    }


    @Test
    void jvmTierReachesBuildMetadataAndTheReport() throws IOException {
        writeModule("build-metadata", "");
        for (String[] tier : new String[][] {{"-XX:TieredStopAtLevel=1", "c1"}, {"-Xmx128m", "c2"}}) {
            recipe(V2_HEADER + "controlPlane: {modules: [build-metadata], build: {mode: jvm, variant: jvm-" + tier[1]
                    + "}, jvm: {args: ['" + tier[0] + "']}}\n");

            assertThat(run("printRecipeProps", "-Precipe=recipe.yaml").getOutput()).contains(
                    "props :control-plane-modules:build-metadata nativeOptimization=null nativeGc=null nativeMonitoring=null"
                            + " nanofaasBuildVariant=jvm-" + tier[1] + " nanofaasBuildOptimization=" + tier[1]);
            run("assembleRecipe", "-Precipe=recipe.yaml", docker());
            assertThat(report().at("/components/0/optimization").asText()).isEqualTo(tier[1]);
        }
    }

    private List<String> containerBuild() throws IOException {
        List<List<String>> calls = dockerCalls();
        return calls.stream().filter(call -> call.contains("native-executable")).findFirst()
                .orElseThrow(() -> new AssertionError("no container build in " + calls));
    }

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
    void containerBuilderHandsTheStagedContainerdRepositoryToTheBuilder() throws IOException {
        writeModule("containerd-deployment-provider", "");
        recipe(V2_HEADER + "controlPlane: {modules: [containerd-deployment-provider], build: {mode: native, builder: container}}\n");
        Path repository = Files.createDirectories(outsideDir.resolve("containerd-m2"));

        run("assembleRecipe", "-Precipe=recipe.yaml", docker(), "-PcontainerdMavenLocal=true",
                "-Dmaven.repo.local=" + repository);

        assertThat(containerBuild()).contains("containerd_maven_repo=" + repository,
                "GRADLE_ARGS=-PnanofaasBuildType=native -PcontrolPlaneModules=containerd-deployment-provider"
                        + " -PcontainerdMavenLocal=true -Dmaven.repo.local=/tmp/containerd-m2");
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

        assertThat(RecipeTasks.hostProblem("Mac OS X", host, 2)).contains("Linux host")
                .contains("use build.builder: container");
        // v1 has no build.builder: point to the version that does instead of suggesting an invalid field.
        assertThat(RecipeTasks.hostProblem("Mac OS X", host, 1)).contains("Linux host")
                .contains("build.builder: container needs schemaVersion: 2").doesNotContain("; use build.builder");
        assertThat(RecipeTasks.hostProblem("Mac OS X", container, 2)).isNull();
        assertThat(RecipeTasks.hostProblem("Linux", host, 1)).isNull();
    }


    @Test
    void skippingCleanRecipeStillRefusesAnUnownedOutputWhenEveryComponentIsContainerBuilt() throws IOException {
        // Nothing for stageRecipe to copy: its Sync is skipped as NO-SOURCE, and so is its own ownership check.
        recipe(V2_HEADER + "controlPlane: {modules: [], build: {mode: native, builder: container}, config: {a: 1}}\n");
        Path unrelated = Files.createDirectories(outsideDir.resolve("skip-clean-container"));
        Files.writeString(unrelated.resolve("keep.txt"), "user data");
        Files.writeString(unrelated.resolve("distribution.json"), "user report");

        assertThat(fails("assembleRecipe", "-x", "cleanRecipe", "-Precipe=recipe.yaml", docker(), output(unrelated)))
                .contains("is not empty and holds no recipe output");
        assertThat(unrelated.resolve("keep.txt")).hasContent("user data");
        assertThat(unrelated.resolve("distribution.json")).hasContent("user report");
        assertThat(unrelated.resolve("control-plane")).doesNotExist();
    }

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

    private static final String FULL_RECIPE = HEADER + """
            registry: {repository: registry.example:5000/team, tag: "1.0.0"}
            controlPlane:
              modules: []
              build: {mode: jvm}
              jvm: {args: ['-Xmx128m', '-Dgreeting=hello world', '-Dquote="x"', 'C:\\tmp']}
              container: {image: control-plane}
              config: {nanofaas: {metrics: {profile: basic}}}
            functions:
              - {name: word-stats, sdk: java-lite, build: {mode: native}, container: {image: ws-lite}}
              - {name: word-stats, sdk: java, build: {mode: jvm}}
              - {name: word-stats, sdk: python, container: {image: ws-python}}
            """;

    @Test
    void assembleBuildsOnlySelectedArtifactsAndImagesWithoutPushing() throws IOException {
        recipe(FULL_RECIPE);

        BuildResult result = run("assembleRecipe", "-Precipe=recipe.yaml", "-PrecipeTag=2.0.0", docker());

        assertThat(result.task(":control-plane:bootJar").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":functions:java:word-stats-lite:nativeCompile").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":functions:java:word-stats:bootJar").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(result.task(":control-plane:nativeCompile")).isNull();
        assertThat(result.task(":functions:java:word-stats-lite:installDist")).isNull();
        assertThat(result.task(":functions:java:word-stats:nativeCompile")).isNull();

        Path out = projectDir.resolve("build/recipes/demo");
        assertThat(out.resolve("control-plane/app.jar")).isRegularFile();
        // Fixed control-plane flags first, so the recipe's options (later on the command line) win.
        assertThat(Files.readString(out.resolve("control-plane/jvm.options")))
                .startsWith(CONTROL_PLANE_FLAGS)
                .endsWith("\"-Xmx128m\"\n\"-Dgreeting=hello world\"\n\"-Dquote=\\\"x\\\"\"\n\"C:\\\\tmp\"\n")
                .doesNotContain("UseSerialGC");
        assertThat(Files.readString(out.resolve("control-plane/launch.args"))).isEqualTo("\"-jar\"\n\"app.jar\"\n");
        assertThat(Files.readString(out.resolve("control-plane/config/recipe.yaml"))).contains("profile: basic");
        assertThat(out.resolve("functions/java-lite/word-stats/application")).isExecutable();
        assertThat(out.resolve("functions/java-lite/word-stats/unrelated.txt")).doesNotExist();
        assertThat(out.resolve("functions/java/word-stats/app.jar")).isRegularFile();
        assertThat(Files.readString(out.resolve("functions/java/word-stats/jvm.options"))).isEmpty();
        assertThat(out.resolve("functions/python")).doesNotExist();

        Path root = projectDir.toRealPath();
        assertThat(dockerCalls().stream().filter(call -> call.getFirst().equals("build")).toList()).containsExactlyInAnyOrder(
                List.of("build", "-f", root.resolve("deploy/recipes/Dockerfile.jvm").toString(),
                        "-t", "registry.example:5000/team/control-plane:2.0.0", root.resolve("build/recipes/demo/control-plane").toString()),
                List.of("build", "-f", root.resolve("deploy/recipes/Dockerfile.native").toString(),
                        "-t", "registry.example:5000/team/ws-lite:2.0.0",
                        root.resolve("build/recipes/demo/functions/java-lite/word-stats").toString()),
                List.of("build", "-f", root.resolve("functions/python/word-stats/Dockerfile").toString(),
                        "-t", "registry.example:5000/team/ws-python:2.0.0", root.toString()));

        String report = Files.readString(out.resolve("distribution.json"));
        assertThat(report)
                .contains("\"schemaVersion\" : 2", "\"tag\" : \"2.0.0\"", "\"source\" : null", "\"modules\" : [ ]")
                .contains("\"sha256\" : \"" + sha256(projectDir.resolve("recipe.yaml")) + "\"")
                .contains("\"reference\" : \"registry.example:5000/team/ws-python:2.0.0\"", "\"status\" : \"built\"")
                .contains("\"artifact\" : \"functions/java-lite/word-stats/\"")
                .doesNotContain("profile", "digest");
    }

    @Test
    void controlPlaneKeepsDefaultTuningWithoutJvmArgs() throws IOException {
        recipe(HEADER + CP_JVM);

        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        Path controlPlane = projectDir.resolve("build/recipes/demo/control-plane");
        assertThat(Files.readString(controlPlane.resolve("jvm.options"))).isEqualTo(CONTROL_PLANE_FLAGS + "\"-XX:+UseSerialGC\"\n");
        assertThat(controlPlane.resolve("config")).doesNotExist();
        assertThat(dockerCalls()).isEmpty();
    }

    @Test
    void liteJvmStagesClasspathAndMainClass() throws IOException {
        recipe(HEADER + CP_JVM + "functions: [{name: word-stats, sdk: java-lite, build: {mode: jvm}}]\n");

        BuildResult result = run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        Path function = projectDir.resolve("build/recipes/demo/functions/java-lite/word-stats");
        assertThat(result.task(":functions:java:word-stats-lite:installDist").getOutcome()).isEqualTo(TaskOutcome.SUCCESS);
        assertThat(function.resolve("lib/word-stats-lite.jar")).isRegularFile();
        assertThat(function.resolve("bin")).doesNotExist();
        assertThat(Files.readString(function.resolve("launch.args")))
                .isEqualTo("\"-cp\"\n\"lib/*\"\n\"demo.WordStatsLite\"\n");
    }

    @Test
    void builderFailureFailsAssemblyWithoutReport() throws IOException {
        recipe(FULL_RECIPE);
        Files.writeString(projectDir.resolve("fail-build"), "");

        assertThat(fails("assembleRecipe", "-Precipe=recipe.yaml", docker())).contains("fake docker build failed");
        assertThat(projectDir.resolve("build/recipes/demo/distribution.json")).doesNotExist();
    }

    @Test
    void reassemblyDropsRemovedFunctionsAndStaleReport() throws IOException {
        recipe(FULL_RECIPE);
        run("assembleRecipe", "-Precipe=recipe.yaml", docker());
        Path report = projectDir.resolve("build/recipes/demo/distribution.json");
        Files.writeString(report, Files.readString(report).replace("\"built\"", "\"published\", \"digest\" : \"sha256:old\""));

        recipe(HEADER + CP_JVM + "functions: [{name: word-stats, sdk: python, container: {image: ws-python}}]\n");
        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        assertThat(projectDir.resolve("build/recipes/demo/functions/java-lite")).doesNotExist();
        assertThat(projectDir.resolve("build/recipes/demo/control-plane/config")).doesNotExist();
        assertThat(Files.readString(report)).doesNotContain("sha256:old", "java-lite", "published");
    }

    @Test
    void untrackedSourcesMakeTheReportDirty() throws Exception {
        recipe(HEADER + CP_JVM);
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "fixture");
        write("functions/python/untracked/Dockerfile", "FROM scratch\n");

        runner("assembleRecipe", "-Precipe=recipe.yaml", docker())
                .withEnvironment(Map.of("PATH", System.getenv("PATH"))).build();

        assertThat(report().get("source").get("revision").asText()).hasSize(40);
        assertThat(report().get("source").get("dirty").asBoolean()).isTrue();
    }


    @Test
    void anOutputInsideTheRepositoryDoesNotMakeTheReportDirty() throws Exception {
        recipe(HEADER + CP_JVM);
        write(".gitignore", "build/\n.gradle/\nmarkers/\n");   // Gradle's and the fixture's own outputs
        git("init", "-q");
        git("add", "-A");
        git("-c", "user.name=test", "-c", "user.email=test@example.invalid", "commit", "-q", "-m", "fixture");

        runner("assembleRecipe", "-Precipe=recipe.yaml", docker(), "-PrecipeOutput=recipe-out")
                .withEnvironment(Map.of("PATH", System.getenv("PATH"))).build();

        JsonNode report = new ObjectMapper().readTree(projectDir.resolve("recipe-out/distribution.json").toFile());
        assertThat(report.get("source").get("revision").asText()).hasSize(40);
        assertThat(report.get("source").get("dirty").asBoolean()).isFalse();
    }

    private void git(String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", projectDir.toString()));
        command.addAll(List.of(arguments));
        assertThat(new ProcessBuilder(command).inheritIO().start().waitFor()).isZero();
    }

    @Test
    void assembleRequiresRecipe() {
        assertThat(fails("assembleRecipe")).contains("assembleRecipe requires -Precipe=<file>");
    }

    private static final String CP_REF = "registry.example:5000/team/control-plane:2.0.0";
    private static final String LITE_REF = "registry.example:5000/team/ws-lite:2.0.0";
    private static final String PYTHON_REF = "registry.example:5000/team/ws-python:2.0.0";

    @Test
    void publishPushesAfterAllBuildsAndRecordsRegistryDigests() throws IOException {
        recipe(FULL_RECIPE);

        run("publishRecipe", "-Precipe=recipe.yaml", "-PrecipeTag=2.0.0", docker());

        List<List<String>> calls = dockerCalls();
        List<String> commands = calls.stream().map(List::getFirst).toList();
        assertThat(commands.lastIndexOf("build")).isLessThan(commands.indexOf("push"));
        assertThat(calls.stream().filter(call -> call.getFirst().equals("push")))
                .containsExactly(List.of("push", CP_REF), List.of("push", LITE_REF), List.of("push", PYTHON_REF));
        JsonNode report = report();
        assertThat(report.get("tag").asText()).isEqualTo("2.0.0");
        assertThat(image(report, CP_REF).get("status").asText()).isEqualTo("published");
        assertThat(image(report, CP_REF).get("digest").asText()).isEqualTo(digestOf(CP_REF));
        assertThat(image(report, PYTHON_REF).get("digest").asText()).isEqualTo(digestOf(PYTHON_REF));
    }

    @Test
    void publishChecksPrerequisitesBeforeBuilding() throws IOException {
        recipe(HEADER + "controlPlane: {modules: [], build: {mode: jvm}, container: {image: cp}}\n");
        assertThat(fails(":publishRecipe", "-Precipe=recipe.yaml", docker()))
                .contains("publishRecipe requires a registry section");

        recipe(HEADER + "registry: {repository: registry.example/team, tag: t}\n" + CP_JVM);
        assertThat(fails("publishRecipe", "-Precipe=recipe.yaml", docker())).contains("publishRecipe found no images");

        assertThat(projectDir.resolve("markers")).doesNotExist();
        assertThat(projectDir.resolve("docker.log")).doesNotExist();
    }

    @Test
    void failedSecondPushKeepsTheFirstSuccessInTheReport() throws IOException {
        recipe(FULL_RECIPE);
        Files.writeString(projectDir.resolve("fail-push-2"), "");

        String output = fails("publishRecipe", "-Precipe=recipe.yaml", "-PrecipeTag=2.0.0", docker());

        assertThat(output).contains("docker push " + LITE_REF + " failed")
                .contains("already published: [" + CP_REF + "@" + digestOf(CP_REF) + "]");
        JsonNode report = report();
        assertThat(image(report, CP_REF).get("status").asText()).isEqualTo("published");
        assertThat(image(report, CP_REF).get("digest").asText()).isEqualTo(digestOf(CP_REF));
        assertThat(image(report, LITE_REF).get("status").asText()).isEqualTo("failed");
        assertThat(image(report, PYTHON_REF).get("status").asText()).isEqualTo("built");
        assertThat(dockerCalls()).doesNotContain(List.of("push", PYTHON_REF));
    }

    @Test
    void missingPushDigestFallsBackToTheMatchingRepoDigest() throws IOException {
        recipe(PUBLISH_CP_ONLY);
        Files.writeString(projectDir.resolve("no-digest"), "");
        String other = "sha256:" + "c".repeat(64);
        String matching = "sha256:" + "d".repeat(64);
        Files.writeString(projectDir.resolve("repo-digests"), "[\"registry.example:5000/team/other@" + other
                + "\",\"registry.example:5000/team/control-plane@" + matching + "\"]\n");

        run("publishRecipe", "-Precipe=recipe.yaml", docker());

        assertThat(dockerCalls()).contains(List.of("image", "inspect", "--format", "{{json .RepoDigests}}",
                "registry.example:5000/team/control-plane:1.0.0"));
        assertThat(image(report(), "registry.example:5000/team/control-plane:1.0.0").get("digest").asText())
                .isEqualTo(matching);
    }

    @Test
    void undeterminableDigestIsReportedAsPublishedUnverified() throws IOException {
        recipe(PUBLISH_CP_ONLY);
        Files.writeString(projectDir.resolve("no-digest"), "");

        assertThat(fails("publishRecipe", "-Precipe=recipe.yaml", docker()))
                .contains("registry.example:5000/team/control-plane:1.0.0 was pushed, but its registry digest");
        JsonNode image = image(report(), "registry.example:5000/team/control-plane:1.0.0");
        assertThat(image.get("status").asText()).isEqualTo("published-unverified");
        assertThat(image.get("digest").isNull()).isTrue();
    }

    @Test
    void reassemblyAfterPublishForgetsPublication() throws IOException {
        recipe(PUBLISH_CP_ONLY);
        run("publishRecipe", "-Precipe=recipe.yaml", docker());

        run("assembleRecipe", "-Precipe=recipe.yaml", docker());

        assertThat(Files.readString(projectDir.resolve("build/recipes/demo/distribution.json")))
                .contains("\"status\" : \"built\"").doesNotContain("digest", "published");
    }

    @Test
    void publishRequiresRecipe() {
        assertThat(fails("publishRecipe")).contains("publishRecipe requires -Precipe=<file>");
    }

    private static final String PUBLISH_CP_ONLY = HEADER + """
            registry: {repository: registry.example:5000/team, tag: "1.0.0"}
            controlPlane: {modules: [], build: {mode: jvm}, container: {image: control-plane}}
            """;

    private JsonNode report() throws IOException {
        return new ObjectMapper().readTree(projectDir.resolve("build/recipes/demo/distribution.json").toFile());
    }

    private static JsonNode image(JsonNode report, String reference) {
        for (JsonNode component : report.get("components")) {
            if (component.path("image").path("reference").asText().equals(reference)) {
                return component.get("image");
            }
        }
        throw new AssertionError("no image " + reference + " in " + report);
    }

    private static String digestOf(String reference) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(reference.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static String imageIdOf(String reference) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(("id-" + reference).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String docker() {
        return "-PrecipeDocker=" + projectDir.resolve("bin/docker");
    }

    private List<List<String>> dockerCalls() throws IOException {
        Path log = projectDir.resolve("docker.log");
        List<List<String>> calls = new ArrayList<>();
        if (!Files.exists(log)) {
            return calls;
        }
        List<String> current = new ArrayList<>();
        for (String line : Files.readAllLines(log)) {
            if (line.equals("--")) {
                calls.add(current);
                current = new ArrayList<>();
            } else {
                current.add(line);
            }
        }
        return calls;
    }

    private static String sha256(Path file) throws IOException {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static final String CONTROL_PLANE_FLAGS = """
            "-XX:MaxRAMPercentage=70"
            "-Xss256k"
            "-Dspring.main.banner-mode=off"
            "-Dspring.jmx.enabled=false"
            "-Dspring.devtools.restart.enabled=false"
            "-Dmanagement.endpoints.enabled-by-default=false"
            """;

    private static final String CP_JVM = "controlPlane: {modules: [], build: {mode: jvm}}\n";

    private void writeJavaFunction(String name, String sdk, String fakes) throws IOException {
        write("functions/java/" + name + "/build.gradle", """
                plugins { id 'java' }
                apply from: rootProject.file('marker.gradle')
                dependencies { implementation project('%s') }
                %s
                """.formatted(sdk, fakes));
    }

    private void writeModule(String id, String conflicts) throws IOException {
        write("platform/modules/" + id + "/build.gradle", "");
        write("platform/modules/" + id + "/module.properties", "schemaVersion=1\nid=" + id
                + "\ndefaultEnabled=false\nrequires.strong=\nrequires.weak=\nrequires.oneOf=\nconflicts=" + conflicts + "\n");
    }

    private void recipe(String content) throws IOException {
        write("recipe.yaml", content);
    }

    private void write(String relative, String content) throws IOException {
        Path file = projectDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private BuildResult run(String... arguments) {
        return runner(arguments).build();
    }

    private String fails(String... arguments) {
        return runner(arguments).buildAndFail().getOutput();
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath()
                .withEnvironment(Map.of())
                .forwardOutput();
    }
}
