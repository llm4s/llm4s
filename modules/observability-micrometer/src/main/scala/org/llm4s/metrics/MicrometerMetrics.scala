package org.llm4s.metrics

import io.micrometer.core.instrument.{ Counter, MeterRegistry, Tag, Tags, Timer }
import org.slf4j.LoggerFactory

import java.time.Duration
import java.util.concurrent.{ ConcurrentHashMap, TimeUnit }
import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.duration.FiniteDuration
import scala.util.{ Failure, Success, Try }

/**
 * A [[MetricsCollector]] that records into a Micrometer [[io.micrometer.core.instrument.MeterRegistry]].
 *
 * Use it when the application already has a registry (Spring Boot's Actuator registry, a Quarkus registry, a
 * `PrometheusMeterRegistry` of your own) and the LLM metrics should appear next to the rest:
 * {{{
 * val metrics = new MicrometerMetrics(registry)
 * val client  = LLMConnect.getClient(config, metrics)
 * }}}
 *
 * The meter names follow the series that `llm4s-observability-prometheus` exposes, so a dashboard built on
 * `llm4s_requests_total` or `llm4s_request_duration_seconds` keeps working when the registry is exported as
 * Prometheus (Micrometer's Prometheus naming turns `llm4s.requests` into `llm4s_requests_total`). See
 * [[MicrometerMetrics.Names]] for every meter and its tags; the full table is in the monitoring guide.
 *
 * Properties:
 *  - No global state: the registry is the only state, and two collectors on two registries are independent.
 *  - Safe: like every [[MetricsCollector]], no method throws. A failure inside Micrometer (for example a
 *    `MeterFilter` that rejects a meter) is logged at WARN and dropped.
 *  - Bounded tag cardinality: tags are provider, model, a fixed outcome or error label, a fixed token type,
 *    and a fixed set of image operations and circuit-breaker states. Anything outside those sets is
 *    recorded as `other`, and a blank provider or model as `unknown`. Prompts, user ids, request ids and
 *    other unbounded values are never tags. `provider` and `model` come from the caller, so they are capped
 *    too: after 64 distinct providers or 256 distinct models on one collector, further values are recorded
 *    as `other` (logged once at WARN), so arbitrary model strings cannot grow the registry without limit.
 *  - Thread-safe: Micrometer meters are, and this class holds no mutable state.
 *
 * Differences from `PrometheusMetrics`: this class also implements `recordRetryAttempt`,
 * `recordCircuitBreakerTransition` and `recordError`, which the Prometheus collector leaves as no-ops, and it
 * ignores negative or non-finite amounts instead of passing them to the registry.
 *
 * This module is Beta: the meter names are intended to be stable, but they are not covered by the 1.x
 * compatibility promise.
 *
 * @param registry the registry to record into; must not be null
 */
final class MicrometerMetrics(registry: MeterRegistry) extends MetricsCollector {
  require(registry != null, "registry must not be null")

  import MicrometerMetrics.*

  private val logger = LoggerFactory.getLogger(getClass)

  // Caps on the distinct provider and model tag values this collector creates meters for; see `BoundedLabels`.
  private val providers = new BoundedLabels("provider", MaxProviderValues)
  private val models    = new BoundedLabels("model", MaxModelValues)

  private def providerLabel(value: String): String = providers.admit(label(value))
  private def modelLabel(value: String): String    = models.admit(label(value))

  override def observeRequest(
    provider: String,
    model: String,
    outcome: Outcome,
    duration: FiniteDuration
  ): Unit =
    safely("request") {
      val p = providerLabel(provider)
      val m = modelLabel(model)
      counter(
        Names.Requests,
        "Total number of LLM requests",
        Tags.of(ProviderTag, p, ModelTag, m, StatusTag, status(outcome))
      )
        .increment()
      timer(Names.RequestDuration, "Request duration", RequestBuckets, Tags.of(ProviderTag, p, ModelTag, m))
        .record(duration.toNanos, TimeUnit.NANOSECONDS)
      outcome match {
        case Outcome.Error(kind) => countError(p, kind)
        case Outcome.Success     => ()
      }
    }

  override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit =
    safely("token") {
      val tags = Tags.of(ProviderTag, providerLabel(provider), ModelTag, modelLabel(model))
      if (inputTokens >= 0) tokens(tags, "input", inputTokens.toDouble)
      if (outputTokens >= 0) tokens(tags, "output", outputTokens.toDouble)
    }

  override def recordCost(provider: String, model: String, costUsd: Double): Unit =
    safely("cost") {
      if (isAmount(costUsd))
        counter(
          Names.CostUsd,
          "Total estimated cost in USD",
          Tags.of(ProviderTag, providerLabel(provider), ModelTag, modelLabel(model))
        )
          .increment(costUsd)
    }

