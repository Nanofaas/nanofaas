package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import static org.assertj.core.api.Assertions.*;

class ImageInventoryNativeHintsTest {
    @Test void nativeImageInspectionCanBindOverlayStorageData() throws Exception {
        var hints = new RuntimeHints();
        new DockerJavaRuntimeHints().registerHints(hints, getClass().getClassLoader());
        var type = com.github.dockerjava.api.command.GraphData.class;
        assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(type.getDeclaredConstructor()))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onType(type)
                .withMemberCategory(org.springframework.aot.hint.MemberCategory.ACCESS_DECLARED_FIELDS))
                .accepts(hints);
    }
    @Test void nativeClientCanReadAnExistingDockerConfigurationFile() throws Exception {
        var hints = new RuntimeHints();
        new DockerJavaRuntimeHints().registerHints(hints, getClass().getClassLoader());
        for (Class<?> type : new Class<?>[]{com.github.dockerjava.core.DockerConfigFile.class,
                com.github.dockerjava.api.model.AuthConfig.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(type.getDeclaredConstructor()))
                    .accepts(hints);
        }
        assertThat(RuntimeHintsPredicates.reflection().onMethodInvocation(
                com.github.dockerjava.core.DockerConfigFile.class.getDeclaredMethod("setCurrentContext", String.class)))
                .accepts(hints);
    }
    @Test void immutableImageInspectionModelsHaveNativeBindingMetadata() throws Exception {
        var hints=new RuntimeHints();new DockerJavaRuntimeHints().registerHints(hints,getClass().getClassLoader());
        for(var type:new Class<?>[]{com.github.dockerjava.api.command.InspectImageResponse.class,com.github.dockerjava.api.command.GraphDriver.class,com.github.dockerjava.api.command.RootFS.class,com.github.dockerjava.api.model.ContainerConfig.class,com.github.dockerjava.api.model.HealthCheck.class}) {
            assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(type.getDeclaredConstructor())).accepts(hints);
        }
    }
    @Test void registersTheDockerImageDeserializationDto() throws Exception {
        var hints = new RuntimeHints();
        new DockerJavaRuntimeHints().registerHints(hints, getClass().getClassLoader());
        assertThat(RuntimeHintsPredicates.reflection().onType(com.github.dockerjava.api.model.Image.class))
                .accepts(hints);
        assertThat(RuntimeHintsPredicates.reflection().onConstructorInvocation(
                com.github.dockerjava.api.model.Image.class.getDeclaredConstructor())).accepts(hints);
    }
}
