package org.llm4s.quarkus.test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.arc.Arc;
import io.quarkus.test.QuarkusUnitTest;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.literal.NamedLiteral;
import jakarta.enterprise.inject.spi.Bean;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import io.smallrye.common.annotation.Identifier;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.quarkus.runtime.Llm4sHealthConfig;

/**
 * The client beans come from {@code application.properties}: one {@code @Named} client per section of
 * {@code llm4s.providers}, the default section's also {@code @Default}, and the embedding client of
 * {@code llm4s.embeddings.model}. The readiness check is UP without calling the provider when the probe is off.
 */
class ClientBeansTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
        .withApplicationRoot(jar -> jar.addClass(OllamaStub.class))
        .setBeforeAllCustomizer(() -> OllamaStub.start(false))
        .setAfterAllCustomizer(OllamaStub::stop)
        .overrideConfigKey("quarkus.http.test-port", "0")
        .overrideConfigKey("llm4s.providers.provider", "main")
        .overrideConfigKey("llm4s.providers.main.provider", "ollama")
        .overrideConfigKey("llm4s.providers.main.model", "stub-model")
        .overrideConfigKey("llm4s.providers.main.base-url", OllamaStub.URL)
        .overrideConfigKey("llm4s.providers.other.provider", "ollama")
        .overrideConfigKey("llm4s.providers.other.model", "other-model")
        .overrideConfigKey("llm4s.providers.other.base-url", OllamaStub.URL)
        // a key the provider declares, not one every section has: passed to llm4s as written
        .overrideConfigKey("llm4s.providers.other.headers.X-Team-Id", "team-7")
        .overrideConfigKey("llm4s.embeddings.model", "ollama/stub-embed")
        .overrideConfigKey("llm4s.embeddings.ollama.base-url", OllamaStub.URL);

    @Inject
    JLlmClient client;

    @Inject
    @Named("main")
    JLlmClient main;

    @Inject
    @Named("other")
    JLlmClient other;

    @Inject
    JEmbeddingClient embeddings;

    @Inject
    @Identifier("other")
    JLlmClient otherById;

    @Inject
    Llm4sHealthConfig health;

    @Test
    void theHealthSettingsHaveTheirDefaults() {
        assertTrue(health.enabled());
        assertFalse(health.probe());
        assertEquals(Duration.ofSeconds(10), health.probeTimeout());
    }

    @Test
    void theDefaultClientIsTheDefaultSectionsAndAnswers() {
        assertEquals(OllamaStub.REPLY, client.complete("Hi").get());
        assertEquals(OllamaStub.REPLY, main.complete("Hi").get());
        var beans = Arc.container().beanManager();
        Bean<?> defaultBean = beans.resolve(beans.getBeans(JLlmClient.class));
        assertTrue(defaultBean.getQualifiers().contains(NamedLiteral.of("main")));
    }

    @Test
    void everySectionIsANamedClient() {
        assertEquals(OllamaStub.REPLY, other.complete("Hi").get());
        assertSame(other, otherById);
        assertEquals(2, Arc.container().beanManager().getBeans(JLlmClient.class, Any.Literal.INSTANCE).size());
    }

    @Test
    void theEmbeddingModelIsAnEmbeddingClient() {
        assertEquals("stub-embed", embeddings.model());
        assertEquals(3, embeddings.embed(List.of("a")).get().vectors().get(0).length);
    }

    @Test
    void readinessIsUpWithoutAProbe() {
        given().when().get("/q/health/ready").then()
            .statusCode(200)
            .body("status", equalTo("UP"))
            .body("checks[0].name", equalTo("llm4s"))
            .body("checks[0].data.probe", equalTo("disabled"))
            .body("checks[0].data.section", equalTo("main"))
            .body("checks[0].data.provider", equalTo("ollama"))
            .body("checks[0].data.model", equalTo("stub-model"));
    }
}
