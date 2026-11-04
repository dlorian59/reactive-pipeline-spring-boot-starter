# reactive-pipeline-spring-boot-starter

<details name="readme-language">
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-en-dark.svg"><img src="assets/lang-en-light.svg" width="42" height="26" alt="EN"></picture></summary>

**Version:** [0.1.0 guide](docs/0.1.0/en.md) · [All versions](docs/index.md) · Java 17+ · Spring Boot 4.1.2

[Complete API reference](docs/0.1.0/reference.en.md) · [Annotated service example](examples/product-enrichment/src/main/java/example/AnnotatedExampleApplication.java)

Turn a business record into an enriched view with an ordinary typed Reactor
pipeline. The starter supplies configuration, a factory, a step policy and an
observer. Your application writes validation, the remote call and the output
mapping. No new DSL, automatic subscriptions or executor are introduced.

```java
// In an application @Bean method; catalog is your application-owned client.
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

[Complete business flow](examples/product-enrichment/src/main/java/example/ProductEnrichment.java) · [Runnable example and expected result](examples/product-enrichment/README.md#english)

| Capability | Concrete example |
| --- | --- |
| Typed pipeline and configured processing defaults | [Business pipeline](examples/product-enrichment/src/main/java/example/ProductEnrichment.java), [processing records](examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| Timeout and retry on selected I/O steps | [HTTP status classification](examples/product-enrichment/src/main/java/example/CatalogClient.java) |
| Concurrency, order and attempt settings | [application.properties](examples/product-enrichment/src/main/resources/application.properties) |
| Opt-in service AOP: lazy Mono/Flux, explicit retry safety | [Annotated service](examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java), [contract](docs/0.1.0/reference.en.md#aop-service-methods) |
| User beans override defaults | [Policy and factory](examples/product-enrichment/src/main/java/example/CustomDefaults.java), [observer](examples/product-enrichment/src/main/java/example/DemoChecks.java) |

From sibling local checkouts, build the core dependency first:

```sh
../reactive-pipeline-kit/gradlew -p ../reactive-pipeline-kit :core:publishToMavenLocal
./gradlew build publishToMavenLocal
./gradlew -p examples/product-enrichment run
```

The example starts a loopback catalogue, retries one HTTP 503 and asserts two
validation runs, three HTTP attempts and two enriched products. It needs no
external service. [Install and configure your application](docs/0.1.0/en.md).

Retries are disabled by default and require an explicit step policy. Concurrency
is per processing stream, not a global limiter. Cancellation cannot undo a remote
effect; make repeatable operations idempotent. The starter includes only the
Java 17 core, without the optional Java 25/27 adapters.

Source and artifacts are local; no GitHub repository availability or Maven Central
release is claimed. [MIT](LICENSE) · [Dependency notices](THIRD-PARTY-NOTICES.md)

</details>

<details name="readme-language" open>
<summary><picture><source media="(prefers-color-scheme: dark)" srcset="assets/lang-ru-dark.svg"><img src="assets/lang-ru-light.svg" width="42" height="26" alt="RU"></picture></summary>

**Версия:** [Руководство 0.1.0](docs/0.1.0/ru.md) · [Все версии](docs/index.md) · Java 17+ · Spring Boot 4.1.2

[Полный справочник API](docs/0.1.0/reference.ru.md) · [Пример аннотированного сервиса](examples/product-enrichment/src/main/java/example/AnnotatedExampleApplication.java)

Преобразуйте бизнес-запись в обогащённую модель обычным типизированным пайплайном
Reactor. Стартер предоставляет настройки, фабрику, политику шага и наблюдатель.
Приложение описывает проверку, запрос и выходное преобразование. Новых DSL,
автоматических подписок и executor здесь нет.

```java
// В @Bean-методе приложения; catalog — клиент, которым владеет приложение.
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

[Полный бизнес-процесс](examples/product-enrichment/src/main/java/example/ProductEnrichment.java) · [Запуск и ожидаемый результат](examples/product-enrichment/README.md#русский)

| Возможность | Пример в файле |
| --- | --- |
| Типизированный пайплайн и настройки обработки | [Бизнес-пайплайн](examples/product-enrichment/src/main/java/example/ProductEnrichment.java), [обработка записей](examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| Таймаут и повтор выбранных I/O-шагов | [Классификация HTTP-статусов](examples/product-enrichment/src/main/java/example/CatalogClient.java) |
| Параллельность, порядок и настройки попыток | [application.properties](examples/product-enrichment/src/main/resources/application.properties) |
| Явно включаемый AOP: ленивые Mono/Flux, разрешение безопасных повторов | [Аннотированный сервис](examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java), [полный контракт](docs/0.1.0/reference.ru.md) |
| Приоритет beans приложения | [Политика и фабрика](examples/product-enrichment/src/main/java/example/CustomDefaults.java), [наблюдатель](examples/product-enrichment/src/main/java/example/DemoChecks.java) |

Из соседних локальных каталогов сначала соберите зависимость core:

```sh
../reactive-pipeline-kit/gradlew -p ../reactive-pipeline-kit :core:publishToMavenLocal
./gradlew build publishToMavenLocal
./gradlew -p examples/product-enrichment run
```

Пример запускает локальный каталог, повторяет один HTTP 503 и проверяет две
валидации, три HTTP-попытки и два обогащённых товара. Внешний сервис не нужен.
[Подключение и настройка приложения](docs/0.1.0/ru.md).

По умолчанию повторы выключены, политика подключается к шагу явно. Лимит
параллельности действует на отдельный поток обработки. Отмена не откатывает
удалённый эффект: повторяемые операции требуют идемпотентности. Стартер включает
только core для Java 17, без дополнительных адаптеров Java 25/27.

Исходники и артефакты пока локальные; доступность репозитория GitHub и релиз в Maven
Central не заявляются. [MIT](LICENSE) · [Лицензии зависимостей](THIRD-PARTY-NOTICES.md)

</details>
