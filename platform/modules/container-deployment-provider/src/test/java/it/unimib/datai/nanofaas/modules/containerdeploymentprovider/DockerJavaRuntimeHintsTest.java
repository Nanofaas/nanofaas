package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerHostConfig;
import com.github.dockerjava.api.model.ContainerMount;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ContainerNetworkSettings;
import com.github.dockerjava.api.model.ContainerPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PullResponseItem;
import com.github.dockerjava.api.model.ResponseItem;
import com.github.dockerjava.core.command.CreateContainerCmdImpl;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

class DockerJavaRuntimeHintsTest {

    @Test
    void registerHints_exposesDockerCreateRequestToJackson() {
        RuntimeHints hints = new RuntimeHints();

        new DockerJavaRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.reflection()
                .onType(CreateContainerCmdImpl.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(HostConfig.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
    }

    @Test
    void registerHints_exposesImagePullResponseToJackson() {
        RuntimeHints hints = new RuntimeHints();

        new DockerJavaRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : new Class<?>[]{
                PullResponseItem.class,
                ResponseItem.class,
                ResponseItem.ProgressDetail.class,
                ResponseItem.ErrorDetail.class,
                ResponseItem.AuxDetail.class
        }) {
            assertThat(RuntimeHintsPredicates.reflection()
                    .onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS)
                    .test(hints))
                    .as("reflection hints for %s", type.getSimpleName())
                    .isTrue();
        }
    }

    @Test
    void registerHints_exposesContainerDiscoveryToJackson() {
        RuntimeHints hints = new RuntimeHints();

        new DockerJavaRuntimeHints().registerHints(hints, getClass().getClassLoader());

        for (Class<?> type : new Class<?>[]{
                Container.class,
                ContainerPort.class,
                ContainerHostConfig.class,
                ContainerNetworkSettings.class,
                ContainerNetwork.class,
                ContainerNetwork.Ipam.class,
                ContainerMount.class
        }) {
            assertThat(RuntimeHintsPredicates.reflection()
                    .onType(type)
                    .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                    .test(hints))
                    .as("reflection hints for %s", type.getSimpleName())
                    .isTrue();
        }
    }
}
