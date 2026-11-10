package org.llm4s.quarkus.test;

import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * Every problem of the configuration's structure is reported at once, each naming its property: a section with no
 * model, and a default that names no section.
 */
class MissingModelFailsBuildTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
        .overrideConfigKey("llm4s.providers.provider", "mian")
        .overrideConfigKey("llm4s.providers.main.provider", "ollama")
        .assertException(failure -> Failures.assertMessageNames(
            failure,
            "llm4s.providers.main.model is not set",
            "llm4s.providers.provider names section 'mian'"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
