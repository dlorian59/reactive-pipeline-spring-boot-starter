package example.products;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;

class WebFluxConsumerTest {
    @Test
    void realHttpNdjsonRetriesSelectedReadAndRejectsInvalidOrUnauthorizedWithoutRetry() throws Exception {
        var calls = new ConcurrentHashMap<Long, AtomicInteger>();
        var fixture = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fixture.createContext("/categories/", exchange -> {
            long id = Long.parseLong(exchange.getRequestURI().getPath().substring("/categories/".length()));
            int attempt = calls.computeIfAbsent(id, unused -> new AtomicInteger()).incrementAndGet();
            int status = id == 3 ? 401 : id == 1 && attempt == 1 ? 503 : 200;
            byte[] body = (status == 200 ? "books" : "unavailable").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        fixture.start();
        try (var app = SpringApplication.run(ProductsApplication.class, "--server.port=0",
                "--catalog.base-url=http://127.0.0.1:" + fixture.getAddress().getPort())) {
            var endpoint = URI.create("http://127.0.0.1:" + app.getEnvironment().getProperty("local.server.port")
                    + "/products/enrich");
            var client = HttpClient.newHttpClient();
            var response = send(client, endpoint, "{\"id\":1,\"sku\":\"BK-1\"}\n{\"id\":2,\"sku\":\"BK-2\"}\n");
            assertThat(response.statusCode()).isEqualTo(200);
            assertThat(response.body().lines().toList()).hasSize(2);
            assertThat(response.body()).containsSubsequence("\"id\":1", "\"id\":2").contains("\"category\":\"books\"");
            assertThat(calls.get(1L).get()).isEqualTo(2);
            assertThat(calls.get(2L).get()).isEqualTo(1);
            assertThat(send(client, endpoint, "{\"id\":0,\"sku\":\"\"}\n").statusCode()).isEqualTo(400);
            assertThat(calls).doesNotContainKey(0L);
            assertThat(send(client, endpoint, "{\"id\":3,\"sku\":\"private\"}\n").statusCode()).isEqualTo(502);
            assertThat(calls.get(3L).get()).isEqualTo(1);
        } finally {
            fixture.stop(0);
        }
    }

    private static HttpResponse<String> send(HttpClient client, URI endpoint, String input) throws Exception {
        return client.send(HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-ndjson").header("Accept", "application/x-ndjson")
                .POST(HttpRequest.BodyPublishers.ofString(input)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
