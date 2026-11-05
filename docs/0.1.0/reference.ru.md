# 0.1.0 — полный справочник стартера

[EN](reference.en.md) · [RU](reference.ru.md) · [Практическое руководство](ru.md) · [Версии](../index.md) · [README](../../README.md)

Справочник охватывает прикладной API, настройки, точки замены и ограничения
интеграции. Инфраструктурные классы создают beans, а не новую модель описания
пайплайнов. Core остаётся отдельной библиотекой Java 17.

## Фабрика и политика пайплайна

| Публичная возможность | Поведение | Исходник и запускаемый пример |
| --- | --- | --- |
| PipelineFactory(StepPolicy, int, Ordering, StepObserver) | Неизменяемые явные настройки; concurrency положителен, остальные аргументы не null | [Фабрика](../../src/main/java/io/github/alex59/pipeline/spring/PipelineFactory.java), [собственная фабрика](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |
| start<T>() | Возвращает core Pipeline<T,T> с заданными observer и настройками обработки; ничего не запускает и не регистрирует | [Бизнес-пайплайн](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java) |
| stepPolicy() | Возвращает StepPolicy для явного then(..., policy) | [HTTP-шаг](../../examples/product-enrichment/src/main/java/example/ProductEnrichment.java) |
| process(pipeline, source) | Применяет параллельность и порядок, не заменяет наблюдатель переданного пайплайна | [Обработка и проверки](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| Замена StepPolicy | Меняет классификатор IOException/TimeoutException, таймаут и бюджет повторов | [CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |
| Замена StepObserver | Заменяет StepObserver.NONE; callbacks должны быть потокобезопасными и неблокирующими | [DemoChecks.java](../../examples/product-enrichment/src/main/java/example/DemoChecks.java) |
| Замена PipelineFactory | Настройками владеет конструктор фабрики; каждый стандартный bean отдельно уступает bean того же типа | [CustomDefaults.java](../../examples/product-enrichment/src/main/java/example/CustomDefaults.java) |

Обычные core map/then сохраняют политику 30 секунд без повторов. Фабрика не
перехватывает произвольные пайплайны. Бюджет параллельности относится к подписке
на поток обработки, а не ко всему приложению или байтам. Callbacks и изменяемые
аргументы не становятся потокобезопасными. Стартер не создаёт scheduler, executor
или HTTP-клиент.

## AOP для service-методов

Включите advisor и аннотируйте сервис, вызываемый через внедрённый proxy:

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

Метод взят из [AnnotatedCatalogService.java](../../examples/product-enrichment/src/main/java/example/AnnotatedCatalogService.java);
в том же файле находятся HTTP-адаптер приложения и импорты.
[AnnotatedExampleApplication.java](../../examples/product-enrichment/src/main/java/example/AnnotatedExampleApplication.java)
внедряет сервис, проверяет отсутствие работы при обычном вызове и затем подписывается
на Mono и Flux. После локальной публикации выполните из корня стартера:

```sh
./gradlew -p examples/product-enrichment run -PexampleMain=example.AnnotatedExampleApplication
```

Ожидаются две подписи товаров, две категории, monoMethodAttempts=3,
fluxMethodAttempts=1 и annotatedHttpRequests=5. В Mono-сценарии повторяется один
HTTP 503. Последующий Flux выполняет ещё два успешных чтения.

| Параметр аннотации | По умолчанию | Контракт |
| --- | --- | --- |
| policy | пустая строка | Выбор StepPolicy по типу/@Primary при подписке; непустая строка выбирает bean с таким именем |
| retrySafe | false | False запрещает повторы даже при положительном бюджете; true явно подтверждает безопасность повторов и разрешает бюджет/предикат политики |

[Исходник аннотации](../../src/main/java/io/github/alex59/pipeline/spring/aop/ReactivePolicy.java) ·
[Реализация advisor](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/ReactivePolicyAdvisor.java)

- Поддерживаются только public non-final instance-методы, реализация которых объявляет точный тип Mono или Flux. Аннотация ставится на метод или его интерфейсное объявление. Неподдерживаемая сигнатура мешает созданию proxy; null вместо publisher приводит к ошибке при подписке.
- Каждая подписка вызывает метод заново. Каждый разрешённый повтор также вызывает его заново с отдельным клоном оставшейся цепочки Spring advice. Массив аргументов фиксируется, но его объекты глубоко не копируются: используйте неизменяемые входные данные.
- Внутри нет subscribe, block и scheduler. Тело выполняется в потоке подписки, должно быть неблокирующим и возвращать cold publisher. Hot/cached publishers и заранее запущенные futures имеют самостоятельную работу; advisor не делает её cold и не отменяет внешние эффекты.
- Пустой Mono, Mono<Void> и пустой Flux успешно завершаются. В отличие от шага core, advisor не требует одного значения и не создаёт StepFailure, ExecutionContext или события StepObserver. Итоговая ошибка сохраняет свой тип вместо обёртки retry-exhausted.
- Таймаут Mono ограничивает ожидание значения/завершения. Таймаут Flux измеряет бездействие до первого элемента и между элементами, а не полную длительность потока. Часы не останавливаются, когда downstream удерживает demand.
- Flux повторяется только до первого элемента. После выдачи элемента последующие ошибки и таймауты передаются без retry, сохраняя уже выданные данные без replay. retrySafe=true не откатывает записи, сделанные до первого элемента.
- Отмена отменяет активный publisher/таймер и не повторяется. Она не может принудительно остановить блокирующее тело или отменить выполненную удалённую операцию. Немедленная отмена может предотвратить сам вызов тела.
- Политика выбирается один раз на подписку. Отсутствующий или неоднозначный bean вызывает ошибку до запуска целевого метода. Несколько политик всё ещё требуют @Primary для обычной PipelineFactory, если вы не собираете фабрику явно.

### Границы proxy и порядка advice

Self-invocation (this.lookup), new Service(), private/static/final методы и вызовы
в обход Spring proxy не получают это поведение. Финальному классу нужен подходящий
интерфейс и JDK proxy; CGLIB не может унаследовать его. Аннотация сама по себе не
делает вызов в обход proxy безопасным.

Advisor использует общий auto-proxy creator Spring, без AspectJ weaving. Уже
существующая более функциональная инфраструктура proxy сохраняется; если creator
ещё нет, Spring выбирает интерфейсный proxy при наличии интерфейса, иначе proxy
класса. Стартер отдельно не навязывает тип proxy. Его явное включение создаёт
инфраструктуру даже при отключённой автоматической AOP-конфигурации Boot; для
отключения этого механизма используйте reactive.pipeline.aop.enabled=false.

Порядок advisor по умолчанию — Integer.MAX_VALUE. Меньшее число оборачивает
последующие advisors; равные числа не задают гарантированный взаимный порядок.
Внешний advice выполняется при первоначальном вызове proxy и не вызывается заново
этим retry. Внутренний advice откладывается до подписки и повторяется с попытками.

Для отдельной транзакции на попытку policy-advisor должен оборачивать transaction
advice (иметь меньшее значение order), а транзакционный механизм — поддерживать
реактивную модель. Проверка общей оставшейся advice-chain не доказывает все сочетания
@Transactional, security и tracing. Проверяйте свой стек: стартер не создаёт
транзакции и не переносит ThreadLocal-контекст. Не складывайте retry core-шага и
@ReactivePolicy для одного I/O незаметно: бюджеты умножаются. Выберите одного
владельца retry. Одноразовый InputStream, тело загрузки файла или неповторяемую
команду нельзя считать retrySafe без отдельного прикладного контракта.

## Все настройки

[Исходник свойств](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineProperties.java) ·
[Настройки примера](../../examples/product-enrichment/src/main/resources/application.properties)

Все имена начинаются с reactive.pipeline. Настройки связываются один раз при запуске.

| Суффикс | По умолчанию | Проверка / применение |
| --- | --- | --- |
| enabled | true | false запрещает установку стандартных beans, включая AOP |
| concurrency | 4 | Положительное число; factory.start и factory.process |
| ordering | INPUT | INPUT или COMPLETION; factory.start и factory.process |
| step.timeout | 30s | Положительный; попытка core либо описанный выше таймаут сигналов AOP |
| step.max-retries | 0 | 0…2147483646; AOP дополнительно требует retrySafe=true |
| step.retry-delay | 100ms | Неотрицательная фиксированная задержка, ноль означает немедленный повтор |
| aop.enabled | false | Явное включение proxy-интеграции |
| aop.order | 2147483647 | Любое int; порядок Spring advisor, не лимит параллельности |

Длительности должны помещаться в знаковое 64-битное число наносекунд. При включённой
конфигурации неизвестные ключи и неверные параметры обработки/попыток мешают binding.
PipelineProperties, StepDefaults и AopDefaults — неизменяемые records с соответствующими
accessors. Указанные значения по умолчанию применяет Boot binding. При прямом
вызове Java-конструктора нужно передать все аргументы; они проверяются, а
@DefaultValue не подставляет пропущенные значения Java. Исходный конструктор
PipelineProperties с четырьмя аргументами сохранён и оставляет AOP выключенным.

## Автоконфигурация и упаковка

[PipelineAutoConfiguration](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineAutoConfiguration.java)
предоставляет стандартные фабрику, политику и наблюдатель. При положительном бюджете
стандартная политика повторяет IOException и TimeoutException, не обходя cause chain.
[PipelineAopAutoConfiguration](../../src/main/java/io/github/alex59/pipeline/spring/autoconfigure/PipelineAopAutoConfiguration.java)
запускается после установки настроек и требует aop.enabled=true. Infrastructure-bean
типа Advisor с именем reactivePolicyAdvisor заменяет стандартный; такая расширенная
настройка сама отвечает за поведение и порядок.

Обе записи находятся в
[AutoConfiguration.imports](../../src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports).
Обычный JAR содержит сгенерированные configuration metadata и байткод Java 17.
Spring AOP входит в зависимости, AspectJ weaving и модули core Java 25/27 — нет.
[Сборка](../../build.gradle.kts) по умолчанию использует JVM Gradle и --release 17;
testJavaVersion выбирает другой установленный toolchain.

## Проверки и диагностика

[Тесты настоящих proxy](../../src/test/java/io/github/alex59/pipeline/spring/autoconfigure/ReactivePolicyAopTest.java)
проверяют отложенность, повторную подписку и оставшийся advice, политики по имени
и интерфейсу, opt-in retry, пустые результаты, частичный Flux, demand и отмену.
[Локальные результаты и непроверенные runtime](../VERIFICATION.md).

| Симптом | Что проверить |
| --- | --- |
| Аннотация не действует | Включите aop.enabled, вызывайте внедрённый proxy, а не this/new; проверьте public non-final сигнатуру |
| Вызовов больше ожидаемого | Считайте подписки отдельно от повторов; уберите вложенные retry |
| Flux остановился после выдачи данных | Это намеренно: после частичной выдачи retry запрещён |
| NoSuchBeanDefinitionException / NoUniqueBeanDefinitionException | Исправьте имя политики или @Primary; имя в аннотации не заменяет wiring фабрики |
| Неожиданная область транзакции | Задайте порядок advisors и проверьте реальный реактивный транзакционный стек |

Первичные источники: [Spring proxying](https://docs.spring.io/spring-framework/reference/core/aop/proxying.html),
[invocableClone](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/aop/ProxyMethodInvocation.html),
[Mono](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Mono.html),
[Flux](https://projectreactor.io/docs/core/release/api/reactor/core/publisher/Flux.html).

[← Практическое руководство](ru.md) · [Версии](../index.md) · [README](../../README.md)

## Обработка напрямую через pipeline фабрики

factory.start() закрепляет настроенные бюджет записей и порядок выдачи, поэтому
сервис хранит один pipeline и возвращает pipeline.process(records). then/map/
thenIf/observe сохраняют настройки. withProcessing(options) заменяет их в новом
неизменяемом определении; исходное не меняется. Явные
process(records, concurrency, ordering) и factory.process(pipeline, records)
сохранены. Политика шага остаётся явной: настроенные повторы не применяются
скрытно к валидации или каждому обычному методу.

[Полный HTTP-consumer](../../examples/webflux-products/README.md) · [README](../../README.md)
