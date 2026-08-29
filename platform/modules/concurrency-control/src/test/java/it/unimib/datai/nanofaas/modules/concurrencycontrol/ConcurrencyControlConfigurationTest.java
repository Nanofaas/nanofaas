package it.unimib.datai.nanofaas.modules.concurrencycontrol;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The module is activated only when a queue provider supplies both workload readings and a
 * capacity controller.
 */
class ConcurrencyControlConfigurationTest {

    @Test
    void configurationRunsAfterEitherQueueProvider() {
        assertThat(ConcurrencyControlConfiguration.class.getAnnotation(AutoConfigureAfter.class).name())
                .containsExactlyInAnyOrder(
                        "it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration",
                        "it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration"
                );
    }

    @Test
    void configurationClassCanBeLoadedWithoutProviderBeans() {
        assertThatCode(ConcurrencyControlConfiguration::new)
                .doesNotThrowAnyException();
    }
}
