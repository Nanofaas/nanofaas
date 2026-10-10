package it.unimib.datai.nanofaas.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Executors;
import static org.junit.jupiter.api.Assertions.*;

class LogEncoderTest {
    @Test void consumesSharedSdkCorpus() throws Exception {
        var mapper = new ObjectMapper();
        var corpus = mapper.readTree(java.nio.file.Path.of(System.getProperty("nanofaas.loggingCorpus")).toFile());
        var encoder = new LogEncoder(); encoder.setFormat("json");
        encoder.setContext(new LoggerContext()); encoder.start();
        for (var item : corpus.get("cases")) {
            var input = item.get("input"); var event = new LoggingEvent();
            event.setLoggerName("contract"); event.setLevel(Level.valueOf(input.get("level").asText()));
            event.setTimeStamp(1791626400000L); event.setMessage(input.get("message").asText());
            var context = new java.util.HashMap<String, String>();
            for (String key : new String[]{"executionId", "traceId"}) {
                if (input.has(key)) context.put(key, input.get(key).asText());
            }
            event.setMDCPropertyMap(context);
            if (input.has("attributes")) {
                input.get("attributes").properties().forEach(entry -> event.addKeyValuePair(
                        new org.slf4j.event.KeyValuePair(entry.getKey(), entry.getValue().asText())));
            }
            if (input.has("exception")) event.setThrowableProxy(new ch.qos.logback.classic.spi.ThrowableProxy(
                    new IllegalStateException(input.get("exception").asText())));
            var line = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertEquals(1, line.lines().count()); var json = mapper.readTree(line);
            for (var entry : item.get("expected").properties()) {
                if (entry.getKey().equals("timestamp")) continue;
                if (entry.getKey().equals("exception")) assertTrue(json.get("exception").asText().contains(entry.getValue().asText()));
                else assertEquals(entry.getValue(), json.get(entry.getKey()), item.get("name").asText());
            }
            if (!input.has("executionId")) assertFalse(json.has("executionId"));
        }
    }
    @Test void emitsCanonicalJsonForEveryModuleWithCorrectEscaping() throws Exception {
        var encoder = new LogEncoder();
        encoder.setFormat("json"); encoder.setService("control-plane");
        encoder.setContext(new LoggerContext()); encoder.start();
        for (String logger : new String[]{"common", "execution-runtime", "offload", "p2p-discovery"}) {
            var event = new LoggingEvent(); event.setLoggerName(logger); event.setLevel(Level.INFO);
            event.setTimeStamp(1791626400000L); event.setMessage("caffè \"line\"\nnext");
            event.setMDCPropertyMap(Map.of("executionId", "exec-7", "traceId", "trace-7"));
            String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
            assertEquals(1, line.lines().count());
            var json = new ObjectMapper().readTree(line);
            assertEquals(1, json.get("schemaVersion").asInt());
            assertEquals("INFO", json.get("level").asText());
            assertEquals("control-plane", json.get("service").asText());
            assertEquals(logger, json.get("logger").asText());
            assertEquals("exec-7", json.get("executionId").asText());
            assertEquals(event.getMessage(), json.get("message").asText());
            assertTrue(json.get("timestamp").asText().endsWith("Z"));
        }
    }
    @Test void absentContextDoesNotInventIdsAndTextRemainsAvailable() throws Exception {
        var encoder = new LogEncoder(); encoder.setContext(new LoggerContext()); encoder.setFormat("json"); encoder.start();
        var event = new LoggingEvent(); event.setLoggerName("scheduler"); event.setLevel(Level.INFO);
        event.setMessage("batch"); event.setMDCPropertyMap(Map.of());
        var json = new ObjectMapper().readTree(encoder.encode(event));
        assertFalse(json.has("executionId")); assertFalse(json.has("traceId"));
        encoder.stop(); encoder.setFormat("text"); encoder.start();
        assertTrue(new String(encoder.encode(event), StandardCharsets.UTF_8).contains("batch"));
    }
    @Test void scopedContextRestoresParentOnExceptionAndWorkerReuse() throws Exception {
        MDC.put("traceId", "outer");
        try {
            assertThrows(IllegalStateException.class, () -> {
                try (var ignored = LoggingContext.open(Map.of("executionId", "child"))) {
                    assertEquals("child", MDC.get("executionId")); assertNull(MDC.get("traceId"));
                    throw new IllegalStateException();
                }
            });
            assertEquals("outer", MDC.get("traceId")); assertNull(MDC.get("executionId"));
            try (var executor = Executors.newSingleThreadExecutor()) {
                for (String id : new String[]{"one", "two"}) {
                    var task = LoggingContext.wrap(Map.of("executionId", id), () -> assertEquals(id, MDC.get("executionId")));
                    executor.submit(task).get();
                    assertNull(executor.submit(() -> MDC.get("executionId")).get());
                }
            }
        } finally { MDC.clear(); }
    }
}
