package it.unimib.datai.nanofaas.cli.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import it.unimib.datai.nanofaas.common.model.FunctionSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HttpJsonTest {

    @Test
    void fromJsonWithInvalidJsonThrows() {
        HttpJson json = new HttpJson();
        assertThatThrownBy(() -> json.fromJson("not valid json", FunctionSpec.class))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to parse JSON");
    }

    @Test
    void toJsonAndFromJsonRoundTrip() {
        HttpJson json = new HttpJson();
        FunctionSpec spec = new FunctionSpec("echo", "img:1",
                null, null, null, null, null, null, null, null, null, null, null, null);
        String serialized = json.toJson(spec);
        assertThat(serialized).contains("\"name\":\"echo\"");
        FunctionSpec deserialized = json.fromJson(serialized, FunctionSpec.class);
        assertThat(deserialized.name()).isEqualTo("echo");
    }

    @Test
    void functionDetailsCarriesTheBackendObjectNamesThroughTheCli() {
        // `fn get` reprints its own copy of the response, so anything FunctionDetails
        // does not model is dropped between the control plane and the user.
        HttpJson json = new HttpJson();
        String fromControlPlane = """
                {"name":"word-stats","image":"img:1",\
                "deploymentObjects":{"deployment":"fn-word-stats","namespace":"nanofaas-e2e"}}""";

        String reprinted = json.toJson(json.fromJson(fromControlPlane, FunctionDetails.class));

        assertThat(reprinted).contains("\"deployment\":\"fn-word-stats\"");
        assertThat(reprinted).contains("\"namespace\":\"nanofaas-e2e\"");
    }

    @Test
    void constructorWithCustomMapper() {
        ObjectMapper custom = new ObjectMapper().findAndRegisterModules();
        HttpJson json = new HttpJson(custom);

        FunctionSpec spec = new FunctionSpec("test", "img:2",
                null, null, null, null, null, null, null, null, null, null, null, null);
        String serialized = json.toJson(spec);
        assertThat(serialized).contains("\"name\":\"test\"");
    }

    @Test
    void mapperReturnsNonNull() {
        HttpJson json = new HttpJson();
        assertThat(json.mapper()).isNotNull();
    }
}
