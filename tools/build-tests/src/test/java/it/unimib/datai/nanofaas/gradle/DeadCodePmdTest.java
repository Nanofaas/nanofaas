package it.unimib.datai.nanofaas.gradle;

import org.gradle.testkit.runner.GradleRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The real shared PMD script. A source set added after it ran (Spring Boot adds {@code aot} that
 * way) must still get a creatable PMD task with the JDK's jrt-fs.jar: an IDE's Gradle import
 * realises every task, and once failed with "Could not create task ':control-plane:pmdAot'".
 */
class DeadCodePmdTest {

    @TempDir
    Path projectDir;

    @Test
    void aSourceSetAddedAfterTheScriptGetsAWorkingPmdTask() throws IOException {
        Path repositoryRoot = Path.of(System.getProperty("nanofaas.warmEchoBuildScript"))
                .getParent().getParent().getParent().getParent();
        for (String shared : new String[] {"gradle/dead-code.gradle", "gradle.properties", "config/pmd/dead-code.xml"}) {
            Path target = projectDir.resolve(shared);
            Files.createDirectories(target.getParent());
            Files.copy(repositoryRoot.resolve(shared), target);
        }
        Files.writeString(projectDir.resolve("settings.gradle"), "rootProject.name = 'fixture'\n");
        Files.writeString(projectDir.resolve("build.gradle"), """
                apply plugin: 'java'
                apply from: 'gradle/dead-code.gradle'
                sourceSets { late }
                // Realised during configuration, as an IDE import realises every task.
                def pmdTasks = ['pmdMain', 'pmdLate'].collect { tasks.named(it).get() }
                tasks.register('printPmdClasspaths') {
                    doLast {
                        pmdTasks.each { task ->
                            def files = task.classpath.files
                            println "${task.name} jrtFs=${files.any { it.name == 'jrt-fs.jar' }}" +
                                    " output=${files.any { it.path.replace('\\\\', '/').contains('/classes/java/') }}"
                        }
                    }
                }
                """);

        String output = GradleRunner.create().withProjectDir(projectDir.toFile()).withArguments("printPmdClasspaths")
                .withEnvironment(Map.of()).forwardOutput().build().getOutput();

        assertThat(output).contains("pmdMain jrtFs=true output=true", "pmdLate jrtFs=true output=true");
    }
}
