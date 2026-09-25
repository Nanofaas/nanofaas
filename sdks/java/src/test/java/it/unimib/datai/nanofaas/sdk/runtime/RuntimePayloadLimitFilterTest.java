package it.unimib.datai.nanofaas.sdk.runtime;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class RuntimePayloadLimitFilterTest {
    @Test
    void rejectsOversizedChunkedBodyBeforeControllerAndDoesNotRetainAnUnlimitedCopy() throws Exception {
        RuntimePayloadLimitFilter filter = new RuntimePayloadLimitFilter(16);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/invoke");
        request.setContent("{\"input\":\"0123456789\"}".getBytes(StandardCharsets.UTF_8));
        request.removeHeader("Content-Length");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean controllerCalled = new AtomicBoolean();

        filter.doFilter(request, response, (req, res) -> controllerCalled.set(true));

        assertEquals(413, response.getStatus());
        assertFalse(controllerCalled.get());
        assertEquals("RUNTIME_INPUT_TOO_LARGE",
                tools.jackson.databind.json.JsonMapper.builder().build()
                        .readTree(response.getContentAsByteArray()).path("error").path("code").asText());
    }

    @Test
    void bufferedBodyNotifiesANonBlockingReaderThatEverythingIsAvailable() throws Exception {
        RuntimePayloadLimitFilter filter = new RuntimePayloadLimitFilter(64);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/invoke");
        request.setContent("{\"input\":1}".getBytes(StandardCharsets.UTF_8));
        List<String> events = new ArrayList<>();

        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            ServletInputStream input = req.getInputStream();
            input.setReadListener(new ReadListener() {
                @Override public void onDataAvailable() throws IOException {
                    events.add("data:" + new String(input.readAllBytes(), StandardCharsets.UTF_8));
                }
                @Override public void onAllDataRead() { events.add("done"); }
                @Override public void onError(Throwable failure) { events.add("error"); }
            });
        });

        assertEquals(List.of("data:{\"input\":1}", "done"), events);
    }
}
