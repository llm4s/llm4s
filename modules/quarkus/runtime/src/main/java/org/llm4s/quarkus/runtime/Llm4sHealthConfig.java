package org.llm4s.quarkus.runtime;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;

/**
 * {@code llm4s.health.*}: the readiness check of the default client, in the Spring Boot starter's spelling.
 *
 * <p>The rest of {@code llm4s.*} (the provider sections, {@code llm4s.credentials}, {@code llm4s.embeddings},
 * {@code llm4s.http}) is llm4s's own configuration, whose keys each provider module declares, so no mapping can list
 * them all: the extension passes them to llm4s as written, and checks them when the application is built.
 */
@ConfigMapping(prefix = "llm4s.health")
public interface Llm4sHealthConfig {

    /**
     * Whether the default client has a readiness check, when the application has quarkus-smallrye-health. Read when the
     * application is built.
     */
    @WithDefault("true")
    boolean enabled();

    /**
     * Whether the readiness check sends the default client a one-token completion. When false it makes no provider call
     * and reports the client configured, not reachable.
     */
    @WithDefault("false")
    boolean probe();

    /** How long the probe waits for the provider before the check is DOWN. */
    @WithDefault("10s")
    Duration probeTimeout();

    /** How long a probe's outcome is reused, so that frequent checks do not each call the provider. */
    @WithDefault("60s")
    Duration probeTtl();
}
