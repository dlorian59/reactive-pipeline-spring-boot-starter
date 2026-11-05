# Verification / Проверки — 2026-11-05

## OSS-013 integration update — 2026-11-05

- Java 17: starter build/source/Javadoc packaging and all 18 configuration/proxy tests passed.
- New separate Maven-artifact WebFlux consumer: one real HTTP integration scenario passed;
  two NDJSON records preserve input order, first catalogue 503 retries once, invalid
  input returns 400 before catalogue access, catalogue 401 is called once and becomes 502.
- The application requires both Boot 4 WebFlux and WebClient starters. Initial missing
  WebClient.Builder was fixed in the consumer and both language guides.
- Existing annotated consumer passed: lazy=true, monoMethodAttempts=3,
  fluxMethodAttempts=1, annotatedHttpRequests=5. Advisor implementation was unchanged.
- Current companion core: 33 core + 5 Jolt tests passed on Java 17. The named read
  policy excludes unclassified I/O failures (including invalid TLS certificates).
- README examples match compiled consumer classes. Local documentation links/anchors,
  workflow YAML and application YAML were checked. CI gained the real HTTP consumer.
- This change does not rerun the full Java 21/25/27 matrix. No registry publication,
  production auth/load benchmark or external deployment is implied by local checks.

### Русский

В OSS-013 прошли сборка стартера на Java 17, 18 тестов конфигурации/proxy и отдельный
HTTP-consumer артефактов Maven. Он проверяет порядок двух NDJSON-записей, один повтор
503, 400 до обращения к каталогу и неповторённый 401 каталога с ответом 502.
Для Boot 4 явно подключены стартеры WebFlux и WebClient; пример и инструкции совпадают.
Старый AOP-consumer сохранил lazy=true, три попытки Mono, одну Flux и пять HTTP-вызовов.
Advice не менялся. В core прошли 33 теста и 5 Jolt; профиль чтения не повторяет
неклассифицированные I/O-сбои, включая неверный TLS-сертификат. Ссылки/якоря и YAML
проверены; HTTP-consumer добавлен в CI. Полная матрица JVM этим изменением не
повторяется, удалённой публикации и проверки рабочего развёртывания нет.

[Reproduce / Повторить](../CONTRIBUTING.md)


[EN guide](0.1.0/en.md) · [Руководство](0.1.0/ru.md) · [Versions / Версии](index.md) · [README](../README.md)

## English

### Current AOP change — 2026-11-05

- Java 17: build and Maven Local publication passed; 18 starter tests passed
  (11 existing configuration tests plus 7 real-proxy AOP tests).
- Independent annotated consumer on Java 17 passed: lazy=true,
  monoMethodAttempts=3, fluxMethodAttempts=1, annotatedHttpRequests=5.
- Original factory-based consumer on Java 17 passed unchanged:
  validationRuns=2, catalogAttempts=3, httpRequests=3.
- JAR classes are version 61.0 (Java 17, no preview); Gradle API/runtime variants
  require JVM 17. Eight generated properties, two auto-configuration imports and
  the MIT license are packaged. Spring AOP is present; AspectJ and optional core
  Java 25/27 modules are not dependencies.
- Markdown navigation: 180 starter and 287 core relative file/anchor/asset links
  resolve. Runnable Java example comments are paired EN then RU.
- The core algorithms were not changed, and its suite/optional runtime matrix
  was not rerun for this AOP integration. Actual AOP execution was checked on
  Java 17 only; the earlier Java 25 result below concerns the original consumer.

```sh
./gradlew build publishToMavenLocal -PtestJavaVersion=17 --no-daemon --max-workers=2
./gradlew -p examples/product-enrichment run -PexampleJava=17 -PexampleMain=example.AnnotatedExampleApplication --no-daemon --max-workers=2
./gradlew -p examples/product-enrichment run -PexampleJava=17 --no-daemon --max-workers=2
```

Reports: build/test-results/test. The examples resolve the Maven Local JAR without
project substitution. The generic advice-chain test covers replay of subsequent
advice, not every transaction/security/tracing combination. No remote publication.

### Previous factory integration — 2026-11-04

Local Linux checks used Gradle 9.8.2, Spring Boot 4.1.2, Reactor 3.8.8 and the
locally published reactive-pipeline-kit 0.3.0. The core test suite was not rerun:
only its core artifact was built, plus the new business example. No core algorithm
was changed for this integration.

| Check | Observed result |
| --- | --- |
| Starter tests, actual Java 17 | 11 passed: defaults, binding, classification, concurrency/order, user beans, disabling, invalid/misspelled properties |
| Published starter consumed by independent example, Java 17 | Default configuration and custom policy/factory mode both passed |
| Consumer copied to a fresh directory, Java 25 | Passed without source/project dependency substitution |
| HTTP scenario in both modes | Two product views; two validations; three HTTP requests; only product 1 retried |
| Core ProductEnrichmentExample, Java 17 | Same business result and retry-boundary assertions passed |
| JAR | Java 17 class files, AutoConfiguration.imports, six generated configuration properties, MIT license |
| Publication variants | Minimum JVM 17; no Java 25/27 optional core artifact dependency |
| Dependency resolution | Reactor 3.8.8 selected by the Boot BOM and core consistently |
| Documentation | 96 starter and 165 core relative file/anchor links resolve; all example Java comments are paired EN then RU |

