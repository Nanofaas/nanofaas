package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

class ControlPlaneApplicationMainTest {

    @Test
    void main_delegatesToSpringApplicationRun() {
        String[] args = {"--spring.main.web-application-type=none"};
        try (MockedStatic<SpringApplication> springApp = mockStatic(SpringApplication.class)) {
            springApp.when(() -> SpringApplication.run(eq(ControlPlaneApplication.class), any(String[].class)))
                    .thenReturn(mock(ConfigurableApplicationContext.class));
            ControlPlaneApplication.main(args);
            springApp.verify(() -> SpringApplication.run(ControlPlaneApplication.class, args));
        }
    }
}
