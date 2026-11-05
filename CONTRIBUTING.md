# Contributor checks / Проверки разработчика

[README](README.md) · [EN integration](docs/0.1.0/en.md) · [RU интеграция](docs/0.1.0/ru.md)

## English

Use JDK 17+ and the wrapper; the independent WebFlux example selects an installed
JDK 17. These are source-maintenance instructions, not application setup. Publish
the sibling core and this starter to the same Maven Local repository first:

```sh
../reactive-pipeline-kit/gradlew -p ../reactive-pipeline-kit :core:publishToMavenLocal --no-daemon
./gradlew build publishToMavenLocal --no-daemon
./gradlew -p examples/webflux-products test --no-daemon
./gradlew -p examples/product-enrichment run --no-daemon
./gradlew -p examples/product-enrichment run -PexampleMain=example.AnnotatedExampleApplication --no-daemon
```

The WebFlux consumer has no project/source substitution: it resolves the two JARs,
starts a real Boot HTTP server on a random port and an isolated catalogue fixture,
then closes both. Its HTTP test checks NDJSON, order, 503 retry, invalid-input 400
and nonretried catalogue 401. The old consumer separately checks factory wiring
and proxy/AOP. Unit tests preserve advice chains, cancellation and Flux partial
output. See [recorded evidence](docs/VERIFICATION.md).

To run the application against your own trusted development catalogue:

```sh
./gradlew -p examples/webflux-products run --args='--catalog.base-url=http://127.0.0.1:9091'
```

The catalogue must provide GET /categories/{id} with a small text category.
Production authorization, TLS and import limits belong to the application.
Nothing here publishes to Central or deploys an external application.

## Русский

Нужны JDK 17+ и wrapper; отдельный WebFlux consumer выбирает установленную JDK 17.
Это инструкция работы с исходниками, не подключения к приложению. Команды выше
сначала публикуют соседний core и стартер в один Maven Local, затем проверяют
потребителей артефактов. Подстановки соседнего проекта или исходников нет.

HTTP-тест запускает настоящий Boot-сервер на случайном порту и изолированный
каталог, затем закрывает оба. Проверяются NDJSON, порядок, повтор 503, ошибка 400
до каталога и отсутствие повтора его 401. Прежний consumer отдельно проверяет
фабрику и AOP. Unit-тесты защищают цепочку advice, отмену и частичный вывод Flux.
[Фактические результаты](docs/VERIFICATION.md).

Последняя команда запускает приложение с вашим доверенным локальным каталогом.
Он должен отдавать небольшую текстовую категорию на GET /categories/{id}.
Рабочая авторизация, TLS и лимиты импорта принадлежат приложению. Команды не
публикуют артефакты в Central и не развёртывают внешнее приложение.
