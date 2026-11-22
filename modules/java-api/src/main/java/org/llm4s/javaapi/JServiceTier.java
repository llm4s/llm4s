package org.llm4s.javaapi;

/**
 * The processing tier to ask a provider for, set with {@link JCompletionOptions.Builder#serviceTier}.
 *
 * <p>Each constant is one of core's {@code ServiceTier} values. OpenAI receives it as {@code service_tier}, Anthropic
 * as {@code auto} or {@code standard_only}, Gemini and Bedrock as their own tier field and Vertex AI as a request
 * header. A tier a provider cannot send as asked is reported on the completion; a provider with no tiers reports it
 * dropped.
 *
 * <p>A Java enum, not a Scala 3 one: Java reads a Scala 3 enum's cases through its companion object,
 * and a {@code switch} that runs before that object is initialised finds them {@code null}.
 */
public enum JServiceTier {
  /** The provider's own choice: the tier set for the project or account, or its default. */
  AUTO,

  /** The standard tier, at the standard price and speed. */
  DEFAULT,

  /** Cheaper and slower, served when there is capacity to spare; may queue or be shed under load. */
  FLEX,

  /** Faster and more reliable under load, at a higher price, where the account has access to it. */
  PRIORITY
}
