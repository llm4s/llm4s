package org.llm4s.samples.micronaut;

import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.Factory;
import jakarta.inject.Singleton;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

/**
 * Makes the application's one {@link JLlmClient} a bean, built from {@link Llm4sSettings} with the Java facade's
 * client builders, and closes it when the application context stops.
 */
@Factory
public class LlmClientFactory {

    @Singleton
    @Bean(preDestroy = "close")
    JLlmClient llmClient(Llm4sSettings settings) {
        LlmResult<JLlmClient> client = build(settings);
        if (client.isFailure()) {
            // a missing base URL or an unknown provider: fail the first injection with llm4s's message
            throw new IllegalStateException(
                "Cannot build the llm4s client: " + client.getError().getMessage(), client.getError());
        }
        return client.get();
    }

    static LlmResult<JLlmClient> build(Llm4sSettings settings) {
        return switch (settings.provider()) {
            case "ollama" -> {
                var builder = Llm4s.ollama().model(settings.model());
                yield (settings.baseUrl() == null ? builder : builder.baseUrl(settings.baseUrl())).build();
            }
            case "openai-compatible" -> {
                var builder = Llm4s.openAiCompatible().model(settings.model()).baseUrl(settings.baseUrl());
                yield (settings.apiKey() == null ? builder : builder.apiKey(settings.apiKey())).build();
            }
            default -> throw new IllegalStateException(
                "Unknown llm4s.provider '" + settings.provider() + "': use ollama or openai-compatible");
        };
    }
}
