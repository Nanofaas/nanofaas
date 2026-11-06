package it.unimib.datai.nanofaas.containerdeployment;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Shared observation resources for all local deployments. */
public final class ManagedReplicaPoolFactory implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ExecutorService observations = Executors.newVirtualThreadPerTaskExecutor();
    public ManagedReplicaSet create(String functionName) {
        return new ManagedReplicaPool(new HttpRuntimeExecutionProbe(client, Duration.ofSeconds(2)),
                observations, Duration.ofMinutes(10));
    }
    @Override public void close() { observations.shutdownNow(); client.close(); observations.close(); }
}
