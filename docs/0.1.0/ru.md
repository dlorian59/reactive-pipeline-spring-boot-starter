# 0.1.0 — обогащение товаров со Spring Boot

[Полный справочник API](reference.ru.md)

[EN](en.md) · [RU](ru.md) · [Версии](../index.md) · [README](../../README.md)

## Задача и ответственность

Получив ID и SKU товара, проверить запись, загрузить категорию из каталога и
вернуть модель для отображения. Временную ошибку каталога нужно повторить, не
запуская валидацию заново. Приложение владеет типизированными шагами, HTTP-клиентом
и подписками; стартер собирает настройки и beans.

| Возможность | Исполняемый исходник |
| --- | --- |
| Описать бизнес-пайплайн | [ProductEnrichment.java](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java) |
| Классифицировать HTTP-ошибку внутри повторяемого шага | [CatalogClient.java](../../examples/product-enrichment/src/main/java/example/CatalogClient.java) |
| Применить параллельность и порядок к записям | [DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| Настроить попытки и обработку | [application.properties](../../examples/product-enrichment/src/main/resources/application.properties) |
| Заменить beans политики и фабрики | [CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |
| Подключить собственного наблюдателя | [DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |

## Подключение и запуск

Нужны JDK 17+, wrapper Gradle 9.8.2, Spring Boot 4.1.2 и core 0.3.0. Из каталога
стартера, рядом с которым находится исходный core:

```sh
../reactive-pipeline-kit/gradlew -p ../reactive-pipeline-kit :core:publishToMavenLocal
./gradlew build publishToMavenLocal
./gradlew -p examples/product-enrichment run
```

Пример — самостоятельный Gradle consumer, который получает опубликованные JAR
из Maven Local. Здесь нет includeBuild и подмены зависимостей соседними проектами.
Для существующего приложения добавьте:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}
dependencies {
    implementation("io.github.59alex:reactive-pipeline-spring-boot-starter:0.1.0")
}
```

Обычный @SpringBootApplication обнаружит автоконфигурацию через
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports.
Сканирование пакетов библиотеки и явный @Import не нужны. В обычный JAR также
включены сгенерированные Spring configuration metadata. Публикация в Maven Central
не заявляется.

## Описать бизнес-процесс

[ProductEnrichment.java](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java)
содержит полный bean. Его основная цепочка:

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

Результат — обычный Pipeline<Product, ProductView> из core, без новой DSL и
реестра на рефлексии. Внедрите PipelineFactory и этот пайплайн в точке входа записей
и верните вызывающему коду pipelines.process(pipeline, records). Вызов block в демо
нужен на границе CLI и не предназначен для обработчика на event loop WebFlux.

## Осознанно задать настройки

```properties
reactive.pipeline.concurrency=2
reactive.pipeline.ordering=INPUT
reactive.pipeline.step.timeout=2s
reactive.pipeline.step.max-retries=1
reactive.pipeline.step.retry-delay=10ms
```

| Свойство после reactive.pipeline | По умолчанию | Значение |
| --- | --- | --- |
| enabled | true | false отключает все автоматически создаваемые beans стартера |
| concurrency | 4 | Положительный предел активных подписок на записи в одном вызове process |
| ordering | INPUT | INPUT или COMPLETION; порядок внешних эффектов не гарантируется |
| step.timeout | 30s | Положительный таймаут каждой попытки явно выбранного шага |
| step.max-retries | 0 | Дополнительные попытки, а не их общее количество |
| step.retry-delay | 100ms | Неотрицательная задержка между повторами |

Настройки попыток применяются только к шагам с pipelines.stepPolicy(). Обычные
map/then сохраняют политику core: 30 секунд, без повторов. Factory.process задаёт
параллельность и порядок; прямой pipeline.process использует переданные в него
аргументы. Настройки неизменяемы после запуска, динамического обновления нет.
При включённой автоконфигурации неизвестные ключи и неверные значения
параметров обработки и попыток останавливают запуск. Длительности должны
помещаться в знаковое 64-битное число наносекунд; max-retries — от 0 до 2147483646,
чтобы общее число попыток помещалось в целочисленный счётчик core.

Если повторы включены, стандартный предикат принимает только IOException и
TimeoutException. Он не обходит произвольную цепочку cause. HTTP-статусы сами по
себе являются данными: [клиент](../../examples/product-enrichment/src/main/java/example/CatalogClient.java)
превращает 503 в подкласс IOException внутри HTTP-шага. Ошибка следующего
преобразования не повторяет запрос. Для эффектов с неустановленной идемпотентностью
оставляйте повторы выключенными.

## Заменить beans и подключить наблюдение

Запустите тот же сценарий с политикой и фабрикой приложения:

```sh
./gradlew -p examples/product-enrichment run --args='--demo.custom-defaults=true'
```

[CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java)
задаёт доменный предикат повторов и параллельность 1. Его StepPolicy и PipelineFactory
заменяют соответствующие стандартные beans. Собственная фабрика владеет своими
настройками: свойства конфигурации уже не выбирают аргументы её конструктора.
[DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java)
добавляет потокобезопасный StepObserver вместо пустого наблюдателя. События
подтверждают две валидации и три HTTP-попытки.

Каждый стандартный bean отдельно уступает пользовательскому bean того же типа.
Если beans одного типа несколько, выберите один через @Primary либо явно соберите
фабрику. Наблюдатель должен быть неблокирующим и потокобезопасным. Нефатальные ошибки
наблюдателя не повторяют бизнес-эффекты; объекты исключений в диагностике могут
содержать данные приложения.

## Результат и границы

Локальный сервер возвращает 503 на первый запрос товара 1, а на повтор отвечает
успешно. Товар 2 загружается сразу. Программа проверяет и выводит:

```text
products=[ProductView[id=1, label=BK-1 / books], ProductView[id=2, label=GM-2 / games]]
validationRuns=2; catalogAttempts=3
httpRequests=3; product1Attempts=2; product2Attempts=1
```

Таймаут запрашивает отмену; отмена и повтор не откатывают удалённый эффект. Строковое
тело HTTP-ответа полностью буферизуется. Параллельность ограничивается на поток
обработки, а не глобально и не по байтам. Входные значения желательно делать
неизменяемыми. Здесь нет очереди, постоянного журнала, восстановления после
перезапуска, распределённой транзакции, scheduler или автоконфигурации HTTP-клиента.
Аутентификация, адреса, лимиты тел и идемпотентность принадлежат приложению.
Необязательные модули core для Java 25/27 транзитивно не подключаются.

## Если не запускается

- Could not find io.github.59alex:reactive-pipeline-kit:0.3.0: сначала соберите core в тот же Maven Local, который используют стартер и пример.
- Failed to bind properties under reactive.pipeline: проверьте написание, положительные concurrency/timeout, неотрицательные значения повторов и INPUT/COMPLETION.
- No qualifying bean of type PipelineFactory: проверьте runtime-зависимость от стартера, включённую автоконфигурацию Boot и отсутствие reactive.pipeline.enabled=false.
- NoUniqueBeanDefinitionException: при нескольких политиках или наблюдателях выберите стандартный bean приложения через @Primary.
- HTTP 503 не повторился: классифицируйте статус внутри HTTP-шага, передайте factory.stepPolicy() и явно включите повторы.

Байткод собирается для Java 17 без preview. [Официальный диапазон Spring Boot 4.1.2](https://docs.spring.io/spring-boot/system-requirements.html)
— Java 17–27; фактические локальные проверки указаны в
[отчёте](../VERIFICATION.md). Интеграция следует
[контракту автоконфигурации Spring Boot](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html).

## Политика для внедрённого сервиса

Для одного Mono/Flux-метода включите reactive.pipeline.aop.enabled и добавьте
@ReactivePolicy к публичному нефинальному методу. По умолчанию retrySafe=false
запрещает повторы даже при положительном бюджете политики. retrySafe=true
допустим, только когда весь метод можно безопасно выполнить снова.

```java
@ReactivePolicy(retrySafe = true)
public Mono<EnrichedProduct> lookup(Product product) {
    return read(product);
}
```

Метод взят из [AnnotatedCatalogService.java](../../examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java);
там находятся импорты и HTTP-реализация. [AnnotatedExampleApplication.java](../../examples/product-enrichment/src/main/java/example/AnnotatedExampleApplication.java)
внедряет proxy, объявляет политики и подписывается, проверяя отсутствие работы до
подписки. PipelineFactory остаётся прежним.

```sh
./gradlew -p examples/product-enrichment run -PexampleMain=example.AnnotatedExampleApplication
```

Ожидается: monoMethodAttempts=3, fluxMethodAttempts=1, annotatedHttpRequests=5.
Пример также выбирает именованную политику для Flux. После первой выданной записи
Flux не повторяется; его таймаут измеряет отсутствие новых сигналов данных.
Вызов через this обходит proxy. Выберите одного владельца retry для I/O вместо
одновременного повтора политикой шага и аннотацией. [Полный контракт AOP](reference.ru.md#aop-для-service-методов)
описывает отмену, пустые результаты, выбор политики, транзакции и порядок advice.


[Запуск и исходники примера](../../examples/product-enrichment/README.md#русский) · [Назад к версиям](../index.md) · [README](../../README.md)
