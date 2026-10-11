package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.offload.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.assertThat;

class TransitionQueueRoutingTest {
    @Test
    void originalsUseLocalQueueOnlyWhileAllocationIsChanging() {
        var changing = new AtomicBoolean();
        var settings = OneShotHopGuardE2eTest.settings(3, "http://cloud:8080");
        var router = new PlanRouter(Optional::empty, () -> Optional.of(settings), rev -> Optional.of(settings),
                OneShotHopGuardE2eTest.peers("a", "http://a:8080", "b", "http://b:8080"), () -> 100,
                name -> changing.get() ? Optional.of("a") : Optional.empty());
        var spec = new FunctionSpec("f", "img@" + OneShotHopGuardE2eTest.DIGEST, null, null, null, null,
                null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        var request = new InvocationRequest(Map.of("nested", List.of(1, 2), "payload", "value"), Map.of());
        var task = new InvocationTask("request", "f", spec, request, null, null, Instant.EPOCH, 1, InvocationKind.SYNC);
        assertThat(router.route(task, OffloadContext.none()).executionNode()).isEqualTo("cloud");
        changing.set(true);
        var route = router.route(task, OffloadContext.none());
        assertThat(route.kind()).isEqualTo(PlannedInvocationRoute.Kind.LOCAL);
        assertThat(route.executionNode()).isEqualTo("a");
        changing.set(false);
        assertThat(router.route(task, OffloadContext.none()).executionNode()).isEqualTo("cloud");
    }
}
