package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import org.springframework.context.annotation.Bean;

import java.time.InstantSource;

/**
 * Gives the replica snapshot's refresh pools an owner in the application context: Spring closes the
 * bean on shutdown, so the executors are stopped exactly once by whoever created them (invariant
 * I8), and Boot binds its refresh queue/rejection/duration meters automatically because the snapshot
 * is a {@link io.micrometer.core.instrument.binder.MeterBinder}.
 *
 * <p>Deliberately not annotated {@code @Configuration}: a stereotype here would be component-scanned
 * and the snapshot would exist in every profile, including one with no managed deployment provider,
 * where its two refresh pools could never have a provider to call. It is imported instead, by the
 * managed orchestration that is itself conditional on a provider — and being importable on its own
 * is what lets the ownership be asserted without standing up every managed collaborator.</p>
 */
public class ReplicaStatusSnapshotConfiguration {

    @Bean(destroyMethod = "close")
    public ReplicaStatusSnapshot replicaStatusSnapshot() {
        return ReplicaStatusSnapshot.withDefaults(InstantSource.system());
    }
}
