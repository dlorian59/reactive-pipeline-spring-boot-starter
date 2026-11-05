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
