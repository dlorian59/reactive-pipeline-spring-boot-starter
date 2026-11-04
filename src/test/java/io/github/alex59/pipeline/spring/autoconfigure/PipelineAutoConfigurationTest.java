package io.github.alex59.pipeline.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alex59.pipeline.Ordering;
import io.github.alex59.pipeline.StepEvent;
import io.github.alex59.pipeline.StepFailure;
import io.github.alex59.pipeline.StepObserver;
import io.github.alex59.pipeline.StepPolicy;
import io.github.alex59.pipeline.spring.PipelineFactory;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

class PipelineAutoConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PipelineAutoConfiguration.class));

    @Test
    void defaultsAreAvailableWithoutPropertiesAndDoNotRetry() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PipelineFactory.class).hasSingleBean(StepPolicy.class);
            var policy = context.getBean(PipelineFactory.class).stepPolicy();
            assertThat(policy.timeout()).isEqualTo(Duration.ofSeconds(30));
            assertThat(policy.maxRetries()).isZero();
            assertThat(context.getBean(PipelineProperties.class).concurrency()).isEqualTo(4);
            assertThat(context.getBean(StepObserver.class)).isSameAs(StepObserver.NONE);
        });
    }

    @Test
    void boundSettingsControlConcurrencyOrderingAndExplicitIoPolicy() {
        runner.withPropertyValues("reactive.pipeline.concurrency=2", "reactive.pipeline.ordering=completion",
                "reactive.pipeline.step.timeout=5s", "reactive.pipeline.step.max-retries=1",
                "reactive.pipeline.step.retry-delay=0ms").run(context -> {
            var factory = context.getBean(PipelineFactory.class);
            assertThat(factory.stepPolicy().timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(factory.stepPolicy().retryDelay()).isZero();
            var calls = new AtomicInteger();
            var io = factory.<Integer>start().then("lookup", (value, execution) ->
                    calls.incrementAndGet() == 1 ? Mono.error(new IOException("temporary")) : Mono.just(value),
                    factory.stepPolicy());
            StepVerifier.create(io.run(7)).expectNext(7).verifyComplete();
            assertThat(calls.get()).isEqualTo(2);
            var validations = new AtomicInteger();
            var invalid = factory.<Integer>start().then("invalid", (value, execution) -> {
                validations.incrementAndGet();
                return Mono.error(new IllegalArgumentException("invalid"));
            }, factory.stepPolicy());
            StepVerifier.create(invalid.run(1)).expectError(StepFailure.class).verify();
            assertThat(validations.get()).isOne();

            List<Sinks.One<Integer>> gates = List.of(Sinks.one(), Sinks.one(), Sinks.one());
            var started = new AtomicInteger();
            var pipeline = factory.<Integer>start().then("gate", (value, execution) -> {
                started.incrementAndGet();
                return gates.get(value).asMono();
            });
            StepVerifier.create(factory.process(pipeline, Flux.range(0, 3)))
                    .then(() -> assertThat(started.get()).isEqualTo(2))
                    .then(() -> gates.get(1).tryEmitValue(1)).expectNext(1)
                    .then(() -> assertThat(started.get()).isEqualTo(3))
                    .then(() -> gates.get(0).tryEmitValue(0)).expectNext(0)
                    .then(() -> gates.get(2).tryEmitValue(2)).expectNext(2).verifyComplete();
        });
    }

    @Test
    void applicationPolicyAndObserverAreUsedWithoutDuplicateBeans() {
        var events = new ArrayList<StepEvent>();
        StepObserver observer = events::add;
        var policy = StepPolicy.once(Duration.ofSeconds(7));
        runner.withBean(StepPolicy.class, () -> policy).withBean(StepObserver.class, () -> observer)
                .run(context -> {
                    assertThat(context).hasSingleBean(StepPolicy.class).hasSingleBean(StepObserver.class);
                    var factory = context.getBean(PipelineFactory.class);
                    assertThat(factory.stepPolicy()).isSameAs(policy);
                    StepVerifier.create(factory.<Integer>start().map("value", value -> value).run(3))
                            .expectNext(3).verifyComplete();
                    assertThat(events).extracting(StepEvent::kind)
                            .containsExactly(StepEvent.Kind.STARTED, StepEvent.Kind.SUCCEEDED);
                });
    }

    @Test
    void applicationFactoryWinsAndFeatureCanBeDisabled() {
        var custom = new PipelineFactory(StepPolicy.defaults(), 1, Ordering.INPUT, StepObserver.NONE);
        runner.withBean(PipelineFactory.class, () -> custom).run(context -> {
            assertThat(context).hasSingleBean(PipelineFactory.class);
            assertThat(context.getBean(PipelineFactory.class)).isSameAs(custom);
        });
        runner.withPropertyValues("reactive.pipeline.enabled=false").run(context ->
                assertThat(context).doesNotHaveBean(PipelineFactory.class)
                        .doesNotHaveBean(StepPolicy.class).doesNotHaveBean(StepObserver.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "reactive.pipeline.concurrency=0",
            "reactive.pipeline.ordering=unknown",
            "reactive.pipeline.step.timeout=0s",
            "reactive.pipeline.step.timeout=9223372036854775807s",
            "reactive.pipeline.step.max-retries=-1",
            "reactive.pipeline.step.retry-delay=-1ms",
            "reactive.pipeline.step.timeuot=1s"
    })
    void invalidOrMisspelledPropertiesFailStartup(String property) {
        runner.withPropertyValues(property).run(context -> assertThat(context).hasFailed());
    }
}
