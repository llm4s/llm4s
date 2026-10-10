package org.llm4s.javaapi

import org.llm4s.llmconnect.model.{ CompletionOptions, ReasoningEffort }

import java.util.{ Objects, Optional, OptionalInt }

/**
 * The options of one completion request, built for Java and Kotlin callers and passed to
 * `JLlmClient.complete(Conversation, JCompletionOptions)`.
 *
 * {{{
 * JCompletionOptions options = JCompletionOptions.builder()
 *     .temperature(0.2)
 *     .maxTokens(512)
 *     .reasoning(JReasoningEffort.HIGH)
 *     .build();
 * LlmResult<String> answer = client.complete(conversation, options);
 * }}}
 *
 * A setting left unset keeps core's default: temperature `0.7`, top-p `1.0`, no presence or frequency penalty,
 * and no token limit, reasoning level or thinking budget. A value that may be absent is read as an `Optional`
 * or `OptionalInt`, and set either directly or with one, so an empty `Optional` clears it. No Scala type appears
 * in a signature.
 *
 * A value: two are equal when every setting is. Tools and response formats are not options here yet.
 */
final class JCompletionOptions private (private[javaapi] val underlying: CompletionOptions) {

  /** The sampling temperature: higher is more random. Reasoning models may ignore it. */
  def temperature: Double = underlying.temperature

  /** Nucleus sampling: only tokens within this cumulative probability are considered. */
  def topP: Double = underlying.topP

  /** The most tokens the completion may contain, or empty for the provider's default. */
  def maxTokens: OptionalInt = JCompletionOptions.optionalInt(underlying.maxTokens)

  /** The penalty for a token that has appeared at all, encouraging new topics. */
  def presencePenalty: Double = underlying.presencePenalty

  /** The penalty for a token in proportion to how often it has appeared, discouraging repetition. */
  def frequencyPenalty: Double = underlying.frequencyPenalty

  /** The reasoning level asked for, or empty for none. */
  def reasoning: Optional[JReasoningEffort] =
    underlying.reasoning.fold(Optional.empty[JReasoningEffort]())(e => Optional.of(JCompletionOptions.toJava(e)))

  /** The explicit thinking-token budget (Anthropic), or empty to derive one from [[reasoning]]. */
  def budgetTokens: OptionalInt = JCompletionOptions.optionalInt(underlying.budgetTokens)

  /** A builder that starts from these options. */
  def toBuilder: JCompletionOptions.Builder = new JCompletionOptions.Builder(underlying)

  override def equals(other: Any): Boolean = other match {
    case that: JCompletionOptions => underlying == that.underlying
    case _                        => false
  }

  override def hashCode: Int = underlying.hashCode

  override def toString: String =
    s"JCompletionOptions(temperature=$temperature, topP=$topP, maxTokens=${text(maxTokens)}, " +
      s"presencePenalty=$presencePenalty, frequencyPenalty=$frequencyPenalty, " +
      s"reasoning=${reasoning.map[String](_.name).orElse("none")}, budgetTokens=${text(budgetTokens)})"

  private def text(value: OptionalInt): String =
    if (value.isPresent) value.getAsInt.toString else "none"
}

object JCompletionOptions {

  /** A builder holding core's defaults. */
  def builder(): Builder = new Builder(CompletionOptions())

  /**
   * Builds a [[JCompletionOptions]]. Immutable: every setter returns a new builder, so one may be shared and
   * extended in two ways. A value no provider accepts - a negative or non-finite temperature, a top-p outside
   * `0..1`, a non-finite penalty, a token count below 1 - throws `IllegalArgumentException` at once, and a
   * `null` throws `NullPointerException`; a range only some models accept is left to the provider.
   */
  final class Builder private[javaapi] (options: CompletionOptions) {

    /** Sets the sampling temperature; at least `0`. */
    def temperature(value: Double): Builder =
      next(options.withTemperature(check(value, value >= 0, "temperature", "at least 0")))

    /** Sets nucleus sampling; from `0` to `1`. */
    def topP(value: Double): Builder =
      next(options.withTopP(check(value, value >= 0 && value <= 1, "topP", "between 0 and 1")))

    /** Limits the completion to `value` tokens; at least `1`. */
    def maxTokens(value: Int): Builder = next(options.withMaxTokens(positive(value, "maxTokens")))

    /** Limits the completion to the value's tokens, or clears the limit when it is empty. */
    def maxTokens(value: OptionalInt): Builder = next(options.withMaxTokens(positive(value, "maxTokens")))

    /** Sets the presence penalty; any finite value, the range being the provider's. */
    def presencePenalty(value: Double): Builder =
      next(options.withPresencePenalty(check(value, true, "presencePenalty", "finite")))

    /** Sets the frequency penalty; any finite value, the range being the provider's. */
    def frequencyPenalty(value: Double): Builder =
      next(options.withFrequencyPenalty(check(value, true, "frequencyPenalty", "finite")))

    /** Asks for this level of reasoning. */
    def reasoning(effort: JReasoningEffort): Builder =
      next(options.withReasoning(toCore(Objects.requireNonNull(effort, "reasoning must not be null"))))

    /** Asks for the value's level of reasoning, or for none when it is empty. */
    def reasoning(effort: Optional[JReasoningEffort]): Builder =
      next(
        options.withReasoning(
          Option(Objects.requireNonNull(effort, "reasoning must not be null").orElse(null)).map(toCore)
        )
      )

    /** Sets an explicit thinking-token budget (Anthropic), overriding the one [[reasoning]] implies; at least `1`. */
    def budgetTokens(value: Int): Builder = next(options.withBudgetTokens(positive(value, "budgetTokens")))

    /** Sets the value as the thinking-token budget, or clears it when it is empty. */
    def budgetTokens(value: OptionalInt): Builder = next(options.withBudgetTokens(positive(value, "budgetTokens")))

    /** The options set so far. */
    def build(): JCompletionOptions = new JCompletionOptions(options)

    private def next(updated: CompletionOptions): Builder = new Builder(updated)
  }

  private def check(value: Double, inRange: Boolean, name: String, rule: String): Double =
    if (value.isFinite && inRange) value
    else throw new IllegalArgumentException(s"$name must be $rule, got $value")

  private def positive(value: Int, name: String): Int =
    if (value >= 1) value else throw new IllegalArgumentException(s"$name must be at least 1, got $value")

  private def positive(value: OptionalInt, name: String): Option[Int] = {
    val present = Objects.requireNonNull(value, s"$name must not be null")
    if (present.isPresent) Some(positive(present.getAsInt, name)) else None
  }

  private def optionalInt(value: Option[Int]): OptionalInt = value.fold(OptionalInt.empty())(OptionalInt.of)

  private def toCore(effort: JReasoningEffort): ReasoningEffort = effort match {
    case JReasoningEffort.NONE   => ReasoningEffort.None
    case JReasoningEffort.LOW    => ReasoningEffort.Low
    case JReasoningEffort.MEDIUM => ReasoningEffort.Medium
    case JReasoningEffort.HIGH   => ReasoningEffort.High
  }

  private def toJava(effort: ReasoningEffort): JReasoningEffort = effort match {
    case ReasoningEffort.None   => JReasoningEffort.NONE
    case ReasoningEffort.Low    => JReasoningEffort.LOW
    case ReasoningEffort.Medium => JReasoningEffort.MEDIUM
    case ReasoningEffort.High   => JReasoningEffort.HIGH
  }
}
