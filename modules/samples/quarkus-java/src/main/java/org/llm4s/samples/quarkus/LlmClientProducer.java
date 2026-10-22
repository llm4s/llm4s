package org.llm4s.samples.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.javaapi.Llm4s;

/**
 * Makes the application's one {@link JLlmClient} a CDI bean, built from {@link Llm4sSettings} with the Java facade's
 * client builders, and closes it when the application stops.
 */
@ApplicationScoped
public class LlmClientProducer {

    @Produces
    @ApplicationScoped
    JLlmClient llmClient(Llm4sSettings settings) {
        LlmResult<JLlmClient> client = build(settings);
        if (client.isFailure()) {
            // a missing key or an unknown provider: fail the first injection with llm4s's message
            throw new IllegalStateException(
                "Cannot build the llm4s client: " + client.getError().getMessage(), client.getError());
        }
        return client.get();
    }

    void close(@Disposes JLlmClient client) {
        client.close();
    }

    static LlmResult<JLlmClient> build(Llm4sSettings settings) {
        String baseUrl = settings.baseUrl().orElse(null);
        String apiKey = settings.apiKey().orElse(null);
        return switch (settings.provider()) {
            case "ollama" -> {
                var builder = Llm4s.ollama().model(settings.model());
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "openai" -> {
                var builder = Llm4s.openAi().model(settings.model()).apiKey(apiKey);
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "anthropic" -> {
                var builder = Llm4s.anthropic().model(settings.model()).apiKey(apiKey);
                yield (baseUrl == null ? builder : builder.baseUrl(baseUrl)).build();
            }
            case "openai-compatible" -> {
                var builder = Llm4s.openAiCompatible().model(settings.model()).baseUrl(baseUrl);
                yield (apiKey == null ? builder : builder.apiKey(apiKey)).build();
            }
            default -> throw new IllegalStateException("Unknown llm4s.provider '" + settings.provider()
                + "': use ollama, openai, anthropic or openai-compatible");
        };
    }
}
