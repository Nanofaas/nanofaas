package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.util.LinkedHashSet;

final class EphemeralPortAllocator implements PortAllocator {

    // ponytail: remembers the last 1024 ports handed out; a single scale-up larger than that
    // could repeat one. Linux repeats a closed ephemeral port within ~100 draws.
    private static final int REMEMBERED = 1024;
    private static final int ATTEMPTS = 100;

    private final String bindHost;
    private final LinkedHashSet<Integer> handedOut = new LinkedHashSet<>();

    EphemeralPortAllocator(String bindHost) {
        this.bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
    }

    /**
     * Replicas start concurrently, so a port handed out may not be bound by its container yet when
     * the next is drawn, and the operating system is free to return it again. A port already
     * handed out recently is therefore drawn again. Still best-effort against other processes: one
     * could bind the port after the probe socket closes and before the container does.
     */
    @Override
    public synchronized int nextPort() {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int port = probe();
            if (handedOut.add(port)) {
                if (handedOut.size() > REMEMBERED) handedOut.removeFirst();
                return port;
            }
        }
        throw new IllegalStateException("No ephemeral port on " + bindHost + " not already handed out");
    }

    private int probe() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName(bindHost))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate ephemeral port on " + bindHost, e);
        }
    }
}
