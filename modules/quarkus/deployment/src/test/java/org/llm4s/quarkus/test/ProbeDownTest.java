package org.llm4s.quarkus.test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With the probe on, readiness is DOWN, with the provider's error, when the provider fails the completion. */
class ProbeDownTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
        .withApplicationRoot(jar -> jar.addClass(OllamaStub.class))
        .setBeforeAllCustomizer(() -> OllamaStub.start(true))
        .setAfterAllCustomizer(OllamaStub::stop)
        .overrideConfigKey("quarkus.http.test-port", "0")
        .overrideConfigKey("llm4s.health.probe", "true")
        .overrideConfigKey("llm4s.providers.main.provider", "ollama")
        .overrideConfigKey("llm4s.providers.main.model", "stub-model")
        .overrideConfigKey("llm4s.providers.main.base-url", OllamaStub.URL);

    @Test
    void readinessIsDown() {
        given().when().get("/q/health/ready").then()
            .statusCode(503)
            .body("status", equalTo("DOWN"))
            .body("checks[0].data.probe", equalTo("failed"))
            .body("checks[0].data.error", notNullValue());
    }
}
