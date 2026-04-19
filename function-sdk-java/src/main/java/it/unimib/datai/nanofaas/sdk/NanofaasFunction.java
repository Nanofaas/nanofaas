package it.unimib.datai.nanofaas.sdk;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Marks a Spring bean as a Nanofaas function handler.
 *
 * <p>Spring component scanning, not user code, discovers this annotation and registers the
 * handler in the application context. The runtime then resolves the single active handler bean
 * from that context when the control plane calls {@code /invoke}.</p>
 *
 * <p>This keeps function authors focused on the handler implementation while the Spring Boot
 * lifecycle provides bean construction, dependency injection, and discovery.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface NanofaasFunction {
    /** Optional bean name used when multiple handler beans are present. */
    String value() default "";
}
