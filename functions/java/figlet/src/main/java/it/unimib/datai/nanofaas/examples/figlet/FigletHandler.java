package it.unimib.datai.nanofaas.examples.figlet;

import com.github.lalyos.jfiglet.FigletFont;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.sdk.FunctionContext;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Renders text as ASCII art using FIGlet fonts.
 *
 * Input: {@code {"text": "nanoFaaS", "font": "standard"}}  (font defaults to "standard")
 * Output: {@code {"asciiArt": "...", "font": "standard", "text": "nanoFaaS"}}
 */
@NanofaasFunction
public class FigletHandler implements FunctionHandler {

    private static final Logger log = FunctionContext.getLogger(FigletHandler.class);
    private static final String DEFAULT_FONT = "standard";

    @Override
    @SuppressWarnings("unchecked")
    public Object handle(InvocationRequest request) {
        log.info("figlet invoked, executionId={}", FunctionContext.getExecutionId());

        Map<String, Object> input = toMap(request.input());
        String text = input.getOrDefault("text", "").toString();
        if (text.isBlank()) {
            return Map.of("error", "Field 'text' is required and must be non-empty");
        }

        String fontName = input.getOrDefault("font", DEFAULT_FONT).toString();
        if (fontName.isBlank()) {
            fontName = DEFAULT_FONT;
        }

        String asciiArt;
        String fontUsed = fontName;
        try {
            asciiArt = FigletFont.convertOneLine(classpathFont(fontName), text);
        } catch (IOException | NullPointerException _) {
            log.warn("FIGlet font '{}' not found, falling back to standard", fontName);
            try {
                asciiArt = FigletFont.convertOneLine(text);
            } catch (IOException | NullPointerException ex) {
                return Map.of(
                    "error", "Failed to render FIGlet text: " + ex.getMessage()
                );
            }
            fontUsed = DEFAULT_FONT;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("text", text);
        result.put("font", fontUsed);
        result.put("asciiArt", asciiArt);
        return result;
    }

    private static String classpathFont(String name) {
        if (name.startsWith("classpath:")) {
            return name;
        }
        return "classpath:/" + (name.endsWith(".flf") ? name : name + ".flf");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> toMap(Object input) {
        if (input instanceof Map) {
            return (Map<String, Object>) input;
        }
        if (input instanceof String s) {
            return Map.of("text", s);
        }
        return Map.of();
    }
}
