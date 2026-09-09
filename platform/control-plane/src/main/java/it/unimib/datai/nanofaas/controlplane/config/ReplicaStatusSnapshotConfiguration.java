package it.unimib.datai.nanofaas.controlplane.config;

import it.unimib.datai.nanofaas.controlplane.deployment.ReplicaStatusSnapshot;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.InstantSource;

/**
 * Gives the replica snapshot's refresh pools an owner in the application context: Spring closes the
 * bean on shutdown, so the executors are stopped exactly once by whoever created them (invariant
 * I8), and Boot binds its refresh queue/rejection/duration meters automatically because the snapshot
 * is a {@link io.micrometer.core.instrument.binder.MeterBinder}.
 */
@Configuration
public class ReplicaStatusSnapshotConfiguration {

    @Bean(destroyMethod = "close")
    public ReplicaStatusSnapshot replicaStatusSnapshot() {
        return ReplicaStatusSnapshot.withDefaults(InstantSource.system());
    }
}
