package org.llm4s.samples;

import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;

/**
 * A first call to a model from Java with {@code llm4s-java-api}, in a Maven project.
 *
 * <p>The provider comes from {@code application.conf} (see {@code src/main/resources}); with
 * {@code OPENAI_API_KEY} set it needs nothing else. Every call returns an {@link LlmResult}, so a failure is a
 * value to check, not an exception to catch.
 */
public final class HelloLLM4S {

    private HelloLLM4S() {}

    public static void main(String[] args) {
        System.exit(run());
    }

    /** Asks one question and returns the process exit code: 0 when the call succeeded, 1 otherwise. */
    static int run() {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            // The error says what is missing, for example no default provider or no API key.
            System.err.println("Could not create a client: " + created.getError().getMessage());
            return 1;
        }

        // JLlmClient is AutoCloseable: it holds an HTTP client, so close it.
        try (JLlmClient client = created.get()) {
            LlmResult<String> answer = client.complete("In one sentence, what is Scala?");
            if (answer.isFailure()) {
                System.err.println("The call failed: " + answer.getError().getMessage());
                return 1;
            }
            System.out.println(answer.get());
            return 0;
        } catch (Exception e) {
            System.err.println("Closing the client failed: " + e.getMessage());
            return 1;
        }
    }
}
