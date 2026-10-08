package org.llm4s.javaapi;

import java.util.Arrays;
import java.util.List;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.llm4s.error.LLMError;
import org.llm4s.error.RecoverableError;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JLlmClient;
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
 * only exist for the spec come after the block. The imports from {@code java.util.Optional} down are the guide's
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

    /** The "Handling a failure" block. */
    static void handlingAFailure(JLlmClient client) {
        try {
            String text = client.complete("What is 2+2?").get();
            System.out.println(text);
        } catch (LlmException e) {
            LLMError error = e.error();                // the llm4s error: a Scala type
            System.err.println(error.message());       // the same text as e.getMessage()
            System.err.println(error.formatted());     // the message plus its code and context
            if (error instanceof RecoverableError) {
                System.err.println("a retry may succeed");
            }
        }
    }
}
