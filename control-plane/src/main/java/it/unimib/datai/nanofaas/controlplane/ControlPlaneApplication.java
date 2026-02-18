package it.unimib.datai.nanofaas.controlplane;

import it.unimib.datai.nanofaas.common.controlplane.ControlPlaneModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionDefaults;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(FunctionDefaults.class)
@Import(ControlPlaneModuleImportSelector.class)
public class ControlPlaneApplication {

    private static final Logger log = LoggerFactory.getLogger(ControlPlaneApplication.class);

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication();
        application.setSources(applicationSources(Thread.currentThread().getContextClassLoader()));
        application.run(args);
    }

    static Set<String> applicationSources(ClassLoader classLoader) {
        Set<String> sources = new LinkedHashSet<>();
        sources.add(ControlPlaneApplication.class.getName());

        List<String> moduleNames = new ArrayList<>();
        ServiceLoader.load(ControlPlaneModule.class, classLoader).forEach(module -> {
            moduleNames.add(module.name());
            module.configurationClasses().stream()
                    .filter(Objects::nonNull)
                    .map(Class::getName)
                    .forEach(sources::add);
        });

        if (!moduleNames.isEmpty()) {
            log.info("Loaded control-plane modules: {}", moduleNames);
        }

        return sources;
    }
}
