package it.unimib.datai.nanofaas.examples.romannumeral;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.FunctionContext;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import org.slf4j.Logger;

import java.util.Map;

@NanofaasFunction
public class RomanNumeralHandler implements FunctionHandler {

    private static final Logger log = FunctionContext.getLogger(RomanNumeralHandler.class);

    private static final int[] VALUES   = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] SYMS  = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(InvocationRequest request) {
        log.info("roman-numeral invoked, executionId={}", FunctionContext.getExecutionId());

        var input = (Map<String, Object>) request.input();

        if (!input.containsKey("number")) {
            return Map.of("error", "missing required field: number");
        }
        int n;
        try {
            n = ((Number) input.get("number")).intValue();
        } catch (ClassCastException e) {
            return Map.of("error", "field 'number' must be an integer");
        }
        if (n < 1 || n > 3999) {
            return Map.of("error", "number must be between 1 and 3999, got: " + n);
        }
        return Map.of("roman", toRoman(n));
    }

    static String toRoman(int n) {
        var sb = new StringBuilder();
        for (int i = 0; i < VALUES.length; i++) {
            while (n >= VALUES[i]) {
                sb.append(SYMS[i]);
                n -= VALUES[i];
            }
        }
        return sb.toString();
    }
}
