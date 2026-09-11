package it.unimib.datai.nanofaas.sdk.lite;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.DynamicTest;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SharedSaturationWireCorpusTest {
    @TestFactory
    List<DynamicTest> executesSharedScenariosAgainstRuntimeOwners() throws Exception {
        JsonNode corpus = CorpusTestSupport.load();
        CorpusTestSupport.validate(corpus);
        assertEquals(12, corpus.path("scenarios").size());
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode scenario : corpus.path("scenarios"))
            tests.add(DynamicTest.dynamicTest(scenario.path("id").asText(), () -> executeScenario(
                    scenario, corpus.path("runtimeConfigurations").path(scenario.path("runtimeConfigRef").asText()))));
        return tests;
    }
    static void executeScenario(JsonNode scenario, JsonNode config) throws Exception {
        SaturationRuntimeHarness.run(scenario, config);
    }
}
