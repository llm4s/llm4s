package org.llm4s.metrics

import io.prometheus.metrics.core.metrics.{ Counter, Gauge, Histogram }
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.slf4j.LoggerFactory

import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try }

/**
 * Prometheus implementation of [[MetricsCollector]].
 *
 * Tracks request volumes, token usage, errors, and latency across
 * different providers and models using Prometheus metrics.
 *
 * Registered metric names:
 * - `llm4s_requests_total`
 * - `llm4s_tokens_total`
 * - `llm4s_cost_usd_total`
 * - `llm4s_errors_total`
 * - `llm4s_request_duration_seconds`
 * - `llm4s_image_generations_total`
 * - `llm4s_images_generated_total`
 * - `llm4s_image_generation_duration_seconds`
 * - `llm4s_image_generation_cost_usd_total`
 * - `llm4s_image_generation_errors_total`
 *
 * Generic [[Metric]]s that other modules declare (the agent runtime's run, claim, queue and child-task metrics) are
 * registered on their first recording, their name's dots turned into underscores: a counter
 * `llm4s.agent.runs.started` is exposed as `llm4s_agent_runs_started_total`, a timer as a histogram
 * `<name>_seconds` with the metric's buckets, a gauge under its name. Label values are bounded as
 * [[MetricsCollector.increment]] describes.
 *
 * All operations are wrapped in try-catch to ensure metric failures
 * never propagate to callers. This implementation is thread-safe.
 *
 * Example usage:
 * {{{
 * val registry = new PrometheusRegistry()
 * val metrics = new PrometheusMetrics(registry)
 *
 * // Use with endpoint
 * PrometheusEndpoint.start(9090, registry).foreach { endpoint =>
 *   // ... use metrics ...
 *   endpoint.stop()
 * }
 * }}}
 *
 * @param registry Prometheus collector registry
 */
