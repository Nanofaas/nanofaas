package it.unimib.datai.nanofaas.controlplane.e2e;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuildpackE2eCommandTest {

    @Test
    void buildCommandUsesWrapperForControlPlaneImage() {
        List<String> command = BuildpackE2eTest.controlPlaneImageCommand(false);

        assertThat(command).contains("./scripts/control-plane-build.sh", "image", "--profile", "all");
        assertThat(command).contains("-PcontrolPlaneImage=nanofaas/control-plane:buildpack");
    }
}
