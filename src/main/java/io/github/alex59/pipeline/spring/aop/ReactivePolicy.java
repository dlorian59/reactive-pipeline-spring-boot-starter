package io.github.alex59.pipeline.spring.aop;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opt-in timeout/retry advice on an injected Spring service proxy.
 * Requires reactive.pipeline.aop.enabled=true. Use public, non-final instance
 * methods declared to return exactly Mono or Flux. The method body is invoked
 * on subscription and again for each eligible retry; arguments are not deep-copied.
 * Empty completion is valid. Flux retries stop being eligible after its first value.
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ReactivePolicy {
    /**
     * Selects a StepPolicy bean by name, or by type/Primary when empty.
     * Lookup occurs at subscription; a missing/ambiguous bean fails before invoking the method.
     * @return optional policy bean name
     */
    String policy() default "";

    /**
     * Explicitly asserts that repeating this operation before its first result is safe.
     * False disables retries even when the selected policy has a positive retry budget.
     * This declaration does not supply idempotency or rollback external effects.
     * @return whether the policy's retry budget and predicate may be used
     */
    boolean retrySafe() default false;
}
