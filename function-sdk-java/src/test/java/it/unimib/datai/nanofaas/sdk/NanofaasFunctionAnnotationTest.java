package it.unimib.datai.nanofaas.sdk;

import org.junit.jupiter.api.Test;
import org.springframework.stereotype.Component;

import static org.junit.jupiter.api.Assertions.*;

class NanofaasFunctionAnnotationTest {

    @Test
    void annotationIsMetaAnnotatedWithComponent() {
        assertTrue(NanofaasFunction.class.isAnnotationPresent(Component.class));
    }

    @Test
    void annotationHasRuntimeRetention() {
        assertTrue(NanofaasFunction.class.isAnnotationPresent(java.lang.annotation.Retention.class));
        assertEquals(java.lang.annotation.RetentionPolicy.RUNTIME,
                NanofaasFunction.class.getAnnotation(java.lang.annotation.Retention.class).value());
    }
}
