package it.unimib.datai.nanofaas.controlplane.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HttpClientPropertiesTest {

    @Test
    void constructor_withNullValues_appliesDefaults() {
        HttpClientProperties properties = new HttpClientProperties(null, null, null);

        assertThat(properties.connectTimeoutMs()).isEqualTo(5000);
        assertThat(properties.readTimeoutMs()).isEqualTo(30000);
        assertThat(properties.maxInMemorySizeMb()).isEqualTo(1);
    }

    @Test
    void constructor_withNonPositiveValues_appliesDefaults() {
        HttpClientProperties properties = new HttpClientProperties(0, -1, 0);

        assertThat(properties.connectTimeoutMs()).isEqualTo(5000);
        assertThat(properties.readTimeoutMs()).isEqualTo(30000);
        assertThat(properties.maxInMemorySizeMb()).isEqualTo(1);
    }

    @Test
    void constructor_withPositiveValues_keepsProvidedValues() {
        HttpClientProperties properties = new HttpClientProperties(1500, 4200, 8);

        assertThat(properties.connectTimeoutMs()).isEqualTo(1500);
        assertThat(properties.readTimeoutMs()).isEqualTo(4200);
        assertThat(properties.maxInMemorySizeMb()).isEqualTo(8);
    }
}
