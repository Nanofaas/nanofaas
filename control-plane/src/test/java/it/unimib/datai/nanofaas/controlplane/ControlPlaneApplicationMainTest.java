package it.unimib.datai.nanofaas.controlplane;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;
import org.springframework.boot.SpringApplication;

import static org.mockito.Mockito.verify;

class ControlPlaneApplicationMainTest {

    @Test
    void main_delegatesToSpringApplicationRun() {
        String[] args = {"--spring.main.web-application-type=none"};
        try (MockedConstruction<SpringApplication> springApplications = org.mockito.Mockito.mockConstruction(SpringApplication.class)) {
            ControlPlaneApplication.main(args);
            SpringApplication springApplication = springApplications.constructed().getFirst();
            verify(springApplication).setSources(ControlPlaneApplication.applicationSources(Thread.currentThread().getContextClassLoader()));
            verify(springApplication).run(args);
        }
    }
}
