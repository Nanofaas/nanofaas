package it.unimib.datai.nanofaas.modules.k8s.e2e;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class K8sE2eScenarioManifestTest {

    @Test
    void loadFromSystemProperty_parsesSelectedFunctionsAndTargets() throws Exception {
        Path manifest = Files.createTempFile("nanofaas-k8s-manifest", ".json");
        Files.writeString(
                manifest,
                """
                {
                  "name": "k8s-demo-java",
                  "baseScenario": "k8s-vm",
                  "runtime": "java",
                  "namespace": "nanofaas-e2e-alt",
                  "functions": [
                    {
                      "key": "word-stats-java",
                      "family": "word-stats",
                      "runtime": "java",
                      "image": "localhost:5000/nanofaas/java-word-stats:e2e",
                      "payloadPath": "/tmp/word-stats.json"
                    }
                  ],
                  "payloads": {
                    "word-stats-java": "/tmp/word-stats.json"
                  },
                  "load": {
                    "profile": "quick",
                    "targets": ["word-stats-java"]
                  }
                }
                """
        );

        String original = System.getProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME);
        System.setProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME, manifest.toString());
        try {
            var loaded = K8sE2eScenarioManifest.loadFromSystemProperty();

            assertTrue(loaded.isPresent());
            assertEquals("nanofaas-e2e-alt", loaded.get().namespaceOr("nanofaas-e2e"));
            assertEquals(List.of("word-stats-java"), loaded.get().loadTargets());
            assertEquals("word-stats-java", loaded.get().selectedFunctions().getFirst().key());
            assertEquals(
                    "/tmp/word-stats.json",
                    loaded.get().payloadPathFor("word-stats-java").orElseThrow()
            );
        } finally {
            if (original == null) {
                System.clearProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME);
            } else {
                System.setProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME, original);
            }
        }
    }

    @Test
    void loadFromSystemProperty_returnsEmptyWhenUnset() {
        String original = System.getProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME);
        System.clearProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME);
        try {
            assertFalse(K8sE2eScenarioManifest.loadFromSystemProperty().isPresent());
        } finally {
            if (original != null) {
                System.setProperty(K8sE2eScenarioManifest.SYSTEM_PROPERTY_NAME, original);
            }
        }
    }
}
