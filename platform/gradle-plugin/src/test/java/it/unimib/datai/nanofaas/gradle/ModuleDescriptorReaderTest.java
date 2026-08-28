package it.unimib.datai.nanofaas.gradle;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModuleDescriptorReaderTest {

    @TempDir
    Path tempDir;

    private final ModuleDescriptorReader reader = new ModuleDescriptorReader();

    @Test
    void readsAndTrimsDescriptorProperties() throws Exception {
        Path descriptor = Files.createTempFile(tempDir, "module", ".properties");
        Files.writeString(descriptor, """
                schemaVersion=1
                id= autoscaler
                defaultEnabled= true
                requires.strong= runtime-config, common
                requires.weak= metrics,
                requires.oneOf= async-queue, sync-queue,
                conflicts= async-queue
                """);

        ModuleDescriptor result = reader.read(descriptor);

        assertThat(result.schemaVersion()).isEqualTo(1);
        assertThat(result.id()).isEqualTo("autoscaler");
        assertThat(result.defaultEnabled()).isTrue();
        assertThat(result.strongRequirements()).containsExactly("runtime-config", "common");
        assertThat(result.weakRequirements()).containsExactly("metrics");
        assertThat(result.oneOfRequirements()).containsExactly("async-queue", "sync-queue");
        assertThat(result.conflicts()).containsExactly("async-queue");
    }

    @Test
    void rejectsMissingAndUnsupportedSchema() throws Exception {
        Path missing = Files.createTempFile(tempDir, "module", ".properties");
        Files.writeString(missing, "id=sync-queue\n");
        Path unsupported = Files.createTempFile(tempDir, "module", ".properties");
        Files.writeString(unsupported, "schemaVersion=2\nid=sync-queue\n");

        assertThatThrownBy(() -> reader.read(missing))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> reader.read(unsupported))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsInvalidProperties() throws Exception {
        assertInvalid("schemaVersion=1\nid= \ndefaultEnabled=true\n", "id");
        assertInvalid("schemaVersion=1\nid=sync\ndefaultEnabled=maybe\n", "defaultEnabled");
        assertInvalid("schemaVersion=1\nid=sync\ndefaultEnabled=true\nrequires.strong=a,a\n", "duplicate");
        assertInvalid("schemaVersion=1\nid=sync\ndefaultEnabled=true\nrequires.oneOf=a,a\n", "duplicate");
        assertInvalid("schemaVersion=1\nid=sync\ndefaultEnabled=true\nunknown=value\n", "unknown");
    }

    private void assertInvalid(String content, String message) throws Exception {
        Path descriptor = Files.createTempFile(tempDir, "module", ".properties");
        Files.writeString(descriptor, content);

        assertThatThrownBy(() -> reader.read(descriptor))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(message);
    }
}
