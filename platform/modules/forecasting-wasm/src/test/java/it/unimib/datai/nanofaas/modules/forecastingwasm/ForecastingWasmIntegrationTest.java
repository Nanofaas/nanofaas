package it.unimib.datai.nanofaas.modules.forecastingwasm;

import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.modules.forecasting.ForecastingConfiguration;
import it.unimib.datai.nanofaas.modules.forecasting.TrendForecastSource;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ForecastingWasmIntegrationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(ForecastingConfiguration.class, ForecastingWasmConfiguration.class));
    ApplicationContextRunner enabled() { return runner.withPropertyValues("nanofaas.forecasting.enabled=true",
            "nanofaas.forecasting.node-id=node", "nanofaas.forecasting.window=1s", "nanofaas.forecasting.max-age=1m"); }
    @Test void disabledModuleDoesNotStartGuest() {
        runner.run(context -> { assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(HoltForecastAlgorithm.class); });
    }
    @Test void routesToRealGuestAndSwitchesOnWarmHistory() {
        var now=Instant.parse("2026-11-11T12:00:00Z");
        var clock=mock(Clock.class); when(clock.instant()).thenReturn(now);
        var trend=new TrendForecastSource("node",Duration.ofSeconds(1),Duration.ofMinutes(1),10,60,
                ()->new TrendForecastSource.Holt(.4,.4,.95),clock);
        enabled().withBean("testTrend",TrendForecastSource.class,()->trend,bean->bean.setPrimary(true))
                .withPropertyValues("nanofaas.forecasting.provider=holt_wasm").run(context -> {
            assertThat(context).hasNotFailed();
            var namespace=context.getBean(RuntimeConfigExtension.class);
            assertThat(namespace.snapshot().get("available").toString()).contains("holt_wasm");
            trend.observe("f",1,now);
            var query=new ForecastQuery("node","f",1,now.plusSeconds(1),now.plusSeconds(3));
            var source=context.getBean("selectedForecastSource",ForecastSource.class);
            assertThat(source.forecast(query).status()).isEqualTo(ForecastSnapshot.Status.MISSING);
            trend.record(new ExternalArrival("f",1,now,"request-1"));
            when(clock.instant()).thenReturn(now.plusSeconds(1));
            var wasm=source.forecast(query);
            assertThat(wasm.status()).isEqualTo(ForecastSnapshot.Status.AVAILABLE);
            assertThat(wasm.provider()).isEqualTo("holt_wasm");
            assertThat(wasm.rate()).isEqualTo(1.0);
            namespace.apply(Map.of("provider","holt","holtLevel",0.7));
            var java=source.forecast(query);assertThat(java.provider()).isEqualTo("holt");
            assertThat(java.rate()).isEqualTo(wasm.rate());
            namespace.apply(Map.of("provider","holt_wasm"));
            assertThat(source.forecast(query).provider()).isEqualTo("holt_wasm");
        });
    }
}
