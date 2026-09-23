package it.unimib.datai.nanofaas.sdk.runtime;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@SuppressWarnings("EmptyCatch") // Closing a timed-out request body is best-effort.
final class RuntimePayloadLimitFilter extends OncePerRequestFilter {
    private static final byte[] TOO_LARGE = ("{\"error\":{\"code\":\"RUNTIME_INPUT_TOO_LARGE\","
            + "\"message\":\"Runtime input exceeds configured byte limit\"}}")
            .getBytes(StandardCharsets.UTF_8);
    private static final byte[] READ_TIMEOUT = ("{\"error\":{\"code\":\"RUNTIME_BODY_READ_TIMEOUT\","
            + "\"message\":\"Runtime request body read timed out\"}}")
            .getBytes(StandardCharsets.UTF_8);
    private final int maxInputBytes;
    private final long bodyReadTimeoutMs;

    @Autowired
    RuntimePayloadLimitFilter(
            @Value("${nanofaas.input.max-bytes:${NANOFAAS_MAX_INPUT_BYTES:1048576}}") int maxInputBytes,
            @Value("${nanofaas.body.read.timeout-ms:${NANOFAAS_BODY_READ_TIMEOUT_MS:5000}}") long bodyReadTimeoutMs) {
        if (maxInputBytes <= 0 || bodyReadTimeoutMs <= 0) {
            throw new IllegalArgumentException("max input bytes and body read timeout must be positive");
        }
        this.maxInputBytes = maxInputBytes;
        this.bodyReadTimeoutMs = bodyReadTimeoutMs;
    }

    RuntimePayloadLimitFilter(int maxInputBytes) { this(maxInputBytes, 5_000); }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!"POST".equalsIgnoreCase(request.getMethod()) || !"/invoke".equals(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        ServletInputStream input = request.getInputStream();
        FutureTask<byte[]> read = new FutureTask<>(() -> input.readNBytes(maxInputBytes + 1));
        Thread reader = Thread.ofVirtual().name("nanofaas-body-reader").start(read);
        final byte[] body;
        try {
            body = read.get(bodyReadTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException _) {
            read.cancel(true);
            reader.interrupt();
            response.setStatus(408);
            response.setContentType("application/json");
            response.getOutputStream().write(READ_TIMEOUT);
            try { input.close(); } catch (IOException _) { }
            return;
        } catch (InterruptedException ex) {
            read.cancel(true);
            reader.interrupt();
            try { input.close(); } catch (IOException _) { }
            Thread.currentThread().interrupt();
            throw new ServletException("Interrupted while reading invocation body", ex);
        } catch (ExecutionException ex) {
            if (ex.getCause() instanceof IOException ioException) throw ioException;
            throw new ServletException("Failed to read invocation body", ex.getCause());
        }
        if (body.length > maxInputBytes) {
            response.setStatus(413);
            response.setContentType("application/json");
            response.getOutputStream().write(TOO_LARGE);
            return;
        }
        chain.doFilter(new BufferedRequest(request, body), response);
    }

    private static final class BufferedRequest extends HttpServletRequestWrapper {
        private final byte[] body;
        private BufferedRequest(HttpServletRequest request, byte[] body) { super(request); this.body = body; }
        @Override public ServletInputStream getInputStream() {
            ByteArrayInputStream input = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override public boolean isFinished() { return input.available() == 0; }
                @Override public boolean isReady() { return true; }
                // The body is already buffered, so a non-blocking reader can read it all at once.
                @Override public void setReadListener(ReadListener listener) {
                    try {
                        listener.onDataAvailable();
                        listener.onAllDataRead();
                    } catch (IOException failure) {
                        listener.onError(failure);
                    }
                }
                @Override public int read() { return input.read(); }
                @Override public int read(byte[] bytes, int off, int len) { return input.read(bytes, off, len); }
            };
        }
    }
}
