package example.products;

import io.github.alex59.pipeline.StepFailure;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class ProductController {
    private final ProductService products;
    public ProductController(ProductService products) { this.products = products; }

    @PostMapping(value = "/products/enrich", consumes = "application/x-ndjson", produces = "application/x-ndjson")
    public Flux<ProductView> enrich(@RequestBody Flux<Product> input) {
        return products.enrich(input);
    }

    // This response can replace a failure only before HTTP output is committed.
    // Этот ответ заменяет ошибку только до фиксации HTTP-ответа.
    @ExceptionHandler(StepFailure.class)
    public ProblemDetail failed(StepFailure failure) {
        boolean invalid = failure.step().equals("validate") && failure.getCause() instanceof IllegalArgumentException;
        return ProblemDetail.forStatusAndDetail(invalid ? HttpStatus.BAD_REQUEST : HttpStatus.BAD_GATEWAY,
                invalid ? "Invalid product" : "Catalogue request failed");
    }
}