final class PrometheusMetrics(
  private[llm4s] val registry: PrometheusRegistry
) extends MetricsCollector {

  private val logger = LoggerFactory.getLogger(getClass)

  // Request counter with labels
  private val requestsTotal = Counter
    .builder()
    .name("llm4s_requests_total")
    .help("Total number of LLM requests")
    .labelNames("provider", "model", "status")
    .register(registry)

  // Token counter
  private val tokensTotal = Counter
    .builder()
    .name("llm4s_tokens_total")
    .help("Total tokens consumed")
    .labelNames("provider", "model", "type")
    .register(registry)

  // Cost counter
  private val costUsdTotal = Counter
    .builder()
    .name("llm4s_cost_usd_total")
    .help("Total estimated cost in USD")
    .labelNames("provider", "model")
    .register(registry)

  // Error counter
  private val errorsTotal = Counter
    .builder()
    .name("llm4s_errors_total")
    .help("Total number of errors")
    .labelNames("provider", "error_type")
    .register(registry)

  // Request duration histogram
  private val requestDuration = Histogram
    .builder()
    .name("llm4s_request_duration_seconds")
    .help("Request duration in seconds")
    .labelNames("provider", "model")
    .classicUpperBounds(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0)
    .register(registry)

  // Image generation counter
  private val imageGenerationsTotal = Counter
    .builder()
    .name("llm4s_image_generations_total")
    .help("Total number of image generation operations")
    .labelNames("provider", "model", "operation", "status")
    .register(registry)

  // Image count counter
  private val imagesGeneratedTotal = Counter
    .builder()
    .name("llm4s_images_generated_total")
    .help("Total number of images generated")
    .labelNames("provider", "model")
    .register(registry)

  // Image generation duration histogram
  private val imageGenerationDuration = Histogram
    .builder()
    .name("llm4s_image_generation_duration_seconds")
    .help("Image generation duration in seconds")
    .labelNames("provider", "model", "operation")
    .classicUpperBounds(1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0)
    .register(registry)

  // Image generation cost counter
  private val imageGenerationCostUsdTotal = Counter
    .builder()
    .name("llm4s_image_generation_cost_usd_total")
    .help("Total estimated image generation cost in USD")
    .labelNames("provider", "model")
    .register(registry)

  // Image generation error counter (Issue #501 requirement)
  private val imageGenerationErrorsTotal = Counter
    .builder()
    .name("llm4s_image_generation_errors_total")
    .help("Total number of image generation errors")
    .labelNames("provider", "model", "operation", "error_type")
    .register(registry)

  /** Convert ErrorKind to a stable snake_case label for metrics. */
  private def errorKindToLabel(errorKind: ErrorKind): String = {
    val kindStr   = errorKind.toString
    val snakeCase = kindStr.replaceAll("([A-Z])", "_$1").toLowerCase.drop(1)
    snakeCase
  }

  /**
   * Record an LLM request with its outcome and duration.
   *
   * Updates `llm4s_requests_total` and
   * `llm4s_request_duration_seconds`. Error outcomes also increment
   * `llm4s_errors_total`.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit =
    Try {
      val status = outcome match {
        case Outcome.Success          => "success"
        case Outcome.Error(errorKind) => s"error_${errorKindToLabel(errorKind)}"
      }

      requestsTotal.labelValues(provider, model, status).inc()
      requestDuration.labelValues(provider, model).observe(duration.toMillis / 1000.0)

      outcome match {
        case Outcome.Error(errorKind) =>
          errorsTotal.labelValues(provider, errorKindToLabel(errorKind)).inc()
        case _ => // No additional action for success
      }
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record request metrics: ${e.getMessage}")
    }

  /**
   * Record token usage.
   *
   * Updates `llm4s_tokens_total` with `type` labels of `input` and `output`.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def addTokens(
    provider: String,
    model: String,
    inputTokens: Long,
    outputTokens: Long
  ): Unit =
    Try {
      tokensTotal.labelValues(provider, model, "input").inc(inputTokens.toDouble)
      tokensTotal.labelValues(provider, model, "output").inc(outputTokens.toDouble)
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record token metrics: ${e.getMessage}")
    }

  /**
   * Record estimated cost in USD.
   *
   * Updates `llm4s_cost_usd_total`.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def recordCost(
    provider: String,
    model: String,
    costUsd: Double
  ): Unit =
    Try {
      costUsdTotal.labelValues(provider, model).inc(costUsd)
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record cost metrics: ${e.getMessage}")
    }

  /**
   * Record an image generation operation.
   *
   * Updates `llm4s_image_generations_total` and
   * `llm4s_image_generation_duration_seconds`. Successful operations with
   * images also update `llm4s_images_generated_total`; error outcomes update
   * `llm4s_errors_total` and `llm4s_image_generation_errors_total`.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def observeImageGeneration(
    provider: String,
    model: String,
    operation: String,
    outcome: Outcome,
    duration: FiniteDuration,
    imageCount: Int
  ): Unit =
    Try {
      val status = outcome match {
        case Outcome.Success          => "success"
        case Outcome.Error(errorKind) => s"error_${errorKindToLabel(errorKind)}"
      }

      imageGenerationsTotal.labelValues(provider, model, operation, status).inc()
      imageGenerationDuration.labelValues(provider, model, operation).observe(duration.toMillis / 1000.0)

      if (outcome == Outcome.Success && imageCount > 0) {
        imagesGeneratedTotal.labelValues(provider, model).inc(imageCount.toDouble)
      }

      outcome match {
        case Outcome.Error(errorKind) =>
          val errorLabel = errorKindToLabel(errorKind)
          errorsTotal.labelValues(provider, errorLabel).inc()
          imageGenerationErrorsTotal.labelValues(provider, model, operation, errorLabel).inc()
        case _ =>
      }
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record image generation metrics: ${e.getMessage}")
    }

  /**
   * Record estimated image generation cost in USD.
   *
   * Updates `llm4s_image_generation_cost_usd_total`.
   *
   * Safe: catches and logs any Prometheus errors without propagating.
   */
  override def recordImageGenerationCost(
    provider: String,
    model: String,
    costUsd: Double,
    imageCount: Int
  ): Unit =
    Try {
      imageGenerationCostUsdTotal.labelValues(provider, model).inc(costUsd)
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record image generation cost metrics: ${e.getMessage}")
    }

  // ---- generic metrics ----

  private val labels     = new MetricLabels()
  private val counters   = new ConcurrentHashMap[String, Registered[Counter]]()
  private val histograms = new ConcurrentHashMap[String, Registered[Histogram]]()
  private val gauges     = new ConcurrentHashMap[String, Registered[Gauge]]()

  /** A generic metric's family, as registered for the definition first recorded under its name. */
  final private case class Registered[F](metric: Metric, family: Try[F])

  /**
   * The family registered in `families` for `metric`, registering it with `build` - given the Prometheus name - on its
   * first recording; `None` (logged) when another definition holds its name or the registry refused it.
   */
  private def family[F](families: ConcurrentHashMap[String, Registered[F]], metric: Metric)(
    build: String => F
  ): Option[F] =
    families.computeIfAbsent(metric.name, _ => Registered(metric, Try(build(metric.name.replace('.', '_'))))) match {
      case Registered(m, Success(f)) if m == metric => Some(f)
      case Registered(m, Success(_)) =>
        logger.warn(s"Metric ${metric.name} is already registered with another definition ($m); dropping the recording")
        None
      case Registered(_, Failure(e)) =>
        logger.warn(s"Metric ${metric.name} could not be registered: ${e.getMessage}")
        None
    }

  private def generic(metric: Metric, values: Seq[String])(record: Array[String] => Unit): Unit =
    Try {
      labels(metric, values) match {
        case Some(admitted) => record(admitted.toArray)
        case None =>
          logger.warn(
            s"Metric ${metric.name} takes ${metric.labelNames.size} label values " +
              s"(${metric.labelNames.mkString(", ")}), was given ${Option(values).fold(0)(_.size)}; dropping the recording"
          )
      }
    }.recover { case e: Exception =>
      logger.warn(s"Failed to record metric ${metric.name}: ${e.getMessage}")
    }: Unit

  /**
   * Adds `amount` to counter `metric`, registered on its first recording and exposed as `<name>_total`. A negative or
   * non-finite amount is dropped.
   */
  override def increment(metric: Metric.Counter, labelValues: Seq[String], amount: Double): Unit =
    if (amount.isNaN || amount.isInfinite || amount < 0) ()
    else
      generic(metric, labelValues) { values =>
        family(counters, metric)(name =>
          Counter.builder().name(name).help(metric.help).labelNames(metric.labelNames*).register(registry)
        ).foreach(_.labelValues(values*).inc(amount))
      }

  /** Records `duration` in timer `metric`, registered on its first recording as a histogram `<name>_seconds`. */
  override def observe(metric: Metric.Timer, labelValues: Seq[String], duration: FiniteDuration): Unit =
    generic(metric, labelValues) { values =>
      family(histograms, metric)(name =>
        Histogram
          .builder()
          .name(s"${name}_seconds")
          .help(metric.help)
          .labelNames(metric.labelNames*)
          .classicUpperBounds(metric.buckets.map(_.toNanos / 1e9)*)
          .register(registry)
      ).foreach(_.labelValues(values*).observe(duration.toNanos / 1e9))
    }

  /** Sets gauge `metric`, registered on its first recording under its name. A non-finite value is dropped. */
  override def set(metric: Metric.Gauge, labelValues: Seq[String], value: Double): Unit =
    if (value.isNaN || value.isInfinite) ()
    else
      generic(metric, labelValues) { values =>
        family(gauges, metric)(name =>
          Gauge.builder().name(name).help(metric.help).labelNames(metric.labelNames*).register(registry)
        ).foreach(_.labelValues(values*).set(value))
      }
}

object PrometheusMetrics {

  /**
   * Create a new PrometheusMetrics instance with a fresh registry.
   *
   * Use this when you want an isolated metrics collector, e.g., for testing
   * or when you'll expose metrics through your own HTTP endpoint.
   *
   * @return New PrometheusMetrics instance
   */
  def create(): PrometheusMetrics = {
    val registry = new PrometheusRegistry()
    new PrometheusMetrics(registry)
  }
}
