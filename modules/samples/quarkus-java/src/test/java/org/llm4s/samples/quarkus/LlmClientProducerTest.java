package org.llm4s.samples.quarkus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmErrorKind;
import org.llm4s.javaapi.LlmResult;

/** The producer's choice of builder, without starting Quarkus. */
class LlmClientProducerTest {

    private static Llm4sSettings settings(String provider, String baseUrl, String apiKey) {
        return new Llm4sSettings() {
            @Override public String provider() { return provider; }
            @Override public String model() { return "some-model"; }
            @Override public Optional<String> baseUrl() { return Optional.ofNullable(baseUrl); }
            @Override public Optional<String> apiKey() { return Optional.ofNullable(apiKey); }
        };
    }

    @Test
    void buildsEachProviderFromItsSettings() {
        for (Llm4sSettings s : new Llm4sSettings[] {
            settings("ollama", null, null),
            settings("ollama", "http://127.0.0.1:1", null),
            settings("openai", null, "sk-test"),
            settings("anthropic", null, "sk-ant-test"),
            settings("openai-compatible", "http://127.0.0.1:1/v1", null),
        }) {
            LlmResult<JLlmClient> client = LlmClientProducer.build(s);
            assertTrue(client.isSuccess(), () -> s.provider() + ": " + client.getError().getMessage());
            client.get().close();
        }
    }

    @Test
    void reportsAMissingKeyAsAValidationFailure() {
        LlmResult<JLlmClient> client = LlmClientProducer.build(settings("openai", null, null));
        assertTrue(client.isFailure());
        assertEquals(LlmErrorKind.VALIDATION, client.getError().getKind());
        assertThrows(IllegalStateException.class,
            () -> new LlmClientProducer().llmClient(settings("openai", null, null)));
    }

    @Test
    void refusesAnUnknownProvider() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> LlmClientProducer.build(settings("nope", null, null)));
        assertTrue(e.getMessage().contains("nope"));
    }
}
