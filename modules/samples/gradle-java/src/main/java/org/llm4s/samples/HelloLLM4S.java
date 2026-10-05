package org.llm4s.samples;

import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.llmconnect.model.Conversation;

/**
 * Calling llm4s from Java with {@code llm4s-java-api}.
 *
 * <p>Every call returns an {@link LlmResult}, so Java code never touches Scala's {@code Either}, {@code Option}
 * or {@code Nil}. A failure is a value to check, not an exception to catch: the sample shows both ways to read
 * one ({@code ifSuccess} / {@code ifFailure}, and {@code isFailure} then {@code getError}).
 *
 * <p>The provider comes from {@code application.conf} (see {@code src/main/resources}); with
 * {@code OPENAI_API_KEY} set it needs nothing else.
 */
public final class HelloLLM4S {

    private HelloLLM4S() {}

    public static void main(String[] args) {
        System.exit(run());
    }

    /** Runs the demos and returns the process exit code: 0 when every call succeeded, 1 otherwise. */
    static int run() {
        LlmResult<JLlmClient> created = Llm4s.createDefaultClient();
        if (created.isFailure()) {
            // The error says what is missing, for example no default provider or no API key.
            System.err.println("Could not create a client: " + created.getError().getMessage());
            return 1;
        }

        // JLlmClient is AutoCloseable: it holds an HTTP client, so close it.
        try (JLlmClient client = created.get()) {
            boolean first = oneQuestion(client);
            boolean second = aConversation(client);
            return first && second ? 0 : 1;
        } catch (Exception e) {
            System.err.println("Closing the client failed: " + e.getMessage());
            return 1;
        }
    }

    /** One question, as a plain string. */
    private static boolean oneQuestion(JLlmClient client) {
        System.out.println("== One question");
        LlmResult<String> answer = client.complete("Explain the difference between a class and an object in 2 sentences.");

        // ifSuccess and ifFailure each take a lambda and return the result, so they chain.
        answer
            .ifSuccess(text -> System.out.println(text))
            .ifFailure(error -> System.err.println("The call failed: " + error.getMessage()));
        return answer.isSuccess();
    }

    /** A conversation with a system message, built with {@link ConversationBuilder}. */
    private static boolean aConversation(JLlmClient client) {
        System.out.println("== A conversation");
        Conversation conversation = ConversationBuilder.create()
            .system("You answer in one short sentence.")
            .user("What is a monad?")
            .build();

        LlmResult<String> answer = client.complete(conversation);
        if (answer.isFailure()) {
            System.err.println("The call failed: " + answer.getError().getMessage());
            return false;
        }
        System.out.println(answer.get());
        return true;
    }
}
