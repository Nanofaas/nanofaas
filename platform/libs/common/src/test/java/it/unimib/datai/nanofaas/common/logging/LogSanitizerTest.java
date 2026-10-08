package it.unimib.datai.nanofaas.common.logging;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LogSanitizerTest {
    @Test
    void escapesEveryLineSeparatorThatCouldForgeAnotherLogEntry() {
        assertEquals("first\\r\\nsecond\\u0085third\\u2028fourth\\u2029fifth",
                LogSanitizer.singleLine("first\r\nsecond\u0085third\u2028fourth\u2029fifth"));
    }

    @Test
    void returnsAnAlreadySafeStringWithoutAllocatingACopy() {
        String safe = "execution-123";

        assertSame(safe, LogSanitizer.singleLine(safe));
    }
}
