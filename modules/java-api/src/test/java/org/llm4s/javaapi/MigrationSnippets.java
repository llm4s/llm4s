package org.llm4s.javaapi;

import java.util.List;
import java.util.concurrent.Flow;

import org.llm4s.llmconnect.model.Conversation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The llm4s side of {@code docs/guide/java-migration.md}, compiled with javac against this module's public API and
 * run by {@code JavaMigrationGuideSpec} against a {@link FakeLlmClient} or a client the spec scripts. Each method holds
 * one block of the guide, word for word (the spec checks that); the lines that only exist for the spec come after the
 * block. Change the guide and this file together.
 */
final class MigrationSnippets {

    private MigrationSnippets() {}

    /** "A first call": a client in code, from plain Java values. */
    static List<LlmResult<JLlmClient>> firstClient(String apiKey) {
        LlmResult<JLlmClient> openai = Llm4s.openAi()
            .apiKey(apiKey)
            .model("gpt-4o-mini")
            .build();
        LlmResult<JLlmClient> fromConfig = Llm4s.createDefaultClient();   // application.conf

        return List.of(openai, fromConfig);
    }

    /** "A first call": one question, one answer. */
    static String firstCall(JLlmClient client) {
        String answer = client.complete("Say hello in French").get();   // LlmException if the call failed

        return answer;
    }

    /** "Structured output into a record". */
    static String structured(JLlmClient client) {
        record Invoice(String customer, double total, List<String> items) {}

        Invoice invoice = client.complete("Extract the invoice: ACME paid 9.50 for two bolts and a nut", Invoice.class).get();

        return invoice.customer() + " " + invoice.total() + " " + invoice.items();
    }

    /** "A tool": the annotated class. */
    public static final class OrderTools {
        @Tool(description = "The shipping status of an order")
        public String orderStatus(@Param(name = "orderId", description = "The order number") String orderId) {
            return "Order " + orderId + " shipped on Monday";
        }
    }

    /** "A tool": the agent that offers it to the model and runs it. */
    static String tool(JLlmClient client) {
        List<JTool> tools = JTool.fromAnnotated(new OrderTools()).get();
        JAgent agent = Llm4s.createAgentWithTools(client, tools).get();

        String answer = agent.run("Where is order 42?").get().answer().orElse("");

        return answer;
    }

    /** "A conversation with memory": the history as a value you pass on each call. */
    static String history(JLlmClient client, String firstAnswer) {
        Conversation conversation = ConversationBuilder.create()
            .system("You are a helpful assistant.")
            .user("My name is Ada.")
            .assistant(firstAnswer)
            .user("What is my name?")
            .build();
        String answer = client.complete(conversation).get();

        return answer;
    }

    /** "A conversation with memory": an agent thread that keeps the history for you. */
    static JAgentResult thread(JLlmClient client) {
        JAgent agent = Llm4s.createAgent(client);
        JAgentResult first = agent.run("My name is Ada.").get();
        JAgentResult second = agent.continueConversation(first, "What is my name?").get();   // same thread

        return second;
    }

    /** "Streaming": a listener, and a JDK Flow.Publisher. */
    static JCompletion streaming(JLlmClient client, Flow.Subscriber<String> subscriber) {
        JCompletion reply;
        try (CompletionStream stream = client.stream("Tell me a short story", piece -> System.out.print(piece)).get()) {
            reply = stream.await().get();
        }

        Flow.Publisher<String> pieces = client.streamAsPublisher("Tell me a short story");
        pieces.subscribe(subscriber);

        return reply;
    }

    /** "Error handling". */
    static String errors(JLlmClient client) {
        LlmResult<String> result = client.complete("What is 2+2?");
        if (result.isFailure()) {
            LlmException error = result.getError();
            return switch (error.getKind()) {
                case AUTHENTICATION, CONFIGURATION -> "Fix the setup: " + error.getMessage();
                case RATE_LIMIT, TIMEOUT, NETWORK -> "Try again later";
                default -> error.isRecoverable() ? "Retry: " + error.getMessage() : "Failed: " + error.getMessage();
            };
        }
        return result.get();
    }

    /** "Error handling": the same, for code that prefers exceptions. */
    static String throwing(JLlmClient client) {
        try {
            return client.complete("What is 2+2?").get();
        } catch (LlmException e) {
            return "Failed (" + e.getKind() + "): " + e.getMessage();
        }
    }

    /** Code under test in the "A test with FakeLlmClient" block: your own class that takes a JLlmClient. */
    static final class Greeter {
        private final JLlmClient client;

        Greeter(JLlmClient client) {
            this.client = client;
        }

        String greet() {
            return client.complete("Say hello in French").get();
        }
    }

    /** "A test with FakeLlmClient". */
    static final class GreeterTest {

        @Test
        void greetsInFrench() {
            FakeLlmClient fake = FakeLlmClient.replying("Bonjour");

            String greeting = new Greeter(fake.client()).greet();

            assertEquals("Bonjour", greeting);
            assertEquals(List.of("Say hello in French"), fake.prompts());
        }
    }

    /** "Configuration": the in-code builders for the providers this module brings. */
    static List<LlmResult<JLlmClient>> providers(String openAiKey, String azureKey, String anthropicKey) {
        LlmResult<JLlmClient> openai = Llm4s.openAi()
            .apiKey(openAiKey)
            .model("gpt-4o-mini")
            .build();
        LlmResult<JLlmClient> azure = Llm4s.azureOpenAi()
            .endpoint("https://my-resource.openai.azure.com")
            .apiKey(azureKey)
            .model("my-gpt-4o-deployment")   // the deployment name
            .build();
        LlmResult<JLlmClient> anthropic = Llm4s.anthropic()
            .apiKey(anthropicKey)
            .model("claude-sonnet-4-20250514")
            .build();
        LlmResult<JLlmClient> ollama = Llm4s.ollama()
            .baseUrl("http://localhost:11434")
            .model("llama3.2")
            .build();

        return List.of(openai, azure, anthropic, ollama);
    }
}
