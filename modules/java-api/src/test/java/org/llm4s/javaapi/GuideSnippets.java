package org.llm4s.javaapi;

import java.util.Arrays;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JCompletion;
import org.llm4s.javaapi.JCompletionOptions;
import org.llm4s.javaapi.JEmbeddingClient;
import org.llm4s.javaapi.JEmbeddingPurpose;
import org.llm4s.javaapi.JEmbeddings;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.JReasoningEffort;
import org.llm4s.javaapi.JToolCall;
import org.llm4s.javaapi.Llm4s;
import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;
import org.llm4s.llmconnect.config.AnthropicConfig;
import org.llm4s.llmconnect.config.OllamaConfig;
import org.llm4s.llmconnect.config.OpenAIConfig;
import org.llm4s.llmconnect.model.Conversation;

/**
 * The fragments of {@code docs/guide/java.md}, compiled, and run by {@code JavaGuideSpec} against a client the
 * spec supplies. Each method holds one block of the guide, word for word (the spec checks that); the lines that
 * only exist for the spec come after the block. The imports from {@code java.util.List} down are the guide's
 * "imports" block.
 */
final class GuideSnippets {

    private GuideSnippets() {}

    /** The "Configure in code" block: a client from a provider config, with no application.conf. */
    static List<LlmResult<JLlmClient>> inCode(String apiKey) {
        LlmResult<JLlmClient> openai = Llm4s.createClient(OpenAIConfig.apply(apiKey, "gpt-4o-mini"));
        LlmResult<JLlmClient> anthropic = Llm4s.createClient(AnthropicConfig.apply(apiKey, "claude-sonnet-4-20250514"));
        LlmResult<JLlmClient> local = Llm4s.createClient(OllamaConfig.apply("llama3.2", "http://localhost:11434"));

        return Arrays.asList(openai, anthropic, local);
    }

    /** The "A conversation" block. */
    static void conversation(JLlmClient client) {
        Conversation conversation = ConversationBuilder.create()
            .system("You answer in one short sentence.")
            .user("What is a monad?")
            .build();

        LlmResult<String> answer = client.complete(conversation);
        System.out.println(answer.get());
    }

    /** The "Completion options" block: temperature, a token limit and reasoning, set with the builder. */
    static void completionOptions(JLlmClient client, Conversation conversation) {
        JCompletionOptions options = JCompletionOptions.builder()
            .temperature(0.2)
            .maxTokens(512)
            .reasoning(JReasoningEffort.MEDIUM)
            .build();

        LlmResult<String> answer = client.complete(conversation, options);
        System.out.println(answer.get());
    }

    /** The "Reading a result" block: the ways to take a value out of an {@link LlmResult}. */
    static List<String> readingAResult(JLlmClient client) {
        LlmResult<String> result = client.complete("What is 2+2?");

        String text = result.get();                       // the value, or throws LlmException
        String orNull = result.getOrNull();               // the value, or null when the call failed
        Optional<String> optional = result.toOptional();  // Optional.empty() when the call failed
        LlmResult<Integer> length = result.map(String::length);
        CompletableFuture<String> future = result.toCompletableFuture();

        return Arrays.asList(text, orNull, optional.orElse("none"), String.valueOf(length.get()), future.join());
    }

    /** The "The whole reply" block: the model, token usage, cost and tool calls of one reply. */
    static JCompletion wholeReply(JLlmClient client, Conversation conversation) {
        JCompletion reply = client.completion(conversation).get();

        System.out.println(reply.model() + ": " + reply.content());
        reply.usage().ifPresent(usage ->
            System.out.println(usage.promptTokens() + " tokens in, " + usage.completionTokens() + " out"));
        reply.estimatedCost().ifPresent(cost -> System.out.println("about $" + cost.toPlainString()));
        for (JToolCall call : reply.toolCalls()) {
            System.out.println("wants " + call.name() + " " + call.argumentsJson());
        }

        return reply;
    }

    /** The "Handling a failure" block. */
    static void handlingAFailure(JLlmClient client) {
        try {
            String text = client.complete("What is 2+2?").get();
            System.out.println(text);
        } catch (LlmException e) {
            System.err.println(e.getMessage());
            switch (e.getKind()) {
                case AUTHENTICATION, CONFIGURATION -> System.err.println("check the API key and the provider section");
                case RATE_LIMIT -> System.err.println("rate limited; wait "
                    + e.getRetryAfter().map(d -> d.toSeconds() + " s").orElse("a while"));
                case SERVICE -> System.err.println("the provider answered HTTP "
                    + (e.getStatusCode().isPresent() ? e.getStatusCode().getAsInt() : "?"));
                default -> { }
            }
            if (e.isRecoverable()) {
                System.err.println("a retry may succeed");
            }
        }
    }

    /** The "An agent turn" block: a turn's result read with Java types only. */
    static JAgentResult agentTurn(JLlmClient client) {
        JAgent agent = Llm4s.createAgent(client);
        JAgentResult result = agent.run("What is 2+2?").get();

        switch (result.status().kind()) {
            case COMPLETED -> System.out.println(result.answer().orElseThrow());
            case BLOCKED -> System.out.println("Blocked by " + result.status().guardrail().orElseThrow());
            case STEP_LIMIT_REACHED -> System.out.println("Hit the step limit");
            case SUSPENDED -> System.out.println("Waiting for " + result.status().pending().size() + " answers");
        }
        for (JMessage message : result.messages()) {
            System.out.println(message.role() + ": " + message.content());
        }
        JUsageSummary usage = result.usage();
        System.out.println(usage.inputTokens() + " tokens in, " + usage.outputTokens() + " out");

        JAgentResult next = agent.continueConversation(result, "And 3+3?").get();

        return next;
    }

    /** The first "Embeddings" block: an embedding client for the model application.conf configures. */
    static JEmbeddingClient defaultEmbedder() {
        LlmResult<JEmbeddingClient> created = Llm4s.createDefaultEmbeddingClient();
        created.ifFailure(error -> System.err.println("Could not create an embedding client: " + error.getMessage()));
        JEmbeddingClient embedder = created.getOrNull();   // null when it failed

        return embedder;
    }

    /** The "Embeddings" block that embeds two sentences and prints how similar they are. */
    static double twoSentences(JEmbeddingClient embedder) {
        JEmbeddings embeddings = embedder.embed(List.of(
            "The cat sat on the mat.",
            "A kitten was sitting on the rug.")).get();

        List<float[]> vectors = embeddings.vectors();
        double similarity = JEmbeddings.cosineSimilarity(vectors.get(0), vectors.get(1));
        System.out.println(embeddings.model() + ", " + embeddings.dimensions() + " dimensions: similarity " + similarity);

        return similarity;
    }

    /** The "Embeddings" block that embeds documents and a query, each for its purpose, and finds the closest. */
    static String closest(JEmbeddingClient embedder, List<String> texts) {
        List<float[]> documents = embedder.embed(texts, JEmbeddingPurpose.DOCUMENT).get().vectors();
        float[] query = embedder.embed(List.of("Where did the cat sit?"), JEmbeddingPurpose.QUERY).get().vectors().get(0);

        int best = 0;
        for (int i = 1; i < documents.size(); i++) {
            if (JEmbeddings.cosineSimilarity(query, documents.get(i)) > JEmbeddings.cosineSimilarity(query, documents.get(best))) {
                best = i;
            }
        }
        System.out.println("closest: " + texts.get(best));

        return texts.get(best);
    }
}
