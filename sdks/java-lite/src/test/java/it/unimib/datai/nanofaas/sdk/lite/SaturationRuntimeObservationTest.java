package it.unimib.datai.nanofaas.sdk.lite;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SaturationRuntimeObservationTest {
    @Test
    void outputCounterObservesRealOutputBeforeDraining() throws Exception {
        var corpus = CorpusTestSupport.load();
        var scenario = CorpusTestSupport.scenario(corpus, "success-drain");
        var config = corpus.path("runtimeConfigurations").path(scenario.path("runtimeConfigRef").asText());
        try (var harness = new SaturationRuntimeHarness(scenario, config)) {
            harness.execute();
            assertTrue(harness.peakOutput.get() > 0, "must observe bytes returned by the real handler");
            assertEquals(0, harness.retainedOutput.get());
        }
    }
}
