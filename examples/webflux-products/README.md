# WebFlux product enrichment / Обогащение товаров на WebFlux

[English chapter](../../docs/0.1.0/en.md) · [Русская глава](../../docs/0.1.0/ru.md) · [Versions / Версии](../../docs/index.md) · [README](../../README.md)

## English

Complete Java 17 application consuming packaged starter 0.1.0 and core 0.3.0.
Stack: Spring Boot 4.1.2, WebFlux server, WebClient, Reactor Netty. It receives
NDJSON products, validates, reads an external category, and returns ordered NDJSON.
The service keeps one immutable pipeline; no manual subscription is required.

Read [ProductService](src/main/java/example/products/ProductService.java), then
[CatalogClient](src/main/java/example/products/CatalogClient.java),
[ProductController](src/main/java/example/products/ProductController.java) and
[application.yml](src/main/resources/application.yml). The guide includes every file.

[WebFluxConsumerTest](src/test/java/example/products/WebFluxConsumerTest.java) uses
real HTTP and a synthetic isolated catalogue. It asserts retry/order and errors;
it is not production auth or a throughput test. [Run contributor checks](../../CONTRIBUTING.md).

## Русский

Полное приложение Java 17 использует упакованные стартер 0.1.0 и core 0.3.0.
Стек: Spring Boot 4.1.2, сервер WebFlux, WebClient, Reactor Netty. Принимает товары
в NDJSON, проверяет, получает внешнюю категорию и выдаёт NDJSON во входном порядке.
Сервис хранит один неизменяемый pipeline; ручная подписка не нужна.

Порядок чтения: ProductService → CatalogClient → ProductController → application.yml;
ссылки на исходники выше, все файлы разобраны в руководстве. WebFluxConsumerTest
использует настоящий HTTP и изолированный синтетический каталог. Проверяет повторы,
порядок и ошибки, а не рабочую авторизацию или производительность.
[Проверки разработчика](../../CONTRIBUTING.md).
