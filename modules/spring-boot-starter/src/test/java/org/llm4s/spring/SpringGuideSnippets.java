package org.llm4s.spring;

import java.util.concurrent.CompletableFuture;

import org.llm4s.javaapi.LlmException;
import org.llm4s.javaapi.LlmResult;

/**
 * Two fragments of {@code docs/guide/spring-boot.md}, compiled, and run by {@code SpringBootGuideSpec}. Each method
 * holds one block of the guide, word for word (the spec checks that); the lines that only exist for the spec come
 * after the block.
 */
final class SpringGuideSnippets {

    private SpringGuideSnippets() {}

    /** The "When a call fails" block. */
    static LlmResult<String> whenACallFails(LLM4STemplate llm) {
        try {
            String answer = llm.complete("What is 2+2?");
            System.out.println(answer);
        } catch (LlmException e) {
            System.err.println(e.getMessage());
        }

        LlmResult<String> result = llm.tryComplete("What is 2+2?");

        return result;
    }

    /** The "Asynchronous calls" block. */
    static CompletableFuture<String> asynchronous(LLM4STemplate llm, String text) {
        CompletableFuture<String> reply = llm.completeAsync("Translate to French: " + text);
        reply.thenAccept(answer -> System.out.println(answer));

        return reply;
    }
}
