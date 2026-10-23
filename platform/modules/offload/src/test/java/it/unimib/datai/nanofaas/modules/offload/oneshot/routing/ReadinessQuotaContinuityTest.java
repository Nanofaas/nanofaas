package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;

import it.unimib.datai.nanofaas.common.model.*;
import it.unimib.datai.nanofaas.controlplane.offload.*;
import it.unimib.datai.nanofaas.controlplane.scheduler.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ActiveRoutingPlan;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.Assignment;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ReadinessQuotaContinuityTest {
    @Test
    void addingAnOutboundReadyGrantDoesNotRefillAnAlreadySpentLocalQuota() {
        var digest = OneShotHopGuardE2eTest.DIGEST;
        var from = Instant.EPOCH;
        var grant = new Assignment("grant", "a", "a-run", "b", "b-run", "f", digest, 7, 3, 1, 1, true);
        var initial = new ActiveRoutingPlan("a", "a-run", 1, 1, from, from.plusSeconds(300), 1,
                Map.of("f", new ActiveRoutingPlan.FunctionPlan(digest, 3, 1, 1, 1, 2, 1, 1, 1, List.of(), List.of())));
        var completed = new ActiveRoutingPlan("a", "a-run", 1, 1, from, from.plusSeconds(300), 1,
                Map.of("f", new ActiveRoutingPlan.FunctionPlan(digest, 3, 1, 1, 0, 2, 1, 1, 1, List.of(), List.of(grant))));
        var current = new AtomicReference<>(initial);
        var config = OneShotHopGuardE2eTest.settings(3, "http://cloud:8080");
        var router = new PlanRouter(() -> Optional.of(current.get()), () -> Optional.of(config), rev -> Optional.of(config),
                OneShotHopGuardE2eTest.peers("a", "http://a:8080", "b", "http://b:8080"), () -> 100);
        var spec = new FunctionSpec("f", "img@" + digest, null, null, null, null, null, null, null,
                null, ExecutionMode.DEPLOYMENT, null, null, null);
        var request = new InvocationRequest(Map.of("nested", List.of(1, 2), "payload", "value"), Map.of());
        var task = new InvocationTask("request", "f", spec, request, null, null, from, 1, InvocationKind.SYNC);
        assertThat(router.route(task, OffloadContext.none()).kind()).isEqualTo(PlannedInvocationRoute.Kind.LOCAL);
        current.set(completed);
        var second = router.route(task, OffloadContext.none());
        assertThat(second.kind()).isEqualTo(PlannedInvocationRoute.Kind.REMOTE);
        assertThat(second.executionNode()).isEqualTo("cloud");
    }
}
