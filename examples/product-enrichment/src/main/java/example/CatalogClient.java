package example;

import example.ProductEnrichment.EnrichedProduct;
import example.ProductEnrichment.Product;
import io.github.alex59.pipeline.ExecutionContext;
import io.github.alex59.pipeline.HttpPort;
import io.github.alex59.pipeline.HttpSteps;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import reactor.core.publisher.Mono;

public final class CatalogClient {
    private final HttpPort<HttpRequest, HttpResponse<String>> http;
    private final URI endpoint;

    public CatalogClient(HttpClient client, URI endpoint) {
        this.http = HttpSteps.jdk(client, HttpResponse.BodyHandlers.ofString());
        this.endpoint = endpoint;
    }

    public Mono<EnrichedProduct> lookup(Product product, ExecutionContext context) {
        var request = HttpRequest.newBuilder(endpoint.resolve("/category/" + product.id())).GET().build();
        // Classify status inside this step so a retry repeats the request, not validation.
        // Классифицируем статус внутри шага, чтобы повторить запрос, а не валидацию.
        return http.exchange(request, context).flatMap(response -> {
            if (response.statusCode() == 503) return Mono.error(new CatalogUnavailableException());
            if (response.statusCode() != 200) {
                return Mono.error(new IllegalStateException("Catalogue status: " + response.statusCode()));
            }
            return Mono.just(new EnrichedProduct(product, response.body()));
        });
    }

    // A domain-specific IOException is eligible for the starter's default I/O classifier.
    // Доменный IOException подходит под стандартный классификатор ошибок I/O стартера.
    public static final class CatalogUnavailableException extends IOException {
        public CatalogUnavailableException() { super("Catalogue temporarily unavailable"); }
    }
}
