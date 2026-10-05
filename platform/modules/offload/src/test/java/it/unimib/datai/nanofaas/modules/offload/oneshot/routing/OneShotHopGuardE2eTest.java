package it.unimib.datai.nanofaas.modules.offload.oneshot.routing;
import it.unimib.datai.nanofaas.controlplane.ControlPlaneApplication;
import it.unimib.datai.nanofaas.controlplane.capacity.FunctionCapacityRegistry;
import it.unimib.datai.nanofaas.controlplane.offload.OffloadGateway;
import it.unimib.datai.nanofaas.modules.offload.DefaultOffloadGateway;
import it.unimib.datai.nanofaas.modules.offload.oneshot.actuation.ActiveRoutingPlan;
import it.unimib.datai.nanofaas.modules.offload.oneshot.api.*;
import it.unimib.datai.nanofaas.modules.offload.oneshot.auction.Assignment;
import it.unimib.datai.nanofaas.p2papi.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import okhttp3.mockwebserver.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
/** Real HTTP and core lifecycle; deliberately supplies plans directly to isolate routing from calibration. */
class OneShotHopGuardE2eTest {
    static final String DIGEST="sha256:"+"b".repeat(64);
    static String url(ConfigurableApplicationContext app) { return "http://127.0.0.1:"+app.getEnvironment().getProperty("local.server.port"); }
    static ConfigurableApplicationContext app(String registry) {
        return new SpringApplicationBuilder(ControlPlaneApplication.class).run("--server.port=0","--management.server.port=0","--nanofaas.registry.path="+registry,
            "--spring.autoconfigure.exclude=it.unimib.datai.nanofaas.modules.asyncqueue.AsyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueConfiguration,it.unimib.datai.nanofaas.modules.syncqueue.SyncQueueRuntimeConfigAutoConfiguration,it.unimib.datai.nanofaas.modules.runtimeconfig.RuntimeConfigConfiguration","--sync-queue.enabled=false");
    }
    static HttpResponse<String> send(String url,String method,String body,Map<String,String> headers) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(10)).header("Content-Type","application/json");headers.forEach(request::header);
        try(var client=HttpClient.newHttpClient()) {
            var response=client.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
            if(url.endsWith("/v1/functions") && response.statusCode()!=201)
                throw new AssertionError("Registration "+url+": "+response.statusCode()+" "+response.body()+" "+response.headers().map());
            return response;
        }
    }
    static OneShotSettings settings(long generation,String cloud) {
        String hash=ServiceProfileStore.hash(JsonMapper.builder().enable(tools.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build().writeValueAsBytes(Map.of("nested",List.of(1,2),"payload","value")));
        return new OneShotSettings(1,"p","sha256:"+"a".repeat(64),"workflow-validation",true,URI.create(cloud),1,1,Duration.ofMinutes(5),Duration.ofSeconds(20),false,Instant.EPOCH,Duration.ofSeconds(2),.1,1,Map.of("f",new OneShotSettings.Function(generation,DIGEST,hash,.8,1,.9,.1)));
    }
    static PeerTransport peers(String node,String uri,String other,String otherUri) {
        return new PeerTransport() {
            @Override public Optional<PeerEndpoint> localEndpoint() { return Optional.of(new PeerEndpoint(node,node+"-run",URI.create(uri))); }
            @Override public List<PeerEndpoint> activeNeighbors() { return List.of(new PeerEndpoint(other,other+"-run",URI.create(otherUri))); }
            @Override public Mono<byte[]> request(String peer,String topic,byte[] bytes,Duration timeout) { return Mono.error(new UnsupportedOperationException()); }
            @Override public PeerSubscription subscribe(String topic,PeerReceiver receiver) { return ()->{}; }
        };
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"cloud","stale-grant"})
    void terminalCloudHasTrustedIdentityButAnInactiveSellerRejectsNativeGrants(String assignment) throws Exception {
        try(var backend=new MockWebServer()) {
            backend.enqueue(new MockResponse().setHeader("Content-Type","application/json")
                    .setHeader("X-NanoFaaS-Execution-Node","spoof").setBody("\"executed\""));
            backend.start();
            var registry=java.nio.file.Files.createTempDirectory("one-shot-terminal-").resolve("functions.json");
            try(var terminal=app(registry.toString())) {
                String target=url(terminal);
                String spec="{\"name\":\"f\",\"image\":\"img@"+DIGEST+"\",\"executionMode\":\"EXTERNAL\",\"endpointUrl\":\""+backend.url("/invoke")+"\",\"timeoutMs\":5000,\"concurrency\":1}";
                assertThat(send(target+"/v1/functions","POST",spec,Map.of()).statusCode()).isEqualTo(201);
                var headers=Map.of("Idempotency-Key","terminal-replay","X-NanoFaaS-Offload-Hop","1",
                        "X-NanoFaaS-Offload-Version","1","X-NanoFaaS-Offload-Origin","a@a-run",
                        "X-NanoFaaS-Offload-Epoch","1","X-NanoFaaS-Offload-Assignment",assignment);
                var response=send(target+"/v1/functions/f:invoke","POST","{\"input\":{}}",headers);
                if(assignment.equals("cloud")) {
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                    assertThat(response.headers().firstValue("X-NanoFaaS-Execution-Node")).contains("cloud");
                    var replay=send(target+"/v1/functions/f:invoke","POST","{\"input\":{}}",headers);
                    assertThat(replay.headers().firstValue("X-NanoFaaS-Execution-Node")).contains("cloud");
                    assertThat(backend.getRequestCount()).isEqualTo(1);
                } else {
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(429);
                    assertThat(response.headers().firstValue("X-NanoFaaS-Execution-Node")).isEmpty();
                    assertThat(backend.getRequestCount()).isZero();
                }
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void handlerErrorAndItsRemoteReplayCarryOnlyPhysicalExecutionAttribution(boolean executed) throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var execution=new java.util.concurrent.atomic.AtomicReference<String>();
        var attempt=new java.util.concurrent.atomic.AtomicReference<String>();
        try(var backend=new MockWebServer();var cloud=new MockWebServer()) {
            backend.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) {
                    String path=request.getPath();
                    if(path.equals("/runtime/status")) return new MockResponse().setBody("{\"schemaVersion\":1,\"incarnation\":\"physical-run\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}");
                    if(path.startsWith("/runtime/executions/")) {
                        String proof=executed?"{\"state\":\"RELEASED\",\"incarnation\":\"physical-run\",\"executionId\":\""+execution.get()+"\",\"dispatchAttempt\":\""+attempt.get()+"\",\"occupancySeconds\":0.001}":"{\"state\":\"UNKNOWN\"}";
                        return new MockResponse().setBody(proof);
                    }
                    calls.incrementAndGet();execution.set(request.getHeader("X-Execution-Id"));attempt.set(request.getHeader("X-Dispatch-Attempt"));
                    return new MockResponse().setResponseCode(500).setHeader("Content-Type","application/json")
                            .setHeader("X-NanoFaaS-Handler-Executed","true").setHeader("X-NanoFaaS-Execution-Node","spoof")
                            .setBody("{\"error\":{\"code\":\""+(executed?"HANDLER_ERROR":"RUNTIME_HANDLER_BUSY")+"\",\"message\":\"failed\"}}");
                }
            });
            backend.start();cloud.start();
            try(var proxy=new it.unimib.datai.nanofaas.containerdeployment.RoundRobinFunctionProxy("127.0.0.1")) {
                proxy.updateLimits(2,Duration.ofSeconds(2));
                proxy.enablePhysicalSlots();proxy.updateBackends(List.of(backend.url("/").toString().replaceAll("/+$","")));
                var directory=java.nio.file.Files.createTempDirectory("one-shot-handler-error-");
                try(var a=app(directory.resolve("a.json").toString());var b=app(directory.resolve("b.json").toString())) {
                    String aUrl=url(a),bUrl=url(b),cloudUrl=cloud.url("/").toString().replaceAll("/+$", "");
                    String spec="{\"name\":\"f\",\"image\":\"img@"+DIGEST+"\",\"executionMode\":\"EXTERNAL\",\"endpointUrl\":\""+proxy.endpointUrl()+"\",\"env\":{\"NANOFAAS_ONE_SHOT_PROFILE\":\"true\"},\"timeoutMs\":5000,\"concurrency\":1,\"maxRetries\":0}";
                    assertThat(send(aUrl+"/v1/functions","POST",spec,Map.of()).statusCode()).isEqualTo(201);
                    assertThat(send(bUrl+"/v1/functions","POST",spec,Map.of()).statusCode()).isEqualTo(201);
                    long ag=a.getBean(FunctionCapacityRegistry.class).activeGeneration("f").id(),bg=b.getBean(FunctionCapacityRegistry.class).activeGeneration("f").id();
                    var grant=new Assignment("grant","a","a-run","b","b-run","f",DIGEST,bg,ag,1,1,true);
                    var from=Instant.now().minusSeconds(1);var until=from.plusSeconds(300);
                    var ap=new ActiveRoutingPlan("a","a-run",1,1,from,until,1,Map.of("f",new ActiveRoutingPlan.FunctionPlan(DIGEST,ag,0,0,0,1,1,.5,.8,List.of(),List.of(grant))));
                    var bp=new ActiveRoutingPlan("b","b-run",1,1,from,until,1,Map.of("f",new ActiveRoutingPlan.FunctionPlan(DIGEST,bg,1,0,0,0,1,.5,.8,List.of(grant),List.of())));
                    var ac=settings(ag,cloudUrl);var bc=settings(bg,cloudUrl);
                    ((DefaultOffloadGateway)a.getBean(OffloadGateway.class)).plannedRouting(new PlanRouter(()->Optional.of(ap),()->Optional.of(ac),rev->Optional.of(ac),peers("a",aUrl,"b",bUrl),()->0));
                    ((DefaultOffloadGateway)b.getBean(OffloadGateway.class)).plannedRouting(new PlanRouter(()->Optional.of(bp),()->Optional.of(bc),rev->Optional.of(bc),peers("b",bUrl,"a",aUrl),()->0));
                    String payload="{\"input\":{\"payload\":\"value\",\"nested\":[1,2]}}";
                    var response=send(aUrl+"/v1/functions/f:invoke","POST",payload,Map.of("Idempotency-Key","error-replay"));
                    assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                    assertThat(JsonMapper.shared().readTree(response.body()).path("status").asText()).isEqualTo("error");
                    assertThat(response.headers().firstValue("X-NanoFaaS-Execution-Node")).isEqualTo(executed?Optional.of("b"):Optional.empty());
                    var replay=send(aUrl+"/v1/functions/f:invoke","POST",payload,Map.of("Idempotency-Key","error-replay"));
                    assertThat(replay.statusCode()).isEqualTo(response.statusCode());
                    assertThat(replay.headers().firstValue("X-NanoFaaS-Execution-Node")).isEqualTo(response.headers().firstValue("X-NanoFaaS-Execution-Node"));
                    assertThat(calls.get()).isEqualTo(1);assertThat(cloud.getRequestCount()).isZero();
                }
            }
        }
    }

    @Test void confirmedOneHopPreservesNodeOnReplayAndOverflowUsesCloudBeforeSending() throws Exception {
        var backend=new MockWebServer();var cloud=new MockWebServer();backend.start();cloud.start();
        backend.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest r) {return new MockResponse().setHeader("Content-Type","application/json").setBody("\"executed\"");} });
        cloud.setDispatcher(new Dispatcher() { @Override public MockResponse dispatch(RecordedRequest r) {return new MockResponse().setHeader("Content-Type","application/json").setHeader("X-NanoFaaS-Execution-Node","cloud").setBody("{\"executionId\":\"cloud-id\",\"status\":\"success\",\"output\":\"cloud\"}");} });
        var directory=java.nio.file.Files.createTempDirectory("one-shot-routing-");
        try(var a=app(directory.resolve("a.json").toString());var b=app(directory.resolve("b.json").toString())) {
            var cloudUrl=cloud.url("/").toString().replaceAll("/+$", "");var aUrl=url(a);var bUrl=url(b);
            var spec="{\"name\":\"f\",\"image\":\"img@"+DIGEST+"\",\"executionMode\":\"EXTERNAL\",\"endpointUrl\":\""+backend.url("/invoke")+"\",\"timeoutMs\":5000,\"concurrency\":1,\"offload\":{\"mode\":\"always\",\"targetUrl\":\""+cloudUrl+"\"}}";
            assertThat(send(aUrl+"/v1/functions","POST",spec,Map.of()).statusCode()).isEqualTo(201);
            assertThat(send(bUrl+"/v1/functions","POST",spec,Map.of()).statusCode()).isEqualTo(201);
            long ag=a.getBean(FunctionCapacityRegistry.class).activeGeneration("f").id(),bg=b.getBean(FunctionCapacityRegistry.class).activeGeneration("f").id();
            var grant=new Assignment("grant","a","a-run","b","b-run","f",DIGEST,bg,ag,1,1,true);
            var from=Instant.now().minusSeconds(1);var until=from.plusSeconds(300);
            var ap=new ActiveRoutingPlan("a","a-run",1,1,from,until,1,Map.of("f",new ActiveRoutingPlan.FunctionPlan(DIGEST,ag,0,0,0,1,1,.5,.8,List.of(),List.of(grant))));
            var bp=new ActiveRoutingPlan("b","b-run",1,1,from,until,1,Map.of("f",new ActiveRoutingPlan.FunctionPlan(DIGEST,bg,1,0,0,0,1,.5,.8,List.of(grant),List.of())));
            var clock=new AtomicLong();var ac=settings(ag,cloudUrl);var bc=settings(bg,cloudUrl);
            ((DefaultOffloadGateway)a.getBean(OffloadGateway.class)).plannedRouting(new PlanRouter(()->Optional.of(ap),()->Optional.of(ac),rev->Optional.of(ac),peers("a",aUrl,"b",bUrl),clock::get));
            ((DefaultOffloadGateway)b.getBean(OffloadGateway.class)).plannedRouting(new PlanRouter(()->Optional.of(bp),()->Optional.of(bc),rev->Optional.of(bc),peers("b",bUrl,"a",aUrl),clock::get));
            String payload="{\"input\":{\"payload\":\"value\",\"nested\":[1,2]},\"headers\":{\"X-NanoFaaS-Offload-Origin\":\"spoof\",\"X-NanoFaaS-Execution-Node\":\"spoof\"}}";
            var result=send(aUrl+"/v1/functions/f:invoke","POST",payload,Map.of("Idempotency-Key","replay"));
            assertThat(result.statusCode()).as(result.body()).isEqualTo(200);assertThat(result.headers().firstValue("X-NanoFaaS-Execution-Node")).contains("b");
            var replay=send(aUrl+"/v1/functions/f:invoke","POST",payload,Map.of("Idempotency-Key","replay"));
            assertThat(replay.headers().firstValue("X-NanoFaaS-Execution-Node")).contains("b");assertThat(backend.getRequestCount()).isEqualTo(1);assertThat(cloud.getRequestCount()).isZero();
            var backendRequest=backend.takeRequest();assertThat(backendRequest.getBody().readUtf8()).doesNotContain("spoof","x-nanofaas-offload-origin");
            var overflow=send(aUrl+"/v1/functions/f:invoke","POST",payload,Map.of());assertThat(overflow.headers().firstValue("X-NanoFaaS-Execution-Node")).contains("cloud");
            assertThat(backend.getRequestCount()).isEqualTo(1);assertThat(cloud.getRequestCount()).isEqualTo(1);
            var denied=send(bUrl+"/v1/functions/f:invoke","POST",payload,Map.of("X-NanoFaaS-Offload-Hop","1","X-NanoFaaS-Offload-Version","1","X-NanoFaaS-Offload-Origin","a@a-run","X-NanoFaaS-Offload-Epoch","1","X-NanoFaaS-Offload-Assignment","grant"));
            assertThat(denied.statusCode()).isEqualTo(429);assertThat(cloud.getRequestCount()).isEqualTo(1);
        } finally {backend.close();cloud.close();}
    }
}
