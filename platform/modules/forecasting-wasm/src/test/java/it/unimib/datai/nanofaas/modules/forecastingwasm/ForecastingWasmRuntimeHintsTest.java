package it.unimib.datai.nanofaas.modules.forecastingwasm;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForecastingWasmRuntimeHintsTest {
    @Test void includesBundledGuestInNativeImage() {
        var hints = new RuntimeHints();
        new ForecastingWasmRuntimeHints().registerHints(hints, getClass().getClassLoader());
        assertTrue(RuntimeHintsPredicates.resource().forResource("wasm/holt-v1.wasm").test(hints));
    }
}
