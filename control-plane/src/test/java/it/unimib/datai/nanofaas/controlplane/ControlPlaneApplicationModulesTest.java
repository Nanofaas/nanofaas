package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ControlPlaneApplicationModulesTest {

    @Test
    void importSelectorDiscoversTestModuleConfiguration() {
        ControlPlaneModuleImportSelector selector = new ControlPlaneModuleImportSelector();
        String[] imports = selector.selectImports(null);

        assertThat(imports).contains(TestModuleConfiguration.class.getName());
    }
}
