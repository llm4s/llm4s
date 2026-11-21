package org.llm4s.benchcmp.springai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;

/**
 * The cold-start scenario: a fresh JVM builds a client and makes one chat call. Uses only Spring AI, never the
 * harness, so the process loads what a minimal chat app would. Prints {@code DONE} once the reply is in, then exits.
 */
public final class SpringAiColdStart {
  public static void main(String[] args) {
    OpenAiChatModel model = OpenAiChatModel.builder()
        .options(OpenAiChatOptions.builder()
            .baseUrl(args[0])
            .apiKey("bench-key")
            .model("bench-model")
            .maxRetries(0)
            .build())
        .build();
    String reply = ChatClient.create(model).prompt("Hello").call().content();
    System.out.println(reply == null || reply.isEmpty() ? "EMPTY" : "DONE");
    System.out.flush();
    System.exit(0);
  }
}
