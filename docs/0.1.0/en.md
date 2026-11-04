# 0.1.0 — enrich product records with Spring Boot

[Complete API reference](reference.en.md)

[EN](en.md) · [RU](ru.md) · [Versions](../index.md) · [README](../../README.md)

## Task and ownership

Given a product ID and SKU, validate it, load its category from a catalogue and
return a display model. Retry a temporary catalogue failure without repeating
validation. The application owns the typed steps, HTTP client and subscriptions;
the starter centralizes settings and bean wiring.

| Capability | Read this executable source |
| --- | --- |
| Define the business pipeline | [ProductEnrichment.java](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java) |
| Classify HTTP errors inside the retry boundary | [CatalogClient.java](../../examples/product-enrichment/src/main/java/example/CatalogClient.java) |
| Apply concurrency and ordering to records | [DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| Configure attempt and processing defaults | [application.properties](../../examples/product-enrichment/src/main/resources/application.properties) |
| Replace policy and factory beans | [CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |
| Replace the diagnostic observer | [DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |

## Install and run

Use JDK 17+, Gradle wrapper 9.8.2, Spring Boot 4.1.2 and core 0.3.0. From the
starter checkout, with the core checkout next to it:

```sh
../reactive-pipeline-kit/gradlew -p ../reactive-pipeline-kit :core:publishToMavenLocal
./gradlew build publishToMavenLocal
./gradlew -p examples/product-enrichment run
```

The example is a separate Gradle consumer resolving published JARs from Maven
Local. There is no includeBuild or project dependency substitution. To connect an
existing application, add:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("io.github.59alex:reactive-pipeline-spring-boot-starter:0.1.0")
}
```

An ordinary @SpringBootApplication discovers the auto-configuration from
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
No component scan of the library or explicit @Import is needed. The plain JAR
also includes generated Spring configuration metadata. No Maven Central release
is claimed.

## Write the business flow

[ProductEnrichment.java](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java)
contains the complete bean; its essential chain is:

```java
return pipelines.<Product>start()
    .map("validate", product -> {
        if (product.id() < 1 || product.sku() == null || product.sku().isBlank()) {
            throw new IllegalArgumentException("Product id and SKU are required");
        }
        return product;
    })
    .then("catalog", catalog::lookup, pipelines.stepPolicy())
    .map("view", value -> new ProductView(value.product().id(),
        value.product().sku() + " / " + value.category()));
```

The return type is the core Pipeline<Product, ProductView>; no wrapper DSL or
reflection registry is involved. Inject PipelineFactory and that pipeline where
records enter the application, then return pipelines.process(pipeline, records)
to the caller. The demo's block is a command-line execution boundary, not a
pattern for a WebFlux event-loop handler.

## Configure defaults deliberately

```properties
reactive.pipeline.concurrency=2
reactive.pipeline.ordering=INPUT
reactive.pipeline.step.timeout=2s
reactive.pipeline.step.max-retries=1
reactive.pipeline.step.retry-delay=10ms
```

| Property suffix under reactive.pipeline | Default | Meaning |
| --- | --- | --- |
| enabled | true | false skips all starter auto-configured beans |
| concurrency | 4 | Positive maximum active record subscriptions per process call |
| ordering | INPUT | INPUT or COMPLETION; neither orders external effects |
| step.timeout | 30s | Positive timeout for each attempt of an explicitly selected step |
| step.max-retries | 0 | Additional attempts, not total attempts |
| step.retry-delay | 100ms | Nonnegative delay between retries |

Only steps passed pipelines.stepPolicy() use the configured attempt settings.
Plain map/then retain the core's own default policy (30 seconds, no retries).
Factory.process applies concurrency/order; calling pipeline.process directly uses
the arguments you provide there. Settings are immutable startup values, not
dynamic refresh. While auto-configuration is enabled, unknown keys and invalid processing/attempt
values fail startup rather than being silently ignored. Durations must fit in signed 64-bit nanoseconds; max-retries is
0 through 2147483646 so the total attempt count fits the core integer counter.

When enabled, the default retry predicate accepts IOException and TimeoutException
only. It does not walk arbitrary cause chains. HTTP statuses are data: the
[client](../../examples/product-enrichment/src/main/java/example/CatalogClient.java)
converts 503 into an IOException subtype inside the HTTP step. A later mapping
failure cannot repeat that request. Keep retries disabled for effects whose
idempotency has not been established.

## Override beans and observe

Run the same scenario with application-owned policy and factory beans:

```sh
./gradlew -p examples/product-enrichment run --args='--demo.custom-defaults=true'
```

[CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java)
uses a domain-specific retry predicate and concurrency 1. Its StepPolicy and
PipelineFactory beans suppress the corresponding defaults. The custom factory
owns its processing settings; configuration properties no longer choose its
constructor arguments. [DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java)
provides a thread-safe StepObserver instead of the no-op default. Its events prove
that validation runs twice and HTTP attempts total three.

Each default backs off independently by type. When providing multiple beans of
one type, choose one with @Primary or supply an explicitly wired factory. Observers
must be nonblocking and thread-safe. Core nonfatal observer errors do not retry
business effects; diagnostics can still contain sensitive exception data.

## Result and limits

The loopback fixture fails the first request for product 1 with 503, then succeeds.
Product 2 succeeds immediately. The application checks and prints:

```text
products=[ProductView[id=1, label=BK-1 / books], ProductView[id=2, label=GM-2 / games]]
validationRuns=2; catalogAttempts=3
httpRequests=3; product1Attempts=2; product2Attempts=1
```

Timeout requests cancellation; neither cancellation nor retry can undo a remote
effect. HTTP string bodies are fully buffered. Concurrency is per stream, not an
application-wide or byte limit. Inputs should be immutable. There is no queue,
durable execution log, restart recovery, distributed transaction, scheduler or
HTTP client auto-configuration. Authentication, endpoints, body limits and
idempotency belong to the application. Java 25/27 optional core modules are not
transitive dependencies.

## Troubleshooting

- Could not find io.github.59alex:reactive-pipeline-kit:0.3.0: publish the core locally first, using the same Maven Local location as the starter and example.
- Failed to bind properties under reactive.pipeline: check spelling, positive concurrency/timeout, nonnegative retry values and INPUT/COMPLETION.
- No qualifying bean of type PipelineFactory: ensure the starter is on the runtime classpath, Boot auto-configuration is enabled and reactive.pipeline.enabled is not false.
- NoUniqueBeanDefinitionException: select an application default with @Primary when declaring several policies or observers.
- An HTTP 503 was not retried: classify it inside the HTTP step, pass factory.stepPolicy(), and enable retries explicitly.

Baseline bytecode is Java 17 without preview. Spring Boot 4.1.2's [upstream compatibility range](https://docs.spring.io/spring-boot/system-requirements.html)
is Java 17–27; exact local checks are reported in
[verification](../VERIFICATION.md). The integration follows
[Spring Boot's auto-configuration contract](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html).

## Apply a policy to an injected service

For a single Mono/Flux service method, enable reactive.pipeline.aop.enabled and
annotate a public non-final method with @ReactivePolicy. Default retrySafe=false
suppresses retries even when the policy budget is positive. Use retrySafe=true
only for an operation whose whole method can safely run again.

```java
@ReactivePolicy(retrySafe = true)
public Mono<EnrichedProduct> lookup(Product product) {
    return read(product);
}
```

This method belongs to [AnnotatedCatalogService.java](../../examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java),
including imports and its HTTP implementation. [AnnotatedExampleApplication.java](../../examples/product-enrichment/src/main/java/example/AnnotatedExampleApplication.java)
injects the proxy, declares policies and subscribes; it checks no method work
occurs before subscription. No changes to PipelineFactory are required.

```sh
./gradlew -p examples/product-enrichment run -PexampleMain=example.AnnotatedExampleApplication
```

Expected: monoMethodAttempts=3, fluxMethodAttempts=1, annotatedHttpRequests=5.
The sample also selects a named policy for a Flux method. Flux never retries
after its first emitted item; its timeout is an inactivity timeout. Self-calls
bypass the proxy. Choose one retry owner instead of stacking a pipeline policy
and annotation on the same I/O. See the [complete AOP contract](reference.en.md#aop-service-methods)
for cancellation, empty results, policy lookup, transactions and advisor order.


[Run and inspect the example](../../examples/product-enrichment/README.md#english) · [Back to versions](../index.md) · [README](../../README.md)
