package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ControlPlaneApplicationMainTest {

    @Test
    void main_delegatesToSpringApplicationRun() {
        String[] args = {"--spring.main.web-application-type=none"};
        ConfigurableApplicationContext context = mock(ConfigurableApplicationContext.class);

        try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
            spring.when(() -> SpringApplication.run(ControlPlaneApplication.class, args)).thenReturn(context);

            ControlPlaneApplication.main(args);

            spring.verify(() -> SpringApplication.run(ControlPlaneApplication.class, args));
        }
    }
}
