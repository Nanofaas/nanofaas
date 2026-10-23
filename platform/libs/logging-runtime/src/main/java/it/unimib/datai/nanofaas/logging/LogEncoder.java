package it.unimib.datai.nanofaas.logging;

import ch.qos.logback.classic.PatternLayout;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.encoder.EncoderBase;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Shared console encoder; it never installs appenders or changes the host logger. */
public final class LogEncoder extends EncoderBase<ILoggingEvent> {
    private final ObjectMapper mapper = new ObjectMapper();
    private final PatternLayout text = new PatternLayout();
    private String format = "text";
    private String service = "function-runtime";
    public void setFormat(String format) { this.format = format; }
    public void setService(String service) { if (service != null && !service.isBlank()) this.service = service; }
    @Override public void start() {
        if (!"json".equals(format) && !"text".equals(format)) {
            addError("Log format must be json or text"); return;
        }
        text.setContext(getContext());
        text.setPattern("%d{HH:mm:ss.SSS} [%thread] %-5level %logger{36} [exec=%X{executionId:-} trace=%X{traceId:-}] - %msg%n");
        text.start(); super.start();
    }
    @Override public void stop() { text.stop(); super.stop(); }
    @Override public byte[] headerBytes() { return new byte[0]; }
    @Override public byte[] footerBytes() { return new byte[0]; }
    @Override public byte[] encode(ILoggingEvent event) {
        if (!"json".equals(format)) return text.doLayout(event).getBytes(StandardCharsets.UTF_8);
        Map<String, Object> record = new LinkedHashMap<>();
        record.put("schemaVersion", 1);
        record.put("timestamp", Instant.ofEpochMilli(event.getTimeStamp()).toString());
        record.put("level", event.getLevel().toString()); record.put("service", service);
        record.put("logger", event.getLoggerName()); record.put("message", event.getFormattedMessage());
        Map<String, String> mdc = event.getMDCPropertyMap();
        for (String key : new String[]{"executionId", "traceId", "functionName", "event", "status"}) {
            String value = mdc.get(key);
            if (value != null && !value.isBlank()) record.put(key, value);
        }
        String attempt = mdc.get("attempt");
        if (attempt != null) {
            try { int n = Integer.parseInt(attempt); if (n > 0) record.put("attempt", n); }
            catch (NumberFormatException ignored) { /* Unknown attempt is omitted. */ }
        }
        if (event.getThrowableProxy() != null) record.put("exception", ThrowableProxyUtil.asString(event.getThrowableProxy()));
        if (event.getKeyValuePairs() != null && !event.getKeyValuePairs().isEmpty()) {
            Map<String, Object> attributes = new LinkedHashMap<>();
            event.getKeyValuePairs().forEach(pair -> attributes.put(pair.key, pair.value));
            record.put("attributes", attributes);
        }
        try { return (mapper.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8); }
        catch (JsonProcessingException exception) {
            addError("Cannot serialize log event", exception);
            // A hostile application attribute must not suppress the operational event.
            record.remove("attributes");
            try { return (mapper.writeValueAsString(record) + "\n").getBytes(StandardCharsets.UTF_8); }
            catch (JsonProcessingException failure) { throw new IllegalStateException("Cannot serialize log event", failure); }
        }
    }
}
