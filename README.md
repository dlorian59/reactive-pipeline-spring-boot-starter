# reactive-pipeline-spring-boot-starter

<details name="readme-language">
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-en-dark.svg"><img src="assets/lang-en-light.svg" width="42" height="26" alt="EN"></picture></summary>

**Version:** [0.1.0](docs/0.1.0/en.md) · [Versions](docs/index.md) · Java 17+ · Spring Boot 4.1.2

Add a typed, reusable enrichment flow to an existing Spring WebFlux service. A
catalogue timeout can retry the catalogue read without repeating validation or
an earlier successful step. The starter supplies a PipelineFactory and configured
processing defaults; your service supplies ordinary reactive methods.

Use it for record enrichment, API composition and transformations with several
independent failure boundaries. For one simple Reactor chain without those needs,
ordinary operators are sufficient. This is in-process execution, not a durable job engine.

## Get started in your application

This example targets **Spring Boot 4.1.2 + Spring WebFlux + Reactor Netty**, Java 17.
Keep your application's existing Boot dependency management; this is not an MVC
migration recipe. The starter itself does not start a web server. Maven Central
publication is not yet claimed: your configured repository must contain these
artifacts. Source/local verification instructions are in [Contributing](CONTRIBUTING.md).

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

### Configure the selected read operation

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

The factory attaches concurrency/order to each new pipeline. Only the step given
pipelines.stepPolicy() uses the configured timeout/retries. map and ordinary then
keep 30 seconds and no retry. Four means active records per subscription, not a
server-wide limit. The 64KB codec bound protects each decoded value, not the whole request.

### Put the flow in a service

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

[Product](examples/webflux-products/src/main/java/example/products/Product.java),
[ProductView](examples/webflux-products/src/main/java/example/products/ProductView.java)
and [CatalogClient](examples/webflux-products/src/main/java/example/products/CatalogClient.java)
are complete application classes. CatalogClient uses the injected WebClient.Builder,
reads the configured catalogue endpoint and classifies 502/503/504 and selected I/O
failures inside the retry boundary. Authentication and validation failures are not retried.

### Return its publisher from the controller

```java
package example.products;

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
}
```

WebFlux subscribes and propagates client cancellation. No manual subscribe, block,
extra scheduler, pipeline registry or custom auto-configuration is needed.
[Full controller with safe pre-response error mapping](examples/webflux-products/src/main/java/example/products/ProductController.java).
The guide supplies every file, imports, request/response and failure behavior:
[complete WebFlux integration](docs/0.1.0/en.md).

## Capabilities in use

