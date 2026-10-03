package it.unimib.datai.nanofaas.modules.p2pdiscovery;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class P2pSharingEnvironmentTest {
    @Test void deploymentEnvironmentVariablesBindToTheThreeIndependentFlags() {
        new ApplicationContextRunner().withUserConfiguration(P2pConfiguration.class)
                .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                        new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                                "NANOFAAS_P2P_SHAREFUNCTIONS", "true",
                                "NANOFAAS_P2P_SHAREIMAGES", "false",
                                "NANOFAAS_P2P_SHARERESOURCES", "true"))))
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(P2pSettings.class).sharing())
                            .isEqualTo(new P2pSettings.Sharing(true, false, true));
                    assertThat(ctx.getBean(P2pService.class).state()).isEqualTo(P2pService.State.DISABLED);
                    assertThat(ctx.getBean(P2pService.class).isRunning()).isFalse();
                });
    }
}
