# 0.1.0 — endpoint обогащения товаров на Spring WebFlux

[EN](en.md) · [RU](ru.md) · [Versions / Версии](../index.md) · [README](../../README.md)

## Начните с задачи приложения

API импорта принимает товары в NDJSON. Каждую запись нужно проверить, дополнить
категорией из внешнего каталога и вернуть, когда готова её позиция во входном
порядке. Каталог иногда отвечает 503. Повтор всего запроса заново запустил бы
валидацию и успешную работу, а накопление всего импорта задержало бы выдачу до
последней записи.

Мы создадим один неизменяемый pipeline в Spring-сервисе и вернём его Flux из
контроллера WebFlux. Каждая запись получит своё выполнение; повторять можно будет
только шаг каталога. Решение о продолжении частично полученного импорта остаётся
у вызывающего приложения. Постоянное хранение, exactly-once и атомарность пакета
этот пример не обеспечивают.

Глава рассчитана на существующее приложение **Spring Boot 4.1.2 WebFlux на Java
17+**, с Reactor Netty и реактивным WebClient. Это не Spring MVC, не пример с JDBC
и не автоматическая миграция на другую версию Boot. Порядок чтения: зависимости →
записи → настройки нагрузки → HTTP-клиент → сервис → контроллер → результат →
границы отказа. Необязательный AOP рассматривается последним.

## 1. Добавьте зависимости

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

Если приложение уже управляет зависимостями Boot 4.1.2, сохраните это управление
вместо второго конкурирующего BOM. WebFlux даёт реактивный сервер, а отдельный
стартер WebClient — внедряемый builder в Boot 4. Не нужны @Import или сканирование
пакетов библиотеки. Выпуск в Central пока не заявлен: координаты должны
разрешаться из настроенного репозитория артефактов.
[Проверка исходников](../../CONTRIBUTING.md) — отдельный процесс для разработчика библиотеки.

## 2. Опишите записи и границу приложения

Разместите файлы в области component scan приложения: в полном consumer это
example.products. В существующем приложении сохраните свою точку входа Boot.
Проверка record — обычный бизнес-код, аннотации библиотеки для неё не нужны.

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
## 3. Настройте нагрузку

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

На один ответ одновременно обрабатываются четыре записи. INPUT сохраняет порядок
выдачи: медленный первый товар задержит уже готовые следующие результаты, хотя их
HTTP-вызовы могли уже выполниться. Для порядка самих вызовов выберите concurrency 1.
COMPLETION выдаёт готовые записи сразу: клиент сопоставляет их по ID. У каждого
запроса свой бюджет, поэтому десять запросов могут создать сорок активных записей.
Для общего лимита нужен контроль допуска на границе приложения.

Codec-лимит 64KB ограничивает отдельный буферизуемый объект/строку, но не длительность
запроса, количество записей и суммарную память. Для публичного endpoint добавьте
прикладные ограничения размера/времени импорта и аутентификацию. CATALOG_BASE_URL —
доверенная настройка развёртывания, а не URL из входного тела. Loopback нужен для
локального примера; TLS и авторизация рабочего каталога принадлежат HTTP-клиенту.

Пример намеренно включает две дополнительные попытки выбранного чтения. Стандартные
значения библиотеки остаются max-retries=0, timeout=30s, retry-delay=100ms,
concurrency=4, ordering=INPUT. Свойства связываются один раз при старте; опечатки
и недопустимые лимиты прерывают запуск. AOP по умолчанию выключен.

<a id="classify-remote-failures"></a>
## 4. Классифицируйте внешний сбой внутри шага

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

Метод принимает Product и возвращает Mono<ProductView>: параметр контекста
библиотеки не нужен. Publisher WebClient выполняет HTTP-запрос при подписке.
Только 502/503/504 и WebClientRequestException с непосредственной причиной
IOException превращаются в повторяемый CatalogUnavailable. Ответы 401/403/404 и
неожиданное содержимое не повторяются. Другому транспорту может потребоваться
собственный явный классификатор. Пример буферизует одну небольшую строку категории,
а не выполняет потоковый разбор JSON.