Reproduce from the starter root with the core checkout next to it:

```sh
../reactive-pipeline-kit/gradlew -p ../reactive-pipeline-kit :core:publishToMavenLocal -PtestJavaVersion=17 --no-daemon --max-workers=2
./gradlew build publishToMavenLocal -PtestJavaVersion=17 --no-daemon --max-workers=2
./gradlew -p examples/product-enrichment run -PexampleJava=17 --no-daemon --max-workers=2
./gradlew -p examples/product-enrichment run -PexampleJava=17 --args='--demo.custom-defaults=true' --no-daemon --max-workers=2
./gradlew -p examples/product-enrichment run -PexampleJava=25 --no-daemon --max-workers=2
```

The standalone copy was placed under build/review/new-consumer; its configuration
and source were copied from examples/product-enrichment and resolved Maven Local
JARs. Build/test XML reports remain under build/test-results/test. Configuration
metadata is generated by Spring Boot's annotation processor, not handwritten.

Java 27 execution was attempted but could not start: the installed toolchain link
points to a missing target. No replacement JDK was downloaded. Java 21 and 27,
native images, remote CI, deployment and external service integration are not
verified for this starter. The [upstream Boot Java 17–27 range](https://docs.spring.io/spring-boot/system-requirements.html)
is separate from this observed local matrix. No performance or production
reliability claim is made by the small loopback example. No remote registry
publication was performed; artifacts were published only to Maven Local.

## Русский

### Текущее изменение AOP — 2026-11-05

- Java 17: сборка и публикация в Maven Local успешны; прошли 18 тестов стартера
  (11 прежних конфигурационных и 7 новых проверок настоящего AOP proxy).
- Независимый аннотированный consumer Java 17 прошёл: lazy=true,
  monoMethodAttempts=3, fluxMethodAttempts=1, annotatedHttpRequests=5.
- Прежний consumer с фабрикой на Java 17 сохранил результат:
  validationRuns=2, catalogAttempts=3, httpRequests=3.
- Байткод JAR — 61.0, Java 17 без preview; API/runtime-варианты Gradle требуют
  JVM 17. Упакованы восемь сгенерированных свойств, две записи автоконфигурации и
  MIT. Есть Spring AOP; AspectJ и модули core Java 25/27 не включены в зависимости.
- Проверены 180 ссылок/якорей/ресурсов стартера и 287 core; комментарии Java-примеров
  идут парами EN, затем RU.
- Алгоритмы core не менялись; его тесты и матрица дополнительных модулей повторно
  не запускались. Новый AOP проверен только на Java 17. Прежний результат Java 25
  ниже относится к consumer с фабрикой.

Команды текущей проверки приведены выше, отчёты — build/test-results/test.
Примеры используют JAR Maven Local без подстановки проекта. Тест следующей
advice-chain проверяет повтор внутренних advice, а не всю матрицу транзакций,
security и tracing. Удалённой публикации нет.

### Предыдущая интеграция фабрики — 2026-11-04

Локальная проверка на Linux: Gradle 9.8.2, Spring Boot 4.1.2, Reactor 3.8.8,
reactive-pipeline-kit 0.3.0 из Maven Local. Полный набор тестов core повторно не
запускался: собран его основной артефакт и проверен новый бизнес-пример. Алгоритмы
core для этой интеграции не изменялись.

| Проверка | Фактический результат |
| --- | --- |
| Тесты стартера на настоящей Java 17 | 11 успешно: defaults, binding, классификация ошибок, параллельность/порядок, пользовательские beans, отключение, неверные свойства и опечатки |
| Отдельный consumer опубликованного JAR, Java 17 | Стандартный режим и собственные политика/фабрика успешно |
| Consumer, скопированный в новый каталог, Java 25 | Успешно, без подмены зависимости исходниками соседнего проекта |
| HTTP-сценарий в обоих режимах | Два результата, две валидации, три HTTP-запроса; повторился только товар 1 |
| ProductEnrichmentExample из core на Java 17 | Тот же бизнес-результат, проверки границ повтора успешны |
| JAR | Байткод Java 17, AutoConfiguration.imports, шесть свойств configuration metadata, лицензия MIT |
| Метаданные публикации | Минимум JVM 17, без зависимостей от дополнительных модулей core Java 25/27 |
| Разрешение зависимостей | Boot BOM и core согласованно выбирают Reactor 3.8.8 |
| Документация | 96 ссылок/якорей стартера и 165 core работают; комментарии Java-примеров идут парами EN, затем RU |

Команды воспроизведения приведены выше; исходники примера находятся в
[examples/product-enrichment](../examples/product-enrichment/README.md#русский).
Проверенная отдельная копия была создана в build/review/new-consumer, XML-отчёты —
в build/test-results/test. Configuration metadata генерирует процессор Boot.

Попытка запуска Java 27 остановилась до исполнения: локальная ссылка на JDK ведёт
в отсутствующий каталог. Другой JDK не скачивался. Java 21/27, native image,
удалённый CI, развёртывание и подключение внешнего сервиса для нового стартера не
проверены. [Официальная совместимость Boot с Java 17–27](https://docs.spring.io/spring-boot/system-requirements.html)
не равна этой фактической матрице. Локальный пример не является замером скорости
или доказательством надёжности в production. Артефакты опубликованы только в Maven
Local; удалённой публикации нет.

[← Versions / Версии](index.md) · [README](../README.md)
