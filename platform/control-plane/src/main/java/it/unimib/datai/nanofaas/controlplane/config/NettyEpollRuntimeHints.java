package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;

/**
 * Netty loads its epoll JNI library from a classpath resource, and the reachability metadata
 * netty-transport-native-epoll 4.2.15 ships registers only the unsuffixed and the x86_64 names.
 * On an aarch64 native image the library is therefore absent, Epoll.isAvailable() is false, and
 * reactor-netty silently serves on NIO — the same fallback the JVM took before the linux-aarch_64
 * classifier was added to this module's runtime classpath.
 *
 * <p>Drop this registrar once Netty's metadata covers aarch_64 itself.
 */
public class NettyEpollRuntimeHints implements RuntimeHintsRegistrar {

    @Override
    public void registerHints(RuntimeHints hints, ClassLoader classLoader) {
        hints.resources().registerPattern("META-INF/native/libnetty_transport_native_epoll_aarch_64.so");
    }
}
