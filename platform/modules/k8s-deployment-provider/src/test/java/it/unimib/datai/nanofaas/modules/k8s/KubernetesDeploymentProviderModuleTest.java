package it.unimib.datai.nanofaas.modules.k8s;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

import static org.assertj.core.api.Assertions.assertThat;

class KubernetesDeploymentProviderModuleTest {

    private static final String CONFIGURATION_CLASS = KubernetesDeploymentProviderConfiguration.class.getName();

    @Test
    void autoConfigurationImportDiscoversKubernetesDeploymentProvider() {
        assertThat(ImportCandidates.load(AutoConfiguration.class,
                Thread.currentThread().getContextClassLoader()).getCandidates())
                .contains(CONFIGURATION_CLASS);
    }

}
