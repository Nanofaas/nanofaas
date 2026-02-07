package it.unimib.datai.nanofaas.sdk.autoconfigure;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.ComponentScan;

/**
 * Auto-configuration that activates the nanofaas function runtime components.
 * Scans the SDK runtime package for controllers, filters, and clients.
 */
@AutoConfiguration
@ComponentScan("it.unimib.datai.nanofaas.sdk.runtime")
public class NanofaasAutoConfiguration {
}
