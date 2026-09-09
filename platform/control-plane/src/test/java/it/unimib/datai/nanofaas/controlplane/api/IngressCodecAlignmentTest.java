package it.unimib.datai.nanofaas.controlplane.api;

import it.unimib.datai.nanofaas.common.model.InvocationRequest;
import org.junit.jupiter.api.Test;
import org.springframework.core.ResolvableType;
import org.springframework.http.MediaType;
import org.springframework.http.codec.HttpMessageReader;
import org.springframework.http.codec.ServerCodecConfigurer;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class IngressCodecAlignmentTest {

    @Test
    void rejectsAnUnsupportedFirstApplicableCustomReaderInsteadOfSelectingALaterReader() {
        ServerCodecConfigurer codecs = ServerCodecConfigurer.create();
        HttpMessageReader<?> custom = mock(HttpMessageReader.class);
        when(custom.canRead(ResolvableType.forClass(InvocationRequest.class), MediaType.APPLICATION_JSON))
                .thenReturn(true);
        codecs.customCodecs().register(custom);

        assertThatThrownBy(() -> new IngressBodyLimitWebFilter(codecs, 1L << 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("first applicable");
    }

    @Test
    void rejectsAConfiguredLimitThatDoesNotMatchTheActiveReader() {
        ServerCodecConfigurer codecs = ServerCodecConfigurer.create();
        codecs.defaultCodecs().maxInMemorySize(64);

        assertThatThrownBy(() -> new IngressBodyLimitWebFilter(codecs, 32))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("does not match");
    }
}
