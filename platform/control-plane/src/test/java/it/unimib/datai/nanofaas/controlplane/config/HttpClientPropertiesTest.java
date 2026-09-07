package it.unimib.datai.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientPropertiesTest {

    @Test
    void constructor_withNullValues_appliesDefaults() {
        HttpClientProperties properties = new HttpClientProperties(null, null, null, null, null, null);

        assertThat(properties.connectTimeoutMs()).isEqualTo(5000);
        assertThat(properties.readTimeoutMs()).isEqualTo(30000);
        assertThat(properties.maxInMemorySizeMb()).isEqualTo(16);
        assertThat(properties.maxConnections()).isEqualTo(500);
        assertThat(properties.pendingAcquireMaxCount()).isEqualTo(1000);
        assertThat(properties.pendingAcquireTimeoutMs()).isEqualTo(45_000);
    }

    @Test
    void constructor_withNonPositiveValues_appliesDefaults() {
        HttpClientProperties properties = new HttpClientProperties(0, -1, 0, 0, -3, -1);

        assertThat(properties.connectTimeoutMs()).isEqualTo(5000);
        assertThat(properties.readTimeoutMs()).isEqualTo(30000);
        assertThat(properties.maxInMemorySizeMb()).isEqualTo(16);
        assertThat(properties.maxConnections()).isEqualTo(500);
        assertThat(properties.pendingAcquireMaxCount()).isEqualTo(1000);
        assertThat(properties.pendingAcquireTimeoutMs()).isEqualTo(45_000);
    }

    @Test
    void constructor_withPositiveValues_keepsProvidedValues() {
        HttpClientProperties properties = new HttpClientProperties(1500, 4200, 8, 40, 120, 750);

        assertThat(properties.connectTimeoutMs()).isEqualTo(1500);
        assertThat(properties.readTimeoutMs()).isEqualTo(4200);
        assertThat(properties.maxInMemorySizeMb()).isEqualTo(8);
        assertThat(properties.maxConnections()).isEqualTo(40);
        assertThat(properties.pendingAcquireMaxCount()).isEqualTo(120);
        assertThat(properties.pendingAcquireTimeoutMs()).isEqualTo(750);
    }

    @Test
    void pendingAcquireMaxCount_defaultsToTwiceTheConfiguredConnections() {
        // Mirrors Reactor Netty's own relationship between the two, so setting only
        // maxConnections keeps a sane queue instead of silently keeping the 1000 default.
        HttpClientProperties properties = new HttpClientProperties(null, null, null, 40, null, null);

        assertThat(properties.pendingAcquireMaxCount()).isEqualTo(80);
    }
}
