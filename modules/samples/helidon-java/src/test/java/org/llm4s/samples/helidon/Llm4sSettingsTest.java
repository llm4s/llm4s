package org.llm4s.samples.helidon;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.helidon.config.Config;
import io.helidon.config.ConfigSources;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmErrorKind;
import org.llm4s.javaapi.LlmResult;

/** Reading the llm4s node and choosing the builder, without starting a server. */
class Llm4sSettingsTest {

    private static Llm4sSettings read(Map<String, String> keys) {
        return Llm4sSettings.from(Config.just(ConfigSources.create(keys)).get("llm4s"));
    }

    @Test
    void readsTheKeysWithOllamaAsTheDefaultProvider() {
        Llm4sSettings settings = read(Map.of("llm4s.model", "m", "llm4s.base-url", "http://127.0.0.1:1"));
        assertEquals(new Llm4sSettings("ollama", "m", "http://127.0.0.1:1", null), settings);
        assertNull(settings.apiKey());
    }

    @Test
    void buildsEachProvider() {
        for (Llm4sSettings s : new Llm4sSettings[] {
            new Llm4sSettings("ollama", "m", null, null),
            new Llm4sSettings("openai-compatible", "m", "http://127.0.0.1:1/v1", "sk-test"),
        }) {
            LlmResult<JLlmClient> client = s.build();
            assertTrue(client.isSuccess(), () -> s.provider() + ": " + client.getError().getMessage());
            client.get().close();
        }
    }

    @Test
    void reportsAMissingBaseUrlAndRefusesAnUnknownProvider() {
        LlmResult<JLlmClient> client = new Llm4sSettings("openai-compatible", "m", null, null).build();
        assertEquals(LlmErrorKind.VALIDATION, client.getError().getKind());
        assertThrows(IllegalStateException.class, () -> new Llm4sSettings("nope", "m", null, null).build());
    }
}
