package io.github.alex59.pipeline.spring;

import io.github.alex59.pipeline.Ordering;
import io.github.alex59.pipeline.Pipeline;
import io.github.alex59.pipeline.ProcessingOptions;
import io.github.alex59.pipeline.StepObserver;
import io.github.alex59.pipeline.StepPolicy;
import java.util.Objects;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

/**
 * Immutable entry point for ordinary typed pipelines using application defaults.
 * No pipeline is registered, subscribed or scheduled by this factory.
 * Steps keep the core's default policy unless explicitly given {@link #stepPolicy()}.
 */
public final class PipelineFactory {
    private final StepPolicy stepPolicy;
    private final int concurrency;
    private final Ordering ordering;
    private final StepObserver observer;

    /**
     * Creates application defaults. Callbacks must obey the core's nonblocking contract.
     * @param stepPolicy policy to apply explicitly to selected steps
     * @param concurrency maximum active record subscriptions per processing stream
     * @param ordering output ordering, not side-effect ordering
     * @param observer thread-safe diagnostic observer attached by start
     */
    public PipelineFactory(StepPolicy stepPolicy, int concurrency, Ordering ordering, StepObserver observer) {
        this.stepPolicy = Objects.requireNonNull(stepPolicy, "stepPolicy");
        if (concurrency < 1) throw new IllegalArgumentException("concurrency must be positive");
        this.concurrency = concurrency;
        this.ordering = Objects.requireNonNull(ordering, "ordering");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    /**
     * Starts an ordinary immutable core pipeline with the configured observer and
     * processing defaults. Call pipeline.process(source) directly in your service.
     * @param <T> input type
     * @return a new pipeline definition, not an execution
     */
    public <T> Pipeline<T, T> start() {
        return Pipeline.<T>start().observe(observer)
                .withProcessing(new ProcessingOptions(concurrency, ordering));
    }

    /**
     * Policy for steps where timeout/retry is appropriate. Adding this policy is explicit.
     * @return application policy; no retries are enabled by the starter's defaults
     */
    public StepPolicy stepPolicy() { return stepPolicy; }

    /**
     * Processes with application defaults. Cancellation and errors retain core semantics.
     * Each invocation has its own concurrency budget; this is not an application-wide limiter.
     * @param pipeline existing typed definition (its observer is not replaced)
     * @param source input publisher
     * @param <I> input type
     * @param <O> output type
     * @return cold processing publisher; callers own subscription
     */
    public <I, O> Flux<O> process(Pipeline<I, O> pipeline, Publisher<I> source) {
        return Objects.requireNonNull(pipeline, "pipeline").process(source, concurrency, ordering);
    }
}
