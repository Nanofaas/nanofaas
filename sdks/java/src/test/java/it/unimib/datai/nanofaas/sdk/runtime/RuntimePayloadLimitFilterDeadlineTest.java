package it.unimib.datai.nanofaas.sdk.runtime;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePayloadLimitFilterDeadlineTest {
    @Test
    void stalledBodyReadIsCancelledAtConfiguredDeadline() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/invoke") {
            @Override public ServletInputStream getInputStream() {
                return new ServletInputStream() {
                    @Override public boolean isFinished() { return false; }
                    @Override public boolean isReady() { return true; }
                    @Override public void setReadListener(ReadListener listener) { /* no-op: this test double ignores the call */ }
                    @Override public int read() throws IOException {
                        try { release.await(); return -1; }
                        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
                    }
                    @Override public void close() { release.countDown(); }
                };
            }
        };
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean controllerCalled = new AtomicBoolean();
        long started = System.nanoTime();
        try {
            new RuntimePayloadLimitFilter(16, 40).doFilter(request, response,
                    (req, res) -> controllerCalled.set(true));

            assertEquals(408, response.getStatus());
            assertFalse(controllerCalled.get());
            assertTrue(response.getContentAsString().contains("RUNTIME_BODY_READ_TIMEOUT"));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 500);
        } finally {
            release.countDown();
        }
    }
}
