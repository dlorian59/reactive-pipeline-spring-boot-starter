# 0.1.0 — a product enrichment endpoint with Spring WebFlux

[EN](en.md) · [RU](ru.md) · [Versions / Версии](../index.md) · [README](../../README.md)

## Start with the application problem

An import API receives products as NDJSON. Each record must be checked, enriched
with a category from a remote catalogue, and returned as soon as its place in the
input order is ready. The catalogue sometimes returns 503. Repeating the whole
request would repeat validation and successful work; buffering the whole import
would delay every result until the last record finishes.

We will build one immutable pipeline in a Spring service and return its Flux from
a WebFlux controller. Each record gets its own execution; only the catalogue step
may retry. The caller owns whether a partially delivered import should be resumed.
No durability, exactly-once delivery or atomic batch behavior is implied.

This chapter assumes an existing **Spring Boot 4.1.2 WebFlux application on Java
17+**, using Reactor Netty and reactive WebClient. It is not Spring MVC, an
imperative JDBC recipe or an automatic migration to a newer Boot version. Read in
order: dependencies → records → workload settings → HTTP client → service →
controller → observable result → failure boundaries. Optional AOP comes last.

## 1. Add the dependencies

Maven (dependencyManagement: Spring Boot 4.1.2):

```xml
<dependency>
  <groupId>io.github.59alex</groupId>
  <artifactId>reactive-pipeline-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webflux</artifactId>
</dependency>
<dependency>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-webclient</artifactId>
</dependency>
```

Gradle Kotlin DSL:

```kotlin
dependencies {
    implementation(platform("org.springframework.boot:spring-boot-dependencies:4.1.2"))
    implementation("io.github.59alex:reactive-pipeline-spring-boot-starter:0.1.0")
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-webclient")
}
```

If the application already manages Boot 4.1.2 dependencies, keep that management
instead of adding another competing BOM. WebFlux supplies the reactive server;
WebClient's starter supplies its injectable builder in Boot 4. No @Import or scan
of library packages is required. No Central release is currently claimed; the
coordinates must resolve from your configured artifact repository.
[Source checkout verification](../../CONTRIBUTING.md) is a separate contributor workflow.

## 2. Define the records and application boundary

Place these files under the application's component-scan root, example.products
in the complete consumer. An existing application keeps its own Boot entry point.
The record's validation is ordinary business code; no library annotations are needed.

```java
package example.products;

public record Product(long id, String sku) {
    public Product validated() {
        if (id < 1 || sku == null || sku.isBlank() || sku.length() > 80) {
            throw new IllegalArgumentException("Positive id and SKU of 1–80 characters required");
        }
        return this;
    }
}
```

```java
package example.products;

public record ProductView(long id, String sku, String category) { }
```

```java
package example.products;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ProductsApplication {
    public static void main(String[] args) {
        SpringApplication.run(ProductsApplication.class, args);
    }
}
```

<a id="configure-the-workload"></a>
## 3. Configure the workload

```yaml
catalog:
  base-url: ${CATALOG_BASE_URL:http://127.0.0.1:9091}
reactive:
  pipeline:
    concurrency: 4
    ordering: INPUT
    step:
      timeout: 3s
      max-retries: 2
      retry-delay: 100ms
spring:
  http:
    codecs:
      max-in-memory-size: 64KB
```

Four records may be in flight for one response. INPUT preserves output order:
a slow first product holds back faster later results, while their HTTP calls may
already have executed. Choose concurrency 1 when invocation order also matters.
COMPLETION emits ready records immediately; the client must correlate them by ID.
Every request has its own budget, so ten requests can create forty active records.
Apply admission control at the application edge when a global budget is needed.

The 64KB codec limit bounds an individual buffered decoded object/string, not
request duration, total records or aggregate memory. Add application-level import
size/time limits and authentication for a public endpoint. CATALOG_BASE_URL is a
trusted deployment setting, never a URL from the request body. The sample endpoint
is loopback for local development; production TLS and authorization belong to the client.

