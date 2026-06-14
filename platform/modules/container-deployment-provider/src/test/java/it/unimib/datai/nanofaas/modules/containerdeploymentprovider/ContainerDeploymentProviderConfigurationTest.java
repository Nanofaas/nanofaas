package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import it.unimib.datai.nanofaas.controlplane.deployment.ManagedDeploymentProvider;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

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
            assertThat(context).hasSingleBean(ManagedDeploymentProvider.class);
            assertThat(context).hasSingleBean(ContainerLocalDeploymentProvider.class);
            assertThat(context.getBean(ContainerLocalDeploymentProvider.class).backendId())
                    .isEqualTo("container-local");
        });
    }
}
