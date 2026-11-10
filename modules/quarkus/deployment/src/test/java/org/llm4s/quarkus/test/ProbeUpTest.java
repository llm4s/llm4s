package org.llm4s.quarkus.test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** With the probe on, readiness is UP when the default client's provider answers a one-token completion. */
class ProbeUpTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
        .withApplicationRoot(jar -> jar.addClass(OllamaStub.class))
        .setBeforeAllCustomizer(() -> OllamaStub.start(false))
        .setAfterAllCustomizer(OllamaStub::stop)
        .overrideConfigKey("quarkus.http.test-port", "0")
        .overrideConfigKey("llm4s.health.probe", "true")
        .overrideConfigKey("llm4s.providers.main.provider", "ollama")
        .overrideConfigKey("llm4s.providers.main.model", "stub-model")
        .overrideConfigKey("llm4s.providers.main.base-url", OllamaStub.URL);

    @Test
    void readinessIsUp() {
        given().when().get("/q/health/ready").then()
            .statusCode(200)
            .body("status", equalTo("UP"))
            .body("checks[0].data.probe", equalTo("ok"));
    }
}
