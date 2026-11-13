package org.llm4s.samples.helidon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.http.HeaderNames;
import io.helidon.http.HttpMediaTypes;
import io.helidon.webclient.http1.Http1Client;
import io.helidon.webclient.http1.Http1ClientResponse;
import io.helidon.webserver.WebServerConfig;
import io.helidon.webserver.testing.junit5.ServerTest;
import io.helidon.webserver.testing.junit5.SetUpServer;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.llm4s.javaapi.FakeLlmClient;
import org.llm4s.javaapi.LlmErrorKind;

/** The application's routes, served on a free port, with a FakeLlmClient: no model, no key, no network. */
@ServerTest
class ChatServiceTest {

    static final FakeLlmClient FAKE = FakeLlmClient.builder()
        .reply("Helidon is a set of Java libraries.")
        .failWith(LlmErrorKind.TIMEOUT, "too slow")
        .reply("Streamed answer")
        .build();

    private final Http1Client http;

    ChatServiceTest(Http1Client http) {
        this.http = http;
    }

    @SetUpServer
    static void server(WebServerConfig.Builder server) {
        Main.setUp(server, FAKE.client(), new Llm4sSettings("ollama", "stub-model", null, null));
    }

    private Http1ClientResponse post(String path, String body) {
        return http.post(path).contentType(HttpMediaTypes.PLAINTEXT_UTF_8).submit(body);
    }

    @Test
    void answersStreamsAndMapsAFailureToItsStatus() {
        try (Http1ClientResponse res = post("/chat", "What is Helidon?")) {
            assertEquals(200, res.status().code());
            assertEquals("Helidon is a set of Java libraries.", res.as(String.class));
        }
        assertEquals(List.of("What is Helidon?"), FAKE.prompts());

        try (Http1ClientResponse res = post("/chat", "Again")) {
            assertEquals(504, res.status().code());
        }

        try (Http1ClientResponse res = post("/chat/stream", "Stream it")) {
            assertEquals(200, res.status().code());
            assertTrue(res.headers().get(HeaderNames.CONTENT_TYPE).get().startsWith("text/event-stream"));
            String events = res.as(String.class);
            assertTrue(events.contains("data:Streamed answer") || events.contains("data: Streamed answer"), events);
        }
    }

    @Test
    void rejectsAnEmptyQuestion() {
        try (Http1ClientResponse res = post("/chat", " ")) {
            assertEquals(400, res.status().code());
        }
    }

    @Test
    void reportsTheClientInTheHealthCheck() {
        try (Http1ClientResponse res = http.get("/observe/health").request()) {
            String health = res.as(String.class);
            assertEquals(200, res.status().code(), health);
            assertTrue(health.contains("\"llm4s\""), health);
            assertTrue(health.contains("stub-model"), health);
        }
    }
}
