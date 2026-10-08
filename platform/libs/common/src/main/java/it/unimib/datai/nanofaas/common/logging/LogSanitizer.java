package it.unimib.datai.nanofaas.common.logging;

/** Prevents untrusted values from creating forged log lines. */
public final class LogSanitizer {
    private LogSanitizer() {
    }

    public static String singleLine(Object value) {
        String text = String.valueOf(value);
        if (text.indexOf('\r') < 0 && text.indexOf('\n') < 0
                && text.indexOf('\u0085') < 0 && text.indexOf('\u2028') < 0
                && text.indexOf('\u2029') < 0) {
            return text;
        }
        return text.replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\u0085", "\\u0085")
                .replace("\u2028", "\\u2028")
                .replace("\u2029", "\\u2029");
    }
}
