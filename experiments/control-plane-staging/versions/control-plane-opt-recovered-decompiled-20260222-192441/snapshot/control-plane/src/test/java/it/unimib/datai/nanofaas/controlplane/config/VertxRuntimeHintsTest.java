package it.unimib.datai.nanofaas.controlplane.config;

import io.fabric8.kubernetes.api.model.DeleteOptions;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.impl.KubernetesClientImpl;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;

import java.io.IOException;
import java.net.URL;
import java.util.Enumeration;

import static org.assertj.core.api.Assertions.assertThat;

class VertxRuntimeHintsTest {

    @Test
    void registerHints_registersVertxAndCoreFabric8Types() {
        RuntimeHints hints = new RuntimeHints();
        ClassLoader classLoader = getClass().getClassLoader();

        new VertxRuntimeHints.VertxResourceHints().registerHints(hints, classLoader);

        assertThat(RuntimeHintsPredicates.resource()
                .forResource("META-INF/vertx/vertx-version.txt")
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(Pod.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(DeleteOptions.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(KubernetesClientImpl.class)
                .withMemberCategory(MemberCategory.INVOKE_DECLARED_CONSTRUCTORS)
                .test(hints)).isTrue();
        assertThat(hints.reflection().typeHints().count()).isGreaterThan(2);
    }

    @Test
    void registerHints_whenClassloaderEnumerationFails_keepsBaseHints() {
        RuntimeHints hints = new RuntimeHints();
        ClassLoader failingLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            public Enumeration<URL> getResources(String name) throws IOException {
                throw new IOException("simulated");
            }
        };

        new VertxRuntimeHints.VertxResourceHints().registerHints(hints, failingLoader);

        assertThat(RuntimeHintsPredicates.reflection()
                .onType(Pod.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
        assertThat(RuntimeHintsPredicates.reflection()
                .onType(DeleteOptions.class)
                .withMemberCategory(MemberCategory.INVOKE_PUBLIC_METHODS)
                .test(hints)).isTrue();
    }
}
