package it.unimib.datai.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hint and the linux-aarch_64 classifier only work together: a hint for a resource that is
 * not on the classpath includes nothing, and a library the image does not include is never
 * loaded. Either half missing leaves an aarch64 build silently on NIO.
 */
class NettyEpollRuntimeHintsTest {

    private static final String LIBRARY = "META-INF/native/libnetty_transport_native_epoll_aarch_64.so";

    @Test
    void registersTheAarch64EpollLibraryForTheNativeImage() {
        RuntimeHints hints = new RuntimeHints();
        new NettyEpollRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource(LIBRARY)).accepts(hints);
    }

    @Test
    void theAarch64EpollLibraryIsOnTheRuntimeClasspath() {
        assertThat(getClass().getClassLoader().getResource(LIBRARY)).isNotNull();
    }
}
