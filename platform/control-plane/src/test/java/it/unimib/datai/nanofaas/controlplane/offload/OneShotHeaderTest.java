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
    @Test void differentlyCasedDuplicateHopFieldsCannotBecomeValidNativeMetadata() {
        var headers=new LinkedHashMap<String,List<String>>();
        headers.put("X-NanoFaaS-Offload-Version",List.of("1"));
        headers.put("X-NanoFaaS-Offload-Origin",List.of("a@inc"));
        headers.put("X-NanoFaaS-Offload-Epoch",List.of("2"));
        headers.put("X-NanoFaaS-Offload-Assignment",List.of("grant"));
        headers.put("X-NanoFaaS-Offload-Hop",List.of("1","1"));
        headers.put("x-nanofaas-offload-hop",List.of("1"));
        assertThat(OffloadContext.fromHttp("1",null,null,headers).invalidMetadata()).isTrue();
        headers.put("X-NanoFaaS-Offload-Hop",List.of("1"));
        assertThat(OffloadContext.fromHttp("1",null,null,headers).invalidMetadata()).isTrue();
    }
}
