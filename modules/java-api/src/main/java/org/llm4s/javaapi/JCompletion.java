package org.llm4s.javaapi;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One model reply with what came with it, as {@code JLlmClient.completion} returns it: the text, the model
 * that answered, the tool calls it asked for, the tokens it used and its estimated cost.
 *
 * <pre>{@code
 * JCompletion reply = client.completion("What is 2+2?").get();
 * System.out.println(reply.model() + ": " + reply.content());
 * reply.usage().ifPresent(u -> System.out.println(u.promptTokens() + " in, " + u.completionTokens() + " out"));
 * reply.estimatedCost().ifPresent(cost -> System.out.println("$" + cost.toPlainString()));
 * reply.toolCalls().forEach(c -> System.out.println(c.name() + " " + c.argumentsJson()));
 * }</pre>
 *
 * <p>A value: two are equal when every field is. {@code toString} prints the full text, as the Scala
 * {@code Completion} does, so mind what you log. Not {@code Serializable}. Built only by this package - its
 * constructor is package-private, so fields can be added without breaking a caller (#1666).
 */
public final class JCompletion {

  private final String id;
  private final String content;
  private final String model;
  private final List<JToolCall> toolCalls;
  private final Optional<JTokenUsage> usage;
  private final Optional<BigDecimal> estimatedCost;
  private final Optional<String> thinking;

  JCompletion(
      String id,
      String content,
      String model,
      List<JToolCall> toolCalls,
      Optional<JTokenUsage> usage,
      Optional<BigDecimal> estimatedCost,
      Optional<String> thinking) {
    this.id = id;
    this.content = content;
    this.model = model;
    this.toolCalls = toolCalls;
    this.usage = usage;
    this.estimatedCost = estimatedCost;
    this.thinking = thinking;
  }

  /** The provider's id for this reply. */
  public String id() {
    return id;
  }

  /** The reply's text; empty, never {@code null}, when the model only asked for tool calls. */
  public String content() {
    return content;
  }

  /**
   * The model that answered, as the provider names it - which may be more specific than the one configured.
   */
  public String model() {
    return model;
  }

  /**
   * The tool calls the model asked for, in order, as the agent's {@link JMessage#toolCalls()} lists them;
   * empty when it asked for none. Unmodifiable.
   */
  public List<JToolCall> toolCalls() {
    return toolCalls;
  }

  /** The tokens the call used, or empty when the provider did not report them. */
  public Optional<JTokenUsage> usage() {
    return usage;
  }

  /**
   * The estimated cost of the call in USD, or empty when it is not known (the provider reported no usage, or
   * the model has no known pricing). The same figure an agent turn adds to its
   * {@link JUsageSummary#totalCost()}.
   */
  public Optional<BigDecimal> estimatedCost() {
    return estimatedCost;
  }

  /** The reasoning text the model reported, or empty when there was none. */
  public Optional<String> thinking() {
    return thinking;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JCompletion that
        && Objects.equals(id, that.id)
        && content.equals(that.content)
        && Objects.equals(model, that.model)
        && toolCalls.equals(that.toolCalls)
        && usage.equals(that.usage)
        && estimatedCost.equals(that.estimatedCost)
        && thinking.equals(that.thinking);
  }

  @Override
  public int hashCode() {
    return Objects.hash(id, content, model, toolCalls, usage, estimatedCost, thinking);
  }

  @Override
  public String toString() {
    return "JCompletion(" + id + ", " + model + ", " + toolCalls.size() + " tool calls: " + content + ")";
  }
}
