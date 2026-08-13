package it.unimib.datai.nanofaas.examples.qrcode;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import it.unimib.datai.nanofaas.common.runtime.FunctionHandler;
import it.unimib.datai.nanofaas.common.runtime.HandlerResponse;
import it.unimib.datai.nanofaas.sdk.NanofaasFunction;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.Map;

@NanofaasFunction
public class QrCodeHandler implements FunctionHandler {
    @Override
    public Object handle(InvocationRequest request) {
        if (!(request.input() instanceof Map<?, ?> input)) {
            return error("Input must be a JSON object");
        }
        var text = input.get("text");
        if (text == null) {
            return error("missing required field: text");
        }
        if (!(text instanceof String value) || value.isEmpty()) {
            return error("field 'text' must be a non-empty string");
        }
        var size = input.containsKey("size") ? input.get("size") : 256;
        if (!(size instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue()) || number.intValue() < 128 || number.intValue() > 1024) {
            return error("field 'size' must be an integer between 128 and 1024");
        }
        if (value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1024) {
            return error("field 'text' must be at most 1024 UTF-8 bytes");
        }
        try (var output = new ByteArrayOutputStream()) {
            MatrixToImageWriter.writeToStream(new QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, number.intValue(), number.intValue()), "PNG", output);
            return new HandlerResponse(Base64.getEncoder().encodeToString(output.toByteArray()), 200, Map.of("Content-Type", "image/png"), "base64");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to generate QR code", exception);
        }
    }

    private static HandlerResponse error(String message) {
        return HandlerResponse.of(Map.of("error", message), 422);
    }
}
