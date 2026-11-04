package example;

import io.github.alex59.pipeline.Pipeline;
import io.github.alex59.pipeline.spring.PipelineFactory;
import java.net.URI;
import java.net.http.HttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class ProductEnrichment {
    public record Product(int id, String sku) { }
    public record EnrichedProduct(Product product, String category) { }
    public record ProductView(int id, String label) { }

    // The application chooses its HTTP client and trusted catalogue endpoint.
    // Приложение выбирает HTTP-клиент и доверенный адрес каталога.
    @Bean
    CatalogClient catalogClient(@Value("${catalog.url}") URI endpoint) {
        return new CatalogClient(HttpClient.newHttpClient(), endpoint);
    }

    // Business flow: validate once, enrich via HTTP, then build the output model.
    // Бизнес-процесс: проверить один раз, обогатить через HTTP, собрать выходную модель.
    @Bean
    Pipeline<Product, ProductView> productPipeline(PipelineFactory pipelines, CatalogClient catalog) {
        return pipelines.<Product>start()
                .map("validate", product -> {
                    if (product.id() < 1 || product.sku() == null || product.sku().isBlank()) {
                        throw new IllegalArgumentException("Product id and SKU are required");
                    }
                    return product;
                })
                // Only the HTTP step opts into the configured retry policy.
                // Только HTTP-шаг использует настроенную политику повторов.
                .then("catalog", catalog::lookup, pipelines.stepPolicy())
                .map("view", value -> new ProductView(value.product().id(),
                        value.product().sku() + " / " + value.category()));
    }
}
