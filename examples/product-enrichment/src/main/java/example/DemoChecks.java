package example;

import example.ProductEnrichment.Product;
import example.ProductEnrichment.ProductView;
import io.github.alex59.pipeline.Pipeline;
import io.github.alex59.pipeline.StepEvent;
import io.github.alex59.pipeline.StepObserver;
import io.github.alex59.pipeline.spring.PipelineFactory;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;

// Verification belongs to the demo, not to the business pipeline configuration.
// Проверки относятся к демо, а не к конфигурации бизнес-пайплайна.
@Configuration(proxyBeanMethods = false)
public class DemoChecks {
    private final ConcurrentLinkedQueue<StepEvent> events = new ConcurrentLinkedQueue<>();

    // A user observer bean replaces the no-op observer without extra wiring.
    // Bean наблюдателя приложения заменяет пустой наблюдатель без дополнительной обвязки.
    @Bean
    StepObserver diagnostics() { return events::add; }

    @Bean
    ApplicationRunner verify(PipelineFactory pipelines, Pipeline<Product, ProductView> pipeline) {
        return arguments -> {
            var records = Flux.just(new Product(1, "BK-1"), new Product(2, "GM-2"));
            // Subscription is application-owned; blocking is only this command-line boundary.
            // Подпиской владеет приложение; блокировка допустима только на этой границе CLI.
            var result = pipelines.process(pipeline, records).collectList().block(Duration.ofSeconds(10));
            var expected = List.of(new ProductView(1, "BK-1 / books"), new ProductView(2, "GM-2 / games"));
            if (!expected.equals(result)) throw new AssertionError("Unexpected result: " + result);
            long validations = events.stream().filter(event -> event.step().equals("validate")
                    && event.kind() == StepEvent.Kind.STARTED).count();
            long attempts = events.stream().filter(event -> event.step().equals("catalog")
                    && event.kind() == StepEvent.Kind.STARTED).count();
            if (validations != 2 || attempts != 3) throw new AssertionError("Retry boundary changed");
            System.out.println("products=" + result);
            System.out.println("validationRuns=" + validations + "; catalogAttempts=" + attempts);
        };
    }
}
