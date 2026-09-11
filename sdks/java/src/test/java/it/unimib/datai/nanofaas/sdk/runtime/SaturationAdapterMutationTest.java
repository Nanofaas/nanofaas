package it.unimib.datai.nanofaas.sdk.runtime;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SaturationAdapterMutationTest {
    @ParameterizedTest(name = "structurally valid {0} mutation must fail runtime comparison")
    @ValueSource(strings = {"status", "outcome", "callback", "attempts", "counters", "observations"})
    void rejectsSelfConsistentCorpusMutations(String mutation) throws Exception {
        var corpus = CorpusTestSupport.load();
        CorpusTestSupport.mutate(corpus, mutation);
        CorpusTestSupport.validate(corpus);
        var scenario = CorpusTestSupport.scenario(corpus, "success-drain");
        var config = corpus.path("runtimeConfigurations").path(scenario.path("runtimeConfigRef").asText());
        AssertionError failure = assertThrows(AssertionError.class,
                () -> SharedSaturationWireCorpusTest.executeScenario(scenario, config));
        assertTrue(failure.getMessage().contains("runtime mismatch"), failure::getMessage);
    }
}
