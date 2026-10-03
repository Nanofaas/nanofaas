package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import static org.assertj.core.api.Assertions.*;

class ImageInventoryNativeHintsTest {
    @Test void registersTheDockerImageDeserializationDto() throws Exception {
        var hints = new RuntimeHints();
        new DockerJavaRuntimeHints().registerHints(hints, getClass().getClassLoader());
        assertThat(RuntimeHintsPredicates.reflection().onType(com.github.dockerjava.api.model.Image.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onConstructor(
                com.github.dockerjava.api.model.Image.class.getDeclaredConstructor())).accepts(hints);
    }
}
