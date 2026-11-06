package it.unimib.datai.nanofaas.modules.forecastingwasm;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

public final class ForecastingWasmRuntimeHints implements RuntimeHintsRegistrar {
    @Override public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("wasm/holt-v1.wasm");
    }
}
