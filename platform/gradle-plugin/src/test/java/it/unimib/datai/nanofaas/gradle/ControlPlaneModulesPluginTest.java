package it.unimib.datai.nanofaas.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
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
    void selectsDefaultEnabledModules() throws IOException {
        writeModule("alpha", true);
        writeModule("beta", false);

        BuildResult result = run("printSelection");

        assertThat(result.task(":printSelection").getOutcome()).isEqualTo(SUCCESS);
        assertThat(result.getOutput()).contains("[alpha]");
    }

    @Test
    void projectPropertyTakesPrecedenceOverEnvironment() throws IOException {
        writeModule("alpha", false);
        writeModule("beta", false);

        assertThat(runWithEnvironment(Map.of("NANOFAAS_CONTROL_PLANE_MODULES", "beta"),
                "printSelection", "-PcontrolPlaneModules=alpha").getOutput())
                .contains("[alpha]");
    }

    @Test
    void usesEnvironmentWhenProjectPropertyIsAbsent() throws IOException {
        writeModule("alpha", false);
        writeModule("beta", false);

        assertThat(runWithEnvironment(Map.of("NANOFAAS_CONTROL_PLANE_MODULES", "beta"),
                "printSelection").getOutput())
                .contains("[beta]");
    }

    @Test
    void allSelectsEveryCompatibleModuleInSortedOrder() throws IOException {
        writeModule("beta", false);
        writeModule("alpha", false);

        assertThat(run("printSelection", "-PcontrolPlaneModules=all").getOutput())
                .contains("[alpha, beta]");
    }

    @Test
    void publishesSelectionAsImmutableExtraProperty() throws IOException {
        writeModule("alpha", false);

        BuildResult result = runner("mutateSelection", "-PcontrolPlaneModules=alpha").buildAndFail();

        assertThat(result.getOutput()).contains("UnsupportedOperationException");
    }

    @Test
    void selectsNoModules() throws IOException {
        writeModule("alpha", true);

        assertThat(run("printSelection", "-PcontrolPlaneModules=none").getOutput())
                .contains("[]");
    }

    @Test
    void rejectsNoneCombinedWithModule() throws IOException {
        writeModule("alpha", true);

        failsWith("-PcontrolPlaneModules=none,alpha", "cannot be combined");
    }

    @Test
    void selectsAValidSingleModule() throws IOException {
        writeModule("alpha", false);
        writeModule("beta", false);

        assertThat(run("printSelection", "-PcontrolPlaneModules=beta").getOutput())
                .contains("[beta]");
    }

    @Test
    void allowsMissingWeakModuleWhenItIsNotSelected() throws IOException {
        writeModule("optional", false, "", "base", "");
        writeModule("base", false);

        assertThat(run("printSelection", "-PcontrolPlaneModules=optional").getOutput())
                .contains("[optional]");
    }

    @Test
    void rejectsMissingStrongModuleWhenItIsNotSelected() throws IOException {
        writeModule("required", false, "base", "", "");
        writeModule("base", false);

        failsWith("-PcontrolPlaneModules=required", "requires strong module 'base'");
    }

    @Test
    void rejectsConflictingModules() throws IOException {
        writeModule("async-queue", false, "", "", "sync-queue");
        writeModule("sync-queue", false, "", "", "async-queue");

        failsWith("-PcontrolPlaneModules=async-queue,sync-queue", "conflict");
    }

    @Test
    void rejectsConflictExpandedByAll() throws IOException {
        writeModule("async-queue", true, "", "", "sync-queue");
        writeModule("sync-queue", true, "", "", "async-queue");

        failsWith("-PcontrolPlaneModules=all", "conflict");
    }

    @Test
    void rejectsUnknownModule() throws IOException {
        writeModule("alpha", false);

        failsWith("-PcontrolPlaneModules=unknown", "Unknown control-plane module");
    }

    @Test
    void rejectsMissingDescriptor() throws IOException {
        writeModule("alpha", false);
        Files.createDirectories(projectDir.resolve("platform/modules/missing"));
        Files.writeString(projectDir.resolve("platform/modules/missing/build.gradle"), "");

        failsWith("-PcontrolPlaneModules=none", "missing module.properties");
    }

    @Test
    void rejectsDescriptorIdMismatch() throws IOException {
        writeModule("alpha", false);
        Path module = projectDir.resolve("platform/modules/actual");
        Files.createDirectories(module);
        Files.writeString(module.resolve("build.gradle"), "");
        Files.writeString(module.resolve("module.properties"), descriptor("declared", false, "", "", ""));

        failsWith("-PcontrolPlaneModules=none", "directory name 'actual' does not match descriptor id 'declared'");
    }

    private BuildResult run(String... arguments) {
        return runner(arguments).build();
    }

    private BuildResult runWithEnvironment(Map<String, String> environment, String... arguments) {
        Map<String, String> testEnvironment = new HashMap<>(System.getenv());
        testEnvironment.putAll(environment);
        return runner(arguments).withEnvironment(testEnvironment).build();
    }

    private GradleRunner runner(String... arguments) {
        return GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(arguments)
                .withPluginClasspath()
                .forwardOutput();
    }

    private void failsWith(String selector, String message) {
        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments("tasks", selector)
                .withPluginClasspath()
                .buildAndFail();
        assertThat(result.getOutput()).contains(message);
    }

    @Test
    void rejectsDuplicateSelectedModules() throws IOException {
        writeModule("alpha", false);

        failsWith("-PcontrolPlaneModules=alpha,alpha", "Duplicate control-plane module 'alpha'");
    }

    private void writeModule(String id, boolean defaultEnabled) throws IOException {
        writeModule(id, defaultEnabled, "", "", "");
    }

    private void writeModule(String id, boolean defaultEnabled, String strong,
                             String weak, String conflicts) throws IOException {
        Path module = projectDir.resolve("platform/modules").resolve(id);
        Files.createDirectories(module);
        Files.writeString(module.resolve("build.gradle"), "");
        Files.writeString(module.resolve("module.properties"), descriptor(id, defaultEnabled, strong, weak, conflicts));
    }

    private String descriptor(String id, boolean defaultEnabled, String strong,
                              String weak, String conflicts) {
        return "schemaVersion=1\n"
                + "id=" + id + "\n"
                + "defaultEnabled=" + defaultEnabled + "\n"
                + "requires.strong=" + strong + "\n"
                + "requires.weak=" + weak + "\n"
                + "conflicts=" + conflicts + "\n";
    }
}
