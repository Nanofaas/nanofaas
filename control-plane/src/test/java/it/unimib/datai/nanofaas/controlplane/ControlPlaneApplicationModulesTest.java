package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneApplicationModulesTest {

    @Test
    void applicationSourcesIncludesDiscoveredModuleConfigurations() {
        Set<String> sources = ControlPlaneApplication.applicationSources(Thread.currentThread().getContextClassLoader());

        assertThat(sources).contains(ControlPlaneApplication.class.getName());
        assertThat(sources).contains(TestModuleConfiguration.class.getName());
    }
}
