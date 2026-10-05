package it.unimib.datai.nanofaas.modules.offload.oneshot.api;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.assertj.core.api.Assertions.*;
class OneShotApiTest {
    @Test void oldClockReportCannotRefreshHealthAndHttpRejectsInvalidProfile() {
        var now=Instant.parse("2026-10-04T10:00:00Z");
        var clock=new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth(Duration.ofMillis(100),Duration.ofSeconds(10),()->now);
        clock.sample(Duration.ZERO,now.minusSeconds(20));assertThat(clock.healthy()).isFalse();
        assertThatThrownBy(()->clock.sample(Duration.ZERO,now.minusSeconds(30))).isInstanceOf(IllegalArgumentException.class);
        assertThat(clock.healthy()).isFalse();
        var controller=new OneShotController(org.mockito.Mockito.mock(OneShotOperations.class),new ServiceProfileStore(),new EpochEventStore(2),clock);
        var client=org.springframework.test.web.reactive.server.WebTestClient.bindToController(controller).build();
        client.put().uri("/v1/admin/offload/one-shot/profiles/p").header("If-Match","0").header("X-Content-SHA256","sha256:"+"a".repeat(64)).contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{}").exchange().expectStatus().isBadRequest();
        client.put().uri("/v1/admin/offload/one-shot/clock-health").contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue("{\"offset\":\"PT0S\",\"measuredAt\":\"2026-10-04T09:59:30Z\"}").exchange().expectStatus().isBadRequest();
    }

    @Test void completeConfigurationBodyBindsStrictly() throws Exception {
        var operations=org.mockito.Mockito.mock(OneShotOperations.class);
        org.mockito.Mockito.when(operations.configure(org.mockito.ArgumentMatchers.eq(0L),org.mockito.ArgumentMatchers.any())).thenAnswer(a->new OneShotConfigurationStore.Snapshot(1,a.getArgument(1)));
        var clock=new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth(Duration.ofMillis(100),Duration.ofSeconds(10),Instant::now);
        var client=org.springframework.test.web.reactive.server.WebTestClient.bindToController(new OneShotController(operations,new ServiceProfileStore(),new EpochEventStore(2),clock)).build();
        byte[] bytes;try(var in=getClass().getResourceAsStream("/oneshot/config.json")) {bytes=in.readAllBytes();}
        client.put().uri("/v1/admin/offload/one-shot/config").header("If-Match","0").contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(bytes).exchange().expectStatus().isOk().expectBody().jsonPath("$.settings.negotiation.maxRounds").isEqualTo(100);
    }
    @Test void fractionalIntegerFieldsRejectWithoutInstallingProfilesOrConfig() throws Exception {
        var operations=org.mockito.Mockito.mock(OneShotOperations.class);
        org.mockito.Mockito.when(operations.configure(org.mockito.ArgumentMatchers.eq(0L),org.mockito.ArgumentMatchers.any()))
                .thenAnswer(a->new OneShotConfigurationStore.Snapshot(1,a.getArgument(1)));
        var profiles=new ServiceProfileStore();
        var clock=new it.unimib.datai.nanofaas.modules.offload.oneshot.coordination.ClockHealth(Duration.ofMillis(100),Duration.ofSeconds(10),Instant::now);
        var client=org.springframework.test.web.reactive.server.WebTestClient.bindToController(new OneShotController(operations,profiles,new EpochEventStore(2),clock)).build();
        byte[] original=ServiceProfileStoreTest.fixture();
        profiles.replace("profile",0,ServiceProfileStore.hash(original),original);
        String profile=new String(original,java.nio.charset.StandardCharsets.UTF_8);
        for(String field:java.util.List.of("schemaVersion", "memoryMiB", "replicas")) {
            byte[] malformed=profile.replace("\""+field+"\": 1", "\""+field+"\": 1.9").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            client.put().uri("/v1/admin/offload/one-shot/profiles/profile").header("If-Match","1")
                    .header("X-Content-SHA256",ServiceProfileStore.hash(malformed)).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .bodyValue(malformed).exchange().expectStatus().isBadRequest();
            assertThat(profiles.get("profile").orElseThrow().revision()).isEqualTo(1);
            assertThat(profiles.get("profile").orElseThrow().contentHash()).isEqualTo(ServiceProfileStore.hash(original));
        }
        String configuration;try(var in=getClass().getResourceAsStream("/oneshot/config.json")) {configuration=new String(in.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);}
        for(String[] change:java.util.List.of(new String[]{"schemaVersion", "1"},new String[]{"generation", "3"},new String[]{"maxRounds", "100"},new String[]{"memoryCapacityMiB", "1024"})) {
            String malformed=configuration.replace("\""+change[0]+"\": "+change[1], "\""+change[0]+"\": "+change[1]+".9");
            client.put().uri("/v1/admin/offload/one-shot/config").header("If-Match","0")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(malformed).exchange().expectStatus().isBadRequest();
        }
        org.mockito.Mockito.verify(operations,org.mockito.Mockito.never()).configure(org.mockito.ArgumentMatchers.anyLong(),org.mockito.ArgumentMatchers.any());
    }

    @Test void boundedEventsRemainPagedAndCensored() {
        var events=new EpochEventStore(2);
        events.record(1,"deadline",true,12,Instant.EPOCH);
        events.record(1,"failed",true,13,Instant.EPOCH);
        events.record(1,"prepared",false,14,Instant.EPOCH);
        assertThat(events.page(1,0,1)).hasSize(1);
        assertThat(events.page(1,0,10)).hasSize(2);
        assertThat(events.page(1,0,10).getFirst().event().censored()).isTrue();
    }
}
