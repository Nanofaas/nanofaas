package it.unimib.datai.nanofaas.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
                include ':functions:java:word-stats', ':functions:java:word-stats-lite', ':functions:java:jvm-only'
                project(':control-plane').projectDir = file('platform/control-plane')
                """);
        write("build.gradle", """
                tasks.register('printNative') { doLast { println "native=${gradle.ext.nanofaasNativeBuildRequested}" } }
                tasks.register('printSelection') { doLast { println "modules=${gradle.ext.nanofaasSelectedControlPlaneModules}" } }
                """);
        // Each fake build task leaves a marker, so tests can prove what did (not) run.
        write("marker.gradle", """
                ext.marker = { String name -> tasks.register(name) { doLast {
                    def file = rootProject.file("markers/${project.name}-${name}")
                    file.parentFile.mkdirs(); file.text = 'ran'
                } } }
                """);
        write("platform/control-plane/build.gradle", """
                apply from: rootProject.file('marker.gradle')
                marker('bootJar'); marker('nativeCompile')
                """);
        write("sdks/java/build.gradle", "plugins { id 'java' }\n");
        write("sdks/java-lite/build.gradle", "plugins { id 'java' }\n");
        writeJavaFunction("word-stats", ":sdks:java", "bootJar", "nativeCompile");
        writeJavaFunction("word-stats-lite", ":sdks:java-lite", "installDist", "nativeCompile");
        writeJavaFunction("jvm-only", ":sdks:java", "bootJar");
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

    private static final String CP_JVM = "controlPlane: {modules: [], build: {mode: jvm}}\n";

    private void writeJavaFunction(String name, String sdk, String... tasks) throws IOException {
        StringBuilder build = new StringBuilder("""
                plugins { id 'java' }
                apply from: rootProject.file('marker.gradle')
                dependencies { implementation project('%s') }
                """.formatted(sdk));
        for (String task : tasks) {
            build.append("marker('").append(task).append("')\n");
        }
        write("functions/java/" + name + "/build.gradle", build.toString());
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
