package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.CreateContainerCmd;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.StartContainerCmd;
import com.github.dockerjava.api.model.HostConfig;
import it.unimib.datai.nanofaas.common.model.ResourceQuantity;
import it.unimib.datai.nanofaas.common.model.ResourceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ContainerInstanceSpec;
import it.unimib.datai.nanofaas.containerdeployment.ContainerRuntimeAdapter;
import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import it.unimib.datai.nanofaas.controlplane.registry.ImageValidator;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
    @ParameterizedTest
    @CsvSource({
            "docker-java,true", "docker,true", "docker-java,false", "docker,false"
    })
    void strictMemoryPolicyControlsSwapWithoutChangingLegacyLimits(String runtime, boolean strict) {
        var properties = memoryPolicyProperties(runtime, strict);
        var executor = mock(CliCommandExecutor.class);
        var client = mock(DockerClient.class);
        var create = mock(CreateContainerCmd.class,
                RETURNS_SELF);
        var start = mock(StartContainerCmd.class);
        var response = new CreateContainerResponse();
        response.setId("strict-container");
        when(client.createContainerCmd("strict-image")).thenReturn(create);
        when(create.exec()).thenReturn(response);
        when(client.startContainerCmd("strict-container")).thenReturn(start);
        when(executor.run(anyList()))
                .thenReturn(ExecutionResult.success("strict-container"));
        try (var factory = mockStatic(ContainerDeploymentProviderConfiguration.class)) {
            factory.when(ContainerDeploymentProviderConfiguration::createDockerClient).thenReturn(client);
            var adapter = new ContainerDeploymentProviderConfiguration()
                    .containerRuntimeAdapter(properties, executor, () -> 18080);
            adapter.runContainer(memoryPolicySpec(new ResourceSpec(
                    new ResourceQuantity(BigDecimal.ONE, 64),
                    new ResourceQuantity(BigDecimal.ONE, 64))));
            if (runtime.equals("docker-java")) {
                var host = ArgumentCaptor.forClass(HostConfig.class);
                verify(create).withHostConfig(host.capture());
                assertThat(host.getValue().getMemory()).isEqualTo(64L * 1024 * 1024);
                assertThat(host.getValue().getMemorySwap())
                        .isEqualTo(strict ? 64L * 1024 * 1024 : null);
            } else {
                @SuppressWarnings("unchecked")
                var command = (ArgumentCaptor<List<String>>) (Object)
                        ArgumentCaptor.forClass(List.class);
                verify(executor).run(command.capture());
                assertThat(command.getValue()).containsSubsequence("--memory", "64m");
                if (strict) {
                    assertThat(command.getValue()).containsSubsequence("--memory-swap", "64m");
                } else {
                    assertThat(command.getValue()).doesNotContain("--memory-swap");
                }
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"docker-java", "docker"})
    void strictMemoryRejectsMissingLimitBeforeAllocatingOrCreating(String runtime) {
        var executor = mock(CliCommandExecutor.class);
        var allocator = mock(PortAllocator.class);
        var client = mock(DockerClient.class);
        try (var factory = mockStatic(ContainerDeploymentProviderConfiguration.class)) {
            factory.when(ContainerDeploymentProviderConfiguration::createDockerClient).thenReturn(client);
            var adapter = new ContainerDeploymentProviderConfiguration()
                    .containerRuntimeAdapter(memoryPolicyProperties(runtime, true), executor, allocator);
            ResourceSpec[] invalid = {
                    null,
                    new ResourceSpec(new ResourceQuantity(BigDecimal.ONE, 64), null),
                    new ResourceSpec(null, new ResourceQuantity(BigDecimal.ONE, null)),
                    new ResourceSpec(null, new ResourceQuantity(BigDecimal.ONE, 0))
            };
            for (var resources : invalid) {
                assertThatThrownBy(() -> adapter.runContainer(memoryPolicySpec(resources)))
                        .isInstanceOf(IllegalArgumentException.class)
                        .hasMessageContaining("memory");
                verifyNoInteractions(executor, allocator, client);
            }
        }
    }

    @Test
    void strictMemoryIsDisabledWhenThePropertyIsAbsent() {
        contextRunner.run(context -> assertThat(context.getBean(ContainerLocalProperties.class)
                .strictMemory()).isFalse());
    }

    private static ContainerLocalProperties memoryPolicyProperties(String runtime, boolean strict) {
        var source = new MapConfigurationPropertySource(
                Map.of("nanofaas.container-local.runtime-adapter", runtime,
                        "nanofaas.container-local.strict-memory", Boolean.toString(strict)));
        return new Binder(source)
                .bind("nanofaas.container-local", Bindable
                        .of(ContainerLocalProperties.class)).get();
    }

    private static ContainerInstanceSpec memoryPolicySpec(
            ResourceSpec resources) {
        return new ContainerInstanceSpec(
                "nanofaas-strict-r1", "strict-image", List.of(), Map.of(), resources,
                Map.of());
    }

}
