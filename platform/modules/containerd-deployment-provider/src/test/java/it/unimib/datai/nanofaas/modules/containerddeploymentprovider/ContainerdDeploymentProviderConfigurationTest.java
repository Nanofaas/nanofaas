package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.spi.ContainerdClient;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ContainerdDeploymentProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContainerdDeploymentProviderConfiguration.class)
            .withBean(ContainerdClient.class, () -> mock(ContainerdClient.class))
            .withPropertyValues(
                    "nanofaas.containerd.socket-path=/run/user/1000/containerd/containerd.sock",
                    "nanofaas.containerd.cni-plugin-directory=/tmp",
                    "nanofaas.containerd.cni-config-directory=/tmp",
                    "nanofaas.containerd.cni-cache-directory=/tmp",
                    "nanofaas.containerd.state-directory=/tmp"
            );

    @Test
    void configuration_registersOnlyContainerdProviderAndValidator() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ContainerRuntimeAdapter.class);
            assertThat(context).hasSingleBean(ManagedDeploymentProvider.class);
            assertThat(context.getBean(ManagedDeploymentProvider.class).backendId()).isEqualTo("containerd");
            assertThat(context).hasSingleBean(ImageValidator.class);
        });
    }

    @Test
    void nonContainerdDefaultDoesNotRegisterContainerdValidator() {
        contextRunner.withPropertyValues("nanofaas.deployment.default-backend=k8s")
                .run(context -> assertThat(context).doesNotHaveBean(ImageValidator.class));
    }
}
