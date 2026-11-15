package org.llm4s.quarkus.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.FrameworkConfig;
import org.llm4s.javaapi.JCompletionOptions;
import org.llm4s.javaapi.JLlmClient;

/**
 * The readiness check of the default client, registered when the application has {@code quarkus-smallrye-health} and
 * {@code llm4s.health.enabled} is true (the default).
 *
 * <p>By default ({@code llm4s.health.probe=false}) it makes no provider call: it is UP with {@code probe=disabled},
 * meaning the client was built from its configuration, not that the provider answers. With
 * {@code llm4s.health.probe=true} it sends a one-token completion, waits at most {@code llm4s.health.probe-timeout},
 * and reuses the outcome for {@code llm4s.health.probe-ttl}; a failed or timed-out probe is DOWN.
 */
@Readiness
@ApplicationScoped
public class Llm4sReadinessCheck implements HealthCheck {

    private static final Pattern TOKEN_LIKE =
        Pattern.compile("(?i)\\b(?:sk|pk|key|token)[-_][A-Za-z0-9_\\-]{8,}|Bearer\\s+\\S+");

    @Inject
    JLlmClient client;

    @Inject
    Llm4sHealthConfig config;

    private HealthCheckResponse cached;
    private long cachedAtNanos;

    @Override
    public synchronized HealthCheckResponse call() {
        if (!config.probe()) {
            return named().up().withData("probe", "disabled").build();
        }
        long now = System.nanoTime();
        if (cached != null && now - cachedAtNanos < config.probeTtl().toNanos()) {
            return cached;
        }
        HealthCheckResponse probed = probe();
        // An interrupt is a fact about this caller's thread, not about the provider: it is not reused.
        if (!Thread.currentThread().isInterrupted()) {
            cached = probed;
            cachedAtNanos = System.nanoTime();
        }
        return probed;
    }

    private HealthCheckResponse probe() {
        CompletableFuture<String> answer = client.completeAsync(
            ConversationBuilder.create().user("ping").build(), JCompletionOptions.builder().maxTokens(1).build());
        try {
            answer.get(config.probeTimeout().toNanos(), TimeUnit.NANOSECONDS);
            return named().up().withData("probe", "ok").build();
        } catch (TimeoutException e) {
            answer.cancel(true);
            return named().down().withData("probe", "timeout")
                .withData("error", "no answer within " + config.probeTimeout().toMillis() + " ms").build();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return named().down().withData("probe", "failed").withData("error", redact(cause.getMessage())).build();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            answer.cancel(true);
            return named().down().withData("probe", "cancelled").build();
        }
    }

    private HealthCheckResponseBuilder named() {
        Map<String, String> properties = Llm4sProperties.current();
        String section = FrameworkConfig.defaultSection(properties).orElse("unknown");
        String prefix = "llm4s.providers." + section;
        return HealthCheckResponse.named("llm4s")
            .withData("section", section)
            .withData("provider", properties.getOrDefault(prefix + ".provider", "unknown"))
            .withData("model", properties.getOrDefault(prefix + ".model", "unknown"));
    }

    private static String redact(String message) {
        return message == null ? "unknown error" : TOKEN_LIKE.matcher(message).replaceAll("[redacted]");
    }
}
