package io.github.alex59.pipeline.spring.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.alex59.pipeline.StepPolicy;
import io.github.alex59.pipeline.spring.aop.ReactivePolicy;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Role;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class ReactivePolicyAopTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PipelineAutoConfiguration.class, PipelineAopAutoConfiguration.class));

    private ApplicationContextRunner enabled() {
        return runner.withPropertyValues("reactive.pipeline.aop.enabled=true",
                "reactive.pipeline.step.max-retries=1", "reactive.pipeline.step.retry-delay=0ms");
    }

    @Test
    void adviceRequiresExplicitOptInAndRespectsGlobalDisable() {
        runner.withBean(Service.class).run(context -> {
            assertThat(AopUtils.isAopProxy(context.getBean(Service.class))).isFalse();
            assertThat(context).doesNotHaveBean("reactivePolicyAdvisor");
        });
        enabled().withPropertyValues("reactive.pipeline.enabled=false").withBean(Service.class)
                .run(context -> assertThat(context).doesNotHaveBean("reactivePolicyAdvisor"));
    }

    @Test
    void lazySubscriptionsAndRetriesReplayTheRemainingAdviceChainWithTheirContext() {
        enabled().withPropertyValues("reactive.pipeline.aop.order=0")
                .withUserConfiguration(WithRemainingAdvice.class).withBean(Service.class).run(context -> {
            var service = context.getBean(Service.class);
            var counters = context.getBean(WithRemainingAdvice.class);
            assertThat(AopUtils.isCglibProxy(service)).isTrue();
            Mono<String> samePublisher = service.lookup("sku");
            assertThat(service.calls()).isZero();
            assertThat(counters.calls.get()).isZero();
            StepVerifier.create(samePublisher.contextWrite(Context.of("tenant", "one")))
                    .expectNext("one:sku").verifyComplete();
            StepVerifier.create(samePublisher.contextWrite(Context.of("tenant", "two")))
                    .expectNext("two:sku").verifyComplete();
            assertThat(service.calls()).isEqualTo(3);
            assertThat(service.subscriptions()).isEqualTo(3);
            assertThat(counters.calls.get()).isEqualTo(3);
        });
    }

    @Test
    void retriesStayDisabledUnlessTheMethodDeclaresSafetyAndEmptyMonoRemainsValid() {
        enabled().withBean(Service.class).run(context -> {
            var service = context.getBean(Service.class);
            StepVerifier.create(service.notRetrySafe()).expectErrorSatisfies(error ->
                    assertThat(error).isSameAs(Service.FAILURE)).verify();
            assertThat(service.calls()).isOne();
            StepVerifier.create(service.empty()).verifyComplete();
        });
    }

    @Test
    void fluxRetriesBeforeItsFirstValueButDoesNotReplayPartialOutput() {
        enabled().withBean(Service.class).run(context -> {
            var service = context.getBean(Service.class);
            StepVerifier.create(service.streamBeforeFirst(), 0).thenRequest(1).expectNext(7)
                    .thenRequest(1).expectNext(8).verifyComplete();
            assertThat(service.streamCalls()).isEqualTo(2);
            StepVerifier.create(service.partial()).expectNext(1)
                    .expectErrorSatisfies(error -> assertThat(error).isSameAs(Service.FAILURE)).verify();
            assertThat(service.partialCalls()).isOne();
        });
    }

    @Test
    void timeoutCancelsEachAttemptAndDownstreamCancellationDoesNotRetry() {
        enabled().withPropertyValues("reactive.pipeline.step.timeout=1s").withBean(Service.class)
                .run(context -> {
                    var service = context.getBean(Service.class);
                    StepVerifier.withVirtualTime(service::never).thenAwait(Duration.ofSeconds(2))
                            .expectError(TimeoutException.class).verify();
                    assertThat(service.calls()).isEqualTo(2);
                    assertThat(service.cancellations()).isEqualTo(2);
                    StepVerifier.create(service.never()).thenCancel().verify();
                    assertThat(service.calls()).isEqualTo(2);
                    var active = service.never().subscribe();
                    assertThat(service.calls()).isEqualTo(3);
                    active.dispose();
                    assertThat(service.cancellations()).isEqualTo(3);
                });
    }

    @Test
    void interfaceAnnotationsWorkWithJdkProxiesAndNamedPolicyLookupIsDeferred() {
        enabled().withBean(Port.class, PortImpl::new)
                .withBean("readPolicy", StepPolicy.class, () -> StepPolicy.once(Duration.ofSeconds(2)))
                .run(context -> {
                    Port port = context.getBean(Port.class);
                    assertThat(AopUtils.isJdkDynamicProxy(port)).isTrue();
                    StepVerifier.create(port.read()).expectNext("value").verifyComplete();
                });
        enabled().withBean(Port.class, PortImpl::new).run(context -> {
            Mono<String> value = context.getBean(Port.class).read();
            StepVerifier.create(value).expectError(org.springframework.beans.factory.NoSuchBeanDefinitionException.class).verify();
        });
    }

    @Test
    void invalidAnnotatedSignatureFailsBeanCreationBeforeExecutingBusinessCode() {
        enabled().withBean(BadService.class).run(context -> assertThat(context).hasFailed());
    }

    public static class Service {
        static final IOException FAILURE = new IOException("temporary");
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicInteger subscriptions = new AtomicInteger();
        private final AtomicInteger streamCalls = new AtomicInteger();
        private final AtomicInteger partialCalls = new AtomicInteger();
        private final AtomicInteger cancellations = new AtomicInteger();

        @ReactivePolicy(retrySafe = true)
        public Mono<String> lookup(String input) {
            int attempt = calls.incrementAndGet();
            return Mono.deferContextual(context -> {
                subscriptions.incrementAndGet();
                return attempt == 1 ? Mono.error(FAILURE) : Mono.just(context.<String>get("tenant") + ":" + input);
            });
        }
        @ReactivePolicy
        public Mono<String> notRetrySafe() { calls.incrementAndGet(); return Mono.error(FAILURE); }
        @ReactivePolicy
        public Mono<Void> empty() { return Mono.empty(); }
        @ReactivePolicy(retrySafe = true)
        public Flux<Integer> streamBeforeFirst() {
            return streamCalls.incrementAndGet() == 1 ? Flux.error(FAILURE) : Flux.just(7, 8);
        }
        @ReactivePolicy(retrySafe = true)
        public Flux<Integer> partial() {
            partialCalls.incrementAndGet();
            return Flux.concat(Flux.just(1), Flux.error(FAILURE));
        }
        @ReactivePolicy(retrySafe = true)
        public Mono<String> never() {
            calls.incrementAndGet();
            return Mono.<String>never().doOnCancel(cancellations::incrementAndGet);
        }
        public int calls() { return calls.get(); }
        public int subscriptions() { return subscriptions.get(); }
        public int streamCalls() { return streamCalls.get(); }
        public int partialCalls() { return partialCalls.get(); }
        public int cancellations() { return cancellations.get(); }
    }

    public interface Port {
        @ReactivePolicy(policy = "readPolicy") Mono<String> read();
    }
    public static final class PortImpl implements Port {
        @Override public Mono<String> read() { return Mono.just("value"); }
    }
    public static class BadService {
        @ReactivePolicy public String bad() { throw new AssertionError("must never run"); }
    }

    @Configuration(proxyBeanMethods = false)
    static class WithRemainingAdvice {
        final AtomicInteger calls = new AtomicInteger();
        @Bean
        @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
        Advisor laterAdvice() {
            var advisor = new DefaultPointcutAdvisor(new StaticMethodMatcherPointcut() {
                @Override public boolean matches(java.lang.reflect.Method method, Class<?> targetClass) {
                    return Service.class.isAssignableFrom(targetClass) && method.getName().equals("lookup");
                }
            }, (MethodInterceptor) invocation -> { calls.incrementAndGet(); return invocation.proceed(); });
            advisor.setOrder(100);
            return advisor;
        }
    }
}
