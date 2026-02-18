package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionDefaults;

import java.util.ArrayList;
import java.util.List;
import java.util.ServiceLoader;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(FunctionDefaults.class)
@Import(ControlPlaneModuleImportSelector.class)
public class ControlPlaneApplication {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }

    @PostConstruct
    void logDiscoveredModules() {
        List<String> moduleNames = new ArrayList<>();
        ServiceLoader.load(ControlPlaneModule.class, Thread.currentThread().getContextClassLoader())
                .forEach(module -> moduleNames.add(module.name()));
        if (!moduleNames.isEmpty()) {
            log.info("Loaded control-plane modules: {}", moduleNames);
        }
    }
}
