package it.unimib.datai.nanofaas.containerdeployment;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
class ReplicaDrainTest {
    @Test void earlyResponseKeepsPhysicalSlotAndDrainWaitsForProof() throws Exception {
        var released=new AtomicBoolean();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/runtime/status", e -> reply(e,200,"{\"schemaVersion\":1,\"incarnation\":\"run-1\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}"));
        server.createContext("/invoke", e -> reply(e,504,"timeout"));
        server.createContext("/runtime/executions/work", e -> reply(e,200,"{\"state\":\""+(released.get()?"RELEASED":"ACTIVE")+"\",\"incarnation\":\"run-1\",\"executionId\":\"work\",\"dispatchAttempt\":\"1\",\"occupancySeconds\":1.0}"));
        server.start(); String backend="http://127.0.0.1:"+server.getAddress().getPort();
        try(var client=HttpClient.newHttpClient(); var proxy=new RoundRobinFunctionProxy("127.0.0.1",2,Duration.ofSeconds(2))) {
            proxy.enablePhysicalSlots(); proxy.updateBackends(List.of(backend));
            assertThat(invoke(client,proxy.endpointUrl(),"work").statusCode()).isEqualTo(504);
            assertThat(invoke(client,proxy.endpointUrl(),"other").statusCode()).isEqualTo(503);
            proxy.beginDrain(backend);
            assertThat(proxy.awaitDrained(backend,Duration.ofMillis(100))).isFalse();
            released.set(true);
            assertThat(proxy.awaitDrained(backend,Duration.ofSeconds(2))).isTrue();
            assertThat(invoke(client,proxy.endpointUrl(),"other").statusCode()).isEqualTo(503);
        } finally { server.stop(0); }
    }
    @Test void occupiedReplicaAForcesNextRequestToFreeReplicaB() throws Exception {
        var callsA=new java.util.concurrent.atomic.AtomicInteger(); var callsB=new java.util.concurrent.atomic.AtomicInteger();
        var a=activeBackend("a",callsA); var b=activeBackend("b",callsB);
        try(var client=HttpClient.newHttpClient(); var proxy=new RoundRobinFunctionProxy("127.0.0.1",3,Duration.ofSeconds(2))) {
            proxy.enablePhysicalSlots(); proxy.updateBackends(List.of("http://127.0.0.1:"+a.getAddress().getPort(),"http://127.0.0.1:"+b.getAddress().getPort()));
            assertThat(invoke(client,proxy.endpointUrl(),"first").statusCode()).isEqualTo(504);
            assertThat(invoke(client,proxy.endpointUrl(),"second").statusCode()).isEqualTo(504);
            assertThat(invoke(client,proxy.endpointUrl(),"third").statusCode()).isEqualTo(503);
            assertThat(callsA.get()).isEqualTo(1); assertThat(callsB.get()).isEqualTo(1);
        } finally { a.stop(0); b.stop(0); }
    }
    private static HttpServer activeBackend(String incarnation, java.util.concurrent.atomic.AtomicInteger calls) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/runtime/status", e -> reply(e,200,"{\"schemaVersion\":1,\"incarnation\":\""+incarnation+"\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}"));
        server.createContext("/invoke", e -> { calls.incrementAndGet(); reply(e,504,"timeout"); });
        server.createContext("/runtime/executions/", e -> reply(e,200,"{\"state\":\"ACTIVE\",\"incarnation\":\""+incarnation+"\"}"));
        server.start(); return server;
    }
    private static HttpResponse<String> invoke(HttpClient client,String url,String id) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).header("X-Execution-Id",id).header("X-Dispatch-Attempt","1").POST(HttpRequest.BodyPublishers.ofString("{\"input\":null}")).build(),HttpResponse.BodyHandlers.ofString());
    }
    private static void reply(com.sun.net.httpserver.HttpExchange e,int status,String json) throws java.io.IOException {
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8); e.sendResponseHeaders(status,bytes.length);
        e.getResponseBody().write(bytes); e.close();
    }
}
