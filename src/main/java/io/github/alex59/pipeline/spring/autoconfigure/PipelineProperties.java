package io.github.alex59.pipeline.spring.autoconfigure;

import io.github.alex59.pipeline.Ordering;
import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Immutable startup settings for the pipeline factory, without dynamic refresh.
 * @param enabled whether auto-configuration is enabled
 * @param concurrency positive active record limit per processing stream
 * @param ordering input or completion ordering
 * @param step policy defaults for explicitly selected steps
 */
@ConfigurationProperties(prefix = "reactive.pipeline", ignoreUnknownFields = false)
public record PipelineProperties(@DefaultValue("true") boolean enabled,
                                 @DefaultValue("4") int concurrency,
                                 @DefaultValue("INPUT") Ordering ordering,
                                 @DefaultValue StepDefaults step) {
    /**
     * Validates settings before application pipelines can be constructed.
     * @param enabled whether auto-configuration is enabled
     * @param concurrency positive record subscription limit
     * @param ordering output ordering
     * @param step explicit attempt defaults
     */
    public PipelineProperties {
        if (concurrency < 1) throw new IllegalArgumentException("reactive.pipeline.concurrency must be positive");
        Objects.requireNonNull(ordering, "reactive.pipeline.ordering");
        Objects.requireNonNull(step, "reactive.pipeline.step");
    }

    /**
     * Attempt settings; retries apply only to IOException and TimeoutException by default.
     * @param timeout positive timeout per attempt
     * @param maxRetries additional attempts; zero disables retries
     * @param retryDelay nonnegative fixed delay between retries
     */
    public record StepDefaults(@DefaultValue("30s") Duration timeout,
                               @DefaultValue("0") int maxRetries,
                               @DefaultValue("100ms") Duration retryDelay) {
        /**
         * Validates scheduler duration range and the core's integer attempt counter.
         * @param timeout positive attempt timeout
         * @param maxRetries additional attempts
         * @param retryDelay nonnegative fixed delay
         */
        public StepDefaults {
            Objects.requireNonNull(timeout, "reactive.pipeline.step.timeout");
            Objects.requireNonNull(retryDelay, "reactive.pipeline.step.retry-delay");
            if (timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("reactive.pipeline.step.timeout must be positive");
            }
            if (retryDelay.isNegative()) {
                throw new IllegalArgumentException("reactive.pipeline.step.retry-delay must be nonnegative");
            }
            if (maxRetries < 0 || maxRetries == Integer.MAX_VALUE) {
                throw new IllegalArgumentException("reactive.pipeline.step.max-retries must be between 0 and 2147483646");
            }
            try {
                timeout.toNanos();
                retryDelay.toNanos();
            } catch (ArithmeticException invalidDuration) {
                throw new IllegalArgumentException("Pipeline durations must fit in signed 64-bit nanoseconds", invalidDuration);
            }
        }
    }
}
