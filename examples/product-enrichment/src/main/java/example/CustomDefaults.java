package example;

import io.github.alex59.pipeline.Ordering;
import io.github.alex59.pipeline.StepObserver;
import io.github.alex59.pipeline.StepPolicy;
import io.github.alex59.pipeline.spring.PipelineFactory;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// Enable with --demo.custom-defaults=true to exercise user bean precedence.
// Включите --demo.custom-defaults=true для проверки приоритета beans приложения.
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "demo.custom-defaults", havingValue = "true")
public class CustomDefaults {
    @Bean
    StepPolicy domainPolicy() {
        // Retry only this domain exception, rather than every IOException/timeout.
        // Повторяем только эту доменную ошибку, а не любой IOException или таймаут.
        return new StepPolicy(Duration.ofSeconds(3), 1, Duration.ofMillis(25),
                CatalogClient.CatalogUnavailableException.class::isInstance);
    }

    @Bean
    PipelineFactory domainFactory(StepPolicy policy, StepObserver observer) {
        // A custom factory owns its defaults; the auto-configured factory backs off.
        // Собственная фабрика задаёт свои настройки; автофабрика не создаётся.
        return new PipelineFactory(policy, 1, Ordering.INPUT, observer);
    }
}
