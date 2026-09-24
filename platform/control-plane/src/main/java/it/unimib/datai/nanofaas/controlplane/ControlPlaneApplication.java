package it.unimib.datai.nanofaas.controlplane;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ImportRuntimeHints;
import it.unimib.datai.nanofaas.controlplane.config.CaffeineRuntimeHints;
import it.unimib.datai.nanofaas.controlplane.config.NettyEpollRuntimeHints;
import it.unimib.datai.nanofaas.controlplane.config.ProcessorMetricsRuntimeHints;
import it.unimib.datai.nanofaas.controlplane.config.InvocationLifecycleRuntimeHints;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionCatalogRuntimeHints;
import it.unimib.datai.nanofaas.controlplane.registry.FunctionDefaults;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(FunctionDefaults.class)
@ImportRuntimeHints({CaffeineRuntimeHints.class, ProcessorMetricsRuntimeHints.class, FunctionCatalogRuntimeHints.class,
        InvocationLifecycleRuntimeHints.class, NettyEpollRuntimeHints.class})
public class ControlPlaneApplication {

    public static void main(String[] args) {
        SpringApplication.run(ControlPlaneApplication.class, args);
    }

}
