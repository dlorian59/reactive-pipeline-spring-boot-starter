package example.products;

import io.github.alex59.pipeline.Pipeline;
import io.github.alex59.pipeline.spring.PipelineFactory;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

@Service
public class ProductService {
    private final Pipeline<Product, ProductView> pipeline;

    public ProductService(PipelineFactory pipelines, CatalogClient catalog) {
        // Validate once per record; retry only the read-only catalogue step.
        // Проверяем запись один раз; повторяем только чтение каталога.
        this.pipeline = pipelines.<Product>start()
                .map("validate", Product::validated)
                .then("catalog", catalog::lookup, pipelines.stepPolicy());
    }

    public Flux<ProductView> enrich(Flux<Product> products) {
        // The pipeline retains factory concurrency/order; WebFlux owns the subscription.
        // Pipeline сохраняет лимит и порядок фабрики; подпиской управляет WebFlux.
        return pipeline.process(products);
    }
}
