package org.llm4s.samples.micronaut;

import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.bind.annotation.Bindable;

/**
 * The {@code llm4s.*} keys of {@code application.yml}, bound by Micronaut at startup. Each one can also be set as a
 * system property ({@code -Dllm4s.model=...}) or an environment variable ({@code LLM4S_MODEL}, {@code LLM4S_API_KEY}).
 *
 * @param provider which client to build: {@code ollama} or {@code openai-compatible}
 * @param model    the model name, as the provider spells it
 * @param baseUrl  {@code llm4s.base-url}: the provider's endpoint; required by {@code openai-compatible}
 * @param apiKey   {@code llm4s.api-key}: unused by {@code ollama}
 */
@ConfigurationProperties("llm4s")
public record Llm4sSettings(
    @Bindable(defaultValue = "ollama") String provider,
    String model,
    @Nullable String baseUrl,
    @Nullable String apiKey) {
}
