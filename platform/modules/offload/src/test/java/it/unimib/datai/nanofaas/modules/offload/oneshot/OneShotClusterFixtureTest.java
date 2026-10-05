package it.unimib.datai.nanofaas.modules.offload.oneshot;
import org.junit.jupiter.api.Test;
import it.unimib.datai.nanofaas.modules.offload.oneshot.api.ServiceProfileStore;
import static org.assertj.core.api.Assertions.*;
class OneShotClusterFixtureTest {
    @Test void aotIncludedDisabledLifecyclesNeverSubscribeOrSchedule() {
        var peers=org.mockito.Mockito.mock(it.unimib.datai.nanofaas.p2papi.PeerTransport.class);
        try(var coordinator=new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.EpochCoordinator(peers,null,null,null,false);
            var actuator=new it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ReplicaPlanActuator(null,null,peers,java.time.Instant::now,()->true,java.time.Duration.ofSeconds(1),java.time.Duration.ZERO,false);
            var operations=new it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotOperations(null,null,null,null,null,null,null,peers,null,false)) {
            for(var lifecycle:java.util.List.of(coordinator,actuator,operations)) {assertThat(lifecycle.isAutoStartup()).isFalse();lifecycle.start();assertThat(lifecycle.isRunning()).isFalse();}
            org.mockito.Mockito.verifyNoInteractions(peers);
        }
    }
    @Test void aotIncludedAdministrationCanBeDisabledAtRuntime() {
        var controller=new it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotController(null,new ServiceProfileStore(),null,null,false);
        var client=org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller).build();
        client.get().uri("/v1/admin/offload/one-shot/status").exchange().expectStatus().isNotFound();
        client.put().uri("/v1/admin/offload/one-shot/profiles/p").header("If-Match","0").header("X-Content-SHA256","irrelevant").contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isNotFound();
    }
    @Test void calibratedInputHashSurvivesHttpDecoding() {
        var mapper=tools.jackson.databind.json.JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).enable(tools.jackson.databind.MapperFeature.SORT_PROPERTIES_ALPHABETICALLY).build();
        var bytes=OneShotLocalClusterE2eTest.JSON.writeValueAsBytes(OneShotLocalClusterE2eTest.INPUT);
        var decoded=mapper.readValue(bytes,Object.class);
        assertThat(mapper.writeValueAsString(decoded)).isEqualTo(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
    }
    @Test void exactSerializedSyntheticClusterProfileCanBeInstalled() {
        var fixture=new OneShotLocalClusterE2eTest();fixture.image="sha256:"+"a".repeat(64);
        var bytes=OneShotLocalClusterE2eTest.JSON.writeValueAsBytes(fixture.profile(null));
        var httpStore=new ServiceProfileStore();
        var clock=new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth(java.time.Duration.ofMillis(100),java.time.Duration.ofSeconds(10),java.time.Instant::now);
        var controller=new it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotController(org.mockito.Mockito.mock(it.unimib.datai.nanofaas.modules.offload.oneshot.api.OneShotOperations.class),httpStore,new it.unimib.datai.nanofaas.modules.offload.oneshot.api.EpochEventStore(2),clock);
        var client=org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller).build();
        client.put().uri("/v1/admin/offload/one-shot/profiles/cluster").header("If-Match","0").header("X-Content-SHA256",ServiceProfileStore.hash(bytes)).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(bytes).exchange().expectStatus().isOk();
        var store=new ServiceProfileStore();assertThat(store.replace("cluster",0,ServiceProfileStore.hash(bytes),bytes).profile().synthetic()).isTrue();
    }
}
