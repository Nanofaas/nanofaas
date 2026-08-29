package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The module is inert without a queue module, and used to be inert silently: the core registers a
 * no artificial no-op source is registered by the core; the configuration is activated only when
 * a queue provider supplies both workload readings and a capacity controller.
 */
class ConcurrencyControlConfigurationTest {

    @Test
    void startsWithoutAnArtificialNoOpSource() {
        assertThatCode(ConcurrencyControlConfiguration::new)
                .doesNotThrowAnyException();
    }
}
