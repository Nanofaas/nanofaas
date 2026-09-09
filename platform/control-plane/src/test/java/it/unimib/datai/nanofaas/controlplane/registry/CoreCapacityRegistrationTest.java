package it.unimib.datai.nanofaas.controlplane.registry;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class CoreCapacityRegistrationTest {
    @Test
    void coreAloneTracksUpdatesAndRemovalWithoutOldTasksResettingTheGovernor() {
        new ApplicationContextRunner().withUserConfiguration(RegistryDefaultsConfiguration.class).run(context -> {
            var capacity = context.getBean(FunctionCapacityRegistry.class);
            var lifecycle = context.getBean(FunctionRegistrationListener.class);
            lifecycle.onRegister(spec(4));
            capacity.setEffectiveConcurrency("fn", 2);
            var first = capacity.tryAcquireLease("fn", 100); // stale task configuration
            assertThat(capacity.configuredConcurrency("fn")).isEqualTo(4);
            assertThat(capacity.effectiveConcurrency("fn")).isEqualTo(2);
            lifecycle.onRegister(spec(1));
            assertThat(capacity.tryAcquireLease("fn", 100)).isNull();
            var old = capacity.state("fn");
            lifecycle.onRemove("fn");
            lifecycle.onRegister(spec(1));
            var fresh = capacity.tryAcquireLease("fn", 100);
            first.release();
            assertThat(old.inFlight()).isZero();
            assertThat(capacity.inFlight("fn")).isEqualTo(1);
            fresh.release();
            assertThat(capacity.inFlight("fn")).isZero();
        });
    }
    private static FunctionSpec spec(int concurrency) {
        return new FunctionSpec("fn", "img", List.of(), Map.of(), null, 10000,
                concurrency, 100, 0, null, ExecutionMode.LOCAL, null, null, null);
    }
}
