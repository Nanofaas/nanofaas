package it.unimib.datai.nanofaas.controlplane.config;

import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.boot.reactor.netty.NettyReactiveWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import reactor.netty.resources.LoopResources;

/**
 * Two event loops that belong to the probes alone.
 *
 * <p>A liveness probe exists to detect a process that is stuck, and Kubernetes acts on
 * it by killing the container. That makes "busy" and "dead" the two answers it must
 * never confuse - and on 2026-08-23 it confused them twice, at 3x the comparison
 * profile, on synchronous and mixed traffic alike:
 *
 * <pre>
 * Liveness probe failed: Get "http://10.42.0.5:8081/actuator/health/liveness":
 *   context deadline exceeded (Client.Timeout exceeded while awaiting headers)
 * Killing: Container control-plane failed liveness probe, will be restarted
 * </pre>
 *
 * <p>The control plane was serving requests at the time. Its state is entirely in
 * memory, so the restart took the function registry, every execution and every
 * idempotency key with it - overload turned into an outage plus total state loss,
 * at exactly the moment neither can be afforded.
 *
 * <p>Reactor Netty gives every server the process-wide {@code HttpResources} loops by
 * default, so the management port shares them with the invocation path. At the peak of
 * a 2x run those loops carried 863 pending tasks each, and the probe's read waited
 * behind them. Two loops of its own cost two threads and take the probe out of that
 * queue.
 *
 * <p>This is not the whole story and is not claimed to be. The same peak throttled 68.9%
 * of the container's CFS periods, and a thread that is runnable but unscheduled answers
 * no faster for having been given its own loop. What this rules out is the half that
 * can be ruled out in the process; if a 3x run still fails its probe, the remaining
 * cause is the CPU quota, and the answer there is a probe timeout that admits what the
 * platform's scheduling delay actually is - the chart ships {@code timeoutSeconds: 1}.
 */
@ManagementContextConfiguration(ManagementContextType.CHILD)
public class ManagementLoopResourcesConfiguration {

    /**
     * Daemon threads: they must never be the reason a shutdown hangs, and a probe that
     * outlives the application it reports on would answer for a process that has gone.
     */
    @Bean
    WebServerFactoryCustomizer<NettyReactiveWebServerFactory> managementLoopResources() {
        LoopResources loops = LoopResources.create("nanofaas-mgmt", 1, 2, true);
        return factory -> factory.addServerCustomizers(server -> server.runOn(loops));
    }
}
