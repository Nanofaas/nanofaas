package it.unimib.datai.nanofaas.modules.forecastingwasm;

import it.unimib.datai.nanofaas.forecastingapi.*;
import it.unimib.datai.nanofaas.controlplane.config.RuntimeConfigExtension;
import it.unimib.datai.nanofaas.modules.forecasting.ForecastingConfiguration;
import it.unimib.datai.nanofaas.modules.forecasting.TrendForecastSource;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class ForecastingWasmIntegrationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner().withConfiguration(
            AutoConfigurations.of(ForecastingConfiguration.class, ForecastingWasmConfiguration.class));
    ApplicationContextRunner enabled() { return runner.withPropertyValues("nanofaas.forecasting.enabled=true",
            "nanofaas.forecasting.node-id=node", "nanofaas.forecasting.window=1s", "nanofaas.forecasting.max-age=1m"); }
    @Test void disabledModuleDoesNotStartGuest() {
        runner.run(context -> { assertThat(context).hasNotFailed(); assertThat(context).doesNotHaveBean(HoltForecastAlgorithm.class); });
    }
    @Test void routesToRealGuestAndSwitchesOnWarmHistory() {
        enabled().withPropertyValues("nanofaas.forecasting.provider=holt_wasm").run(context -> {
            assertThat(context).hasNotFailed();
            var namespace=context.getBean(RuntimeConfigExtension.class);
            assertThat(namespace.snapshot().get("available").toString()).contains("holt_wasm");
            var now=Instant.now();
            context.getBean(TrendForecastSource.class).observe("f",1,Instant.ofEpochSecond(now.getEpochSecond()-2));
            var query=new ForecastQuery("node","f",1,now.plusSeconds(1),now.plusSeconds(3));
            var source=context.getBean(ForecastSource.class);
            var wasm=source.forecast(query);
            assertThat(wasm.status()).isEqualTo(ForecastSnapshot.Status.AVAILABLE);
            assertThat(wasm.provider()).isEqualTo("holt_wasm");
            namespace.apply(Map.of("provider","holt","holtLevel",0.7));
            var java=source.forecast(query);assertThat(java.provider()).isEqualTo("holt");
            assertThat(java.rate()).isEqualTo(wasm.rate());
            namespace.apply(Map.of("provider","holt_wasm"));
            assertThat(source.forecast(query).provider()).isEqualTo("holt_wasm");
        });
    }
}
