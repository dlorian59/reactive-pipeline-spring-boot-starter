package io.github.alex59.pipeline.spring.autoconfigure;

import io.github.alex59.pipeline.StepPolicy;
import io.github.alex59.pipeline.spring.aop.ReactivePolicy;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.ProxyMethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.StaticMethodMatcherPointcut;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

/** Internal adapter; Spring creates the proxy and Reactor owns subscription/cancellation. */
final class ReactivePolicyAdvisor extends DefaultPointcutAdvisor {
    ReactivePolicyAdvisor(BeanFactory beans, int order) {
        super(new StaticMethodMatcherPointcut() {
            @Override
            public boolean matches(Method method, Class<?> targetClass) {
                if (annotation(method, targetClass) == null) return false;
                Method specific = AopUtils.getMostSpecificMethod(method, targetClass);
                int modifiers = specific.getModifiers();
                if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isFinal(modifiers)) {
                    throw new IllegalArgumentException("@ReactivePolicy requires a public non-final instance method: " + specific);
                }
                Class<?> type = specific.getReturnType();
                if (type != Mono.class && type != Flux.class) {
                    throw new IllegalArgumentException("@ReactivePolicy requires an exact Mono or Flux return type: " + specific);
                }
                return true;
            }
        }, (org.aopalliance.intercept.MethodInterceptor) invocation -> advise(invocation, beans));
        setOrder(order);
    }

    private static ReactivePolicy annotation(Method method, Class<?> targetClass) {
        var specific = AopUtils.getMostSpecificMethod(method, targetClass);
        var found = AnnotatedElementUtils.findMergedAnnotation(specific, ReactivePolicy.class);
        return found != null ? found : AnnotatedElementUtils.findMergedAnnotation(method, ReactivePolicy.class);
    }

    @SuppressWarnings("unchecked")
    private static Object advise(MethodInvocation invocation, BeanFactory beans) {
        if (!(invocation instanceof ProxyMethodInvocation proxy)) {
            throw new IllegalStateException("@ReactivePolicy requires a Spring ProxyMethodInvocation");
        }
        var targetClass = AopUtils.getTargetClass(Objects.requireNonNull(invocation.getThis(), "proxy target"));
        var settings = Objects.requireNonNull(annotation(invocation.getMethod(), targetClass), "annotation");
        var method = AopUtils.getMostSpecificMethod(invocation.getMethod(), targetClass);
        Object[] arguments = invocation.getArguments().clone();
        if (method.getReturnType() == Mono.class) {
            return Mono.defer(() -> {
                StepPolicy policy = policy(beans, settings);
                Mono<Object> attempt = Mono.defer(() -> {
                    try { return (Mono<Object>) invoke(proxy, arguments); }
                    catch (Throwable failure) { Exceptions.throwIfFatal(failure); return Mono.error(failure); }
                }).timeout(policy.timeout());
                return settings.retrySafe() && policy.maxRetries() > 0
                        ? attempt.retryWhen(retry(policy, null)) : attempt;
            });
        }
        return Flux.defer(() -> {
            StepPolicy policy = policy(beans, settings);
            var emitted = new AtomicBoolean();
            Flux<Object> attempt = Flux.defer(() -> {
                try { return (Flux<Object>) invoke(proxy, arguments); }
                catch (Throwable failure) { Exceptions.throwIfFatal(failure); return Flux.error(failure); }
            }).timeout(policy.timeout()).doOnNext(value -> emitted.set(true));
            return settings.retrySafe() && policy.maxRetries() > 0
                    ? attempt.retryWhen(retry(policy, emitted)) : attempt;
        });
    }

    private static Object invoke(ProxyMethodInvocation invocation, Object[] arguments) throws Throwable {
        // A fresh invocation preserves the remaining advice chain for every attempt/subscriber.
        return Objects.requireNonNull(invocation.invocableClone(arguments.clone()).proceed(),
                "@ReactivePolicy method returned null publisher");
    }

    private static StepPolicy policy(BeanFactory beans, ReactivePolicy settings) {
        return settings.policy().isEmpty() ? beans.getBean(StepPolicy.class)
                : beans.getBean(settings.policy(), StepPolicy.class);
    }

    private static Retry retry(StepPolicy policy, AtomicBoolean emitted) {
        java.util.function.Predicate<Throwable> filter = error ->
                (emitted == null || !emitted.get()) && policy.retryOn().test(error);
        return policy.retryDelay().isZero()
                ? Retry.max(policy.maxRetries()).filter(filter).onRetryExhaustedThrow((spec, signal) -> signal.failure())
                : Retry.fixedDelay(policy.maxRetries(), policy.retryDelay()).filter(filter)
                        .onRetryExhaustedThrow((spec, signal) -> signal.failure());
    }
}
