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
    void doesNotImposeAnOrderingOnOneQueueProvider() {
        assertThat(ConcurrencyControlConfiguration.class.getAnnotation(AutoConfigureAfter.class))
                .isNull();
    }

    @Test
    void configurationClassCanBeLoadedWithoutProviderBeans() {
        assertThatCode(ConcurrencyControlConfiguration::new)
                .doesNotThrowAnyException();
    }
}
