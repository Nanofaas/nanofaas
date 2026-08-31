package it.unimib.datai.nanofaas.cli.http;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlPlaneCapabilitiesTest {

    private static final String FULL_OPENAPI = """
            openapi: 3.0.0
            info:
              title: nanoFaaS Control Plane
              version: 1.0.0
            paths:
              /v1/functions/{name}:
                patch:
                  summary: Update a function
              /v1/functions/{name}/replicas:
                get:
                  summary: Read replica count
                put:
                  summary: Set replica count
              /v1/functions/{name}:enqueue:
                post:
                  summary: Enqueue an async invocation
              /modules/build-metadata:
                get:
                  summary: Build metadata
              /v1/admin/runtime-config:
                get:
                  summary: Read runtime config
              /v1/admin/runtime-config/{namespace}:
                patch:
                  summary: Update runtime config
              /v1/admin/runtime-config/{namespace}/validate:
                post:
                  summary: Validate runtime config
            """;

    @Test
    void detectsAllCapabilitiesFromOpenApi() {
        ControlPlaneCapabilities caps = ControlPlaneCapabilities.fromOpenApi(FULL_OPENAPI);

        assertThat(caps.functionUpdate()).isTrue();
        assertThat(caps.replicas()).isTrue();
        assertThat(caps.asyncInvocation()).isTrue();
        assertThat(caps.buildMetadata()).isTrue();
        assertThat(caps.runtimeConfig()).isTrue();
    }

    @Test
    void missingRouteYieldsFalseCapability() {
        ControlPlaneCapabilities caps = ControlPlaneCapabilities.fromOpenApi("""
                openapi: 3.0.0
                paths:
                  /v1/functions/{name}:enqueue:
                    post:
                      summary: Enqueue an async invocation
                """);

        assertThat(caps.asyncInvocation()).isTrue();
        assertThat(caps.functionUpdate()).isFalse();
        assertThat(caps.replicas()).isFalse();
        assertThat(caps.buildMetadata()).isFalse();
        assertThat(caps.runtimeConfig()).isFalse();
    }

    @Test
    void missingMethodYieldsFalseCapability() {
        ControlPlaneCapabilities caps = ControlPlaneCapabilities.fromOpenApi("""
                openapi: 3.0.0
                paths:
                  /v1/functions/{name}/replicas:
                    get:
                      summary: Read replica count
                """);

        assertThat(caps.replicas()).isFalse();
    }

    @Test
    void invalidYamlThrowsIllegalArgumentException() {
        assertThatThrownBy(() -> ControlPlaneCapabilities.fromOpenApi("[1, 2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid control-plane OpenAPI document");
    }
}
