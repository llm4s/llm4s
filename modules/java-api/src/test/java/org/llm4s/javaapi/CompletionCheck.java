package org.llm4s.javaapi;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import org.llm4s.llmconnect.model.Conversation;

/**
 * {@link JCompletion} and {@link LlmException}'s error kind from real Java source, compiled by sbt against the module's
 * public API and driven by {@code JCompletionSpec} and {@code LlmErrorKindSpec}. Nothing here names a Scala type.
 */
final class CompletionCheck {

  private CompletionCheck() {}

  /** Every way to ask for the whole reply: a query, a conversation, and a conversation with options. */
  static List<LlmResult<JCompletion>> everyOverload(JLlmClient client) {
    Conversation conversation = ConversationBuilder.create().user("What is 2+2?").build();
    JCompletionOptions options = JCompletionOptions.builder().maxTokens(64).build();
    List<LlmResult<JCompletion>> results = new ArrayList<>();
    results.add(client.completion("What is 2+2?"));
    results.add(client.completion(conversation));
    results.add(client.completion(conversation, options));
    return results;
  }

  /**
   * A literal {@code null} for the options compiles without a cast - there is one options overload of
   * {@code completion} - and fails as a result, not an exception.
   */
  static LlmResult<JCompletion> nullOptions(JLlmClient client) {
    Conversation conversation = ConversationBuilder.create().user("hi").build();
    return client.completion(conversation, null);
  }

  /** What a reply reads back, with Java types only. */
  static List<String> readBack(JCompletion reply) {
    List<String> log = new ArrayList<>();
    String id = reply.id();
    String content = reply.content();
    String model = reply.model();
    List<JToolCall> toolCalls = reply.toolCalls();
    Optional<JTokenUsage> usage = reply.usage();
    Optional<BigDecimal> cost = reply.estimatedCost();
    Optional<String> thinking = reply.thinking();
    log.add("id:" + id);
    log.add("content:" + content);
    log.add("model:" + model);
    for (JToolCall call : toolCalls) {
      log.add("tool:" + call.id() + ":" + call.name() + ":" + call.argumentsJson());
    }
    usage.ifPresent(u -> {
      int prompt = u.promptTokens();
      int completion = u.completionTokens();
      int total = u.totalTokens();
      int thinkingTokens = u.thinkingTokens();
      log.add("usage:" + prompt + "/" + completion + "/" + total + "/" + thinkingTokens);
    });
    log.add("cost:" + cost.map(BigDecimal::toPlainString).orElse("unknown"));
    log.add("thinking:" + thinking.orElse("none"));
    return log;
  }

  /** What a failure reads back, with Java types only. */
  static List<String> failure(LlmException e) {
    List<String> log = new ArrayList<>();
    LlmErrorKind kind = e.getKind();
    boolean recoverable = e.isRecoverable();
    Optional<Duration> retryAfter = e.getRetryAfter();
    OptionalInt status = e.getStatusCode();
    log.add("kind:" + describe(kind));
    log.add("recoverable:" + recoverable);
    log.add("retryAfter:" + retryAfter.map(d -> String.valueOf(d.toMillis())).orElse("none"));
    log.add("status:" + (status.isPresent() ? String.valueOf(status.getAsInt()) : "none"));
    return log;
  }

  /** A {@code switch} expression with no {@code default} covers every kind, or this does not compile. */
  static String describe(LlmErrorKind kind) {
    return switch (kind) {
      case AUTHENTICATION -> "authentication";
      case RATE_LIMIT -> "rate-limit";
      case TIMEOUT -> "timeout";
      case NETWORK -> "network";
      case SERVICE -> "service";
      case VALIDATION -> "validation";
      case CONFIGURATION -> "configuration";
      case CANCELLED -> "cancelled";
      case OTHER -> "other";
    };
  }
}
