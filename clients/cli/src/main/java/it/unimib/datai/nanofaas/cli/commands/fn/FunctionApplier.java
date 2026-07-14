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
            if (e.status() != 409) {
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
        String code = ControlPlaneError.fromBody(exception.body()).code();
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
