package org.llm4s.samples.micronaut;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.micronaut.context.ApplicationContext;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmErrorKind;
import org.llm4s.javaapi.LlmResult;

/** The factory's choice of builder, and the bean it makes from application.yml-style properties. */
class LlmClientFactoryTest {

    @Test
    void buildsEachProviderFromItsSettings() {
        for (Llm4sSettings s : new Llm4sSettings[] {
            new Llm4sSettings("ollama", "some-model", null, null),
            new Llm4sSettings("ollama", "some-model", "http://127.0.0.1:1", null),
            new Llm4sSettings("openai-compatible", "some-model", "http://127.0.0.1:1/v1", "sk-test"),
        }) {
            LlmResult<JLlmClient> client = LlmClientFactory.build(s);
            assertTrue(client.isSuccess(), () -> s.provider() + ": " + client.getError().getMessage());
            client.get().close();
        }
    }

    @Test
    void reportsAMissingBaseUrlAsAValidationFailure() {
        LlmResult<JLlmClient> client = LlmClientFactory.build(new Llm4sSettings("openai-compatible", "m", null, null));
        assertTrue(client.isFailure());
        assertEquals(LlmErrorKind.VALIDATION, client.getError().getKind());
    }

    @Test
    void refusesAnUnknownProvider() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
            () -> LlmClientFactory.build(new Llm4sSettings("nope", "m", null, null)));
        assertTrue(e.getMessage().contains("nope"));
    }

    @Test
    void bindsTheSettingsAndBuildsTheBean() {
        try (ApplicationContext context = ApplicationContext.run(Map.of(
                "llm4s.model", "stub-model", "llm4s.base-url", "http://127.0.0.1:1"))) {
            Llm4sSettings settings = context.getBean(Llm4sSettings.class);
            assertEquals("ollama", settings.provider());
            assertEquals("http://127.0.0.1:1", settings.baseUrl());
            assertTrue(context.getBean(JLlmClient.class) != null);
        }
    }
}
