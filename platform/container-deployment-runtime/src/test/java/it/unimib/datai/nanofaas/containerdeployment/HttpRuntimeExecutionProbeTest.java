package it.unimib.datai.nanofaas.containerdeployment;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpRuntimeExecutionProbeTest {
    @Test
    void fractionalOrCoercedCapabilitiesCannotProveAnIdleSingleHandlerRuntime() throws Exception {
        String valid="{\"schemaVersion\":1,\"incarnation\":\"run\",\"physicalReleaseProof\":true,\"maxConcurrentHandlers\":1,\"activeHandlers\":0}";
        try(var client=HttpClient.newHttpClient()) {
            for(String malformed:List.of(
                    valid.replace("\"schemaVersion\":1","\"schemaVersion\":1.9"),
                    valid.replace("\"maxConcurrentHandlers\":1","\"maxConcurrentHandlers\":1.9"),
                    valid.replace("\"activeHandlers\":0","\"activeHandlers\":0.9"),
                    valid.replace("\"activeHandlers\":0","\"activeHandlers\":\"0\""),
                    valid.replace("\"physicalReleaseProof\":true","\"physicalReleaseProof\":\"true\""),
                    valid.replace("\"incarnation\":\"run\"","\"incarnation\":123"))) {
                var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/runtime/status", exchange->{
                    byte[] bytes=malformed.getBytes(StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,bytes.length);
                    exchange.getResponseBody().write(bytes);exchange.close();
                });
                server.start();
                try {
                    var probe=new HttpRuntimeExecutionProbe(client,Duration.ofSeconds(1));
                    var backend=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
                    assertThatThrownBy(()->probe.eligibleIncarnation(backend)).as(malformed)
                            .isInstanceOf(IllegalStateException.class);
                } finally {server.stop(0);}
            }
        }
    }
}
