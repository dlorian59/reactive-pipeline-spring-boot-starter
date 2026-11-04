package example;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

// A deterministic loopback fixture; no real catalogue or production credentials are needed.
// Детерминированный локальный сервер; настоящий каталог и рабочие credentials не нужны.
public final class CatalogFixture implements AutoCloseable {
    private final HttpServer server;
    private final ConcurrentHashMap<Integer, AtomicInteger> calls = new ConcurrentHashMap<>();

    public CatalogFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/category/", exchange -> {
            int id = Integer.parseInt(exchange.getRequestURI().getPath().substring("/category/".length()));
            int attempt = calls.computeIfAbsent(id, unused -> new AtomicInteger()).incrementAndGet();
            // Product 1 fails once; the next HTTP attempt succeeds.
            // Для товара 1 первый запрос завершается ошибкой, следующий — успешно.
            boolean unavailable = id == 1 && attempt == 1;
            byte[] body = (unavailable ? "try later" : id == 1 ? "books" : "games").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(unavailable ? 503 : 200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
    }

    public URI endpoint() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    public int requests(int product) {
        var count = calls.get(product);
        return count == null ? 0 : count.get();
    }
    @Override public void close() { server.stop(0); }
}
