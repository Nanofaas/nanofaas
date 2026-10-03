package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.*;

class P2pSharingBindingTest {
    @Test void startupPropertiesReachTheEffectiveSharingSettings() {
        new ApplicationContextRunner().withUserConfiguration(P2pConfiguration.class)
                .withPropertyValues("nanofaas.p2p.share-functions=true", "nanofaas.p2p.share-images=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(P2pSettings.class).sharing()).isEqualTo(new P2pSettings.Sharing(true, true, false));
                });
    }
}
