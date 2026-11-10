package org.llm4s.benchcmp.langchain4j;

import dev.langchain4j.model.openai.OpenAiChatModel;

/**
 * The cold-start scenario: a fresh JVM builds a client and makes one chat call. Uses only LangChain4j, never the
 * harness, so the process loads what a minimal chat app would. Prints {@code DONE} once the reply is in, then exits.
 */
public final class LangChain4jColdStart {
  public static void main(String[] args) {
    OpenAiChatModel model = OpenAiChatModel.builder()
        .baseUrl(args[0])
        .apiKey("bench-key")
        .modelName("bench-model")
        .maxRetries(0)
        .build();
    String reply = model.chat("Hello");
    System.out.println(reply.isEmpty() ? "EMPTY" : "DONE");
    System.out.flush();
    System.exit(0);
  }
}