Классификация находится именно здесь: политика на последующем map повторяла бы
обработку одного ответа вместо запроса. Стандартная политика принимает прямые
IOException и TimeoutException, не произвольную цепочку причин. Три попытки могут
занять примерно 9,2 секунды при этих настройках: три секунды — таймаут попытки,
а не всего HTTP-импорта.

## 5. Создайте цепочку один раз; выполняйте при подписке

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

Метод start фабрики закрепляет observer и настройки обработки. map проверяет
вход один раз; then принимает обычную ссылку на метод. Передача stepPolicy() явно
разрешает повтор выбранного чтения. enrich только возвращает холодный publisher.
Каждая подписка создаёт новые execution ID и счётчики попыток. Повторная подписка
на весь ответ снова выполнит весь импорт: локальная политика шага это не меняет.
Прикладные очередь, реестр, scheduler и ручная подписка не нужны.

Настройки отдельного pipeline можно переопределить через
withProcessing(ProcessingOptions.sequential()) или
withProcessing(ProcessingOptions.completionOrder(8)). Определения остаются
неизменяемыми. Существующие factory.process(pipeline, source) и явный
pipeline.process(source, concurrency, ordering) тоже доступны.

## 6. Откройте endpoint NDJSON

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

POST /products/enrich, заголовки Content-Type и Accept — application/x-ndjson:

```json
{"id":1,"sku":"BK-1"}
{"id":2,"sku":"BK-2"}
```

Если каталог возвращает books для обоих ID, ответ 200 содержит:

```json
{"id":1,"sku":"BK-1","category":"books"}
{"id":2,"sku":"BK-2","category":"books"}
```

Это отдельные JSON-записи, не общий массив. Полный consumer содержит
[HTTP-проверку](../../examples/webflux-products/src/test/java/example/products/WebFluxConsumerTest.java),
которая запускает настоящий Boot-сервер и локальный каталог: товар 1 сначала
получает 503, затем успешно читается, товар 2 читается один раз; выход сохраняет
входной порядок. Также проверяются 400 до обращения к каталогу при неверном вводе
и один неповторённый ответ каталога 401, преобразованный в 502 до начала ответа.
Fixture использует синтетические данные: это не рабочая аутентификация и не нагрузочный тест.

## 7. Разберите отказы до расширения примера

До первой строки ответа ошибка валидации превращается в безопасный ProblemDetail
со статусом 400, сбой каталога — в 502 без исходного ответа или исключения. После
отправки NDJSON HTTP-статус и заголовки уже зафиксированы: поздняя ошибка завершит
поток; обработчик исключения не сможет заменить ответ. Клиент должен учитывать
частично выполненный импорт. Повтор всего POST не делает операцию атомарной.
Модель результата с ошибкой на каждую запись — прикладной контракт, который
библиотека не придумывает за приложение.

Отмена передаётся активным publisher и ожидающим повторам. Она не отменяет уже
завершённый запрос и не гарантирует остановку сервера каталога. Pipeline прекращает
поток при первой ошибке и отменяет другие активные записи, но выполненные эффекты
остаются. Входные значения лучше делать неизменяемыми; лимит относится к записям,
а не к байтам или общей нагрузке приложения.

<a id="change-policy-and-observation"></a>
## 8. Меняйте политику и наблюдение без новой инфраструктуры

YAML удобен для общих настроек развёртывания. Для отдельного bean чтения в
@Configuration приложения готовый профиль core позволяет не повторять набор полей:

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

Профиль означает 3 секунды на попытку, две дополнительные попытки, задержку
100ms, только прямые ConnectException, SocketTimeoutException, HttpTimeoutException или TimeoutException. Ошибки авторизации/валидации
не считаются временными. readOnlyRemoteCall(predicate) принимает явный
классификатор, once(Duration) отключает повторы. Для CatalogUnavailable из примера используйте readOnlyRemoteCall(error -> error instanceof CatalogClient.CatalogUnavailable || error instanceof java.util.concurrent.TimeoutException). Такой bean заменяет StepPolicy
из YAML, но не параллельность и порядок. Несколько политик требуют однозначного
@Primary или фабрики с явной передачей зависимостей.

