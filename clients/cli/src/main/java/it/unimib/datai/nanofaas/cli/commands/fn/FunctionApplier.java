package it.unimib.datai.nanofaas.cli.commands.fn;

import it.unimib.datai.nanofaas.cli.http.ControlPlaneClient;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneError;
import it.unimib.datai.nanofaas.cli.http.ControlPlaneHttpException;
import it.unimib.datai.nanofaas.cli.http.FunctionDetails;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;

public final class FunctionApplier {
    private FunctionApplier() {}

    public static void apply(ControlPlaneClient client, FunctionSpec desired) {
        try {
            client.registerFunction(desired);
            return;
        } catch (ControlPlaneHttpException e) {
            if (e.getStatus() != 409) {
                throw mapError(e);
            }
        }

        FunctionDetails existing = client.getFunctionOrNull(desired.name());
        if (existing == null) {
            client.registerFunction(desired);
            return;
        }

        if (!existing.matches(desired)) {
            client.deleteFunction(desired.name());
            client.registerFunction(desired);
        }
    }

    private static RuntimeException mapError(ControlPlaneHttpException exception) {
        var code = ControlPlaneError.fromBody(exception.getBody()).code();

        // The HTTP response may not be parseable as a ControlPlaneError. As
        // example, it may returns a non-JSON response with the following
        // content: "Control-plane HTTP 503 during register function
        // (http://localhost:8080/v1/functions): Ambiguous managed deployment
        // provider selection for function 'qr-code-go': [container-local,
        // k8s]".
        if (code == null) {
            return exception;
        }

        return switch (code) {
            case "IMAGE_NOT_FOUND" -> new IllegalArgumentException(
                    "Image not found in registry. Check image name/tag and retry.");
            case "IMAGE_PULL_AUTH_REQUIRED" -> new IllegalArgumentException(
                    "Image pull authentication failed. Configure imagePullSecrets and retry.");
            case "IMAGE_REGISTRY_UNAVAILABLE" -> new IllegalArgumentException(
                    "Registry unavailable while validating image. Retry later.");
            default -> exception;
        };
    }
}
