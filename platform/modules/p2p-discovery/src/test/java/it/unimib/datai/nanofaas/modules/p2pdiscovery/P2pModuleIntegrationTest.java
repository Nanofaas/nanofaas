package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class P2pModuleIntegrationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withUserConfiguration(P2pConfiguration.class);

    @Test
    void inertByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(P2pService.class);
            assertThat(ctx.getBean(P2pService.class).isRunning()).isFalse();
                    });
    }

    @Test
    void adminControllerAndGateAreAlwaysRegisteredSoNativeImagesCarryThem() {
        // @ConditionalOnProperty is resolved at Spring AOT time, so the switch is enforced at request time by P2pAdminGate
        runner.run(ctx -> {
            assertThat(ctx).hasSingleBean(P2pAdminController.class);
            assertThat(ctx).hasSingleBean(P2pAdminGate.class);
        });
    }
}
