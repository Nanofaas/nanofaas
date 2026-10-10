package it.unimib.datai.nanofaas.sdk.runtime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.SpringApplication;
import static org.junit.jupiter.api.Assertions.*;
class LoggingEnvironmentPostProcessorTest {
    @Test void optInSelectsSharedConfigButDefaultAndHostConfigArePreserved() {
        var processor = new LoggingEnvironmentPostProcessor();
        var environment = new MockEnvironment();
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertNull(environment.getProperty("logging.config"));
        environment.setProperty("NANOFAAS_LOG_FORMAT", "json");
        processor.postProcessEnvironment(environment, new SpringApplication());
        assertEquals("classpath:nanofaas-logback.xml", environment.getProperty("logging.config"));
        var host = new MockEnvironment().withProperty("NANOFAAS_LOG_FORMAT", "json").withProperty("logging.config", "classpath:host.xml");
        processor.postProcessEnvironment(host, new SpringApplication());
        assertEquals("classpath:host.xml", host.getProperty("logging.config"));
    }
}
