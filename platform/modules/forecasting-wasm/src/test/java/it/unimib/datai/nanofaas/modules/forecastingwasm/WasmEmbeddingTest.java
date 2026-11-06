package it.unimib.datai.nanofaas.modules.forecastingwasm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;

class WasmEmbeddingTest {
    @Test void executesGuestAndClosesRuntime() { assertEquals(42, WasmEmbeddingProbe.add()); }
}
