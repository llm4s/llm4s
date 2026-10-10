package org.llm4s.javaapi;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.llm4s.llmconnect.model.Conversation;

/**
 * {@link JCompletionOptions} from real Java source, compiled by sbt against the module's public API and driven by
 * {@code JCompletionOptionsSpec}, which checks the core {@code CompletionOptions} each one becomes. Nothing here
 * names a Scala type.
 */
final class CompletionOptionsCheck {

  private CompletionOptionsCheck() {}

  /** Every setting the builder has, set once. */
  static JCompletionOptions everySetting() {
    return JCompletionOptions.builder()
        .temperature(0.2)
        .topP(0.9)
        .maxTokens(512)
        .presencePenalty(0.5)
        .frequencyPenalty(-0.5)
        .reasoning(JReasoningEffort.HIGH)
        .budgetTokens(4096)
        .build();
  }

  /** The values that may be absent, set through {@code Optional} and {@code OptionalInt}. */
  static JCompletionOptions throughOptionals() {
    return JCompletionOptions.builder()
        .maxTokens(OptionalInt.of(256))
        .reasoning(Optional.of(JReasoningEffort.LOW))
        .budgetTokens(OptionalInt.of(1024))
        .build();
  }

  /** {@code options} with every absent-able value cleared by an empty {@code Optional}. */
  static JCompletionOptions cleared(JCompletionOptions options) {
    return options.toBuilder()
        .maxTokens(OptionalInt.empty())
        .reasoning(Optional.empty())
        .budgetTokens(OptionalInt.empty())
        .build();
  }

  /** Sends {@code options} with a one-message conversation. */
  static LlmResult<String> send(JLlmClient client, JCompletionOptions options) {
    Conversation conversation = ConversationBuilder.create().user("What is 2+2?").build();
    return client.complete(conversation, options);
  }

  /** What the accessors read back, with Java types only. */
  static List<String> readBack(JCompletionOptions options) {
    List<String> log = new ArrayList<>();
    double temperature = options.temperature();
    double topP = options.topP();
    OptionalInt maxTokens = options.maxTokens();
    double presencePenalty = options.presencePenalty();
    double frequencyPenalty = options.frequencyPenalty();
    Optional<JReasoningEffort> reasoning = options.reasoning();
    OptionalInt budgetTokens = options.budgetTokens();
    log.add("temperature:" + temperature);
    log.add("topP:" + topP);
    log.add("maxTokens:" + (maxTokens.isPresent() ? String.valueOf(maxTokens.getAsInt()) : "none"));
    log.add("presencePenalty:" + presencePenalty);
    log.add("frequencyPenalty:" + frequencyPenalty);
    log.add("reasoning:" + reasoning.map(CompletionOptionsCheck::describe).orElse("none"));
    log.add("budgetTokens:" + (budgetTokens.isPresent() ? String.valueOf(budgetTokens.getAsInt()) : "none"));
    return log;
  }

  /** A {@code switch} over the reasoning levels covers every one. */
  static String describe(JReasoningEffort effort) {
    return switch (effort) {
      case NONE -> "none";
      case LOW -> "low";
      case MEDIUM -> "medium";
      case HIGH -> "high";
    };
  }

  /** A builder is immutable: two options built from one base do not affect each other or it. */
  static List<JCompletionOptions> forked() {
    JCompletionOptions.Builder base = JCompletionOptions.builder().temperature(0.1);
    JCompletionOptions brief = base.maxTokens(16).build();
    JCompletionOptions careful = base.reasoning(JReasoningEffort.MEDIUM).build();
    List<JCompletionOptions> all = new ArrayList<>();
    all.add(base.build());
    all.add(brief);
    all.add(careful);
    return all;
  }

  /** The message of the {@code IllegalArgumentException} a negative temperature throws, caught in Java. */
  static String rejectedTemperature() {
    try {
      JCompletionOptions.builder().temperature(-1.0);
      return "accepted";
    } catch (IllegalArgumentException e) {
      return e.getMessage();
    }
  }
}
