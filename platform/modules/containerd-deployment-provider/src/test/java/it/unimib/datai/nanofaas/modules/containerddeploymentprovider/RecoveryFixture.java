package it.unimib.datai.nanofaas.modules.containerddeploymentprovider;

import io.nanofaas.containerd.*;
import io.nanofaas.containerd.spi.ContainerdClient;
import io.nanofaas.containerd.spi.Containers;
import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import it.unimib.datai.nanofaas.containerdeployment.*;
import it.unimib.datai.nanofaas.controlplane.deployment.ProvisionResult;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** A daemon and durable cleanup/attachment state shared by otherwise fresh clients/providers. */
final class RecoveryFixture {
    static final String PREFIX = "nanofaas-echo-092c79e8f8";
    final Map<String, Container> daemon = new LinkedHashMap<>();
    final Map<String, Container> pending = new LinkedHashMap<>();
    final Map<String, NetworkAttachment> attachments = new HashMap<>();
    final Set<String> snapshots = new HashSet<>();
    final Set<String> running = new HashSet<>();
    final Map<String, Integer> removalFailures = new HashMap<>();
    final List<String> events = new ArrayList<>();
    RuntimeException listFailure;
    RuntimeException inspectFailure;
    RuntimeException attachmentFailure;
    boolean failStart;
    CountDownLatch removeEntered;
    CountDownLatch allowRemove;

    Session open() {
        ContainerdClient client = mock(ContainerdClient.class);
        Containers containers = mock(Containers.class);
        when(client.containers()).thenReturn(containers);
        when(containers.list()).thenAnswer(call -> {
            if (listFailure != null) throw listFailure;
            return List.copyOf(daemon.values());
        });
        when(containers.pendingRemovals()).thenAnswer(call -> List.copyOf(pending.values()));
        when(containers.inspect(anyString())).thenAnswer(call -> {
            if (inspectFailure != null) throw inspectFailure;
            String id = call.getArgument(0);
            return new ContainerStatus(id, "echo:1", running.contains(id) ? ContainerState.RUNNING : ContainerState.STOPPED,
                    running.contains(id) ? 123 : 0, null, id, Instant.EPOCH);
        });
        when(containers.networkAttachment(anyString())).thenAnswer(call -> {
            if (attachmentFailure != null) throw attachmentFailure;
            return attachments.get(call.getArgument(0));
        });
        when(containers.create(any())).thenAnswer(call -> {
            ContainerSpec spec = call.getArgument(0);
            if (daemon.containsKey(spec.id()) || pending.containsKey(spec.id())) {
                throw new IllegalStateException("ID still owned: " + spec.id());
            }
            Container container = container(spec.id(), spec.labels());
            daemon.put(spec.id(), container);
            snapshots.add(spec.id());
            events.add("create " + spec.id());
            return container;
        });
        doAnswer(call -> {
            String id = call.getArgument(0);
            events.add("ADD " + id);
            attachments.put(id, address());
            if (failStart) throw new IllegalStateException("start after IP allocation failed");
            running.add(id);
            events.add("start " + id);
            return null;
        }).when(containers).start(anyString());
        doAnswer(call -> {
            String id = call.getArgument(0);
            RemoveOptions options = call.getArgument(1);
            if (!options.force() || !options.removeSnapshot()) throw new AssertionError("force and snapshot cleanup required");
            if (removeEntered != null) {
                removeEntered.countDown();
                if (!allowRemove.await(5, TimeUnit.SECONDS)) throw new AssertionError("cleanup never released");
            }
            events.add("remove " + id);
            Container metadata = daemon.remove(id);
            if (metadata != null) pending.put(id, metadata);
            running.remove(id);
            if (removalFailures.getOrDefault(id, 0) > 0) {
                removalFailures.computeIfPresent(id, (key, count) -> count - 1);
                throw new IllegalStateException("CNI DEL/snapshot timeout: " + id);
            }
            attachments.remove(id);
            snapshots.remove(id);
            pending.remove(id);
            return null;
        }).when(containers).remove(anyString(), any());
        ContainerdRuntimeAdapter adapter = new ContainerdRuntimeAdapter(client, "nanofaas", null, null,
                true, "/run/user/1000/containerd/containerd.sock", java.time.Duration.ofSeconds(1));
        EndpointProbe probe = mock(EndpointProbe.class);
        when(probe.isReady(anyString())).thenReturn(true);
        List<ManagedFunctionProxy> proxies = new ArrayList<>();
        ContainerdDeploymentProvider provider = new ContainerdDeploymentProvider(adapter,
                ContainerdProperties.defaults(Map.of("HOME", "/home/service", "XDG_RUNTIME_DIR", "/run/user/1000")),
                probe, name -> {
                    ManagedFunctionProxy proxy = new RoundRobinFunctionProxyFactory("127.0.0.1").create(name);
                    proxies.add(proxy);
                    return proxy;
                });
        return new Session(client, adapter, provider, proxies);
    }

    void seed(int index, boolean active) {
        String id = id(index);
        daemon.put(id, container(id, labels("echo", index)));
        snapshots.add(id);
        if (active) {
            running.add(id);
            attachments.put(id, address());
        }
    }

    static Container container(String id, Map<String, String> labels) {
        return new Container(id, "echo:1", "native", id, Instant.EPOCH, labels);
    }

    static Map<String, String> labels(String function, int index) {
        return Map.of("io.nanofaas.backend", "containerd", "io.nanofaas.managed", "true",
                "io.nanofaas.function", function, "io.nanofaas.replica", Integer.toString(index));
    }

    static NetworkAttachment address() {
        return new NetworkAttachment(List.of("10.90.0.2/24"), List.of(), List.of(), List.of(), null);
    }

    static String id(int index) { return PREFIX + "-r" + index; }
    static Map<String, String> metadata() { return Map.of(ProvisionResult.CONTAINER_NAME_PREFIX, PREFIX); }
    static FunctionSpec spec() {
        return new FunctionSpec("echo", "echo:1", null, null, null, 30000, 4,
                null, null, null, ExecutionMode.DEPLOYMENT, null, null, null);
    }

    record Session(ContainerdClient client, ContainerdRuntimeAdapter adapter,
                   ContainerdDeploymentProvider provider, List<ManagedFunctionProxy> proxies) implements AutoCloseable {
        @Override public void close() { provider.close(); client.close(); }
    }
}
