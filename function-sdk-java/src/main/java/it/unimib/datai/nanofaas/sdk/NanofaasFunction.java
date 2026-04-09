package it.unimib.datai.nanofaas.sdk;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Marks a bean as a nanoFaaS function handler.
 *
 * <p>This annotation exists so Spring component scanning can discover the handler without extra
 * runtime wiring. The control plane never calls this annotation directly; Spring uses it to turn a
 * user class into the single handler bean resolved by {@code HandlerRegistry} at invoke time.</p>
 *
 * <p>Dependency boundary: this is intentionally tied to the Spring application context. The Java
 * SDK is the Spring-based runtime entry point, unlike the Python SDK which discovers handlers in a
 * module-level runtime.</p>
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface NanofaasFunction {
    /** Optional bean name used when multiple handler beans are present. */
    String value() default "";
}
