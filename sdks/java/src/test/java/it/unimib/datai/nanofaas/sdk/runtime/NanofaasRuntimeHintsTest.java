package it.unimib.datai.nanofaas.sdk.runtime;

import it.unimib.datai.nanofaas.common.model.ErrorInfo;
import org.junit.jupiter.api.Test;
import org.springframework.aot.hint.MemberCategory;
import org.springframework.aot.hint.RuntimeHints;

import static org.junit.jupiter.api.Assertions.*;

class NanofaasRuntimeHintsTest {

    @Test
    void registersCallbackPayloadAndErrorInfoForJacksonBinding() {
        RuntimeHints hints = new RuntimeHints();

        new NanofaasRuntimeHints().registerHints(hints, getClass().getClassLoader());

        assertNotNull(hints.reflection().getTypeHint(CallbackPayload.class));
        assertNotNull(hints.reflection().getTypeHint(ErrorInfo.class));
        assertTrue(hints.reflection().getTypeHint(CallbackPayload.class)
                .getMemberCategories().contains(MemberCategory.INVOKE_PUBLIC_METHODS));
    }
}
