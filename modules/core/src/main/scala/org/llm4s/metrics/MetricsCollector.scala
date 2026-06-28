package org.llm4s.metrics

import scala.concurrent.duration.FiniteDuration

/**
 * Minimal algebra for collecting metrics about LLM operations.
 *
 * The core metrics model tracks request latency, request outcome, token usage,
 * estimated cost, retry attempts, circuit-breaker transitions, generic errors,
 * and image generation usage. Concrete implementations decide where these
 * observations are stored or exported.
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
   * The `outcome` dimension separates successful requests from failures. Error
   * outcomes carry a stable [[ErrorKind]] such as [[ErrorKind.RateLimit]],
   * [[ErrorKind.Timeout]], or [[ErrorKind.ServiceError]] so metrics backends can
   * aggregate failures without depending on exception class names.
   *
   * @param provider Provider name (e.g., "openai", "anthropic", "ollama")
   * @param model Model name (e.g., "gpt-4o", "claude-3-5-sonnet-latest")
   * @param outcome Success or Error with stable error kind
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
   * Implementations typically record input and output tokens separately so
   * dashboards can show prompt and completion usage independently.
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
   * Use this after pricing metadata is available for a request or image
   * generation operation. Implementations should treat the value as an
   * additive counter.
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

  /**
   * Record a retry attempt for reliability tracking.
   *
   * The first retry after the original request fails should use
   * `attemptNumber = 1`.
   *
   * @param provider Provider name
   * @param attemptNumber Which retry attempt (1 = first retry, 2 = second, etc.)
   */
  def recordRetryAttempt(
    provider: String,
    attemptNumber: Int
  ): Unit = () // Default no-op

  /**
   * Record circuit breaker state transition.
   *
   * Expected states are stable labels such as "open", "closed", and
   * "half-open".
   *
   * @param provider Provider name
   * @param newState New circuit breaker state ("open", "closed", "half-open")
   */
  def recordCircuitBreakerTransition(
    provider: String,
    newState: String
  ): Unit = () // Default no-op

  /**
   * Record a generic error for metrics (when full request tracking not applicable).
   *
   * Use this for failures that occur outside a complete request timing scope,
   * such as configuration, validation, or setup failures.
   *
   * @param errorKind Type of error
   * @param provider Provider name
   */
  def recordError(
    errorKind: ErrorKind,
    provider: String
  ): Unit = () // Default no-op

  /**
   * Record an image generation operation.
   *
   * The `outcome` dimension has the same meaning as in [[observeRequest]].
   * `imageCount` records the number of generated images only for successful
   * operations.
   *
   * @param provider Provider name (e.g., "openai", "stability-ai")
   * @param model Model name (e.g., "gpt-image-1", "dall-e-3")
   * @param operation Operation type: "generate" or "edit"
   * @param outcome Success or Error with stable error kind
   * @param duration Request duration
   * @param imageCount Number of images generated
   */
  def observeImageGeneration(
    provider: String,
    model: String,
    operation: String,
    outcome: Outcome,
    duration: FiniteDuration,
    imageCount: Int
  ): Unit = () // Default no-op

  /**
   * Record estimated image generation cost in USD.
   *
   * @param provider Provider name
   * @param model Model name
   * @param costUsd Estimated cost in USD
   * @param imageCount Number of images
   */
  def recordImageGenerationCost(
    provider: String,
    model: String,
    costUsd: Double,
    imageCount: Int
  ): Unit = () // Default no-op
}

object MetricsCollector {

  /**
   * Combine multiple collectors into one that fans out every call to all of them.
   *
   * Useful for running a [[CostTracker]] alongside [[PrometheusMetrics]]:
   * {{{
   * val combined = MetricsCollector.compose(prometheusMetrics, costTracker)
   * val client = LLMConnect.getClient(config, combined)
   * }}}
   */
  def compose(collectors: MetricsCollector*): MetricsCollector = new MetricsCollector {
    private def safeForEach(f: MetricsCollector => Unit): Unit =
      collectors.foreach(c => scala.util.Try(f(c)))

    override def observeRequest(
      provider: String,
      model: String,
      outcome: Outcome,
      duration: FiniteDuration
    ): Unit = safeForEach(_.observeRequest(provider, model, outcome, duration))

    override def addTokens(
      provider: String,
      model: String,
      inputTokens: Long,
      outputTokens: Long
    ): Unit = safeForEach(_.addTokens(provider, model, inputTokens, outputTokens))

    override def recordCost(
      provider: String,
      model: String,
      costUsd: Double
    ): Unit = safeForEach(_.recordCost(provider, model, costUsd))

    override def recordRetryAttempt(
      provider: String,
      attemptNumber: Int
    ): Unit = safeForEach(_.recordRetryAttempt(provider, attemptNumber))

    override def recordCircuitBreakerTransition(
      provider: String,
      newState: String
    ): Unit = safeForEach(_.recordCircuitBreakerTransition(provider, newState))

    override def recordError(
      errorKind: ErrorKind,
      provider: String
    ): Unit = safeForEach(_.recordError(errorKind, provider))
  }

  /**
   * No-op implementation that does nothing.
   *
   * Use as the default collector when metrics are disabled.
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
 *
 * Use [[Outcome.Success]] when the operation completed normally and
 * [[Outcome.Error]] when it failed with a categorized [[ErrorKind]].
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

  /** Provider or gateway rate limit. */
  case object RateLimit extends ErrorKind

  /** Request or operation timeout. */
  case object Timeout extends ErrorKind

  /** Missing, invalid, or unauthorized credentials. */
  case object Authentication extends ErrorKind

  /** Network transport or connectivity failure. */
  case object Network extends ErrorKind

  /** Invalid request, schema, or configuration input. */
  case object Validation extends ErrorKind

  /** Provider-side service error. */
  case object ServiceError extends ErrorKind

  /** Local execution failure outside the provider API. */
  case object ExecutionError extends ErrorKind

  /** Fallback category when no more specific kind is available. */
  case object Unknown extends ErrorKind

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
      case _: org.llm4s.error.ServiceError        => ServiceError
      case _: org.llm4s.error.ExecutionError      => ExecutionError
      case _: org.llm4s.error.ConfigurationError  => Validation
      case _                                      => Unknown
    }
}
