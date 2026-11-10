package org.llm4s.quarkus.test;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.llm4s.javaapi.FakeLlmClient;
import org.llm4s.javaapi.JLlmClient;

/** An application's own JLlmClient producer replaces the extension's client, which is then never built. */
class ReplacedClientTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
        .withApplicationRoot(jar -> jar.addClass(FakeClientProducer.class))
        // Nothing listens on this port: building the extension's client would succeed, but a call would fail.
        .overrideConfigKey("llm4s.providers.main.provider", "ollama")
        .overrideConfigKey("llm4s.providers.main.model", "unused")
        .overrideConfigKey("llm4s.providers.main.base-url", "http://127.0.0.1:9");

    @Inject
    JLlmClient client;

    @Test
    void theApplicationsClientIsInjected() {
        assertEquals("from the fake", client.complete("Hi").get());
    }

    @ApplicationScoped
    static class FakeClientProducer {

        @Produces
        @ApplicationScoped
        JLlmClient client() {
            return FakeLlmClient.replying("from the fake").client();
        }
    }
}
