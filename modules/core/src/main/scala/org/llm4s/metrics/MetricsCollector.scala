package org.llm4s.metrics

import org.llm4s.annotation.{ Experimental, Stable }

import scala.concurrent.duration.FiniteDuration

/**
 * Minimal algebra for collecting metrics about LLM operations.
 *
 * The core metrics model tracks request latency, request outcome, token usage
 * and estimated cost. Everything else is a [[Metric]] its
 * owner declares and records through the generic [[increment]], [[observe]] and
 * [[set]]: `ReliableClient`'s retries, circuit-breaker transitions and errors
 * (`org.llm4s.reliability.ReliabilityMetrics`), and the metrics of modules above
 * core - image generation's are `ImageMetrics` in `llm4s-image` - so core names none
 * of their concepts. A backend that implements the three
 * generic methods exports them all. Concrete implementations decide where these
 * observations are stored or exported.
 *
 * `provider` is a metrics label, which is why it is a `String` and not a `ProviderId`: often a
 * provider's id, but equally a caller's own name for a client, such as the `providerName` a
 * `ReliableClient` is built with (`"openai-main"`, `"kafka-ticket-pipeline"`).
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
 *       metrics.addTokens(provider, model, u.promptTokens, u.totalOutputTokens) // thinking included
 *     }
 *   case Left(error) =>
 *     val duration = FiniteDuration(System.nanoTime() - startNanos, NANOSECONDS)
 *     val errorKind = ErrorKind.fromLLMError(error)
 *     metrics.observeRequest(provider, model, Outcome.Error(errorKind), duration)
 * }
 * }}}
 */
@Stable
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
   * Add `amount` to counter `metric`, with `labelValues` for its label names, in order.
   *
   * The generic entry point for declared metrics (see [[Metric]]): `ReliableClient`'s retry, circuit-breaker and
   * error counters and the agent runtime's run, claim, queue and child-task metrics come through here, so a backend
   * exports them with no method of its own and no dependency on the module that declares them. A
   * backend drops a recording whose number of values is not the metric's number of labels, or whose `amount` is
   * negative or not finite, logging it, and records a null or blank value as `unknown` and values past
   * [[MetricsCollector.MaxLabelValues]] distinct ones per label as `other`.
   */
  @Experimental
  def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double = 1.0): Unit = () // Default no-op

  /** Record one `duration` in timer `metric`, with `labelValues` for its label names; see [[increment]]. */
  @Experimental
  def observe(metric: Metric.Timer, labelValues: Seq[String], duration: FiniteDuration): Unit = () // Default no-op

  /**
   * Set gauge `metric`, with `labelValues` for its label names, to `value`, its latest reading; see [[increment]]. A
   * non-finite value is dropped.
   */
  @Experimental
  def set(metric: Metric.Gauge, labelValues: Seq[String], value: Double): Unit = () // Default no-op
}

object MetricsCollector {

  /**
   * The most distinct values a backend records for one label of one [[Metric]]; further values are recorded as
   * `other`, so a label fed an unbounded value by mistake cannot grow the registry without limit.
   */
  val MaxLabelValues: Int = 256

  /**
   * The count of failed operations: the series a backend adds a failed request to in [[MetricsCollector.observeRequest]]
   * (`llm4s_errors_total` in Prometheus). It is declared so that a module above core can add its own failed
   * operations to that same series through [[MetricsCollector.increment]], as `llm4s-image` does for a failed image
   * generation. Labels: `provider`, `error_type` (an [[ErrorKind]] in snake case, as
   * `org.llm4s.reliability.ReliabilityMetrics.errorTypeLabel` gives it).
   *
   * A backend that keeps this count in a meter of its own must record this metric into that meter, not register
   * a second one under the name: `PrometheusMetrics` and `MicrometerMetrics` do.
   */
  @Experimental
  val Errors: Metric.Counter = Metric.counter("llm4s.errors", "Total number of errors", "provider", "error_type")

  /**
   * Combine multiple collectors into one that fans out every call to all of them.
   *
   * Every method of the trait is forwarded; a
   * collector that throws does not stop the others. A method added to the trait must
   * be forwarded here too - `MetricsCollectorComposeSpec` fails otherwise.
   *
   * Useful for running a `CostTracker` (in `llm4s-observability`) alongside `PrometheusMetrics` (in `llm4s-observability-prometheus`):
   * {{{
   * val combined = MetricsCollector.compose(prometheusMetrics, costTracker)
   * val client = LLMConnect.getClient(config, LlmClientOptions(metrics = combined))
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

    override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit =
      safeForEach(_.increment(metric, labelValues, amount))

    override def observe(metric: Metric.Timer, labelValues: Seq[String], duration: FiniteDuration): Unit =
      safeForEach(_.observe(metric, labelValues, duration))

    override def set(metric: Metric.Gauge, labelValues: Seq[String], value: Double): Unit =
      safeForEach(_.set(metric, labelValues, value))
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
@Stable
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
@Stable
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

  /** The operation was cancelled by interrupting its thread. */
  case object Cancelled extends ErrorKind

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
      case _: org.llm4s.error.RateLimitError       => RateLimit
      case _: org.llm4s.error.TimeoutError         => Timeout
      case _: org.llm4s.error.AuthenticationError  => Authentication
      case _: org.llm4s.error.NetworkError         => Network
      case _: org.llm4s.error.ValidationError      => Validation
      case _: org.llm4s.error.InvalidInputError    => Validation
      case _: org.llm4s.error.ServiceError         => ServiceError
      case _: org.llm4s.error.CircuitOpenError     => ServiceError
      case _: org.llm4s.error.ExecutionError       => ExecutionError
      case _: org.llm4s.error.ConfigurationError   => Validation
      case _: org.llm4s.error.CancelledError       => Cancelled
      case _: org.llm4s.error.ContentFilteredError => Validation
      case _                                       => Unknown
    }
}
