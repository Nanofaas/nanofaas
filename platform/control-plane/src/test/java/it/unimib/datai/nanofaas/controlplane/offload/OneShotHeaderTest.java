package it.unimib.datai.nanofaas.controlplane.offload;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
class OneShotHeaderTest {
    @Test void incompleteNativeMetadataCannotBecomeAnExternalRequest() {
        var context=OffloadContext.fromHttp(null,null,null,Map.of("X-NanoFaaS-Offload-Version",List.of("1")));
        assertThat(context.offloadedHop()).isTrue();assertThat(context.invalidMetadata()).isTrue();
        assertThat(OffloadGateway.noOp().planRoute(null,context).kind()).isEqualTo(PlannedInvocationRoute.Kind.REJECT);
    }
    @Test void cloudMetadataIsTerminalAndLegacyMalformedHopStaysForwarded() {
        var cloud=OffloadContext.fromHttp("1",null,null,Map.of("X-NanoFaaS-Offload-Version",List.of("1"),"X-NanoFaaS-Offload-Origin",List.of("a@inc"),"X-NanoFaaS-Offload-Epoch",List.of("2"),"X-NanoFaaS-Offload-Assignment",List.of("cloud")));
        assertThat(cloud.invalidMetadata()).isFalse();assertThat(OffloadGateway.noOp().planRoute(null,cloud).kind()).isEqualTo(PlannedInvocationRoute.Kind.LOCAL);
        assertThat(OffloadContext.fromHttp("bad",null,null,Map.of()).offloadedHop()).isTrue();
    }
}
