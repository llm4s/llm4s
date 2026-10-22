package org.llm4s.javaapi.reactor;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.llm4s.javaapi.ConversationBuilder;
import org.llm4s.javaapi.JCompletionOptions;
import org.llm4s.javaapi.JLlmClient;
import org.llm4s.javaapi.LlmException;
import org.llm4s.llmconnect.model.Conversation;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * The Java blocks of {@code docs/guide/java-reactor.md}, compiled by sbt with javac against the module's public API and
 * run by {@code ReactorGuideSpec}, which also checks that every block of the guide is here.
 */
final class ReactorGuideSnippets {

  private ReactorGuideSnippets() {}

  /** "Stream a reply": print each piece, and keep the whole text. */
  static String stream(JLlmClient client) {
    Flux<String> reply = Streams.toFlux(client, "Explain monads in one sentence");
    String text = reply
        .doOnNext(System.out::print)
        .collect(Collectors.joining())
        .block(Duration.ofMinutes(1));
    return text;
  }

  /** "A conversation and options". */
  static List<String> conversation(JLlmClient client) {
    Conversation conversation = ConversationBuilder.create()
        .system("You are a concise assistant.")
        .user("Name three JVM languages.")
        .build();
    JCompletionOptions options = JCompletionOptions.builder().temperature(0.2).maxTokens(200).build();
    List<String> pieces = Streams.toFlux(client, conversation, options).collectList().block();
    return pieces;
  }

  /** "Stopping early". */
  static List<String> firstPieces(JLlmClient client) {
    List<String> firstFive = Streams.toFlux(client, "Write a long story").take(5).collectList().block();
    return firstFive;
  }

  /** "Handling a failure". */
  static String handle(JLlmClient client) {
    String text = Streams.toFlux(client, "Hello")
        .collect(Collectors.joining())
        .onErrorResume(LlmException.class, e -> switch (e.getKind()) {
            case RATE_LIMIT -> Mono.just("Busy, try again later");
            default -> Mono.just("Failed: " + e.getMessage());
        })
        .block();
    return text;
  }
}
