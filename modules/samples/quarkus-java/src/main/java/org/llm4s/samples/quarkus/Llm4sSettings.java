package org.llm4s.samples.quarkus;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.util.Optional;

/**
 * The {@code llm4s.*} keys of {@code application.properties}, read by Quarkus at startup. Each one can also be set as
 * a system property ({@code -Dllm4s.model=...}) or an environment variable ({@code LLM4S_MODEL}, {@code LLM4S_API_KEY}).
 */
@ConfigMapping(prefix = "llm4s")
public interface Llm4sSettings {

    /** Which client to build: {@code ollama}, {@code openai}, {@code anthropic} or {@code openai-compatible}. */
    @WithDefault("ollama")
    String provider();

    /** The model name, as the provider spells it. */
    String model();

    /** {@code llm4s.base-url}: the provider's endpoint; each provider has a default except {@code openai-compatible}. */
    Optional<String> baseUrl();

    /** {@code llm4s.api-key}: required by {@code openai} and {@code anthropic}, unused by {@code ollama}. */
    Optional<String> apiKey();
}
