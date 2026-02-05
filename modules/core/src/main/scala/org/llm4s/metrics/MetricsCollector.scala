package org.llm4s.metrics

import scala.concurrent.duration.FiniteDuration

/**
 * Minimal algebra for collecting metrics about LLM operations.
 *
 * Implementations should be safe: failures must not propagate to callers.
 * All methods should catch and log errors internally without throwing.
 *
 * Example usage:
 * {{{
 * val startNanos = System.nanoTime()
 * client.complete(conversation) match {
 *   case Right(completion) =>
 *     val duration = FiniteDuration(System.nanoTime() - startNanos, NANOSECONDS)
 *     metrics.observeRequest(provider, model, Outcome.Success, duration)
 *     completion.usage.foreach { u =>
 *       metrics.addTokens(provider, model, u.promptTokens, u.completionTokens)
 *     }
 *   case Left(error) =>
 *     val duration = FiniteDuration(System.nanoTime() - startNanos, NANOSECONDS)
 *     val errorKind = ErrorKind.fromLLMError(error)
 *     metrics.observeRequest(provider, model, Outcome.Error(errorKind), duration)
 * }
 * }}}
 */
trait MetricsCollector {

  /**
   * Record an LLM request with its outcome and duration.
   *
   * @param provider Provider name (e.g., "openai", "anthropic", "ollama")
   * @param model Model name (e.g., "gpt-4o", "claude-3-5-sonnet-latest")
   * @param outcome Success or Error with error kind
   * @param duration Request duration
   */
  def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit

  /**
   * Record token usage.
   *
   * @param provider Provider name
   * @param model Model name
   * @param inputTokens Number of input/prompt tokens
   * @param outputTokens Number of output/completion tokens
   */
  def addTokens(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long
  ): Unit

  /**
   * Record estimated cost in USD.
   *
   * @param provider Provider name
   * @param model Model name
   * @param costUsd Estimated cost in USD
   */
  def recordCost(
    provider: String,
    model: String,
    costUsd: Double
  ): Unit
}

object MetricsCollector {

  /**
   * No-op implementation that does nothing.
   * Use as default when metrics are disabled.
   */
  val noop: MetricsCollector = new MetricsCollector {
    override def observeRequest(
      provider: String,
      model: String,
      outcome: Outcome,
      duration: FiniteDuration
    ): Unit = ()

    override def addTokens(
      provider: String,
      model: String,
      inputTokens: Long,
      outputTokens: Long
    ): Unit = ()

    override def recordCost(
      provider: String,
      model: String,
      costUsd: Double
    ): Unit = ()
  }
}

/**
 * Outcome of an LLM request.
 */
sealed trait Outcome

object Outcome {

  /** Request completed successfully. */
  case object Success extends Outcome

  /**
   * Request failed with an error.
   *
   * @param errorKind Categorized error type
   */
  final case class Error(errorKind: ErrorKind) extends Outcome
}

/**
 * Stable categorization of LLM errors for metrics.
 *
 * These are stable labels safe for use in metrics dimensions.
 * Do not use exception class names as they may change.
 */
sealed trait ErrorKind

object ErrorKind {
  case object RateLimit      extends ErrorKind
  case object Timeout        extends ErrorKind
  case object Authentication extends ErrorKind
  case object Network        extends ErrorKind
  case object Validation     extends ErrorKind
  case object Unknown        extends ErrorKind

  /**
   * Map LLMError to stable ErrorKind.
   *
   * @param error LLM error
   * @return Categorized error kind
   */
  def fromLLMError(error: org.llm4s.error.LLMError): ErrorKind =
    error match {
      case _: org.llm4s.error.RateLimitError      => RateLimit
      case _: org.llm4s.error.TimeoutError        => Timeout
      case _: org.llm4s.error.AuthenticationError => Authentication
      case _: org.llm4s.error.NetworkError        => Network
      case _: org.llm4s.error.ValidationError     => Validation
      case _: org.llm4s.error.InvalidInputError   => Validation
      case _                                      => Unknown
    }
}