The sample deliberately enables two additional attempts for selected read steps.
Library defaults remain max-retries=0, timeout=30s, retry-delay=100ms, concurrency=4,
ordering=INPUT. Properties bind once at startup; misspelled keys and invalid
limits fail startup. AOP is disabled unless enabled explicitly.

<a id="classify-remote-failures"></a>
## 4. Classify remote failures inside the step

```java
package example.products;

import java.io.IOException;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import reactor.core.publisher.Mono;

@Component
public class CatalogClient {
    private final WebClient client;

    public CatalogClient(WebClient.Builder builder, @Value("${catalog.base-url}") URI endpoint) {
        this.client = builder.baseUrl(endpoint.toString()).build();
    }

    public Mono<ProductView> lookup(Product product) {
        // Only selected transient failures become retryable I/O errors; 401/403/404 stay errors.
        // Только выбранные временные сбои становятся повторяемыми ошибками I/O; 401/403/404 остаются ошибками.
        return client.get().uri("/categories/{id}", product.id()).retrieve()
                .onStatus(status -> status.value() == 502 || status.value() == 503 || status.value() == 504,
                        response -> response.releaseBody().thenReturn(new CatalogUnavailable()))
                .bodyToMono(String.class)
                .switchIfEmpty(Mono.error(new IllegalStateException("Catalogue returned an empty category")))
                .onErrorMap(WebClientRequestException.class, error ->
                        error.getCause() instanceof IOException ? new CatalogUnavailable(error) : error)
                .map(category -> new ProductView(product.id(), product.sku(), category));
    }

    public static final class CatalogUnavailable extends IOException {
        public CatalogUnavailable() { super("Catalogue temporarily unavailable"); }
        public CatalogUnavailable(Throwable cause) { super("Catalogue transport failed", cause); }
    }
}
```

The method takes Product and returns Mono<ProductView>; it needs no library
execution-context parameter. WebClient's publisher performs the HTTP request on
subscription. Only 502/503/504 and a WebClientRequestException directly caused by
IOException become CatalogUnavailable, a retryable IOException. 401/403/404 and
unexpected bodies stay nonretryable. Other transports may need a different explicit
classifier. The sample buffers one small category string; it is not streaming JSON.

Classification must occur in this method: a policy on a later map would retry
mapping the same response instead of repeating the failed request. The default
policy accepts direct IOException and TimeoutException, not arbitrary causes.
All three attempts together can take about 9.2 seconds with the sample settings;
three seconds is the timeout of one attempt, not of the whole HTTP import.

## 5. Construct once; process on subscription

```java
package example.products;

import io.github.alex59.pipeline.Pipeline;
import io.github.alex59.pipeline.spring.PipelineFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ProductService {
    private final Pipeline<Product, ProductView> pipeline;

    public ProductService(PipelineFactory pipelines, CatalogClient catalog) {
        // Validate once per record; retry only the read-only catalogue step.
        // Проверяем запись один раз; повторяем только чтение каталога.
        this.pipeline = pipelines.<Product>start()
                .map("validate", Product::validated)
                .then("catalog", catalog::lookup, pipelines.stepPolicy());
    }

    public Flux<ProductView> enrich(Flux<Product> products) {
        // The pipeline retains factory concurrency/order; WebFlux owns the subscription.
        // Pipeline сохраняет лимит и порядок фабрики; подпиской управляет WebFlux.
        return pipeline.process(products);
    }
}
```

The factory's start attaches its observer and processing settings. map validates
once per input; then accepts an ordinary method reference. Passing stepPolicy()
is the visible decision to retry this read. enrich just returns the cold publisher.
Each subscription creates fresh execution IDs and attempt counters. Re-subscribing
the entire response runs the entire import again; local step retry does not change that.
There is no application-owned queue, registry, scheduler or manual subscription.

