package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyControlModuleTest {

    @Test
    void moduleIsDiscoverableThroughTheServiceLoader() {
        boolean discovered = StreamSupport
                .stream(ServiceLoader.load(ControlPlaneModule.class).spliterator(), false)
                .anyMatch(ConcurrencyControlModule.class::isInstance);

        assertThat(discovered).isTrue();
    }

    @Test
    void moduleExposesItsConfiguration() {
        assertThat(new ConcurrencyControlModule().configurationClasses())
                .containsExactly(ConcurrencyControlConfiguration.class);
    }
}
