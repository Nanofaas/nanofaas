package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class DeploymentProviderResolver {

    private final List<ManagedDeploymentProvider> providers;
    private final DeploymentProperties properties;

    public DeploymentProviderResolver(@Autowired(required = false) List<ManagedDeploymentProvider> providers,
                                      DeploymentProperties properties) {
        this.providers = providers == null ? List.of() : List.copyOf(providers);
        this.properties = properties == null ? new DeploymentProperties() : properties;
    }

    public ManagedDeploymentProvider resolve(FunctionSpec spec, String backendHint) {
        if (backendHint != null && !backendHint.isBlank()) {
            return resolveExplicit(spec, backendHint);
        }
        if (properties.defaultBackend() != null && !properties.defaultBackend().isBlank()) {
            return resolveExplicit(spec, properties.defaultBackend());
        }
        return resolveImplicit(spec);
    }

    public ProvisionResult resolveAndProvision(FunctionSpec spec, String backendHint) {
        try {
            return resolve(spec, backendHint).provision(spec);
        } catch (IllegalStateException ex) {
            if (spec.endpointUrl() != null && !spec.endpointUrl().isBlank()) {
                return new ProvisionResult(
                        spec.endpointUrl(),
                        null,
                        ExecutionMode.POOL,
                        ex.getMessage(),
                        Map.of()
                );
            }
            throw ex;
        }
    }

    public ManagedDeploymentProvider requireBackend(String backendId) {
        return providers.stream()
                .filter(provider -> provider.backendId().equals(backendId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No managed deployment provider with id '" + backendId + "' found"));
    }

    private ManagedDeploymentProvider resolveExplicit(FunctionSpec spec, String backendId) {
        ManagedDeploymentProvider provider = requireBackend(backendId);
        if (!provider.isAvailable()) {
            throw new IllegalStateException("Provider '" + backendId + "' is not available");
        }
        if (!provider.supports(spec)) {
            throw new IllegalStateException(
                    "Provider '" + backendId + "' does not support function '" + spec.name() + "'");
        }
        return provider;
    }

    private ManagedDeploymentProvider resolveImplicit(FunctionSpec spec) {
        List<ManagedDeploymentProvider> candidates = providers.stream()
                .filter(ManagedDeploymentProvider::isAvailable)
                .filter(provider -> provider.supports(spec))
                .toList();

        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "No managed deployment provider available for function '" + spec.name() + "'");
        }
        if (candidates.size() > 1) {
            List<String> ids = candidates.stream()
                    .map(ManagedDeploymentProvider::backendId)
                    .sorted()
                    .toList();
            throw new IllegalStateException(
                    "Ambiguous managed deployment provider selection for function '"
                            + spec.name()
                            + "': "
                            + ids);
        }
        return candidates.getFirst();
    }
}
