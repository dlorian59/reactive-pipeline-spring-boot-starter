# 0.1.0 — complete starter reference

[EN](reference.en.md) · [RU](reference.ru.md) · [Practical guide](en.md) · [Versions](../index.md) · [README](../../README.md)

This inventory covers the application API, configuration, override points and
integration limits. Infrastructure classes install beans; they are not a second
pipeline programming model. The core remains a separate Java 17 library.

## Factory and pipeline policy

| Public capability | Behavior | Source and executable example |
| --- | --- | --- |
| PipelineFactory(StepPolicy, int, Ordering, StepObserver) | Immutable explicit defaults; positive concurrency; other arguments non-null | [Factory source](../../src/main/java/io/github/alex59/pipeline/spring/PipelineFactory.java), [custom factory](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |
| start<T>() | Returns core Pipeline<T,T>, with configured observer and processing defaults; no execution or registration | [Business pipeline](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java) |
| stepPolicy() | Returns the selected StepPolicy for explicit then(..., policy) calls | [HTTP step](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java) |
| process(pipeline, source) | Applies configured concurrency and ordering; does not change that pipeline's observer | [Processing and checks](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| StepPolicy bean override | Replaces the default IOException/TimeoutException classifier, timeout and retry budget | [CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |
| StepObserver bean override | Replaces StepObserver.NONE; callbacks must be thread-safe and nonblocking | [DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| PipelineFactory bean override | Factory constructor owns its settings; each auto-configured bean backs off independently by type | [CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |

Unconfigured core map/then methods retain their own 30-second/no-retry policy.
The factory does not intercept arbitrary core pipelines. A process concurrency
budget is per subscription/stream, not global or byte-based. User callbacks and
mutable arguments are not made thread-safe. No scheduler, executor or HTTP client
is created by the starter.

## AOP service methods

Enable the opt-in advisor and annotate a service invoked through its injected proxy:

```properties
reactive.pipeline.aop.enabled=true
reactive.pipeline.step.max-retries=1
reactive.pipeline.step.retry-delay=10ms
```

```java
@ReactivePolicy(retrySafe = true)
public Mono<EnrichedProduct> lookup(Product product) {
    return read(product);
}
```

The method above is from [AnnotatedCatalogService.java](../../examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java);
its application-owned HTTP bridge and complete imports are in the same file.
[AnnotatedExampleApplication.java](../../examples/product-enrichment/src/main/java/example/AnnotatedExampleApplication.java)
injects the service, checks that calling it performs no work, and then subscribes
to its Mono and Flux methods. Run from the starter root after local publication:

```sh
./gradlew -p examples/product-enrichment run -PexampleMain=example.AnnotatedExampleApplication
```

Expected: two product labels, two category labels, monoMethodAttempts=3,
fluxMethodAttempts=1 and annotatedHttpRequests=5. One HTTP 503 is retried in the
Mono scenario. The later Flux scenario performs two more successful reads.

| Annotation option | Default | Contract |
| --- | --- | --- |
| policy | empty string | Resolve StepPolicy by type/@Primary at subscription; a nonempty value selects that bean name |
| retrySafe | false | False always suppresses retries, even if the policy has a budget; true explicitly asserts repeat safety and allows its budget/predicate |

[Annotation source](../../src/main/java/io/github/alex59/pipeline/spring/aop/ReactivePolicy.java) ·
[Advisor implementation](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/ReactivePolicyAdvisor.java)

- Only public non-final instance methods whose implementation declares exactly Mono or Flux are supported. Annotate the method or its interface declaration. Invalid annotated signatures fail proxy creation; a null publisher fails on subscription.
- Each subscription invokes the method anew. Each eligible retry invokes it again, with a fresh clone of the remaining Spring advice chain. The argument array is captured, but its objects are not deep-copied: prefer immutable inputs.
- There is no internal subscribe, block or scheduler. The body executes on the subscribing thread; it must be nonblocking and return a cold publisher. Hot/cached publishers and externally started futures can have independent work; the advisor cannot make them cold or cancel external effects.
- Empty Mono, Mono<Void> and empty Flux remain successful. Unlike a core pipeline step, the advisor does not require one value or create StepFailure/ExecutionContext/StepObserver events. Final errors retain their type instead of a retry-exhausted wrapper.
- Mono timeout limits waiting for its value/completion. Flux timeout measures inactivity until the first item and between subsequent items, not the total stream lifetime. The timer does not pause when downstream withholds demand.
- Flux may retry only before its first item. After any item has passed the advice, later failures/timeouts propagate without retry, preserving partial output without replay. retrySafe=true still cannot roll back writes performed before the first item.
- Cancellation cancels the active publisher/timer and is not retried. It cannot force a blocking body to stop or undo a remote operation. Immediate cancellation may prevent the body from running at all.
- The policy is selected once per subscription. Missing or ambiguous policy beans fail before invoking the target. Several policies still need @Primary for the existing PipelineFactory unless you wire a factory yourself.

### Proxy and advice-order boundaries

Self-invocation (this.lookup), new Service(), private/static/final methods and
calls outside the Spring proxy do not acquire this behavior. A final class needs
a usable interface/JDK proxy; it cannot be subclassed by CGLIB. Never rely on an
unadvised method being made safe by an annotation alone.

The advisor reuses Spring's shared auto-proxy creator, without AspectJ weaving.
Existing stronger proxy infrastructure is preserved; with no existing creator,
Spring chooses interface proxies where possible, otherwise class proxies. The
starter does not independently force a proxy type. Its own explicit enable flag
registers infrastructure even when Boot's automatic AOP configuration is disabled;
disable this feature with reactive.pipeline.aop.enabled=false.

The default advisor order is Integer.MAX_VALUE. Lower order values wrap later
advisors; equal order does not establish a guaranteed relative order. Advice
outside this boundary runs at the original proxy call and is not re-invoked by
this retry. Advice inside it is deferred and repeated with each attempt.

For a separate transaction per retry, the policy advisor must wrap transaction
advice (a lower order value), and the transaction mechanism must support the
reactive execution model. The generic remaining-advice-chain test does not prove
all @Transactional/security/tracing combinations. Test your actual stack; this
starter creates no transaction or ThreadLocal context propagation. Do not silently
combine a core step retry and @ReactivePolicy retry on the same I/O: their budgets
multiply. Choose one retry owner. A single-use InputStream, upload body or
non-repeatable command is not retrySafe without an application replay contract.

## All configuration properties

[Properties source](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineProperties.java) ·
[Runnable settings](../../examples/product-enrichment/src/main/resources/application.properties)

All names below start with reactive.pipeline. Settings bind once at startup.

| Suffix | Default | Validation / effect |
| --- | --- | --- |
| enabled | true | false prevents starter defaults, including AOP, from being installed |
| concurrency | 4 | Positive; attached by factory.start; also used by factory.process |
| ordering | INPUT | INPUT or COMPLETION; attached by factory.start; also used by factory.process |
| step.timeout | 30s | Positive; per core attempt or the AOP signal-timeout contract above |
| step.max-retries | 0 | 0…2147483646; AOP additionally requires retrySafe=true |
| step.retry-delay | 100ms | Nonnegative; fixed delay, zero means immediate retry |
| aop.enabled | false | Explicit proxy integration activation |
| aop.order | 2147483647 | Any int; Spring advisor ordering, not a concurrency limit |

Durations must fit signed 64-bit nanoseconds. With configuration enabled, unknown
keys and invalid processing/attempt values fail binding. PipelineProperties,
StepDefaults and AopDefaults are immutable records with corresponding accessors;
Boot binding applies the listed defaults. Direct Java construction requires all
record arguments and validates them; @DefaultValue does not fill Java constructor
arguments. The original four-argument
PipelineProperties constructor remains available and leaves AOP disabled.

## Auto-configuration and packaging

[PipelineAutoConfiguration](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineAutoConfiguration.java)
provides factory/policy/observer defaults. The default policy retries IOException
and TimeoutException when its budget is positive, without walking cause chains.
[PipelineAopAutoConfiguration](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineAopAutoConfiguration.java)
runs after those settings are installed and requires aop.enabled=true. An
infrastructure Advisor bean named reactivePolicyAdvisor replaces its default;
this advanced override owns its behavior and order.

Both entries are declared in
[AutoConfiguration.imports](../../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports).
The normal JAR includes generated configuration metadata and Java 17 bytecode.
Spring AOP is a dependency; AspectJ weaving and core's Java 25/27 modules are not.
The [build](../../build.gradle.kts) defaults to the installed Gradle JVM while
targeting --release 17; testJavaVersion selects another installed toolchain.

## Verification and troubleshooting

[Targeted real-proxy tests](../../src/test/java/io/github/alex59/pipeline/spring/autoconfigure/ReactivePolicyAopTest.java)
cover laziness, repeated subscriptions and remaining advice, named/interface
policies, retry opt-in, empty results, Flux partial output, demand and cancellation.
See [local evidence and unverified runtimes](../VERIFICATION.md).

| Symptom | Check |
| --- | --- |
| Annotation has no effect | Enable aop.enabled and invoke the injected proxy, not this/new; check public non-final return signature |
| More calls than expected | Count subscriptions and retries separately; remove stacked retry operators |
| Flux terminates after emitting data | Intentional: partial-output retries are suppressed |
| NoSuchBeanDefinitionException / NoUniqueBeanDefinitionException | Fix policy bean name or @Primary; named annotation does not replace factory wiring |
| Unexpected transaction scope | Set explicit advisor ordering and test the application's reactive transaction stack |

Primary sources: [Spring proxying](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html),
[invocableClone](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/aop/ProxyMethodInvocation.html),
[Mono](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html),
[Flux](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html).

[← Practical guide](en.md) · [Versions](../index.md) · [README](../../README.md)

## Direct processing on a factory-created pipeline

factory.start() now attaches the configured record budget and output order, so a
service can keep one pipeline and return pipeline.process(records). Subsequent
then/map/thenIf/observe keep those settings. withProcessing(options) replaces them
on a new immutable definition; the original is unchanged. Explicit
process(records, concurrency, ordering) and factory.process(pipeline, records)
remain supported. Step policy is still opt-in; no configured retry is silently
applied to validation or every ordinary method.

[Complete HTTP consumer](../../examples/webflux-products/README.md) · [README](../../README.md)
