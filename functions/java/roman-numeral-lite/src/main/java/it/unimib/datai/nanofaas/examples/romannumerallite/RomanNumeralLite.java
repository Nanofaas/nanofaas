package it.unimib.datai.nanofaas.examples.romannumerallite;

import it.unimib.datai.nanofaas.sdk.lite.FunctionContext;
import it.unimib.datai.nanofaas.sdk.lite.NanofaasRuntime;
import org.slf4j.Logger;

import java.util.Map;

public final class RomanNumeralLite {
    private static final Logger log = FunctionContext.getLogger(RomanNumeralLite.class);
    private static final int[] VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};
    private static final String ERROR_KEY = "error";

    private RomanNumeralLite() {
    }

    @SuppressWarnings("java:S1172") // args is required by the JVM main(String[]) contract
    public static void main(String[] args) {
        NanofaasRuntime.builder()
                .handler(request -> handle(request.input()))
                .functionName("roman-numeral-lite")
                .build()
                .start();
    }

    static Object handle(Object input) {
        log.info("roman-numeral-lite invoked, executionId={}", FunctionContext.getExecutionId());

        if (!(input instanceof Map<?, ?> values)) {
            return Map.of(ERROR_KEY, "Input must be a JSON object");
        }
        if (!values.containsKey("number")) {
            return Map.of(ERROR_KEY, "missing required field: number");
        }
        if (!(values.get("number") instanceof Number rawNumber)) {
            return Map.of(ERROR_KEY, "field 'number' must be an integer");
        }

        int number = rawNumber.intValue();
        if (number < 1 || number > 3999) {
            return Map.of(ERROR_KEY, "number must be between 1 and 3999, got: " + number);
        }
        return Map.of("roman", toRoman(number));
    }

    private static String toRoman(int number) {
        StringBuilder roman = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            while (number >= VALUES[i]) {
                roman.append(SYMBOLS[i]);
                number -= VALUES[i];
            }
        }
        return roman.toString();
    }
}
