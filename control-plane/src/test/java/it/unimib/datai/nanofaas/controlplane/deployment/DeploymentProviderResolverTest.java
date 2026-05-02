package it.unimib.datai.nanofaas.controlplane.deployment;

import it.unimib.datai.nanofaas.common.model.ExecutionMode;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DeploymentProviderResolverTest {

    @Test
    void explicitHint_selectsMatchingProvider() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        ManagedDeploymentProvider local = stubProvider("container-local", true, true);

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(k8s, local),
                new DeploymentProperties(null)
        );

        assertThat(resolver.resolve(spec("fn"), "k8s")).isSameAs(k8s);
    }

    @Test
    void explicitHint_unavailableProvider_throws() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", false, true);

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(k8s),
                new DeploymentProperties(null)
        );

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), "k8s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void explicitHint_doesNotSupport_throws() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, false);

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(k8s),
                new DeploymentProperties(null)
        );

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), "k8s"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not support");
    }

    @Test
    void defaultBackend_preferred() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        ManagedDeploymentProvider local = stubProvider("container-local", true, true);

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(k8s, local),
                new DeploymentProperties("container-local")
        );

        assertThat(resolver.resolve(spec("fn"), null)).isSameAs(local);
    }

    @Test
    void blankExplicitHint_fallsBackToDefaultBackend() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        ManagedDeploymentProvider local = stubProvider("container-local", true, true);

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(k8s, local),
                new DeploymentProperties("container-local")
        );

        assertThat(resolver.resolve(spec("fn"), "   ")).isSameAs(local);
    }

    @Test
    void singleProvider_selectedWhenNoHintOrDefault() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(k8s),
                new DeploymentProperties(null)
        );

        assertThat(resolver.resolve(spec("fn"), null)).isSameAs(k8s);
    }

    @Test
    void ambiguousProviders_throwExplicitError() {
        ManagedDeploymentProvider k8s = stubProvider("k8s", true, true);
        ManagedDeploymentProvider local = stubProvider("container-local", true, true);

        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(k8s, local),
                new DeploymentProperties(null)
        );

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Ambiguous");
    }

    @Test
    void noProviders_throwExplicitError() {
        DeploymentProviderResolver resolver = new DeploymentProviderResolver(
                List.of(),
                new DeploymentProperties(null)
        );

        assertThatThrownBy(() -> resolver.resolve(spec("fn"), null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No managed deployment provider");
    }

    private static ManagedDeploymentProvider stubProvider(String id, boolean available, boolean supports) {
        ManagedDeploymentProvider provider = mock(ManagedDeploymentProvider.class);
        when(provider.backendId()).thenReturn(id);
        when(provider.isAvailable()).thenReturn(available);
        when(provider.supports(any())).thenReturn(supports);
        return provider;
    }

    private static FunctionSpec spec(String name) {
        return new FunctionSpec(
                name,
                "img:latest",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                ExecutionMode.DEPLOYMENT,
                null,
                null,
                null
        );
    }
}
