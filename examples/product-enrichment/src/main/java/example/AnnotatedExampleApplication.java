package example;

import example.ProductEnrichment.Product;
import io.github.alex59.pipeline.StepPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Flux;

@SpringBootConfiguration
@EnableAutoConfiguration
public class AnnotatedExampleApplication {
    public static void main(String[] args) throws Exception {
        try (var catalog = new CatalogFixture()) {
            var app = new SpringApplication(AnnotatedExampleApplication.class);
            app.setWebApplicationType(WebApplicationType.NONE);
            // AOP requires explicit activation; ordinary starter usage remains unchanged.
            // AOP нужно явно включить; обычное использование стартера не изменяется.
            app.setDefaultProperties(Map.of("reactive.pipeline.aop.enabled", "true",
                    "catalog.url", catalog.endpoint().toString()));
            try (var context = app.run(args)) {
                if (catalog.requests(1) != 3 || catalog.requests(2) != 2) {
                    throw new AssertionError("Unexpected HTTP request count");
                }
                System.out.println("annotatedHttpRequests=5; product1=3; product2=2");
            }
        }
    }

    @Bean
    AnnotatedCatalogService catalogService(@Value("${catalog.url}") URI endpoint) {
        return new AnnotatedCatalogService(new CatalogClient(HttpClient.newHttpClient(), endpoint));
    }

    // @Primary resolves unnamed annotations and the factory when several policy beans exist.
    // @Primary выбирает политику для аннотации без имени и фабрики при нескольких beans.
    @Bean
    @Primary
    StepPolicy catalogReadPolicy() {
        return new StepPolicy(Duration.ofSeconds(2), 1, Duration.ofMillis(10), IOException.class::isInstance);
    }

    @Bean
    StepPolicy catalogStreamPolicy() {
        return new StepPolicy(Duration.ofSeconds(2), 1, Duration.ZERO, IOException.class::isInstance);
    }

    @Bean
    ApplicationRunner verify(AnnotatedCatalogService service) {
        return args -> {
            var first = new Product(1, "BK-1");
            var second = new Product(2, "GM-2");
            var deferred = service.lookup(first);
            if (service.monoCalls() != 0) throw new AssertionError("Method was invoked eagerly");
            // Use the injected proxy; this.lookup(...) inside the service would bypass advice.
            // Используем внедрённый proxy; this.lookup(...) внутри сервиса обошёл бы advice.
            var monoResults = Flux.concat(deferred, service.lookup(second))
                    .map(value -> value.product().sku() + " / " + value.category())
                    .collectList().block(Duration.ofSeconds(10));
            var streamResults = service.list(List.of(first, second)).map(value -> value.category())
                    .collectList().block(Duration.ofSeconds(10));
            if (!List.of("BK-1 / books", "GM-2 / games").equals(monoResults)
                    || !List.of("books", "games").equals(streamResults)
                    || service.monoCalls() != 3 || service.fluxCalls() != 1) {
                throw new AssertionError("Annotated publisher behavior changed");
            }
            System.out.println("annotatedProducts=" + monoResults);
            System.out.println("annotatedCategories=" + streamResults);
            System.out.println("monoMethodAttempts=3; fluxMethodAttempts=1; lazy=true");
        };
    }
}
