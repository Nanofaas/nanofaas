package it.unimib.datai.nanofaas.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionDefaults;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(FunctionDefaults.class)
public class ControlPlaneApplication {
    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }
}
