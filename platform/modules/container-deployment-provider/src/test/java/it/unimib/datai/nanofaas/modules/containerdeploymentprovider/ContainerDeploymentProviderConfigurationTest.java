package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ContainerDeploymentProviderConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ContainerDeploymentProviderConfiguration.class)
            .withPropertyValues(
                    "nanofaas.container-local.runtime-adapter=podman",
                    "nanofaas.container-local.bind-host=127.0.0.1"
            );

    @Test
    void configuration_registersRuntimeAdapterAndManagedProvider() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(ContainerRuntimeAdapter.class);
            assertThat(context.getBean(ContainerRuntimeAdapter.class))
                    .isInstanceOf(CliContainerRuntimeAdapter.class);
            assertThat(context).hasSingleBean(ManagedDeploymentProvider.class);
            assertThat(context).hasSingleBean(ContainerLocalDeploymentProvider.class);
            assertThat(context.getBean(ContainerLocalDeploymentProvider.class).backendId())
                    .isEqualTo("container-local");
        });
    }

    @Test
    void configuration_selectsDockerJavaRuntimeAdapterExplicitly() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContainerDeploymentProviderConfiguration.class)
                .withPropertyValues(
                        "nanofaas.container-local.runtime-adapter=docker-java",
                        "nanofaas.container-local.bind-host=127.0.0.1",
                        "nanofaas.container-local.network-name=nanofaas"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ContainerRuntimeAdapter.class);
                    assertThat(context.getBean(ContainerRuntimeAdapter.class))
                            .isInstanceOf(DockerJavaContainerRuntimeAdapter.class);
                    assertThat(context.getBean(ContainerLocalProperties.class).networkName())
                            .isEqualTo("nanofaas");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker", "podman", "nerdctl"})
    void configuration_rejectsCliNetworkBeforeAllocatingPortsOrRunningCommands(String runtime) {
        ContainerLocalProperties properties = new ContainerLocalProperties(
                runtime, "127.0.0.1", null, null, null, "nanofaas");
        CliCommandExecutor executor = mock(CliCommandExecutor.class);
        PortAllocator allocator = mock(PortAllocator.class);
        ContainerDeploymentProviderConfiguration configuration = new ContainerDeploymentProviderConfiguration();

        assertThatThrownBy(() -> configuration.containerRuntimeAdapter(properties, executor, allocator))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("network-name")
                .hasMessageContaining("docker-java");
        verifyNoInteractions(executor, allocator);
    }

    /** application.yml sets default-backend to "": container-local is then the only provider this build has. */
    @ParameterizedTest
    @ValueSource(strings = {"", " ", "container-local", "Container-Local"})
    void anUnsetOrOwnBackendRegistersTheDockerImageValidator(String backend) {
        contextRunner.withPropertyValues("nanofaas.deployment.default-backend=" + backend)
                .run(context -> assertThat(context.getBean(ImageValidator.class)).isInstanceOf(DockerImageValidator.class));
    }

    @Test
    void k8sBackendDoesNotRegisterTheDockerImageValidator() {
        new ApplicationContextRunner()
                .withUserConfiguration(ContainerDeploymentProviderConfiguration.class)
                .withPropertyValues(
                        "nanofaas.deployment.default-backend=k8s",
                        "nanofaas.container-local.runtime-adapter=podman",
                        "nanofaas.container-local.bind-host=127.0.0.1"
                )
                .run(context -> assertThat(context).doesNotHaveBean(ImageValidator.class));
    }
}
