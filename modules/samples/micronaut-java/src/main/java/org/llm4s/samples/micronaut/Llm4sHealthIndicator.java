package org.llm4s.samples.micronaut;

import io.micronaut.health.HealthStatus;
import io.micronaut.management.health.indicator.AbstractHealthIndicator;
import jakarta.inject.Singleton;
import java.util.Map;
import org.llm4s.javaapi.JLlmClient;

/**
 * The {@code llm4s} entry of {@code GET /health}: UP once the client is built, with the provider and model it calls.
 * It does not call the model, so a health probe costs no tokens and does not fail when the provider is slow.
 */
@Singleton
public class Llm4sHealthIndicator extends AbstractHealthIndicator<Map<String, String>> {

    private final Llm4sSettings settings;

    // Taking the client makes the indicator, and so /health, fail when the client cannot be built.
    public Llm4sHealthIndicator(JLlmClient client, Llm4sSettings settings) {
        this.settings = settings;
    }

    @Override
    protected Map<String, String> getHealthInformation() {
        healthStatus = HealthStatus.UP;
        return Map.of("provider", settings.provider(), "model", settings.model());
    }

    @Override
    protected String getName() {
        return "llm4s";
    }
}
