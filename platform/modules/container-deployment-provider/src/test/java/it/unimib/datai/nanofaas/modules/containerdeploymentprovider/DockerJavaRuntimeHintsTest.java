package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.model.HostConfig;
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
}
