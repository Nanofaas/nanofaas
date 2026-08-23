package it.unimib.datai.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the probes really did get their own loops.
 *
 * Nothing fails loudly when this wiring is wrong. A typo in the
 * ManagementContextConfiguration.imports file, a renamed class, a management port that
 * happens to equal the main one - each of them silently leaves the probe back on the
 * shared loops, which is the arrangement that got the control plane killed twice on
 * 2026-08-23. The only honest check is to make the server answer and then look at which
 * threads exist.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "management.server.port=0",
                "management.endpoints.web.exposure.include=health",
                "management.endpoint.health.probes.enabled=true"
        })
class ManagementLoopResourcesTest {

    @Value("${local.management.port}")
    int managementPort;

    @Test
    void theLivenessProbeAnswersOnLoopsOfItsOwn() {
        String body = WebClient.create("http://localhost:" + managementPort)
                .get().uri("/actuator/health/liveness")
                .retrieve().bodyToMono(String.class)
                .block(Duration.ofSeconds(10));

        assertThat(body).contains("UP");

        Set<String> loops = Thread.getAllStackTraces().keySet().stream()
                .map(Thread::getName)
                .filter(name -> name.startsWith("nanofaas-mgmt"))
                .collect(Collectors.toSet());

        // Named, so the failure message says which threads DID serve it.
        assertThat(loops)
                .as("management event loops, among all live threads: %s",
                        Thread.getAllStackTraces().keySet().stream().map(Thread::getName).sorted().toList())
                .isNotEmpty();
    }
}