You can explicitly override a pipeline's processing behavior with
withProcessing(ProcessingOptions.sequential()) or
withProcessing(ProcessingOptions.completionOrder(8)). These definitions remain
immutable. The existing factory.process(pipeline, source) and explicit
pipeline.process(source, concurrency, ordering) APIs remain available.

## 6. Expose the NDJSON endpoint

```java
package example.products;

import io.github.alex59.pipeline.StepFailure;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ProductController {
    private final ProductService products;
    public ProductController(ProductService products) { this.products = products; }

    @PostMapping(value = "/products/enrich", consumes = "application/x-ndjson", produces = "application/x-ndjson")
    public Flux<ProductView> enrich(@RequestBody Flux<Product> input) {
        return products.enrich(input);
    }

    // This response can replace a failure only before HTTP output is committed.
    // Этот ответ заменяет ошибку только до фиксации HTTP-ответа.
    @ExceptionHandler(StepFailure.class)
    public ProblemDetail failed(StepFailure failure) {
        boolean invalid = failure.step().equals("validate") && failure.getCause() instanceof IllegalArgumentException;
        return ProblemDetail.forStatusAndDetail(invalid ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                invalid ? "Invalid product" : "Catalogue request failed");
    }
}
```

POST /products/enrich with Content-Type and Accept both application/x-ndjson:

```json
{"id":1,"sku":"BK-1"}
{"id":2,"sku":"BK-2"}
```

When the catalogue returns books for both IDs, response status 200 contains:

```json
{"id":1,"sku":"BK-1","category":"books"}
{"id":2,"sku":"BK-2","category":"books"}
```

These are separate JSON records, not one JSON array. The complete consumer's
[HTTP test](../../examples/webflux-products/src/test/java/example/products/WebFluxConsumerTest.java)
starts a real Boot server and a loopback catalogue: product 1 receives one 503
then succeeds, product 2 succeeds once; output stays input-ordered. It also checks
400 for invalid input before catalogue access and one nonretried 401 from the
catalogue mapped to 502 before response commitment. Its fixture is synthetic,
not an implementation of production authentication or a load benchmark.

## 7. Understand failures before extending the example

Before the first body item, validation failure becomes a safe 400 ProblemDetail;
a catalogue failure becomes 502 without leaking its raw response or exception.
After any NDJSON has been sent, HTTP status/headers are committed: a later failure
terminates the stream; the exception handler cannot replace it with a new response.
The caller must treat an incomplete import as partial work. Do not silently retry
the whole POST to obtain atomic semantics. A per-record result envelope is an
application contract, not something this library invents for you.

Cancellation propagates to the current publishers and queued retries. It cannot
undo a completed request or force a remote server to stop. The pipeline fails fast:
other active records are cancelled, but completed effects remain. Inputs should be
immutable; concurrency bounds records, not bytes or application-wide traffic.

<a id="change-policy-and-observation"></a>
## 8. Change policy and observation without new infrastructure

The YAML profile is useful when operations share deployment settings. For a
read-specific bean in an application @Configuration class, a named core profile
also avoids spelling out the same policy fields:

```java
import io.github.alex59.pipeline.StepPolicy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ReadPolicyConfiguration {
    @Bean
    StepPolicy pipelineStepPolicy() {
        return StepPolicy.readOnlyRemoteCall();
    }
}
```

The profile means 3 seconds per attempt, two additional attempts, 100ms delay,
only direct ConnectException, SocketTimeoutException, HttpTimeoutException or TimeoutException. Authentication/validation are not
transient classifications. readOnlyRemoteCall(predicate) accepts your explicit
classifier, while once(Duration) disables retry. For the example CatalogUnavailable classification, use readOnlyRemoteCall(error -> error instanceof CatalogClient.CatalogUnavailable || error instanceof java.util.concurrent.TimeoutException) instead. Supplying this bean replaces
YAML-derived StepPolicy; it does not change concurrency/order. Multiple policies
need an unambiguous @Primary or an explicitly constructed factory.

