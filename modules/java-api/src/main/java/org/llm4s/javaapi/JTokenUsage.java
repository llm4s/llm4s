package org.llm4s.javaapi;

import java.util.Objects;

/**
 * The tokens one model call used, as {@link JCompletion#usage()} reports them. An agent turn's
 * {@link JUsageSummary} adds these up across its calls: its {@code inputTokens} are the sum of
 * {@code promptTokens}, its {@code outputTokens} of {@code completionTokens}.
 *
 * <p>A value: two are equal when every field is. Built only by this package - its constructor is
 * package-private, so fields can be added without breaking a caller (#1666).
 */
public final class JTokenUsage {

  private final int promptTokens;
  private final int completionTokens;
  private final int totalTokens;
  private final int thinkingTokens;
  private final int cachedTokens;
  private final int cacheCreationTokens;

  JTokenUsage(
      int promptTokens,
      int completionTokens,
      int totalTokens,
      int thinkingTokens,
      int cachedTokens,
      int cacheCreationTokens) {
    this.promptTokens = promptTokens;
    this.completionTokens = completionTokens;
    this.totalTokens = totalTokens;
    this.thinkingTokens = thinkingTokens;
    this.cachedTokens = cachedTokens;
    this.cacheCreationTokens = cacheCreationTokens;
  }

  /** Tokens sent: the conversation, the tools and the instructions. */
  public int promptTokens() {
    return promptTokens;
  }

  /** Tokens received in the reply. */
  public int completionTokens() {
    return completionTokens;
  }

  /** The total the provider reported, normally {@code promptTokens + completionTokens}. */
  public int totalTokens() {
    return totalTokens;
  }

  /**
   * Extended-thinking tokens, billed as output but counted apart from {@code completionTokens}; zero where
   * the provider reports none, as in {@link JUsageSummary#thinkingTokens()}.
   */
  public int thinkingTokens() {
    return thinkingTokens;
  }

  /**
   * Input tokens served from the provider's prompt cache (a cache read), billed at the cheaper cache-read
   * rate; zero where the provider reports none.
   */
  public int cachedTokens() {
    return cachedTokens;
  }

  /**
   * Input tokens written into the provider's prompt cache, billed at the cache-creation rate, which is
   * typically higher than the normal input rate; zero where the provider reports none.
   */
  public int cacheCreationTokens() {
    return cacheCreationTokens;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JTokenUsage that
        && promptTokens == that.promptTokens
        && completionTokens == that.completionTokens
        && totalTokens == that.totalTokens
        && thinkingTokens == that.thinkingTokens
        && cachedTokens == that.cachedTokens
        && cacheCreationTokens == that.cacheCreationTokens;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        promptTokens, completionTokens, totalTokens, thinkingTokens, cachedTokens, cacheCreationTokens);
  }

  @Override
  public String toString() {
    return "JTokenUsage(" + promptTokens + " prompt, " + completionTokens + " completion, " + totalTokens
        + " total, " + thinkingTokens + " thinking, " + cachedTokens + " cached, " + cacheCreationTokens
        + " cache creation)";
  }
}
