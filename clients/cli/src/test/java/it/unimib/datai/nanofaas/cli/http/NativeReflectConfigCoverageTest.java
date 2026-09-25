package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Records are how the CLI talks to Jackson, and a native image binds only what
 * reflect-config.json names. The JVM needs no such metadata, so the rest of the suite passes
 * either way: `fn replicas get`, `control-plane info`, `fn update -f` and `--config` all failed
 * in the native CLI with "Failed to parse JSON" / "Failed to read YAML" / "Failed to serialize
 * JSON" while their JVM tests were green. The rule is deliberately "every record in the CLI":
 * a record that never meets Jackson costs one config entry, a missing one breaks a command.
 */
class NativeReflectConfigCoverageTest {

    private static final String CONFIG =
            "/META-INF/native-image/nanofaas/nanofaas-cli/reflect-config.json";

    private static Set<String> registered() throws Exception {
        try (InputStream in = NativeReflectConfigCoverageTest.class.getResourceAsStream(CONFIG)) {
            assertThat(in).as(CONFIG).isNotNull();
            return new ObjectMapper().readTree(in).findValuesAsText("name").stream().collect(Collectors.toSet());
        }
    }

    @Test
    void everyRecordInTheCliIsRegisteredForNativeSerialization() throws Exception {
        // The main output directory, not getResource(""): the test classes share this package and
        // come first on the classpath, so a relative lookup would scan only the tests.
        URL mainClasses = ReplicaStatus.class.getProtectionDomain().getCodeSource().getLocation();
        Path root = Path.of(mainClasses.toURI());
        Path cliDir = root.resolve("it/unimib/datai/nanofaas/cli");
        List<String> records;
        try (Stream<Path> files = Files.walk(cliDir)) {
            records = files.map(file -> root.relativize(file).toString())
                    .filter(name -> name.endsWith(".class"))
                    .map(name -> name.substring(0, name.length() - 6).replace('/', '.'))
                    .filter(NativeReflectConfigCoverageTest::isRecord)
                    .sorted()
                    .toList();
        }

        assertThat(records).contains(ReplicaStatus.class.getName(), BuildMetadata.Build.class.getName(),
                "it.unimib.datai.nanofaas.cli.commands.controlplane.ControlPlaneInfoCommand$Info");
        assertThat(registered()).containsAll(records);
    }

    @Test
    void theYamlConfigFileTypeIsRegistered() throws Exception {
        assertThat(registered()).contains(it.unimib.datai.nanofaas.cli.config.Config.class.getName());
    }

    private static boolean isRecord(String className) {
        try {
            return Class.forName(className, false, NativeReflectConfigCoverageTest.class.getClassLoader()).isRecord();
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(className, e);
        }
    }
}
