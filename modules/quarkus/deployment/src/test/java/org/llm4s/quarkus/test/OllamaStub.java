package org.llm4s.quarkus.test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * A loopback server that answers Ollama's {@code POST /api/chat} with a canned reply and {@code POST /api/embeddings}
 * with a three-number vector, so the tests run the real llm4s-ollama client over HTTP with no model and no key. A
 * failing stub answers every request with an HTTP 500.
 *
 * <p>A test starts it before Quarkus builds the application and refers to it as {@code ${llm4s-test.stub-url}}: the
 * system property {@link #URL_PROPERTY}, which every class loader of the test sees.
 */
final class OllamaStub {

    static final String REPLY = "Hello from the stub";

    static final String URL_PROPERTY = "llm4s-test.stub-url";

    /** {@code base-url}'s value in a test's configuration. */
    static final String URL = "${" + URL_PROPERTY + "}";

    private static HttpServer server;

    private OllamaStub() {
    }

    static synchronized void start(boolean fail) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/chat", exchange -> answer(exchange, fail,
            "{\"model\":\"stub-model\",\"message\":{\"role\":\"assistant\",\"content\":\"" + REPLY + "\"},"
                + "\"done\":true,\"prompt_eval_count\":12,\"eval_count\":5}"));
        server.createContext("/api/embeddings", exchange -> answer(exchange, fail, "{\"embedding\":[0.1,0.2,0.3]}"));
        server.start();
        System.setProperty(URL_PROPERTY, "http://127.0.0.1:" + server.getAddress().getPort());
    }

    static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        System.clearProperty(URL_PROPERTY);
    }

    private static void answer(HttpExchange exchange, boolean fail, String body) throws IOException {
        exchange.getRequestBody().readAllBytes();
        byte[] bytes = (fail ? "{\"error\":\"the stub was asked to fail\"}" : body).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(fail ? 500 : 200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