For observation, supply a thread-safe nonblocking StepObserver bean. Events expose
execution ID, step, attempt, kind, elapsed duration and error. Do not put blocking
logging/export I/O on the event thread; do not export raw sensitive exceptions.
A nonfatal observer failure must not cause a successful business effect to retry.
[Complete observer/factory override source](../../examples/product-enrichment/src/main/java/example/DemoChecks.java)
and [custom defaults](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java).

## 9. Optional service AOP is a different integration boundary

When there is no multi-step pipeline and an injected service already returns
Mono or Flux, enable reactive.pipeline.aop.enabled=true and annotate a public
non-final method with @ReactivePolicy. retrySafe defaults to false even if the
policy allows retries. Set retrySafe=true only when invoking the whole method
again is safe. The [complete annotated example](../../examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java)
shows default and named policies. Do not add the annotation to the catalogue
method above while also retaining its step retry: nested budgets multiply.

AOP defers the remaining advice chain and method until subscription and re-enters
that chain for every attempt. Self-invocation bypasses the proxy. A Flux timeout
measures inactivity, not total lifetime, and Flux never retries after its first
emitted item. Empty Mono/Flux are legal AOP outputs, whereas a core pipeline step
must produce one value. [Full proxy/advice/transaction contract](reference.en.md#aop-service-methods).

<a id="follow-one-request"></a>
## 10. Read the source along one request

- [ProductsApplication.java](../../examples/webflux-products/src/main/java/example/products/ProductsApplication.java)
- [ProductController.java](../../examples/webflux-products/src/main/java/example/products/ProductController.java)
- [ProductService.java](../../examples/webflux-products/src/main/java/example/products/ProductService.java)
- [Product.java](../../examples/webflux-products/src/main/java/example/products/Product.java)
- [CatalogClient.java](../../examples/webflux-products/src/main/java/example/products/CatalogClient.java)
- [ProductView.java](../../examples/webflux-products/src/main/java/example/products/ProductView.java)

Then inspect [auto-configuration](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineAutoConfiguration.java)
for bean defaults, [PipelineFactory](../../src/main/java/io/github/alex59/pipeline/spring/PipelineFactory.java)
for the small core integration, and [properties](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineProperties.java)
for binding validation. These internals are not classes to copy into your app.

## Troubleshooting and compatibility

| Symptom | Reason and next check |
| --- | --- |
| Artifact not resolved | No Central release is claimed; ensure the configured repository contains core 0.3.0 and starter 0.1.0 |
| No WebClient.Builder | In Boot 4 include spring-boot-starter-webclient, not only webflux |
| No PipelineFactory | Check runtime dependency, Boot auto-configuration and reactive.pipeline.enabled |
| 503 not retried | Classify inside the read method, pass stepPolicy and set a nonzero retry budget |
| Validation repeats | Look for re-subscription of the whole pipeline or retry outside it |
| Stream closes after output | A late failure cannot replace committed HTTP output; handle partial results |
| YAML has no effect | Application StepPolicy/Factory bean replaces those defaults; settings bind at startup |
| Annotation ignored | Invoke a public non-final method through the injected proxy, with AOP enabled |

Core/starter compile to Java 17, and the existing project supports JVM 17/21/25/27.
The optional core java25 and java27 modules require their respective JVMs and are
not transitive starter dependencies. This example intentionally uses Java 17
features (records, method references); a newer JVM does not automatically introduce
ScopedValue, JFR recording or virtual threads into your pipeline.
[Verification evidence](../VERIFICATION.md) distinguishes actual runs from guarantees.

Primary references: [Spring Boot reactive applications](https://docs.spring.io/spring-boot/reference/web/reactive.html),
[Boot 4 module separation](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
[WebClient](https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html).

[API](reference.en.md) · [Versions / Версии](../index.md) · [README](../../README.md)
