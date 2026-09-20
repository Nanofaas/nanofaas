package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.Container;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.HashMap;
import java.util.Map;

import static it.unimib.datai.nanofaas.modules.containerddeploymentprovider.RecoveryFixture.*;
import static org.assertj.core.api.Assertions.*;

class ContainerdRecoveryTest {
    @Test
    void shutdownAndFreshClientAdoptHealthyReplicaWithoutStartOrAdd() {
        RecoveryFixture state = new RecoveryFixture();
        try (var first = state.open()) { first.provider().provision(spec()); }
        state.events.clear();
        try (var restarted = state.open()) {
            restarted.provider().reconcile(spec(), 1, metadata());
            assertThat(restarted.provider().getReadyReplicas("echo")).isEqualTo(1);
            assertThat(state.events).isEmpty();
            assertThat(state.daemon).containsKey(id(1));
        }
    }

    @Test
    void recoveryRecreatesStoppedAndMissingReplicasAndRemovesExtras() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        state.seed(2, false);
        state.seed(4, true);
        try (var restarted = state.open()) {
            restarted.provider().reconcile(spec(), 3, metadata());
            assertThat(restarted.provider().getReadyReplicas("echo")).isEqualTo(3);
            assertThat(state.daemon.keySet()).containsExactlyInAnyOrder(id(1), id(2), id(3));
            assertThat(state.events).containsSubsequence("remove " + id(2), "create " + id(2), "ADD " + id(2), "start " + id(2));
            assertThat(state.events).doesNotContain("start " + id(1), "ADD " + id(1));
        }
    }

    @Test
    void journalOnlyReplicaIsCleanedBeforeItsIdIsRecreated() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        state.pending.put(id(1), state.daemon.remove(id(1)));
        try (var restarted = state.open()) {
            restarted.provider().reconcile(spec(), 1, metadata());
            assertThat(state.events).containsSubsequence("remove " + id(1), "create " + id(1));
            assertThat(state.pending).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"inspect", "attachment", "list"})
    void daemonErrorsAbortReconcileWithoutDestruction(String operation) {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        RuntimeException failure = new IllegalStateException(operation + " timeout");
        switch (operation) {
            case "inspect" -> state.inspectFailure = failure;
            case "attachment" -> state.attachmentFailure = failure;
            default -> state.listFailure = failure;
        }
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.provider().reconcile(spec(), 1, metadata())).isSameAs(failure);
            assertThat(state.events).isEmpty();
            assertThat(state.daemon).containsKey(id(1));
        }
    }

    @Test
    void inconsistentReplicaLabelIsRejectedBeforeRemovingExtraReplica() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        state.daemon.put(id(2), container(id(2), labels("echo", 1)));
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.provider().reconcile(spec(), 1, metadata()))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("replica");
            assertThat(state.events).isEmpty();
        }
    }

    @Test
    void duplicateReplicaIndexesAreRejectedBeforeDestruction() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        state.daemon.put("another-r1", container("another-r1", labels("echo", 1)));
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.provider().reconcile(spec(), 0, metadata()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(state.events).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"io.nanofaas.backend", "io.nanofaas.function", "io.nanofaas.replica"})
    void conflictingJournalAndDaemonOwnershipIsRejectedBeforeDestruction(String key) {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        Container original = state.daemon.get(id(1));
        state.pending.put(id(1), original);
        Map<String, String> conflicting = new HashMap<>(original.labels());
        conflicting.put(key, "foreign");
        state.daemon.put(id(1), container(id(1), conflicting));
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.provider().reconcile(spec(), 0, metadata()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("ownership");
            assertThat(state.events).isEmpty();
        }
    }

    @Test
    void foreignFunctionAtExpectedIdSurvivesReconcileAndStartupRollback() {
        RecoveryFixture state = new RecoveryFixture();
        state.daemon.put(id(1), container(id(1), labels("other", 1)));
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.provider().reconcile(spec(), 1, metadata()))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(state.events).isEmpty();
            assertThat(state.daemon).containsKey(id(1));
        }
    }

    @Test
    void foreignPersistedPrefixIsRejectedBeforeDestruction() {
        RecoveryFixture state = new RecoveryFixture();
        state.seed(1, true);
        try (var restarted = state.open()) {
            assertThatThrownBy(() -> restarted.provider().reconcile(spec(), 0,
                    Map.of("containerNamePrefix", "foreign"))).isInstanceOf(IllegalArgumentException.class);
            assertThat(state.events).isEmpty();
        }
    }
}
