package it.unimib.datai.nanofaas.gradle;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneModuleProjectPluginTest {

    @TempDir
    Path projectDir;

    @Test
    void addsStrongAsImplementationAndWeakAsCompileOnlyWithoutTestDependencies() throws IOException {
        Files.writeString(projectDir.resolve("settings.gradle"),
                "plugins { id 'it.unimib.datai.nanofaas.control-plane-modules' }\n");
        Files.writeString(projectDir.resolve("build.gradle"), "");
        writeModule("strong", "false", "", "", "");
        writeModule("weak", "false", "", "", "");
        writeModule("consumer", "true", "strong", "weak", "", "strong");
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

        BuildResult result = GradleRunner.create()
                .withProjectDir(projectDir.toFile())
                .withArguments(":control-plane-modules:consumer:printModuleDependencies",
                        "-PcontrolPlaneModules=consumer,strong,weak")
                .withPluginClasspath()
                .forwardOutput()
                .build();

        assertThat(result.getOutput()).contains("implementation=[strong]")
                .contains("compileOnly=[weak]")
                .contains("testImplementation=[]");
    }

    private void writeModule(String id, String defaultEnabled, String strong,
                             String weak, String conflicts) throws IOException {
        writeModule(id, defaultEnabled, strong, weak, conflicts, "");
    }

    private void writeModule(String id, String defaultEnabled, String strong,
                             String weak, String conflicts, String oneOf) throws IOException {
        Path module = projectDir.resolve("platform/modules").resolve(id);
        Files.createDirectories(module);
        Files.writeString(module.resolve("build.gradle"), "");
        Files.writeString(module.resolve("module.properties"), descriptor(id, defaultEnabled, strong, weak, oneOf,
                conflicts));
    }

    private String descriptor(String id, String defaultEnabled, String strong,
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
