package example;

import example.ProductEnrichment.EnrichedProduct;
import example.ProductEnrichment.Product;
import io.github.alex59.pipeline.ExecutionContext;
import io.github.alex59.pipeline.spring.aop.ReactivePolicy;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

// Public non-final service methods are called through an injected Spring proxy.
// Открытые нефинальные методы сервиса вызываются через внедрённый Spring proxy.
public class AnnotatedCatalogService {
    private final CatalogClient client;
    private final AtomicInteger monoCalls = new AtomicInteger();
    private final AtomicInteger fluxCalls = new AtomicInteger();

    public AnnotatedCatalogService(CatalogClient client) { this.client = client; }

    // Repeating this read-only HTTP GET is safe; the annotation explicitly permits retries.
    // Этот HTTP GET безопасно повторить; аннотация явно разрешает повторные попытки.
    @ReactivePolicy(retrySafe = true)
    public Mono<EnrichedProduct> lookup(Product product) {
        monoCalls.incrementAndGet();
        return read(product);
    }

    // Named policies are ordinary StepPolicy beans; Flux retries stop after its first value.
    // Именованные политики — обычные beans StepPolicy; Flux не повторяется после первого элемента.
    @ReactivePolicy(policy = "catalogStreamPolicy", retrySafe = true)
    public Flux<EnrichedProduct> list(List<Product> products) {
        fluxCalls.incrementAndGet();
        return Flux.fromIterable(products).concatMap(this::read);
    }

    // This client bridge creates its own core metadata; AOP does not fabricate execution events.
    // Этот адаптер клиента создаёт метаданные core; AOP не генерирует события выполнения пайплайна.
    private Mono<EnrichedProduct> read(Product product) {
        return Mono.deferContextual(context -> client.lookup(product,
                new ExecutionContext(UUID.randomUUID(), context)));
    }

    public int monoCalls() { return monoCalls.get(); }
    public int fluxCalls() { return fluxCalls.get(); }
}
