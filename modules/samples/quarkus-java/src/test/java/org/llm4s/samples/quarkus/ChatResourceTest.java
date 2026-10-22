package org.llm4s.samples.quarkus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.llm4s.javaapi.JLlmClient;

@QuarkusTest
@QuarkusTestResource(OllamaStub.class)
class ChatResourceTest {

    @Inject
    JLlmClient client;

    @Test
    void answersWithTheModelsReply() {
        given().contentType("text/plain").body("What is Quarkus?")
            .when().post("/chat")
            .then().statusCode(200).body(equalTo(OllamaStub.REPLY));

        String sent = OllamaStub.REQUESTS.get(OllamaStub.REQUESTS.size() - 1);
        assertTrue(sent.contains("\"model\":\"stub-model\""), sent);
        assertTrue(sent.contains("What is Quarkus?"), sent);
        assertTrue(sent.contains("You are a concise assistant."), sent);
    }

    @Test
    void reportsAFailedCallAsABadGateway() {
        given().contentType("text/plain").body("please fail")
            .when().post("/chat")
            .then().statusCode(502).body(containsString("the stub was asked to fail"));
    }

    @Test
    void rejectsAnEmptyQuestion() {
        given().contentType("text/plain").body(" ")
            .when().post("/chat")
            .then().statusCode(400);
    }

    @Test
    void injectsTheProducedClient() {
        assertTrue(client.complete("Hi").isSuccess());
    }
}
