package it.unimib.datai.nanofaas.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.gradle.testkit.runner.TaskOutcome.SUCCESS;

class ControlPlaneModulesPluginTest {

    @TempDir
    Path projectDir;

    @BeforeEach
    void writeBuildFiles() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"),
                "plugins { id 'it.unimib.datai.nanofaas.control-plane-modules' }\n");
        Files.writeString(projectDir.resolve("build.gradle"),
                "tasks.register('printSelection') { doLast { println gradle.ext.nanofaasSelectedControlPlaneModules } }\n"
                        + "tasks.register('mutateSelection') { doLast { gradle.ext.nanofaasSelectedControlPlaneModules.add('unexpected') } }\n");
    }

    @Test
    void usesDefaultEnabledWhenSelectorEnvironmentIsAbsent() throws IOException {
        writeModule("alpha", true, "", "", "", "");
        writeModule("beta", false, "", "", "", "");

        BuildResult result = run("printSelection");

        assertThat(result.task(":printSelection").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("[alpha]");
    }

    @Test
    void projectPropertyTakesPrecedenceOverEnvironment() throws IOException {
        writeModule("alpha", false, "", "", "", "");
        writeModule("beta", false, "", "", "", "");

        assertThat(runWithEnvironment(Map.of("NANOFAAS_CONTROL_PLANE_MODULES", "beta"),
                "printSelection", "-PcontrolPlaneModules=alpha").getOutput())
                .contains("[alpha]");
    }

    @Test
    void usesEnvironmentWhenProjectPropertyIsAbsent() throws IOException {
        writeModule("alpha", false, "", "", "", "");
        writeModule("beta", false, "", "", "", "");

        assertThat(runWithEnvironment(Map.of("NANOFAAS_CONTROL_PLANE_MODULES", "beta"),
                "printSelection").getOutput())
                .contains("[beta]");
    }

    @Test
    void allSelectsEveryCompatibleModuleInSortedOrder() throws IOException {
        writeModule("beta", false, "", "", "", "");
        writeModule("alpha", false, "", "", "", "");

        assertThat(run("printSelection", "-PcontrolPlaneModules=all").getOutput())
                .contains("[alpha, beta]");
    }

    @Test
    void allPrefersDefaultEnabledModuleOnConflict() throws IOException {
        writeModule("async-queue", true, "", "", "", "sync-queue");
        writeModule("sync-queue", false, "", "", "", "async-queue");

        assertThat(run("printSelection", "-PcontrolPlaneModules=all").getOutput())
                .contains("[async-queue]")
                .contains("Skipping control-plane module 'sync-queue': conflicts with default-enabled module 'async-queue'");
    }

    @Test
    void allRejectsConflictBetweenNonDefaultModules() throws IOException {
        writeModule("async-queue", false, "", "", "", "sync-queue");
        writeModule("sync-queue", false, "", "", "", "async-queue");

        failsWith("-PcontrolPlaneModules=all", "conflict");
    }

    @Test
    void publishesSelectionAsImmutableExtraProperty() throws IOException {
        writeModule("alpha", false, "", "", "", "");

        BuildResult result = runner("mutateSelection", "-PcontrolPlaneModules=alpha").buildAndFail();

        assertThat(result.getOutput()).contains("UnsupportedOperationException");
    }

    @Test
    void selectsNoModules() throws IOException {
        writeModule("alpha", true, "", "", "", "");

        assertThat(run("printSelection", "-PcontrolPlaneModules=none").getOutput())
                .contains("[]");
    }

    @Test
    void rejectsNoneCombinedWithModule() throws IOException {
        writeModule("alpha", true, "", "", "", "");

        failsWith("-PcontrolPlaneModules=none,alpha", "cannot be combined");
    }

    @Test
    void selectsAValidSingleModule() throws IOException {
        writeModule("alpha", false, "", "", "", "");
        writeModule("beta", false, "", "", "", "");

        assertThat(run("printSelection", "-PcontrolPlaneModules=beta").getOutput())
                .contains("[beta]");
    }

    @Test
    void doesNotAutoSelectOneOfProvider() throws IOException {
        writeModule("consumer", false, "", "", "provider", "");
        writeModule("provider", false, "", "", "", "");

        failsWith("-PcontrolPlaneModules=consumer", "requires at least one oneOf module");
    }

    @Test
    void doesNotAddOneOfProviderAsGradleDependency() throws IOException {
        writeModule("provider", false, "", "", "", "");
        writeModule("consumer", false, "", "", "provider", "");
        Files.writeString(projectDir.resolve("platform/modules/consumer/build.gradle"), """
                plugins { id 'java-library' }
                tasks.register('printModuleDependencies') {
                    doLast {
                        ['implementation', 'compileOnly', 'testImplementation'].each { name ->
                            println name + '=' + configurations.named(name).get().dependencies.collect { it.name }
                        }
                    }
                }
                """);

        BuildResult result = run(":control-plane-modules:consumer:printModuleDependencies",
                "-PcontrolPlaneModules=consumer,provider");

        assertThat(result.getOutput()).contains("implementation=[]")
                .contains("compileOnly=[]")
                .contains("testImplementation=[]");
    }

    @Test
    void allowsMissingWeakModuleWhenItIsNotSelected() throws IOException {
        writeModule("optional", false, "", "base", "", "");
        writeModule("base", false, "", "", "", "");

        assertThat(run("printSelection", "-PcontrolPlaneModules=optional").getOutput())
                .contains("[optional]");
    }

    @Test
    void rejectsMissingStrongModuleWhenItIsNotSelected() throws IOException {
        writeModule("required", false, "base", "", "", "");
        writeModule("base", false, "", "", "", "");

        failsWith("-PcontrolPlaneModules=required", "requires strong module 'base'");
    }

    @Test
    void rejectsConflictingModules() throws IOException {
        writeModule("async-queue", false, "", "", "", "sync-queue");
        writeModule("sync-queue", false, "", "", "", "async-queue");

        failsWith("-PcontrolPlaneModules=async-queue,sync-queue", "conflict");
    }

    @Test
    void rejectsConflictExpandedByAll() throws IOException {
        writeModule("async-queue", true, "", "", "", "sync-queue");
        writeModule("sync-queue", true, "", "", "", "async-queue");

        failsWith("-PcontrolPlaneModules=all", "conflict");
    }

    @Test
    void rejectsUnknownModule() throws IOException {
        writeModule("alpha", false, "", "", "", "");

        failsWith("-PcontrolPlaneModules=unknown", "Unknown control-plane module");
    }

    @Test
    void rejectsMissingDescriptor() throws IOException {
        writeModule("alpha", false, "", "", "", "");
        Files.createDirectories(projectDir.resolve("platform/modules/missing"));
        Files.writeString(projectDir.resolve("platform/modules/missing/build.gradle"), "");

        failsWith("-PcontrolPlaneModules=none", "missing module.properties");
    }

    @Test
    void rejectsDescriptorIdMismatch() throws IOException {
        writeModule("alpha", false, "", "", "", "");
        Path module = projectDir.resolve("platform/modules/actual");
        Files.createDirectories(module);
        Files.writeString(module.resolve("build.gradle"), "");
        Files.writeString(module.resolve("module.properties"), descriptor("declared", false, "", "", "", ""));

        failsWith("-PcontrolPlaneModules=none", "directory name 'actual' does not match descriptor id 'declared'");
    }

    @Test
    void composesSelectedModuleFragmentsIntoControlPlaneOpenApi() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"),
                "plugins { id 'it.unimib.datai.nanofaas.control-plane-modules' }\n"
                        + "include('control-plane'); project(':control-plane').projectDir = file('control-plane')\n");

        Files.createDirectories(projectDir.resolve("openapi"));
        Files.writeString(projectDir.resolve("openapi/core.yaml"), """
                openapi: 3.0.3
                info:
                  title: Core
                  version: "1"
                paths: {}
                components: {}
                """);

        writeModule("alpha", true, "", "", "", "");
        writeModule("beta", true, "", "", "", "");
        writeFragment("alpha", "/alpha");
        writeFragment("beta", "/beta");

        Path controlPlane = projectDir.resolve("control-plane");
        Files.createDirectories(controlPlane);
        Files.writeString(controlPlane.resolve("build.gradle"), "plugins { id 'java' }\n");

        BuildResult result = run("-PcontrolPlaneModules=alpha", ":control-plane:processResources");

        Path output = projectDir.resolve(
                "control-plane/build/generated/openapi/META-INF/resources/openapi.yaml");
        assertThat(output).exists();
        assertThat(Files.readString(output)).contains("/alpha").doesNotContain("/beta");
        assertThat(result.task(":control-plane:composeControlPlaneOpenApi").getOutcome())
                .isEqualTo(TaskOutcome.SUCCESS);
    }

    private void writeFragment(String moduleId, String path) throws IOException {
        Files.writeString(projectDir.resolve("platform/modules/" + moduleId + "/openapi.yaml"), """
                paths:
                  %s:
                    get:
                      operationId: %sRoute
                      responses:
                        "200":
                          description: ok
                """.formatted(path, moduleId));
    }

    private BuildResult run(String... arguments) {
        return runner(arguments).build();
    }

    private BuildResult runWithEnvironment(Map<String, String> environment, String... arguments) {
        return runner(arguments).withEnvironment(environment).build();
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath()
                .withEnvironment(Map.of())
                .forwardOutput();
    }

    private void failsWith(String selector, String message) {
        BuildResult result = runner("tasks", selector).buildAndFail();
        assertThat(result.getOutput()).contains(message);
    }

    @Test
    void rejectsDuplicateSelectedModules() throws IOException {
        writeModule("alpha", false, "", "", "", "");

        failsWith("-PcontrolPlaneModules=alpha,alpha", "Duplicate control-plane module 'alpha'");
    }

    private void writeModule(String id, boolean defaultEnabled, String strong,
                             String weak, String oneOf, String conflicts) throws IOException {
        Path module = projectDir.resolve("platform/modules").resolve(id);
        Files.createDirectories(module);
        Files.writeString(module.resolve("build.gradle"), "");
        Files.writeString(module.resolve("module.properties"),
                descriptor(id, defaultEnabled, strong, weak, oneOf, conflicts));
    }

    private String descriptor(String id, boolean defaultEnabled, String strong,
                              String weak, String oneOf, String conflicts) {
        return "schemaVersion=1\n"
                + "id=" + id + "\n"
                + "defaultEnabled=" + defaultEnabled + "\n"
                + "requires.strong=" + strong + "\n"
                + "requires.weak=" + weak + "\n"
                + "requires.oneOf=" + oneOf + "\n"
                + "conflicts=" + conflicts + "\n";
    }
}
