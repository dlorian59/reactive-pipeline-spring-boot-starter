package io.github.alex59.pipeline.spring.autoconfigure;

import io.github.alex59.pipeline.Pipeline;
import io.github.alex59.pipeline.StepObserver;
import io.github.alex59.pipeline.StepPolicy;
import io.github.alex59.pipeline.spring.PipelineFactory;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/** Installs typed factory defaults only; business pipelines remain application beans. */
@AutoConfiguration
@ConditionalOnClass(Pipeline.class)
@ConditionalOnProperty(prefix = "reactive.pipeline", name = "enabled", havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(PipelineProperties.class)
public class PipelineAutoConfiguration {
    /** Creates safe opt-in retry defaults unless an application provides its own policy.
     * @param properties bound settings
     * @return default policy
     */
    @Bean
    @ConditionalOnMissingBean(StepPolicy.class)
    public StepPolicy pipelineStepPolicy(PipelineProperties properties) {
        var step = properties.step();
        return new StepPolicy(step.timeout(), step.maxRetries(), step.retryDelay(),
                error -> error instanceof IOException || error instanceof TimeoutException);
    }

    /** @return no-op observer when no application observer has been declared */
    @Bean
    @ConditionalOnMissingBean(StepObserver.class)
    public StepObserver pipelineStepObserver() { return StepObserver.NONE; }

    /**
     * @param properties bound processing settings
     * @param policy application or auto-configured policy
     * @param observer application or auto-configured observer
     * @return immutable factory unless the application declares its own
     */
    @Bean
    @ConditionalOnMissingBean(PipelineFactory.class)
    public PipelineFactory pipelineFactory(PipelineProperties properties, StepPolicy policy, StepObserver observer) {
        return new PipelineFactory(policy, properties.concurrency(), properties.ordering(), observer);
    }
}
