package it.unimib.datai.nanofaas.modules.k8s.e2e;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class K8sE2eScenarioManifestCommandTest {

    @Test
    void systemPropertyArgumentRendersJvmFlag() {
        assertEquals(
                "-Dnanofaas.e2e.scenarioManifest=/home/ubuntu/nanofaas/tools/controlplane/runs/manifests/demo.json",
                K8sE2eScenarioManifest.systemPropertyArgument(
                        "/home/ubuntu/nanofaas/tools/controlplane/runs/manifests/demo.json")
        );
    }
}
