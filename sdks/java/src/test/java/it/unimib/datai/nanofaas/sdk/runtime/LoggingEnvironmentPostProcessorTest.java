package it.unimib.datai.nanofaas.sdk.runtime;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.SpringApplication;
import static org.junit.jupiter.api.Assertions.*;
class LoggingEnvironmentPostProcessorTest {
    @Test void conventionalHostLogbackResourceIsPreserved(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.nio.file.Files.writeString(directory.resolve("logback-spring.xml"), "<configuration/>");
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{directory.toUri().toURL()}, null)) {
            var application = new SpringApplication(new org.springframework.core.io.DefaultResourceLoader(loader));
            var environment = new MockEnvironment().withProperty("NANOFAAS_LOG_FORMAT", "json");
            new LoggingEnvironmentPostProcessor().postProcessEnvironment(environment, application);
            assertNull(environment.getProperty("logging.config"));
        }
    }
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