| Need | Short example | Read next |
| --- | --- | --- |
| Reuse a business flow | pipelines.start().map("validate", validator).then("read", client::read) | [Service](examples/webflux-products/src/main/java/example/products/ProductService.java) |
| Bound records, retain input order | pipeline.process(records), concurrency: 4, ordering: INPUT | [Configuration](docs/0.1.0/en.md#configure-the-workload) |
| Retry one selected operation | then("catalog", catalog::lookup, pipelines.stepPolicy()) | [Error classification](docs/0.1.0/en.md#classify-remote-failures) |
| Choose a named read-only profile | StepPolicy.readOnlyRemoteCall() as an application bean | [Overrides](docs/0.1.0/en.md#change-policy-and-observation) |
| Apply a policy to an injected service | @ReactivePolicy(retrySafe = true), AOP explicitly enabled | [Proxy boundaries](docs/0.1.0/reference.en.md#aop-service-methods) |
| Observe attempts without changing business code | @Bean StepObserver observer() | [Observation](docs/0.1.0/en.md#change-policy-and-observation) |

## Where the files go

```text
src/main/java/example/products/
  ProductsApplication.java   Boot entry point
  Product.java               Input and validation
  ProductView.java           Output
  CatalogClient.java         External read and error classification
  ProductService.java        Reusable pipeline
  ProductController.java     NDJSON HTTP boundary
src/main/resources/application.yml
```

[Read the application in order](docs/0.1.0/en.md#follow-one-request) ·
[All API and properties](docs/0.1.0/reference.en.md) ·
[Source verification](docs/VERIFICATION.md)

## Reliability boundaries

A retry does not undo an external effect. The example retries GET; writes require
an application idempotency contract. A failed record cancels the remaining stream;
already emitted NDJSON remains delivered and the HTTP status cannot be replaced
after commitment. AOP also forbids Flux retry after its first item. Do not stack
annotation and step retry on the same operation. Limits are per subscription and
do not cap payload bytes, concurrent requests or database transactions.

Core bytecode is Java 17; Java 21/25/27 runtimes retain this API. Optional core
ScopedValue (25+) and JFR (27) modules are separate dependencies, not starter defaults.
[MIT](LICENSE) · [Dependency notices](THIRD-PARTY-NOTICES.md)

</details>

<details name="readme-language" open>
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-ru-dark.svg"><img src="assets/lang-ru-light.svg" width="42" height="26" alt="RU"></picture></summary>

**Версия:** [0.1.0](docs/0.1.0/ru.md) · [Все версии](docs/index.md) · Java 17+ · Spring Boot 4.1.2

Добавляет типизированную цепочку обогащения в существующий сервис Spring WebFlux.
При таймауте каталога можно повторить его чтение, сохранив результат уже выполненной
валидации и предыдущих шагов. Стартер предоставляет PipelineFactory и настройки
обработки, а сервис приложения — обычные реактивные методы.

Подходит для обогащения записей, объединения API и преобразований с несколькими
независимыми границами отказа. Для простой цепочки без этих требований достаточно
операторов Reactor. Выполнение находится в памяти процесса: это не движок постоянных заданий.

## Подключение в существующее приложение

Пример рассчитан на **Spring Boot 4.1.2 + Spring WebFlux + Reactor Netty**, Java 17.
Используйте действующее управление зависимостями Boot; это не инструкция миграции
с MVC. Сам стартер не запускает веб-сервер. Выпуск в Maven Central пока не заявлен:
артефакты должны быть доступны в настроенном репозитории. Работа с исходниками и
локальные проверки описаны отдельно в [Contributing](CONTRIBUTING.md).

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

### Настройте выбранную операцию чтения

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

Фабрика закрепляет параллельность и порядок за новым pipeline. Настроенные
таймаут и повторы получает только шаг с pipelines.stepPolicy(). У map и обычного
then остаются 30 секунд без повторов. Четыре — число активных записей на подписку,
а не лимит сервера. Ограничение codec в 64KB относится к отдельному декодируемому
значению, а не ко всему запросу.

### Разместите цепочку в сервисе

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

[Product](examples/webflux-products/src/main/java/example/products/Product.java),
[ProductView](examples/webflux-products/src/main/java/example/products/ProductView.java)
и [CatalogClient](examples/webflux-products/src/main/java/example/products/CatalogClient.java)
— полные классы приложения. CatalogClient получает WebClient.Builder, читает адрес
каталога из настроек и классифицирует 502/503/504 и выбранные ошибки I/O внутри
границы повторов. Ошибки авторизации и проверки данных не повторяются.

### Верните publisher из контроллера

```java
package example.products;

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
}
```

WebFlux управляет подпиской и передаёт отмену клиента. Не нужны ручные subscribe,
block, дополнительный scheduler, реестр pipeline или собственная автоконфигурация.
[Полный контроллер с обработкой ошибки до начала ответа](examples/webflux-products/src/main/java/example/products/ProductController.java).
Все файлы, импорты, запрос/ответ и поведение при сбоях находятся в
[руководстве по WebFlux](docs/0.1.0/ru.md).

## Возможности на практике

| Задача | Короткий пример | Подробнее |
| --- | --- | --- |
| Повторно использовать цепочку | pipelines.start().map("validate", validator).then("read", client::read) | [Сервис](examples/webflux-products/src/main/java/example/products/ProductService.java) |
| Ограничить записи и сохранить порядок входа | pipeline.process(records), concurrency: 4, ordering: INPUT | [Настройка](docs/0.1.0/ru.md#configure-the-workload) |
| Повторить только выбранную операцию | then("catalog", catalog::lookup, pipelines.stepPolicy()) | [Классификация ошибок](docs/0.1.0/ru.md#classify-remote-failures) |
| Выбрать готовый профиль чтения | StepPolicy.readOnlyRemoteCall() как bean приложения | [Замены](docs/0.1.0/ru.md#change-policy-and-observation) |
| Применить политику к внедрённому сервису | @ReactivePolicy(retrySafe = true), AOP включён явно | [Границы proxy](docs/0.1.0/reference.ru.md#aop-для-service-методов) |
| Наблюдать попытки отдельно от бизнес-кода | @Bean StepObserver observer() | [Наблюдение](docs/0.1.0/ru.md#change-policy-and-observation) |

## Расположение файлов

```text
src/main/java/example/products/
  ProductsApplication.java   Вход Boot-приложения
  Product.java               Входные данные и валидация
  ProductView.java           Результат
  CatalogClient.java         Внешнее чтение и классификация ошибок
  ProductService.java        Переиспользуемый pipeline
  ProductController.java     HTTP-граница NDJSON
src/main/resources/application.yml
```

[Путь одного запроса](docs/0.1.0/ru.md#follow-one-request) ·
[Все API и свойства](docs/0.1.0/reference.ru.md) ·
[Проверки исходников](docs/VERIFICATION.md)

## Границы надёжности

Повтор не отменяет внешний эффект. В примере повторяется GET; запись требует
прикладного контракта идемпотентности. Ошибка записи отменяет остальной поток,
но уже выданные строки NDJSON остаются у клиента, а начавшийся HTTP-ответ нельзя
заменить другим статусом. AOP тоже не повторяет Flux после первого элемента.
Не накладывайте повторы аннотации и шага на одну операцию. Лимиты действуют на
подписку и не ограничивают байты, параллельные запросы или транзакции БД.

Байт-код core рассчитан на Java 17; JVM 21/25/27 сохраняют этот API. Необязательные
модули core для ScopedValue (25+) и JFR (27) подключаются отдельно и не входят в
стартер. [MIT](LICENSE) · [Лицензии зависимостей](THIRD-PARTY-NOTICES.md)

</details>
