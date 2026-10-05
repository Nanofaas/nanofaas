package it.unimib.datai.nanofaas.modules.offload;

import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadMeters;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.web.reactive.function.client.WebClient;

@AutoConfiguration
@EnableConfigurationProperties(OffloadProperties.class)
@org.springframework.context.annotation.ImportRuntimeHints(it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.AuctionRuntimeHints.class)
public class OffloadConfiguration {
    private static final Logger log = LoggerFactory.getLogger(OffloadConfiguration.class);

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="nanofaas.offload.one-shot",name="enabled",havingValue="true")
    it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth oneShotClockHealth(
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.clock-threshold}") java.time.Duration threshold,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.clock-max-age}") java.time.Duration maxAge) {
        return new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth(threshold,maxAge,java.time.Instant::now);
    }
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="nanofaas.offload.one-shot",name="enabled",havingValue="true")
    it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings oneShotEpochSettings(
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.auction-budget}") java.time.Duration budget,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.peer-timeout}") java.time.Duration peerTimeout,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.solver-budget}") java.time.Duration solverBudget,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.max-rounds:100}") int rounds,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.parallelism:4}") int parallelism,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.queue-capacity:64}") int queue,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.max-peers:16}") int peers,
        @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.max-auction-fraction:0.1}") double fraction) {
        return new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings(budget,peerTimeout,solverBudget,rounds,parallelism,queue,peers,fraction);
    }
    @Bean(destroyMethod="close")
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="nanofaas.offload.one-shot",name="enabled",havingValue="true")
    it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochCoordinator oneShotEpochCoordinator(
        it.unimib.datai.nanofaas.p2papi.PeerTransport peers,
        it.unimib.datai.nanofaas.forecastingapi.ForecastSource forecasts,
        ObjectProvider<it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochInput.Factory> inputs,
        it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings settings,
        it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth health) {
        return new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochCoordinator(peers,(epoch,from,until)->inputs.getObject().freeze(epoch,from,until),settings,health);
    }

    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(prefix="nanofaas.offload.one-shot",name="enabled",havingValue="true")
    static class OneShotAdministration {
        @Bean it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotConfigurationStore oneShotConfigs(
                ObjectProvider<it.unimib.datai.nanofaas.p2papi.PeerTransport> peers,ObjectProvider<it.unimib.datai.nanofaas.forecastingapi.ForecastSource> forecasts) {
            return new it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotConfigurationStore(()->peers.getIfAvailable()!=null,()->forecasts.getIfAvailable()!=null);
        }
        @Bean it.unimib.datai.nanofaas.modules.offload.oneshot.api.ServiceProfileStore oneShotProfiles() { return new it.unimib.datai.nanofaas.modules.offload.oneshot.api.ServiceProfileStore(); }
        @Bean it.unimib.datai.nanofaas.modules.offload.oneshot.api.EpochEventStore oneShotEvents() { return new it.unimib.datai.nanofaas.modules.offload.oneshot.api.EpochEventStore(10000); }
        @Bean it.unimib.datai.nanofaas.modules.offload.oneshot.api.ProfileEpochInputFactory oneShotInputs(
            it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotConfigurationStore configs,it.unimib.datai.nanofaas.modules.offload.oneshot.api.ServiceProfileStore profiles,
            it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView catalog,it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl control,
            it.unimib.datai.nanofaas.p2papi.PeerTransport peers,it.unimib.datai.nanofaas.forecastingapi.ForecastSource forecasts) {
            return new it.unimib.datai.nanofaas.modules.offload.oneshot.api.ProfileEpochInputFactory(configs,profiles,catalog,control,peers,forecasts);
        }
        @Bean(destroyMethod="close") it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ReplicaPlanActuator oneShotActuator(
            it.unimib.datai.nanofaas.controlplane.registry.ManagedReplicaControl control,it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogView catalog,
            it.unimib.datai.nanofaas.p2papi.PeerTransport peers,it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth health,
            @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.preparation-budget}") java.time.Duration budget,
            @org.springframework.beans.factory.annotation.Value("${nanofaas.offload.one-shot.drain-grace:30s}") java.time.Duration grace) {
            return new it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ReplicaPlanActuator(control,catalog,peers,java.time.Instant::now,health::healthy,budget,grace);
        }
        @Bean(destroyMethod="close") it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotOperations oneShotOperations(
            it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotConfigurationStore configs,it.unimib.datai.nanofaas.modules.offload.oneshot.api.ServiceProfileStore profiles,
            it.unimib.datai.nanofaas.modules.offload.oneshot.api.ProfileEpochInputFactory inputs,it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochCoordinator coordinator,
            it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ReplicaPlanActuator actuator,it.unimib.datai.nanofaas.modules.offload.oneshot.api.EpochEventStore events,
            it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochSettings bounds,it.unimib.datai.nanofaas.p2papi.PeerTransport peers,io.micrometer.core.instrument.MeterRegistry meters) {
            return new it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotOperations(configs,profiles,inputs,coordinator,actuator,events,bounds,peers,meters);
        }
        @Bean it.unimib.datai.nanofaas.modules.offload.oneshot.routing.PlanRouter oneShotRouter(
            it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ReplicaPlanActuator actuator,it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotConfigurationStore configs,
            it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotOperations operations,it.unimib.datai.nanofaas.p2papi.PeerTransport peers) {
            return new it.unimib.datai.nanofaas.modules.offload.oneshot.routing.PlanRouter(actuator::activePlan,()->configs.snapshot().map(it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotConfigurationStore.Snapshot::settings),operations::settingsFor,peers,System::nanoTime);
        }
        @Bean it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotController oneShotController(
            it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotOperations operations,it.unimib.datai.nanofaas.modules.offload.oneshot.api.ServiceProfileStore profiles,
            it.unimib.datai.nanofaas.modules.offload.oneshot.api.EpochEventStore events,it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth health) {
            return new it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotController(operations,profiles,events,health);
        }
    }

    @Bean
    OffloadGateway moduleOffloadGateway(OffloadProperties properties,
                                        ObjectProvider<WebClient> webClient,
                                        ObjectProvider<OffloadMeters> metrics,
                                        ObjectProvider<it.unimib.datai.nanofaas.modules.offload.oneshot.routing.PlanRouter> planned) {
        if (Boolean.TRUE.equals(properties.enabled()) && !properties.hasTarget()) {
            log.warn("Offload module loaded without nanofaas.offload.target-url; "
                    + "only functions declaring their own offload.targetUrl can offload");
        }
        var gateway=new DefaultOffloadGateway(properties,webClient::getObject,metrics.getObject());
        gateway.plannedRouting(planned.getIfAvailable());return gateway;
    }
}
