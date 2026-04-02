package it.unimib.datai.nanofaas.modules.containerdeploymentprovider;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

final class EphemeralPortAllocator implements PortAllocator {

    private final String bindHost;

    EphemeralPortAllocator(String bindHost) {
        this.bindHost = bindHost == null || bindHost.isBlank() ? "127.0.0.1" : bindHost;
    }

    @Override
    public int nextPort() {
        try (ServerSocket socket = new ServerSocket(0, 0, InetAddress.getByName(bindHost))) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to allocate ephemeral port on " + bindHost, e);
        }
    }
}
