package it.unimib.datai.nanofaas.containerdeployment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
@EnabledIfEnvironmentVariable(named="NANOFAAS_ONE_SHOT_WORKLOAD_BINARY",matches=".+")
class RustPhysicalProxyTest {
    @Test void actualAdmissionRefusalsDoNotAttributeExecutionAndAllowReplicaReuse() throws Exception {
        int port;
        try (var socket = new java.net.ServerSocket(0)) { port = socket.getLocalPort(); }
        var builder = new ProcessBuilder(System.getenv("NANOFAAS_ONE_SHOT_WORKLOAD_BINARY"));
        builder.environment().put("PORT", Integer.toString(port));
        builder.environment().put("NANOFAAS_MAX_CONCURRENT_HANDLERS", "1");
        builder.environment().put("NANOFAAS_MAX_INPUT_BYTES", "128");
        var process = builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        var backend = URI.create("http://127.0.0.1:" + port);
        try (var client = HttpClient.newHttpClient(); var proxy = new RoundRobinFunctionProxy("127.0.0.1", 2, Duration.ofSeconds(2))) {
            var probe = new HttpRuntimeExecutionProbe(client, Duration.ofMillis(200));
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (true) {
                try { probe.eligibleIncarnation(backend); break; }
                catch (IllegalStateException failure) { if (System.nanoTime() > deadline) throw failure; Thread.sleep(20); }
            }
            proxy.enablePhysicalSlots();
            proxy.updateBackends(List.of(backend.toString()));
            String valid = "{\"input\":{\"iterations\":1,\"working_set_bytes\":1,\"seed\":1}}";
            for (String body : List.of("{\"input\":{}}", valid.substring(0, valid.length() - 1)
                    + ",\"metadata\":{\"extra\":\"" + "x".repeat(256) + "\"}}")) {
                String id = body.length() > 128 ? "oversized" : "invalid-input";
                var request = HttpRequest.newBuilder(URI.create(proxy.endpointUrl()))
                        .header("X-Execution-Id", id).header("X-Dispatch-Attempt", "1")
                        .POST(HttpRequest.BodyPublishers.ofString(body)).build();
                var response = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(body.length() > 128 ? 413 : 400);
                assertThat(response.headers().firstValue("X-NanoFaaS-Handler-Executed")).isEmpty();
                assertThat(probe.observe(backend, id).handlerStarted()).isFalse();
                assertThat(proxy.awaitDrained(backend.toString(), Duration.ofSeconds(1))).isTrue();
            }
            var request = HttpRequest.newBuilder(URI.create(proxy.endpointUrl()))
                    .header("X-Execution-Id", "accepted").header("X-Dispatch-Attempt", "1")
                    .POST(HttpRequest.BodyPublishers.ofString(valid)).build();
            assertThat(client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
            assertThat(probe.observe(backend, "accepted").handlerStarted()).isTrue();
        } finally { process.destroy(); if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
    }
    @Test void runtimeTimeoutCannotReleaseReplicaBeforeBlockingCpuWorkFinishes() throws Exception {
        int port; try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        var builder=new ProcessBuilder(System.getenv("NANOFAAS_ONE_SHOT_WORKLOAD_BINARY"));
        builder.environment().put("PORT",Integer.toString(port));
        builder.environment().put("NANOFAAS_MAX_CONCURRENT_HANDLERS","1");
        builder.environment().put("NANOFAAS_HANDLER_TIMEOUT","10");
        var process=builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        var backend="http://127.0.0.1:"+port;
        try(var client=HttpClient.newHttpClient(); var proxy=new RoundRobinFunctionProxy("127.0.0.1",2,Duration.ofSeconds(2))) {
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(true) {
                try { new HttpRuntimeExecutionProbe(client,Duration.ofMillis(200)).eligibleIncarnation(URI.create(backend)); break; }
                catch(IllegalStateException failure) { if(System.nanoTime()>deadline) throw failure; Thread.sleep(20); }
            }
            proxy.enablePhysicalSlots(); proxy.updateBackends(List.of(backend));
            var request=HttpRequest.newBuilder(URI.create(proxy.endpointUrl())).header("X-Execution-Id","physical").header("X-Dispatch-Attempt","1")
                .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{\"iterations\":200000000,\"working_set_bytes\":1048576,\"seed\":42}}")).build();
            assertThat(client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(504);
            var probe=new HttpRuntimeExecutionProbe(client,Duration.ofSeconds(1));
            assertThat(probe.observe(URI.create(backend),"physical").state()).isEqualTo("ACTIVE");
            assertThat(proxy.awaitDrained(backend,Duration.ofMillis(50))).isFalse();
            proxy.beginDrain(backend);
            assertThat(proxy.awaitDrained(backend,Duration.ofSeconds(20))).isTrue();
            var metrics=client.send(HttpRequest.newBuilder(URI.create(backend+"/metrics")).GET().build(),HttpResponse.BodyHandlers.ofString()).body();
            assertThat(metrics).contains("nanofaas_runtime_active_handlers 0","nanofaas_runtime_replica_occupancy_seconds_count 1");
        } finally { process.destroy(); if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
    }    @Test void actualHandlerErrorHasPositiveExecutionEvidence() throws Exception {
        int port; try(var socket=new java.net.ServerSocket(0)) { port=socket.getLocalPort(); }
        var builder=new ProcessBuilder(System.getenv("NANOFAAS_ONE_SHOT_WORKLOAD_BINARY"));
        builder.environment().put("PORT",Integer.toString(port));
        builder.environment().put("NANOFAAS_MAX_CONCURRENT_HANDLERS","1");
        var process=builder.redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        var backend="http://127.0.0.1:"+port;
        try(var client=HttpClient.newHttpClient(); var proxy=new RoundRobinFunctionProxy("127.0.0.1",2,Duration.ofSeconds(2))) {
            long deadline=System.nanoTime()+Duration.ofSeconds(5).toNanos();
            while(true) {
                try { new HttpRuntimeExecutionProbe(client,Duration.ofMillis(200)).eligibleIncarnation(URI.create(backend)); break; }
                catch(IllegalStateException failure) { if(System.nanoTime()>deadline) throw failure; Thread.sleep(20); }
            }
            proxy.enablePhysicalSlots(); proxy.updateBackends(List.of(backend));
            var request=HttpRequest.newBuilder(URI.create(proxy.endpointUrl())).header("X-Execution-Id","handler-error").header("X-Dispatch-Attempt","1")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"input\":{\"iterations\":1000000001,\"working_set_bytes\":4096,\"seed\":42}}")).build();
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(500);
            assertThat(response.body()).contains("HANDLER_ERROR");
            assertThat(response.headers().firstValue("X-NanoFaaS-Handler-Executed")).contains("true");
        } finally { process.destroy(); if(!process.waitFor(2,java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly(); }
    }

}
