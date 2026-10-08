package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;
class OneShotNodeIsolationTest {
    @Test void nodeNamespaceSeparatesContainerNamesAndRecoveryLabels() {
        var properties=new ContainerLocalProperties("docker","127.0.0.1",Duration.ofSeconds(5),Duration.ofMillis(10),null,null,null,"edge-a");
        var provider=new ContainerLocalDeploymentProvider(null,properties,null,null);
        assertThat(provider.containerNamePrefix("f")).isEqualTo("edge-a-nanofaas-f-252f10c83610ebca");
        assertThat(provider.containerFunctionLabel("f")).isEqualTo("edge-a/f");
        assertThatThrownBy(()->new ContainerLocalProperties("docker","127.0.0.1",Duration.ofSeconds(5),Duration.ofMillis(10),null,null,null,"../escape")).isInstanceOf(IllegalArgumentException.class);
    }
}