  override def recordRetryAttempt(provider: String, attemptNumber: Int): Unit =
    safely("retry") {
      counter(Names.Retries, "Total number of retry attempts", Tags.of(ProviderTag, providerLabel(provider)))
        .increment()
    }

  override def recordCircuitBreakerTransition(provider: String, newState: String): Unit =
    safely("circuit breaker") {
      counter(
        Names.CircuitBreakerTransitions,
        "Total number of circuit breaker state transitions",
        Tags.of(ProviderTag, providerLabel(provider), StateTag, circuitState(newState))
      ).increment()
    }

  override def recordError(errorKind: ErrorKind, provider: String): Unit =
    safely("error") {
      countError(providerLabel(provider), errorKind)
    }

  override def observeImageGeneration(
    provider: String,
    model: String,
    operation: String,
    outcome: Outcome,
    duration: FiniteDuration,
    imageCount: Int
  ): Unit =
    safely("image generation") {
      val p  = providerLabel(provider)
      val m  = modelLabel(model)
      val op = imageOperation(operation)
      counter(
        Names.ImageGenerations,
        "Total number of image generation operations",
        Tags.of(ProviderTag, p, ModelTag, m, OperationTag, op, StatusTag, status(outcome))
      ).increment()
      timer(
        Names.ImageGenerationDuration,
        "Image generation duration",
        ImageBuckets,
        Tags.of(ProviderTag, p, ModelTag, m, OperationTag, op)
      ).record(duration.toNanos, TimeUnit.NANOSECONDS)
      outcome match {
        case Outcome.Success =>
          if (imageCount > 0)
            counter(Names.ImagesGenerated, "Total number of images generated", Tags.of(ProviderTag, p, ModelTag, m))
              .increment(imageCount.toDouble)
        case Outcome.Error(kind) =>
          countError(p, kind)
          counter(
            Names.ImageGenerationErrors,
            "Total number of image generation errors",
            Tags.of(ProviderTag, p, ModelTag, m, OperationTag, op, ErrorTypeTag, errorLabel(kind))
          ).increment()
      }
    }

  override def recordImageGenerationCost(provider: String, model: String, costUsd: Double, imageCount: Int): Unit =
    safely("image generation cost") {
      if (isAmount(costUsd))
        counter(
          Names.ImageGenerationCostUsd,
          "Total estimated image generation cost in USD",
          Tags.of(ProviderTag, providerLabel(provider), ModelTag, modelLabel(model))
        ).increment(costUsd)
    }

  private def tokens(base: Tags, tokenType: String, amount: Double): Unit =
    counter(Names.Tokens, "Total tokens consumed", base.and(Tag.of(TypeTag, tokenType))).increment(amount)

  private def countError(provider: String, kind: ErrorKind): Unit =
    counter(Names.Errors, "Total number of errors", Tags.of(ProviderTag, provider, ErrorTypeTag, errorLabel(kind)))
      .increment()

  private def counter(name: String, description: String, tags: Tags): Counter =
    Counter.builder(name).description(description).tags(tags).register(registry)

  private def timer(name: String, description: String, buckets: Seq[Duration], tags: Tags): Timer =
    Timer
      .builder(name)
      .description(description)
      .serviceLevelObjectives(buckets*)
      .tags(tags)
      .register(registry)

  private def safely(what: String)(body: => Unit): Unit =
    Try(body) match {
      case Failure(e) => logger.warn(s"Failed to record $what metrics: ${e.getMessage}")
      case Success(_) => ()
    }
}

object MicrometerMetrics {

  /**
   * The meter names, as given to Micrometer. With the Prometheus naming convention a counter gains `_total` and
   * a timer gains `_seconds`, which is how `llm4s.requests` becomes `llm4s_requests_total`.
   */
  object Names {

    /** Counter. Tags: `provider`, `model`, `status` (`success` or `error_<kind>`). */
    final val Requests = "llm4s.requests"

    /** Timer with the same buckets as `llm4s_request_duration_seconds`. Tags: `provider`, `model`. */
    final val RequestDuration = "llm4s.request.duration"

    /** Counter of tokens. Tags: `provider`, `model`, `type` (`input` or `output`). */
    final val Tokens = "llm4s.tokens"

    /** Counter of estimated cost in USD. Tags: `provider`, `model`. */
    final val CostUsd = "llm4s.cost.usd"

    /** Counter. Tags: `provider`, `error_type` (see [[ErrorKind]], in snake case). */
    final val Errors = "llm4s.errors"

    /** Counter of retry attempts (not in the Prometheus module). Tags: `provider`. */
    final val Retries = "llm4s.retries"

