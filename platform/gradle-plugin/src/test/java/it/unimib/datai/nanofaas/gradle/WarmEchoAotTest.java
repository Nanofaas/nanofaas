package it.unimib.datai.nanofaas.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The real warm-echo build script, with the real Spring Boot and GraalVM plugins, under a recipe. */
class WarmEchoAotTest {

    @TempDir
    Path projectDir;

    @BeforeEach
    void writeFixture() throws IOException {
        write("settings.gradle", """
                plugins { id 'it.unimib.datai.nanofaas.control-plane-modules' }
                // The real Spring Boot plugin imports its BOM even to plan tasks; only POMs are resolved.
                dependencyResolutionManagement { repositories { mavenCentral() } }
                include ':control-plane', ':sdks:java', ':services:java:warm-echo'
                project(':control-plane').projectDir = file('platform/control-plane')
                """);
        write("build.gradle", """
                // The real root build applies java to every subproject before its script is evaluated.
                subprojects { apply plugin: 'java' }
                gradle.projectsEvaluated {
                    def echo = project(':services:java:warm-echo')
                    println "aot jar=${echo.tasks.jar.enabled} processAot=${echo.tasks.processAot.enabled}" +
                            " compileAotJava=${echo.tasks.compileAotJava.enabled}" +
                            " processAotResources=${echo.tasks.processAotResources.enabled}" +
                            " aotClasses=${echo.tasks.aotClasses.enabled}" +
                            " processTestAot=${echo.tasks.processTestAot.enabled}"
                }
                """);
        write("platform/control-plane/build.gradle", """
                plugins { id 'java' }
                tasks.register('bootJar', Jar) { archiveFileName = 'app.jar' }
                tasks.register('nativeCompile') { ext.outputFile = layout.buildDirectory.file('native/cp') }
                """);
        write("sdks/java/build.gradle", "plugins { id 'java' }\n");
        Path warmEchoScript = Path.of(System.getProperty("nanofaas.warmEchoBuildScript"));
        Files.copy(warmEchoScript, projectDir.resolve(Files.createDirectories(projectDir.resolve("services/java/warm-echo"))
                .resolve("build.gradle")));
        // The script applies the repository's JVM-jar AOT guard; copy the real one next to it.
        Path repositoryRoot = warmEchoScript.getParent().getParent().getParent().getParent();
        Files.copy(repositoryRoot.resolve("gradle/jvm-jar-without-aot.gradle"),
                Files.createDirectories(projectDir.resolve("gradle")).resolve("jvm-jar-without-aot.gradle"));
    }

    @ParameterizedTest(name = "control plane {0}, warm-echo {1}, {2}")
    @CsvSource({
            "jvm, native, assembleRecipe, true",
            "jvm, native, publishRecipe, true",
            "native, jvm, assembleRecipe, false",
            "native, jvm, publishRecipe, false"})
    void recipeModeDecidesServiceAot(String controlPlane, String service, String task, boolean aot) throws IOException {
        write("recipe.yaml", """
                schemaVersion: 2
                name: demo
                registry: {repository: registry.example/team, tag: t}
                controlPlane: {modules: [], build: {mode: %s}}
                services: [{name: warm-echo, sdk: java, build: {mode: %s}, container: {image: echo}}]
                """.formatted(controlPlane, service));

        String output = runner(task, "--dry-run", "-Precipe=recipe.yaml").build().getOutput();

        assertThat(output).contains("aot jar=" + aot + " processAot=" + aot + " compileAotJava=" + aot
                + " processAotResources=" + aot + " aotClasses=" + aot + " processTestAot=false");
    }

    @Test
    void withoutRecipeTaskNamesStillDecide() {
        assertThat(runner(":services:java:warm-echo:nativeCompile", "--dry-run").build().getOutput())
                .contains("aot jar=true processAot=true");
        assertThat(runner(":services:java:warm-echo:bootJar", "--dry-run").build().getOutput())
                .contains("aot jar=false processAot=false");
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create().withProjectDir(projectDir.toFile()).withArguments(arguments)
                .withPluginClasspath().withEnvironment(Map.of()).forwardOutput();
    }

    private void write(String relative, String content) throws IOException {
        Path file = projectDir.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
