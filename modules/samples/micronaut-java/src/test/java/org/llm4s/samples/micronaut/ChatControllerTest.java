package org.llm4s.samples.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.runtime.server.EmbeddedServer;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.llm4s.javaapi.FakeLlmClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmErrorKind;

/** The application with its client replaced by a FakeLlmClient: no model, no key, no network. */
@MicronautTest
class ChatControllerTest {

    // JLlmClient is final, so @MockBean (which proxies) cannot replace it: a factory with @Replaces does.
    @Factory
    static class FakeClients {

        @Singleton
        FakeLlmClient fake() {
            return FakeLlmClient.builder()
                .reply("Micronaut is a JVM framework.")
                .failWith(LlmErrorKind.RATE_LIMIT, "slow down")
                .reply("Streamed answer")
                .build();
        }

        @Singleton
        @Replaces(JLlmClient.class)
        JLlmClient client(FakeLlmClient fake) {
            return fake.client();
        }
    }

    @Inject
    FakeLlmClient fake;

    @Inject
    EmbeddedServer server;

    @Inject
    @Client("/")
    HttpClient http;

    private MutableHttpRequest<String> post(String path, String body) {
        return HttpRequest.POST(path, body).contentType(MediaType.TEXT_PLAIN_TYPE);
    }

    @Test
    void answersAndMapsAFailureToItsStatus() throws Exception {
        assertEquals("Micronaut is a JVM framework.",
            http.toBlocking().retrieve(post("/chat", "What is Micronaut?")));
        assertEquals(List.of("What is Micronaut?"), fake.prompts());

        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> http.toBlocking().retrieve(post("/chat", "Again")));
        assertEquals(429, e.getStatus().getCode());

        // the raw response, as a browser's EventSource reads it
        HttpResponse<String> events = java.net.http.HttpClient.newHttpClient().send(
            java.net.http.HttpRequest.newBuilder(server.getURI().resolve("/chat/stream"))
                .header("Content-Type", "text/plain")
                .POST(BodyPublishers.ofString("Stream it"))
                .build(),
            BodyHandlers.ofString());
        assertEquals(200, events.statusCode());
        assertTrue(events.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream"));
        assertTrue(events.body().contains("data: Streamed answer"), events.body());
    }

    @Test
    void rejectsAnEmptyQuestion() {
        HttpClientResponseException e = assertThrows(HttpClientResponseException.class,
            () -> http.toBlocking().retrieve(post("/chat", " ")));
        assertEquals(400, e.getStatus().getCode());
    }

    @Test
    void reportsTheClientInTheHealthCheck() {
        String health = http.toBlocking().retrieve(HttpRequest.GET("/health"));
        assertTrue(health.contains("\"llm4s\""), health);
        assertTrue(health.contains("\"model\":\"llama3.2\""), health);
    }
}
