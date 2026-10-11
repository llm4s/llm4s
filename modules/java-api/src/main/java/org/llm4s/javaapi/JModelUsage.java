package org.llm4s.javaapi;

import java.math.BigDecimal;
import java.util.Objects;

/**
 * One model's share of a {@link JUsageSummary}, as {@link JUsageSummary#byModel()} maps it.
 *
 * <p>A value: two are equal when every field is, the cost by its numeric value whatever its scale, as for
 * {@link JUsageSummary}. Built only by this package - its constructor is package-private, so fields can be
 * added without breaking a caller (#1666).
 */
public final class JModelUsage {

  private final long requestCount;
  private final long inputTokens;
  private final long outputTokens;
  private final long thinkingTokens;
  private final BigDecimal totalCost;

  JModelUsage(long requestCount, long inputTokens, long outputTokens, long thinkingTokens, BigDecimal totalCost) {
    this.requestCount = requestCount;
    this.inputTokens = inputTokens;
    this.outputTokens = outputTokens;
    this.thinkingTokens = thinkingTokens;
    this.totalCost = totalCost;
  }

  /** Calls to this model. */
  public long requestCount() {
    return requestCount;
  }

  /** Prompt tokens sent to this model. */
  public long inputTokens() {
    return inputTokens;
  }

  /** Completion tokens received from this model. */
  public long outputTokens() {
    return outputTokens;
  }

  /** Extended-thinking tokens from this model; zero where its provider reports none. */
  public long thinkingTokens() {
    return thinkingTokens;
  }

  /** Estimated cost of this model's calls in USD; zero when no cost is known. */
  public BigDecimal totalCost() {
    return totalCost;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JModelUsage that
        && requestCount == that.requestCount
        && inputTokens == that.inputTokens
        && outputTokens == that.outputTokens
        && thinkingTokens == that.thinkingTokens
        && totalCost.compareTo(that.totalCost) == 0;
  }

  @Override
  public int hashCode() {
    return Objects.hash(requestCount, inputTokens, outputTokens, thinkingTokens, totalCost.stripTrailingZeros());
  }

  @Override
  public String toString() {
    return "JModelUsage(" + requestCount + " requests, " + inputTokens + " in, " + outputTokens + " out, "
        + thinkingTokens + " thinking, " + totalCost.toPlainString() + " USD)";
  }
}
