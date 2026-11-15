package org.llm4s.samples.helidon;

import io.helidon.config.Config;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

/**
 * The {@code llm4s} node of {@code application.yaml}, and the client it describes. Each key can also be set as a
 * system property ({@code -Dllm4s.model=...}) or an environment variable ({@code LLM4S_MODEL}; Helidon reads
 * {@code _DASH_} as {@code -}, so {@code base-url} is {@code LLM4S_BASE_DASH_URL}).
 *
 * @param provider which client to build: {@code ollama} or {@code openai-compatible}
 * @param model    the model name, as the provider spells it
 * @param baseUrl  {@code llm4s.base-url}: the provider's endpoint, or null; required by {@code openai-compatible}
 * @param apiKey   {@code llm4s.api-key}, or null; unused by {@code ollama}
 */
public record Llm4sSettings(String provider, String model, String baseUrl, String apiKey) {

    static Llm4sSettings from(Config llm4s) {
        return new Llm4sSettings(
            llm4s.get("provider").asString().orElse("ollama"),
            llm4s.get("model").asString().get(),
            llm4s.get("base-url").asString().orElse(null),
            llm4s.get("api-key").asString().orElse(null));
    }

    /** The client, built with the Java facade's builders: a failed result for a missing base URL. */
    LlmResult<JLlmClient> build() {
        return switch (provider) {
            case "ollama" -> {
                var builder = Llm4s.ollama().model(model);
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "openai-compatible" -> {
                var builder = Llm4s.openAiCompatible().model(model).baseUrl(baseUrl);
                yield (apiKey == null ? builder : builder.apiKey(apiKey)).build();
            }
            default -> throw new IllegalStateException(
                "Unknown llm4s.provider '" + provider + "': use ollama or openai-compatible");
        };
    }
}
