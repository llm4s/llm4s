package org.llm4s.quarkus.test;

import static org.junit.jupiter.api.Assertions.fail;

import io.quarkus.test.QuarkusUnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/** A section naming a provider that is not on the classpath fails the build, naming the property and what is. */
class UnknownProviderFailsBuildTest {

    @RegisterExtension
    static final QuarkusUnitTest app = new QuarkusUnitTest()
        .overrideConfigKey("llm4s.providers.main.provider", "olama")
        .overrideConfigKey("llm4s.providers.main.model", "llama3.2")
        .assertException(failure ->
            Failures.assertMessageNames(failure, "llm4s.providers.main.provider", "olama", "ollama"));

    @Test
    void theBuildFails() {
        fail("the build should have failed");
    }
}
