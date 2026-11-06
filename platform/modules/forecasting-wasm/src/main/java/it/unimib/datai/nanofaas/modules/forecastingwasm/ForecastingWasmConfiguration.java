package it.unimib.datai.nanofaas.modules.forecastingwasm;

import it.unimib.datai.nanofaas.modules.forecasting.ForecastingConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

@AutoConfiguration(before = ForecastingConfiguration.class)
@ConditionalOnProperty(prefix = "nanofaas.forecasting", name = "enabled", havingValue = "true")
public class ForecastingWasmConfiguration {
    @Bean(destroyMethod = "close")
    WasmHoltForecastAlgorithm wasmHoltForecastAlgorithm() { return new WasmHoltForecastAlgorithm(); }
}
