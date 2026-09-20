package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import it.unimib.datai.nanofaas.controlplane.deployment.PartialDeprovisionException;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.HashMap;
import java.util.concurrent.*;

import static it.unimib.datai.nanofaas.modules.containerddeploymentprovider.RecoveryFixture.*;
import static org.assertj.core.api.Assertions.*;

class ContainerdDeprovisionRecoveryTest {
    @Test
    void delFailureRetainsOwnershipAcrossRestartAndRepeatedDeprovision() {
        RecoveryFixture state = new RecoveryFixture();
        try (var first = state.open()) {
            first.provider().provision(spec());
            state.removalFailures.put(id(1), 1);
            assertThatThrownBy(() -> first.provider().deprovision("echo"))
                    .isInstanceOf(PartialDeprovisionException.class);
        }
        assertThat(state.daemon).isEmpty();
        assertThat(state.pending).containsKey(id(1));
        try (var restarted = state.open()) {
            restarted.provider().deprovision("echo");
            assertThat(restarted.adapter().listManagedContainers("echo")).isEmpty();
            assertThat(state.pending).isEmpty();
            assertThat(state.attachments).isEmpty();
            assertThat(state.snapshots).isEmpty();
            state.events.clear();
            restarted.provider().deprovision("echo");
            assertThat(state.events).isEmpty();
        }
    }

    @Test
    void everyReplicaIsAttemptedAndProxyClosesOnPartialDeletion() throws Exception {
        RecoveryFixture state = new RecoveryFixture();
        try (var session = state.open(); HttpClient http = HttpClient.newHttpClient()) {
            String endpoint = session.provider().provision(spec()).endpointUrl();
            session.provider().setReplicas("echo", 3);
            state.removalFailures.put(id(1), 1);
            state.removalFailures.put(id(3), 1);
            assertThatThrownBy(() -> session.provider().deprovision("echo"))
                    .isInstanceOfSatisfying(PartialDeprovisionException.class, failure -> {
                        assertThat(failure.remainingResources()).containsExactlyInAnyOrder(id(1), id(3));
                        assertThat(failure.getSuppressed()).hasSize(1);
                    });
            assertThat(state.events).contains("remove " + id(1), "remove " + id(2), "remove " + id(3));
            assertThatThrownBy(() -> http.send(HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(1)).POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build(), HttpResponse.BodyHandlers.discarding())).isInstanceOf(java.io.IOException.class);
            state.events.clear();
            session.provider().setReplicas("echo", 4);
            assertThat(state.events).isEmpty();
        }
    }

    @Test
    void pendingAndLiveRecordsAreDeduplicatedAndForeignJournalsAreUntouched() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        state.pending.put(id(1), state.daemon.get(id(1)));
        state.pending.put("foreign-r1", container("foreign-r1", labels("other", 1)));
        var labels = new HashMap<>(labels("echo", 1));
        labels.put("io.nanofaas.backend", "other");
        state.pending.put("another-r1", container("another-r1", labels));
        try (var restarted = state.open()) {
            restarted.provider().deprovision("echo");
            assertThat(state.events).containsExactly("remove " + id(1));
            assertThat(state.pending.keySet()).containsExactlyInAnyOrder("foreign-r1", "another-r1");
        }
    }

    @Test
    void unrelatedOwnershipConflictDoesNotBlockCleanupOfThisFunction() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        state.pending.put("foreign-r1", container("foreign-r1", labels("other", 1)));
        state.daemon.put("foreign-r1", container("foreign-r1", labels("another", 1)));
        try (var restarted = state.open()) {
            restarted.provider().deprovision("echo");
            assertThat(state.events).containsExactly("remove " + id(1));
            assertThat(state.pending).containsKey("foreign-r1");
            assertThat(state.daemon).containsKey("foreign-r1");
        }
    }

    @Test
    void snapshotOnlyJournalIsDiscoveredAndCleaned() {
        RecoveryFixture state = new RecoveryFixture();
        state.pending.put(id(1), container(id(1), labels("echo", 1)));
        state.snapshots.add(id(1));
        try (var restarted = state.open()) {
            restarted.provider().deprovision("echo");
            assertThat(state.pending).isEmpty();
            assertThat(state.snapshots).isEmpty();
        }
    }

    @Test
    void failedStartAfterIpAllocationRemainsRecoverableWhenCleanupAlsoFails() {
        RecoveryFixture state = new RecoveryFixture();
        state.failStart = true;
        state.removalFailures.put(id(1), 2); // adapter rollback and shared lifecycle rollback
        try (var first = state.open()) {
            assertThatThrownBy(() -> first.provider().provision(spec()))
                    .hasMessageContaining("start after IP allocation");
        }
        assertThat(state.attachments).containsKey(id(1));
        try (var restarted = state.open()) {
            restarted.provider().deprovision("echo");
            assertThat(state.pending).isEmpty();
            assertThat(state.attachments).isEmpty();
            assertThat(state.snapshots).isEmpty();
        }
    }

    @Test
    void pendingOwnershipCannotAuthorizeRemovalOfConflictingLiveContainer() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        state.pending.put(id(1), state.daemon.get(id(1)));
        state.daemon.put(id(1), container(id(1), labels("other", 1)));
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.adapter().removeContainer(id(1)))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ownership");
            assertThat(state.events).isEmpty();
        }
    }

    @Test
    void daemonListFailureIsReportedAsPartialCleanup() {
        RecoveryFixture state = new RecoveryFixture();
        state.listFailure = new IllegalStateException("daemon timeout");
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.provider().deprovision("echo"))
                    .isInstanceOf(PartialDeprovisionException.class).hasCause(state.listFailure);
        }
    }

    @Test
    void scalingWaitsForConcurrentRemovalAndCannotRecreatePendingReplicas() throws Exception {
        RecoveryFixture state = new RecoveryFixture();
        try (var session = state.open(); var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            session.provider().provision(spec());
            state.removalFailures.put(id(1), 1);
            state.removeEntered = new CountDownLatch(1);
            state.allowRemove = new CountDownLatch(1);
            Future<?> deletion = executor.submit(() -> session.provider().deprovision("echo"));
            assertThat(state.removeEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<?> scaling = executor.submit(() -> session.provider().setReplicas("echo", 3));
            state.allowRemove.countDown();
            assertThatThrownBy(() -> deletion.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(PartialDeprovisionException.class);
            scaling.get(5, TimeUnit.SECONDS);
            assertThat(state.events).doesNotContain("create " + id(2), "create " + id(3));
            assertThat(state.pending).containsKey(id(1));
        } finally {
            if (state.allowRemove != null) state.allowRemove.countDown();
        }
    }
}
