package org.llm4s.javaapi;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;

/**
 * Token usage and estimated cost accumulated over an agent thread, as {@link JAgentResult#usage()} reports
 * it: the totals across every model, and the same figures per model in {@link #byModel()}.
 *
 * <pre>{@code
 * JUsageSummary u = result.usage();
 * System.out.println(u.inputTokens() + " in, " + u.outputTokens() + " out, $" + u.totalCost());
 * u.byModel().forEach((model, m) -> System.out.println(model + ": " + m.requestCount() + " calls"));
 * }</pre>
 *
 * <p>A value: two are equal when every field is, a cost by its numeric value whatever its scale ({@code 1.0}
 * equals {@code 1.00}), as Scala's {@code BigDecimal} compares. {@link #toString()} writes the cost in plain
 * notation. Built only by this package - its constructor is package-private, so fields can be added without
 * breaking a caller (#1666).
 */
public final class JUsageSummary {

  private final long requestCount;
  private final long inputTokens;
  private final long outputTokens;
  private final long thinkingTokens;
  private final BigDecimal totalCost;
  private final Map<String, JModelUsage> byModel;
  private final long cachedTokens;
  private final long cacheCreationTokens;

  JUsageSummary(
      long requestCount,
      long inputTokens,
      long outputTokens,
      long thinkingTokens,
      BigDecimal totalCost,
      Map<String, JModelUsage> byModel,
      long cachedTokens,
      long cacheCreationTokens) {
    this.requestCount = requestCount;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.thinkingTokens = thinkingTokens;
    this.totalCost = totalCost;
    this.byModel = byModel;
    this.cachedTokens = cachedTokens;
    this.cacheCreationTokens = cacheCreationTokens;
  }

  /** Model calls, across every model. */
  public long requestCount() {
    return requestCount;
  }

  /** Prompt tokens sent, across every model. */
  public long inputTokens() {
    return inputTokens;
  }

  /** Output tokens received, across every model, thinking included. */
  public long outputTokens() {
    return outputTokens;
  }

  /**
   * Thinking/reasoning tokens, across every model: the part of {@code outputTokens} that was thinking; zero
   * where a provider reports none.
   */
  public long thinkingTokens() {
    return thinkingTokens;
  }

  /** Estimated cost in USD, across every model; zero when no cost is known. */
  public BigDecimal totalCost() {
    return totalCost;
  }

  /** The same figures per model, keyed by model name and sorted by it; an unmodifiable map. */
  public Map<String, JModelUsage> byModel() {
    return byModel;
  }

  /** Input tokens served from a provider's prompt cache, across every model; a part of {@code inputTokens}. */
  public long cachedTokens() {
    return cachedTokens;
  }

  /** Input tokens written into a provider's prompt cache, across every model; a part of {@code inputTokens}. */
  public long cacheCreationTokens() {
    return cacheCreationTokens;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JUsageSummary that
        && requestCount == that.requestCount
        && inputTokens == that.inputTokens
        && outputTokens == that.outputTokens
        && thinkingTokens == that.thinkingTokens
        && cachedTokens == that.cachedTokens
        && cacheCreationTokens == that.cacheCreationTokens
        && totalCost.compareTo(that.totalCost) == 0
        && byModel.equals(that.byModel);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        requestCount,
        inputTokens,
        outputTokens,
        thinkingTokens,
        totalCost.stripTrailingZeros(),
        byModel,
        cachedTokens,
        cacheCreationTokens);
  }

  @Override
  public String toString() {
    return "JUsageSummary(" + requestCount + " requests, " + inputTokens + " in, " + outputTokens + " out, "
        + thinkingTokens + " thinking, " + totalCost.toPlainString() + " USD)";
  }
}
