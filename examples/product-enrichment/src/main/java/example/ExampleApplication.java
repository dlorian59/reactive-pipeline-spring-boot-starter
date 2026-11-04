package example;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;

@SpringBootConfiguration
@EnableAutoConfiguration
@Import({ProductEnrichment.class, DemoChecks.class, CustomDefaults.class})
public class ExampleApplication {
    public static void main(String[] args) throws Exception {
        // Only the demo starts a local fixture. Real applications use their catalogue URL.
        // Только демо запускает локальный сервер. Рабочее приложение использует адрес своего каталога.
        try (var catalog = new CatalogFixture()) {
            var application = new SpringApplication(ExampleApplication.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            application.setDefaultProperties(Map.of("catalog.url", catalog.endpoint().toString()));
            try (var context = application.run(args)) {
                if (catalog.requests(1) != 2 || catalog.requests(2) != 1) {
                    throw new AssertionError("Expected one retry for product 1 only");
                }
                System.out.println("httpRequests=3; product1Attempts=2; product2Attempts=1");
            }
        }
    }
}
