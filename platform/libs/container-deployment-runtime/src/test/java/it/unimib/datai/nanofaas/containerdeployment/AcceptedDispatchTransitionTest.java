package it.unimib.datai.nanofaas.containerdeployment;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AcceptedDispatchTransitionTest {
    @ParameterizedTest
    @ValueSource(booleans={false,true})
    void localDispatchArrivingDuringTransitionExecutesOnceOnTheNewAllocation(boolean emptyDuringTransition) throws Exception {
        var oldCalls=new AtomicInteger();var newCalls=new AtomicInteger();
        var oldBackend=backend(oldCalls,"old");var newBackend=backend(newCalls,"new");
        try(var client=HttpClient.newHttpClient();var proxy=new RoundRobinFunctionProxy("127.0.0.1",1,Duration.ofSeconds(2))) {
            proxy.enablePhysicalSlots();proxy.updateBackends(List.of(url(oldBackend)));
            var initial=proxy.beginQuiesce();assertThat(proxy.resume(initial,initial.admissionEpoch())).isTrue();
            proxy.beginQuiesce();
            if(emptyDuringTransition) proxy.updateBackends(List.of());
            var request=HttpRequest.newBuilder(URI.create(proxy.endpointUrl())).header("X-Execution-Id","already-accepted")
                    .header("X-Dispatch-Attempt","1").POST(HttpRequest.BodyPublishers.ofString("{\"input\":null}")).build();
            var response=client.sendAsync(request,HttpResponse.BodyHandlers.ofString());
            long deadline=System.nanoTime()+Duration.ofSeconds(1).toNanos();
            while(proxy.snapshot().inFlight()==0 && !response.isDone() && System.nanoTime()-deadline<0) Thread.sleep(5);
            Thread.sleep(50);
            assertThat(response.isDone()).as("an accepted dispatch must not be rejected solely by temporary pool quiescence").isFalse();
            assertThat(oldCalls.get()).isZero();assertThat(newCalls.get()).isZero();
            proxy.updateBackends(List.of(url(newBackend)));
            var current=proxy.beginQuiesce();assertThat(proxy.resume(current,current.admissionEpoch())).isTrue();
            assertThat(response.get(2,java.util.concurrent.TimeUnit.SECONDS).statusCode()).isEqualTo(200);
            assertThat(oldCalls.get()).isZero();assertThat(newCalls.get()).isEqualTo(1);
        } finally {oldBackend.stop(0);newBackend.stop(0);}
    }

    private static String url(HttpServer server) {return "http://127.0.0.1:"+server.getAddress().getPort();}
    private static HttpServer backend(AtomicInteger calls,String incarnation) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/runtime/status",e->reply(e,200,"{\"schemaVersion\":1,\"incarnation\":\""+incarnation+"\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}"));
        server.createContext("/invoke",e->{calls.incrementAndGet();reply(e,200,"{\"result\":1}");});
        server.createContext("/runtime/executions/",e->{
            String id=e.getRequestURI().getPath().substring("/runtime/executions/".length());
            reply(e,200,"{\"state\":\"RELEASED\",\"incarnation\":\""+incarnation+"\",\"executionId\":\""+id+"\",\"dispatchAttempt\":\"1\",\"occupancySeconds\":0.1,\"handlerStarted\":true}");
        });
        server.start();return server;
    }
    private static void reply(HttpExchange exchange,int status,String body) throws java.io.IOException {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(status,bytes.length);
        exchange.getResponseBody().write(bytes);exchange.close();
    }
}
