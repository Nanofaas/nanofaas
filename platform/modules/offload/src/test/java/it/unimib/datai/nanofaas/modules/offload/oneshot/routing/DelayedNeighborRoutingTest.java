package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.offload.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ActiveRoutingPlan;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.assertThat;

class DelayedNeighborRoutingTest {
    private static final String DIGEST = OneShotHopGuardE2eTest.DIGEST;

    private PlanRouter router() {
        var settings = OneShotHopGuardE2eTest.settings(3, "http://cloud:8080");
        var plan = new ActiveRoutingPlan("a", "a-run", 2, 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(360), 1,
                Map.of("f", new ActiveRoutingPlan.FunctionPlan(DIGEST, 3, 1, 0, 1, 1, 64, 1, .8, List.of(), List.of())));
        return new PlanRouter(() -> Optional.of(plan), () -> Optional.of(settings), rev -> Optional.of(settings),
                OneShotHopGuardE2eTest.peers("a", "http://a:8080", "b", "http://b:8080"), () -> 100);
    }

    private InvocationTask task() {
        var spec = new FunctionSpec("f", "img@" + DIGEST, null, null, null, null,
                null, null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
        var request = new InvocationRequest(Map.of("nested", List.of(1, 2), "payload", "value"), Map.of());
        return new InvocationTask("request", "f", spec, request, null, null, Instant.EPOCH, 1, InvocationKind.SYNC);
    }

    private OffloadContext incoming(long epoch, String origin, String incarnation) {
        return new OffloadContext(true, null, null, new OffloadContext.Metadata(origin, incarnation, epoch, "old-grant"), false);
    }

    @Test
    void delayedNeighborRequestUsesCurrentLocalAdmissionWithoutANewGrant() {
        var route = router().route(task(), incoming(1, "b", "b-run"));
        assertThat(route.kind()).isEqualTo(PlannedInvocationRoute.Kind.LOCAL);
        assertThat(route.executionNode()).isEqualTo("a");
    }

    @Test
    void readinessPublishedDuringRoutingDoesNotRejectTheDelayedRequest() {
        var settings = OneShotHopGuardE2eTest.settings(3, "http://cloud:8080");
        var ready = new ActiveRoutingPlan("a", "a-run", 2, 1, Instant.EPOCH, Instant.EPOCH.plusSeconds(360), 1,
                Map.of("f", new ActiveRoutingPlan.FunctionPlan(DIGEST, 3, 1, 0, 1, 1, 64, 1, .8, List.of(), List.of())));
        var current = new AtomicReference<ActiveRoutingPlan>();
        var router = new PlanRouter(() -> Optional.ofNullable(current.get()), () -> Optional.of(settings), rev -> Optional.of(settings),
                OneShotHopGuardE2eTest.peers("a", "http://a:8080", "b", "http://b:8080"), () -> 100,
                name -> {
                    // Publication races the router's initial plan read.
                    current.set(ready);
                    return Optional.empty();
                });
        assertThat(router.route(task(), incoming(1, "b", "b-run")).kind()).isEqualTo(PlannedInvocationRoute.Kind.LOCAL);
    }

    @Test
    void delayedRequestsStillRequireCurrentNeighborIncarnationAndValidWorkload() {
        var router = router();
        assertThat(router.route(task(), incoming(1, "unknown", "b-run")).kind()).isEqualTo(PlannedInvocationRoute.Kind.REJECT);
        assertThat(router.route(task(), incoming(1, "b", "retired-run")).kind()).isEqualTo(PlannedInvocationRoute.Kind.REJECT);
        var task = task();
        var differentInput = new InvocationTask("bad-input", "f", task.functionSpec(),
                new InvocationRequest(Map.of("different", true), Map.of()), null, null, Instant.EPOCH, 1, InvocationKind.SYNC);
        assertThat(router.route(differentInput, incoming(1, "b", "b-run")).kind()).isEqualTo(PlannedInvocationRoute.Kind.REJECT);
    }

    @Test
    void futureOrCurrentEpochStillRequiresItsMatchingReadyGrant() {
        var router = router();
        assertThat(router.route(task(), incoming(2, "b", "b-run")).kind()).isEqualTo(PlannedInvocationRoute.Kind.REJECT);
        assertThat(router.route(task(), incoming(3, "b", "b-run")).kind()).isEqualTo(PlannedInvocationRoute.Kind.REJECT);
    }
}