    /**
     * Counter of circuit-breaker transitions (not in the Prometheus module). Tags: `provider`, `state`
     * (`open`, `closed`, `half-open` or `other`).
     */
    final val CircuitBreakerTransitions = "llm4s.circuit.breaker.transitions"

    /** Counter. Tags: `provider`, `model`, `operation` (`generate`, `edit` or `other`), `status`. */
    final val ImageGenerations = "llm4s.image.generations"

    /** Counter of generated images. Tags: `provider`, `model`. */
    final val ImagesGenerated = "llm4s.images.generated"

    /** Timer. Tags: `provider`, `model`, `operation`. */
    final val ImageGenerationDuration = "llm4s.image.generation.duration"

    /** Counter of estimated image generation cost in USD. Tags: `provider`, `model`. */
    final val ImageGenerationCostUsd = "llm4s.image.generation.cost.usd"

    /** Counter. Tags: `provider`, `model`, `operation`, `error_type`. */
    final val ImageGenerationErrors = "llm4s.image.generation.errors"
  }

  private val ProviderTag  = "provider"
  private val ModelTag     = "model"
  private val StatusTag    = "status"
  private val TypeTag      = "type"
  private val ErrorTypeTag = "error_type"
  private val OperationTag = "operation"
  private val StateTag     = "state"

  private val Unknown = "unknown"
  private val Other   = "other"

  // Generous enough for every provider llm4s ships and any realistic model roster, small enough that a caller
  // passing unbounded strings (a per-request model id, a prompt by mistake) cannot grow the registry without limit.
  private[metrics] val MaxProviderValues = 64
  private[metrics] val MaxModelValues    = 256

  /**
   * The distinct values one tag has taken on this collector. The first `limit` values are recorded as given; any
   * further value is recorded as `other`, and the first such value is logged once at WARN. A value already admitted
   * stays admitted, so a busy set of models keeps its own series.
   */
  final private class BoundedLabels(tag: String, limit: Int) {
    private val seen   = ConcurrentHashMap.newKeySet[String]()
    private val warned = new AtomicBoolean(false)

    def admit(value: String): String =
      if (seen.contains(value)) value
      else
        synchronized {
          if (seen.contains(value) || (seen.size < limit && seen.add(value))) value
          else {
            if (warned.compareAndSet(false, true))
              LoggerFactory
                .getLogger(classOf[MicrometerMetrics])
                .warn(
                  s"More than $limit distinct '$tag' tag values; further values are recorded as '$Other'. " +
                    "Pass bounded provider and model names to the client."
                )
            Other
          }
        }
  }

  // The classic buckets `PrometheusMetrics` uses, so `_bucket` series line up.
  private val RequestBuckets: Seq[Duration] =
    Seq(0.1, 0.5, 1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0).map(seconds)
  private val ImageBuckets: Seq[Duration] =
    Seq(1.0, 2.0, 5.0, 10.0, 30.0, 60.0, 120.0, 300.0).map(seconds)

  private def seconds(s: Double): Duration = Duration.ofMillis(Math.round(s * 1000.0))

  /** A tag value: the text itself, or `unknown` when it is null or blank (Micrometer rejects null tag values). */
  private def label(value: String): String =
    if (value == null || value.trim.isEmpty) Unknown else value

  private def status(outcome: Outcome): String = outcome match {
    case Outcome.Success     => "success"
    case Outcome.Error(kind) => s"error_${errorLabel(kind)}"
  }

  // Exhaustive on purpose: a new ErrorKind in core makes this module stop compiling, rather than
  // being recorded under a label nobody chose.
  private def errorLabel(kind: ErrorKind): String = kind match {
    case ErrorKind.RateLimit      => "rate_limit"
    case ErrorKind.Timeout        => "timeout"
    case ErrorKind.Authentication => "authentication"
    case ErrorKind.Network        => "network"
    case ErrorKind.Validation     => "validation"
    case ErrorKind.ServiceError   => "service_error"
    case ErrorKind.ExecutionError => "execution_error"
    case ErrorKind.Cancelled      => "cancelled"
    case ErrorKind.Unknown        => "unknown"
  }

  private def imageOperation(operation: String): String =
    if (operation == null) Other
    else
      operation.trim.toLowerCase(java.util.Locale.ROOT) match {
        case "generate" => "generate"
        case "edit"     => "edit"
        case _          => Other
      }

  private def circuitState(state: String): String =
    if (state == null) Other
    else
      state.trim.toLowerCase(java.util.Locale.ROOT) match {
        case "open"      => "open"
        case "closed"    => "closed"
        case "half-open" => "half-open"
        case _           => Other
      }

  /** A counter may only grow: a negative, NaN or infinite amount is dropped. */
  private def isAmount(value: Double): Boolean = !value.isNaN && !value.isInfinite && value >= 0.0
}
