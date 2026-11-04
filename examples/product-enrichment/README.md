# Product enrichment / Обогащение товаров

[English guide](../../docs/0.1.0/en.md) · [Руководство](../../docs/0.1.0/ru.md) · [Versions / Версии](../../docs/index.md) · [README](../../README.md)

## English

Run from the starter root after publishing core and starter to Maven Local:

```sh
./gradlew -p examples/product-enrichment run
./gradlew -p examples/product-enrichment run --args='--demo.custom-defaults=true'
```

The default consumer uses the JDK running Gradle. To select another installed JDK,
add -PexampleJava=17 or -PexampleJava=27. It always compiles against Java 17 APIs. Copy this whole directory
to a new location and use Gradle 9.8.2 there to verify an independent consumer;
there are no source or relative project dependencies on the starter checkout.

Read in this order:

1. [ProductEnrichment.java](src/main/java/example/ProductEnrichment.java): the business bean, records and validation → HTTP → output mapping.
2. [CatalogClient.java](src/main/java/example/CatalogClient.java): application HTTP adapter; 503 becomes a retryable exception inside the HTTP step.
3. [application.properties](src/main/resources/application.properties): processing and selected-step defaults.
4. [DemoChecks.java](src/main/java/example/DemoChecks.java): injected factory and observer; assertions for output and retry scope.
5. [CustomDefaults.java](src/main/java/example/CustomDefaults.java): replace policy and factory without modifying the starter.
6. [ExampleApplication.java](src/main/java/example/ExampleApplication.java) and [CatalogFixture.java](src/main/java/example/CatalogFixture.java): loopback test harness and cleanup; this harness is not needed in the business application.

Expected in both modes:

```text
products=[ProductView[id=1, label=BK-1 / books], ProductView[id=2, label=GM-2 / games]]
validationRuns=2; catalogAttempts=3
httpRequests=3; product1Attempts=2; product2Attempts=1
```

Startup output may appear before these lines. A wrong result or repeated validation
throws AssertionError and exits unsuccessfully. The fixture serves small synthetic
bodies; body buffering, authentication and production endpoint policy remain the
application's responsibility. HTTP GET is chosen because retrying writes needs
an application idempotency contract.

### Annotated service mode

```sh
./gradlew -p examples/product-enrichment run -PexampleMain=example.AnnotatedExampleApplication
```

[AnnotatedCatalogService.java](src/main/java/example/AnnotatedCatalogService.java)
contains the annotated Mono/Flux methods. [AnnotatedExampleApplication.java](src/main/java/example/AnnotatedExampleApplication.java)
uses the injected proxy, supplies default/named StepPolicy beans and asserts
laziness and HTTP attempt counts. No self-invocation is used. The complete
[policy/proxy contract](../../docs/0.1.0/reference.en.md#aop-service-methods)
explains why retrySafe must be explicit and why Flux stops retrying after output.

```text
annotatedProducts=[BK-1 / books, GM-2 / games]
annotatedCategories=[books, games]
monoMethodAttempts=3; fluxMethodAttempts=1; lazy=true
annotatedHttpRequests=5; product1=3; product2=2
```

[← English guide](../../docs/0.1.0/en.md)

## Русский

После локальной сборки core и стартера выполните из корня стартера:

```sh
./gradlew -p examples/product-enrichment run
./gradlew -p examples/product-enrichment run --args='--demo.custom-defaults=true'
```

Consumer по умолчанию использует JDK, на котором запущен Gradle. Для другого
установленного JDK добавьте -PexampleJava=17 или -PexampleJava=27; компиляция
всегда ограничена API Java 17. Для проверки
отдельного приложения скопируйте весь этот каталог в новое место и запустите там
Gradle 9.8.2. Зависимостей от исходников или соседнего проекта стартера нет.

Порядок чтения:

1. [ProductEnrichment.java](src/main/java/example/ProductEnrichment.java): бизнес-bean, записи и цепочка валидация → HTTP → выходная модель.
2. [CatalogClient.java](src/main/java/example/CatalogClient.java): HTTP-адаптер приложения; 503 превращается в повторяемую ошибку внутри HTTP-шага.
3. [application.properties](src/main/resources/application.properties): настройки обработки и выбранного шага.
4. [DemoChecks.java](src/main/java/example/DemoChecks.java): внедрённые фабрика и наблюдатель, проверки результата и границ повторов.
5. [CustomDefaults.java](src/main/java/example/CustomDefaults.java): замена политики и фабрики без изменения стартера.
6. [ExampleApplication.java](src/main/java/example/ExampleApplication.java) и [CatalogFixture.java](src/main/java/example/CatalogFixture.java): локальное проверочное окружение и его закрытие; бизнес-приложению это окружение не требуется.

Ожидаемый вывод в обоих режимах:

```text
products=[ProductView[id=1, label=BK-1 / books], ProductView[id=2, label=GM-2 / games]]
validationRuns=2; catalogAttempts=3
httpRequests=3; product1Attempts=2; product2Attempts=1
```

Перед этими строками могут появиться сообщения запуска. Неверный результат или
повторная валидация приводят к AssertionError и неуспешному завершению. Локальный
сервер возвращает небольшие синтетические ответы; лимиты буферизации,
аутентификация и правила рабочих адресов принадлежат приложению. HTTP GET выбран
потому, что повторы записи требуют прикладного контракта идемпотентности.

### Режим аннотированного сервиса

```sh
./gradlew -p examples/product-enrichment run -PexampleMain=example.AnnotatedExampleApplication
```

[AnnotatedCatalogService.java](src/main/java/example/AnnotatedCatalogService.java)
содержит Mono/Flux-методы с аннотациями. [AnnotatedExampleApplication.java](src/main/java/example/AnnotatedExampleApplication.java)
использует внедрённый proxy, объявляет стандартную/именованную StepPolicy и
проверяет ленивый вызов и число HTTP-попыток. Вызовов через this нет.
[Контракт политики/proxy](../../docs/0.1.0/reference.ru.md#aop-для-service-методов)
объясняет явное retrySafe и запрет повторов Flux после выдачи данных.

```text
annotatedProducts=[BK-1 / books, GM-2 / games]
annotatedCategories=[books, games]
monoMethodAttempts=3; fluxMethodAttempts=1; lazy=true
annotatedHttpRequests=5; product1=3; product2=2
```

[← Руководство](../../docs/0.1.0/ru.md) · [README](../../README.md)