Для наблюдения объявите потокобезопасный неблокирующий StepObserver. События
содержат execution ID, шаг, попытку, вид, длительность и ошибку. Не выполняйте
блокирующее логирование/экспорт в потоке события и не отправляйте чувствительные
исключения без фильтрации. Нефатальный сбой observer не повторяет успешный
бизнес-эффект. [Пример observer/factory](../../examples/product-enrichment/src/main/java/example/DemoChecks.java)
и [замена настроек](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java).

## 9. Необязательный AOP — другая граница интеграции

Если многошагового pipeline нет, а внедрённый сервис уже возвращает Mono или Flux,
включите reactive.pipeline.aop.enabled=true и добавьте @ReactivePolicy к public
non-final методу. retrySafe по умолчанию false даже при положительном бюджете
политики. retrySafe=true допустим, только если безопасно заново вызвать весь метод.
[Полный пример](../../examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java)
показывает стандартную и именованную политики. Не добавляйте аннотацию к методу
каталога выше одновременно с политикой шага: вложенные бюджеты перемножаются.

AOP откладывает оставшуюся цепочку advice и тело метода до подписки и повторно
входит в эту цепочку на каждой попытке. Self-invocation обходит proxy. Таймаут
Flux измеряет бездействие, а не время жизни потока; после первого элемента Flux
не повторяется. Пустые Mono/Flux допустимы для AOP, но шаг core обязан вернуть
одно значение. [Полный контракт proxy/advice/транзакций](reference.ru.md#aop-для-service-методов).

<a id="follow-one-request"></a>
## 10. Прочитайте исходники по пути запроса

- [ProductsApplication.java](../../examples/webflux-products/src/main/java/example/products/ProductsApplication.java)
- [ProductController.java](../../examples/webflux-products/src/main/java/example/products/ProductController.java)
- [ProductService.java](../../examples/webflux-products/src/main/java/example/products/ProductService.java)
- [Product.java](../../examples/webflux-products/src/main/java/example/products/Product.java)
- [CatalogClient.java](../../examples/webflux-products/src/main/java/example/products/CatalogClient.java)
- [ProductView.java](../../examples/webflux-products/src/main/java/example/products/ProductView.java)

Затем посмотрите [автоконфигурацию](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineAutoConfiguration.java)
со стандартными bean, [PipelineFactory](../../src/main/java/io/github/alex59/pipeline/spring/PipelineFactory.java)
с небольшой интеграцией core и [properties](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineProperties.java)
с проверкой настроек. Эти внутренние классы не нужно копировать в приложение.

## Диагностика и совместимость

| Симптом | Причина и следующая проверка |
| --- | --- |
| Артефакт не найден | Выпуск в Central не заявлен; проверьте наличие core 0.3.0 и стартера 0.1.0 в настроенном репозитории |
| Нет WebClient.Builder | Для Boot 4 подключите spring-boot-starter-webclient, одного webflux недостаточно |
| Нет PipelineFactory | Проверьте runtime-зависимость, автоконфигурацию Boot и reactive.pipeline.enabled |
| 503 не повторяется | Классифицируйте ошибку внутри чтения, передайте stepPolicy и ненулевой бюджет |
| Валидация повторяется | Проверьте повторную подписку на весь pipeline и внешние retry-операторы |
| Поток закрылся после данных | Поздняя ошибка не заменит начавшийся ответ; учитывайте частичный результат |
| YAML не действует | Bean StepPolicy/Factory приложения заменяет стандартный; настройки связываются при старте |
| Аннотация не действует | Нужен вызов public non-final метода через внедрённый proxy с включённым AOP |

Core/стартер компилируются для Java 17, проект поддерживает JVM 17/21/25/27.
Необязательные модули core java25/java27 требуют соответствующих JVM и не входят
транзитивно в стартер. Пример намеренно использует возможности Java 17 (records,
ссылки на методы); новая JVM сама не добавляет ScopedValue, запись JFR или
виртуальные потоки в pipeline. [Сведения о проверках](../VERIFICATION.md)
отделяют фактические запуски от гарантий.

Первоисточники: [реактивные приложения Boot](https://docs.spring.io/spring-boot/reference/web/reactive.html),
[разделение модулей Boot 4](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide),
[WebClient](https://docs.spring.io/spring-framework/reference/web/webflux-webclient.html).

[API](reference.ru.md) · [Versions / Версии](../index.md) · [README](../../README.md)
