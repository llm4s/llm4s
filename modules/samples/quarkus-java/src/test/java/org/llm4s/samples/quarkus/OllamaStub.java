package org.llm4s.samples.quarkus;

import com.sun.net.httpserver.HttpServer;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A loopback server that answers Ollama's {@code POST /api/chat} with a canned reply, started before Quarkus and
 * handed to it as {@code llm4s.base-url}, so the test runs the real client over HTTP with no model and no key. A
 * request whose body contains {@code "please fail"} gets an HTTP 500 instead.
 */
public class OllamaStub implements QuarkusTestResourceLifecycleManager {

    static final String REPLY = "Hello from the stub";

    /** The bodies of the requests the stub received, in order. */
    static final List<String> REQUESTS = new CopyOnWriteArrayList<>();

    private HttpServer server;

    @Override
    public Map<String, String> start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/api/chat", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            REQUESTS.add(body);
            boolean fail = body.contains("please fail");
            byte[] answer = (fail
                ? "{\"error\":\"the stub was asked to fail\"}"
                : "{\"model\":\"stub-model\",\"message\":{\"role\":\"assistant\",\"content\":\"" + REPLY + "\"},"
                    + "\"done\":true,\"prompt_eval_count\":12,\"eval_count\":5}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(fail ? 500 : 200, answer.length);
            exchange.getResponseBody().write(answer);
            exchange.close();
        });
        server.start();
        return Map.of(
            "llm4s.provider", "ollama",
            "llm4s.model", "stub-model",
            "llm4s.base-url", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Override
    public void stop() {
        if (server != null) {
            server.stop(0);
        }
    }
}
