package it.unimib.datai.nanofaas.sdk;

import org.springframework.stereotype.Component;

import java.lang.annotation.*;

/**
 * Marks a class as a nanofaas function handler.
 * This is a meta-annotation that combines {@link Component} for Spring discovery.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface NanofaasFunction {
    /** Optional bean name. */
    String value() default "";
}
