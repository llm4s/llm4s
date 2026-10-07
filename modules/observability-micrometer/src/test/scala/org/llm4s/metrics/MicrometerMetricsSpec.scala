package org.llm4s.metrics

import io.micrometer.core.instrument.config.MeterFilter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.micrometer.core.instrument.{ Meter, MeterRegistry, Timer }
import org.llm4s.error.{ AuthenticationError, CancelledError, NetworkError, RateLimitError, ServiceError, TimeoutError }
import org.llm4s.llmconnect.model.TokenUsage
import org.llm4s.llmconnect.provider.MetricsRecording
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.TimeUnit
import scala.concurrent.duration.*
import scala.concurrent.{ Await, ExecutionContext, Future }

class MicrometerMetricsSpec extends AnyFlatSpec with Matchers {

  import MicrometerMetrics.Names

  private def fresh(): (SimpleMeterRegistry, MicrometerMetrics) = {
    val registry = new SimpleMeterRegistry()
    (registry, new MicrometerMetrics(registry))
  }

  private def flat(tags: Seq[(String, String)]): Seq[String] = tags.flatMap { case (k, v) => Seq(k, v) }

  private def counter(registry: MeterRegistry, name: String, tags: (String, String)*): Option[Double] =
    Option(registry.find(name).tags(flat(tags)*).counter()).map(_.count())

  private def timer(registry: MeterRegistry, name: String, tags: (String, String)*): Option[Timer] =
    Option(registry.find(name).tags(flat(tags)*).timer())

  private def meters(registry: MeterRegistry, name: String): Int = registry.find(name).meters().size()

  private def tagKeys(registry: MeterRegistry, name: String): Set[String] = {
    import scala.jdk.CollectionConverters.*
    registry.find(name).meters().asScala.flatMap(_.getId.getTags.asScala.map(_.getKey)).toSet
  }

  private val allKinds: Seq[(ErrorKind, String)] = Seq(
    ErrorKind.RateLimit      -> "rate_limit",
    ErrorKind.Timeout        -> "timeout",
    ErrorKind.Authentication -> "authentication",
    ErrorKind.Network        -> "network",
    ErrorKind.Validation     -> "validation",
    ErrorKind.ServiceError   -> "service_error",
    ErrorKind.ExecutionError -> "execution_error",
    ErrorKind.Cancelled      -> "cancelled",
    ErrorKind.Unknown        -> "unknown"
  )

  // --- construction -----------------------------------------------------------------------------

  "MicrometerMetrics" should "refuse a null registry" in {
    an[IllegalArgumentException] should be thrownBy new MicrometerMetrics(null)
  }

  it should "override every method of the MetricsCollector contract" in {
    // A method added to the contract with a no-op default would be silently ignored here, so this fails
    // until someone decides what the Micrometer collector should do with it.
    // Instance methods only: the trait's companion adds static forwarders (`compose`, `noop`) that are not contract.
    def methods(c: Class[?]): Set[String] =
      c.getDeclaredMethods
        .filterNot(m => java.lang.reflect.Modifier.isStatic(m.getModifiers) || m.getName.contains("$"))
        .map(_.getName)
        .toSet
    val contract = methods(classOf[MetricsCollector])
    (contract should contain).allOf("observeRequest", "addTokens", "recordCost", "recordImageGenerationCost")
    (contract -- methods(classOf[MicrometerMetrics])) shouldBe empty
  }

  it should "keep two collectors on two registries independent" in {
    val (r1, m1) = fresh()
    val (r2, _)  = fresh()
    m1.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    counter(r1, Names.Requests, "provider" -> "openai") shouldBe Some(1.0)
    r2.getMeters.size() shouldBe 0
  }

  // --- observeRequest ---------------------------------------------------------------------------

  "observeRequest" should "count a successful request and time it" in {
    val (registry, metrics) = fresh()
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1500.millis)

    counter(registry, Names.Requests, "provider" -> "openai", "model" -> "gpt-4o", "status" -> "success") shouldBe Some(
      1.0
    )
    val t = timer(registry, Names.RequestDuration, "provider" -> "openai", "model" -> "gpt-4o").value
    t.count() shouldBe 1L
    t.totalTime(TimeUnit.NANOSECONDS) shouldBe 1.5e9
  }

  it should "record no error meter for a success" in {
    val (registry, metrics) = fresh()
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 10.millis)
    meters(registry, Names.Errors) shouldBe 0
  }

  it should "add up requests and durations" in {
    val (registry, metrics) = fresh()
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 2.seconds)
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 3.seconds)

    counter(registry, Names.Requests, "status" -> "success") shouldBe Some(3.0)
    val t = timer(registry, Names.RequestDuration).value
    t.count() shouldBe 3L
    t.totalTime(TimeUnit.SECONDS) shouldBe 6.0
  }

  it should "keep different providers and models apart" in {
    val (registry, metrics) = fresh()
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    metrics.observeRequest("openai", "gpt-4o-mini", Outcome.Success, 1.second)
    metrics.observeRequest("anthropic", "claude", Outcome.Success, 1.second)

    counter(registry, Names.Requests, "provider" -> "openai", "model" -> "gpt-4o") shouldBe Some(1.0)
    counter(registry, Names.Requests, "provider" -> "openai", "model" -> "gpt-4o-mini") shouldBe Some(1.0)
    counter(registry, Names.Requests, "provider" -> "anthropic", "model" -> "claude") shouldBe Some(1.0)
    meters(registry, Names.Requests) shouldBe 3
  }

  allKinds.foreach { case (kind, label) =>
    it should s"label an $kind failure as error_$label and count it under error_type=$label" in {
      val (registry, metrics) = fresh()
      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(kind), 200.millis)

      counter(registry, Names.Requests, "status" -> s"error_$label") shouldBe Some(1.0)
      counter(registry, Names.Errors, "provider" -> "openai", "error_type" -> label) shouldBe Some(1.0)
      timer(registry, Names.RequestDuration).value.count() shouldBe 1L
    }
  }

  it should "cover every ErrorKind with a label" in {
    allKinds.map(_._1).toSet shouldBe Set(
      ErrorKind.RateLimit,
      ErrorKind.Timeout,
      ErrorKind.Authentication,
      ErrorKind.Network,
      ErrorKind.Validation,
      ErrorKind.ServiceError,
      ErrorKind.ExecutionError,
      ErrorKind.Cancelled,
      ErrorKind.Unknown
    )
  }

  it should "use the tags documented for each meter and no others" in {
    val (registry, metrics) = fresh()
    metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.Timeout), 1.second)
    tagKeys(registry, Names.Requests) shouldBe Set("provider", "model", "status")
    tagKeys(registry, Names.RequestDuration) shouldBe Set("provider", "model")
    tagKeys(registry, Names.Errors) shouldBe Set("provider", "error_type")
  }

  it should "record a zero-length request" in {
    val (registry, metrics) = fresh()
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, Duration.Zero)
    timer(registry, Names.RequestDuration).value.count() shouldBe 1L
  }

  it should "use `unknown` for a null or blank provider or model" in {
    val (registry, metrics) = fresh()
    metrics.observeRequest(null, "  ", Outcome.Success, 1.second)
    counter(
      registry,
      Names.Requests,
      "provider" -> "unknown",
      "model"    -> "unknown",
      "status"   -> "success"
    ) shouldBe Some(1.0)
  }

  // --- addTokens --------------------------------------------------------------------------------

  "addTokens" should "count input and output tokens under the type tag" in {
    val (registry, metrics) = fresh()
    metrics.addTokens("openai", "gpt-4o", 100L, 40L)

    counter(registry, Names.Tokens, "provider" -> "openai", "model" -> "gpt-4o", "type" -> "input") shouldBe Some(100.0)
    counter(registry, Names.Tokens, "provider" -> "openai", "model" -> "gpt-4o", "type" -> "output") shouldBe Some(40.0)
    tagKeys(registry, Names.Tokens) shouldBe Set("provider", "model", "type")
  }

  it should "accumulate across calls" in {
    val (registry, metrics) = fresh()
    metrics.addTokens("openai", "gpt-4o", 10L, 1L)
    metrics.addTokens("openai", "gpt-4o", 20L, 2L)
    counter(registry, Names.Tokens, "type" -> "input") shouldBe Some(30.0)
    counter(registry, Names.Tokens, "type" -> "output") shouldBe Some(3.0)
  }

  it should "create the series for zero tokens" in {
    val (registry, metrics) = fresh()
    metrics.addTokens("openai", "gpt-4o", 0L, 0L)
    counter(registry, Names.Tokens, "type" -> "input") shouldBe Some(0.0)
    counter(registry, Names.Tokens, "type" -> "output") shouldBe Some(0.0)
  }

  it should "drop a negative count and still record the other side" in {
    val (registry, metrics) = fresh()
    metrics.addTokens("openai", "gpt-4o", -5L, 7L)
    counter(registry, Names.Tokens, "type" -> "input") shouldBe None
    counter(registry, Names.Tokens, "type" -> "output") shouldBe Some(7.0)
  }

  it should "keep counts above 2^31 exact" in {
    val (registry, metrics) = fresh()
    metrics.addTokens("openai", "gpt-4o", 5000000000L, 0L)
    counter(registry, Names.Tokens, "type" -> "input") shouldBe Some(5.0e9)
  }

  // --- recordCost -------------------------------------------------------------------------------

  "recordCost" should "add the cost to a counter" in {
    val (registry, metrics) = fresh()
    metrics.recordCost("openai", "gpt-4o", 0.5)
    metrics.recordCost("openai", "gpt-4o", 0.25)
    counter(registry, Names.CostUsd, "provider" -> "openai", "model" -> "gpt-4o") shouldBe Some(0.75)
    tagKeys(registry, Names.CostUsd) shouldBe Set("provider", "model")
  }

  it should "drop a negative, NaN or infinite amount instead of poisoning the counter" in {
    val (registry, metrics) = fresh()
    metrics.recordCost("openai", "gpt-4o", 1.0)
    Seq(-1.0, Double.NaN, Double.PositiveInfinity, Double.NegativeInfinity).foreach(
      metrics.recordCost("openai", "gpt-4o", _)
    )
    counter(registry, Names.CostUsd) shouldBe Some(1.0)
  }

  it should "not create a meter for an invalid amount" in {
    val (registry, metrics) = fresh()
    metrics.recordCost("openai", "gpt-4o", Double.NaN)
    meters(registry, Names.CostUsd) shouldBe 0
  }

  // --- retries, circuit breaker, generic errors -------------------------------------------------

  "recordRetryAttempt" should "count retries per provider, whatever the attempt number" in {
    val (registry, metrics) = fresh()
    metrics.recordRetryAttempt("openai", 1)
    metrics.recordRetryAttempt("openai", 2)
    metrics.recordRetryAttempt("openai", 3)
    metrics.recordRetryAttempt("anthropic", 1)
    counter(registry, Names.Retries, "provider" -> "openai") shouldBe Some(3.0)
    counter(registry, Names.Retries, "provider" -> "anthropic") shouldBe Some(1.0)
    tagKeys(registry, Names.Retries) shouldBe Set("provider")
  }

  "recordCircuitBreakerTransition" should "count each documented state" in {
    val (registry, metrics) = fresh()
    Seq("open", "closed", "half-open").foreach(metrics.recordCircuitBreakerTransition("openai", _))
    Seq("open", "closed", "half-open").foreach { s =>
      counter(registry, Names.CircuitBreakerTransitions, "provider" -> "openai", "state" -> s) shouldBe Some(1.0)
    }
  }

  it should "read a state case-insensitively and ignore surrounding spaces" in {
    val (registry, metrics) = fresh()
    metrics.recordCircuitBreakerTransition("openai", " HALF-OPEN ")
    counter(registry, Names.CircuitBreakerTransitions, "state" -> "half-open") shouldBe Some(1.0)
  }

  it should "record an unknown or null state as `other`" in {
    val (registry, metrics) = fresh()
    metrics.recordCircuitBreakerTransition("openai", "exploded")
    metrics.recordCircuitBreakerTransition("openai", null)
    counter(registry, Names.CircuitBreakerTransitions, "state" -> "other") shouldBe Some(2.0)
  }

  "recordError" should "count a failure outside a timed request under error_type" in {
    val (registry, metrics) = fresh()
    metrics.recordError(ErrorKind.Validation, "openai")
    counter(registry, Names.Errors, "provider" -> "openai", "error_type" -> "validation") shouldBe Some(1.0)
    meters(registry, Names.Requests) shouldBe 0
  }

  // --- images -----------------------------------------------------------------------------------

  "observeImageGeneration" should "count, time and total the images of a successful operation" in {
    val (registry, metrics) = fresh()
    metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 4.seconds, 3)

    counter(
      registry,
      Names.ImageGenerations,
      "provider"  -> "openai",
      "model"     -> "dall-e-3",
      "operation" -> "generate",
      "status"    -> "success"
    ) shouldBe Some(1.0)
    val t = timer(registry, Names.ImageGenerationDuration, "operation" -> "generate").value
    t.count() shouldBe 1L
    t.totalTime(TimeUnit.SECONDS) shouldBe 4.0
    counter(registry, Names.ImagesGenerated, "provider" -> "openai", "model" -> "dall-e-3") shouldBe Some(3.0)
    meters(registry, Names.ImageGenerationErrors) shouldBe 0
    meters(registry, Names.Errors) shouldBe 0
  }

  it should "not count images for a success that produced none" in {
    val (registry, metrics) = fresh()
    metrics.observeImageGeneration("openai", "dall-e-3", "edit", Outcome.Success, 1.second, 0)
    meters(registry, Names.ImagesGenerated) shouldBe 0
    counter(registry, Names.ImageGenerations, "operation" -> "edit") shouldBe Some(1.0)
  }

  it should "count a failure under both error meters and add no images" in {
    val (registry, metrics) = fresh()
    metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Error(ErrorKind.RateLimit), 1.second, 5)

    counter(registry, Names.ImageGenerations, "status" -> "error_rate_limit") shouldBe Some(1.0)
    counter(
      registry,
      Names.ImageGenerationErrors,
      "provider"   -> "openai",
      "model"      -> "dall-e-3",
      "operation"  -> "generate",
      "error_type" -> "rate_limit"
    ) shouldBe Some(1.0)
    counter(registry, Names.Errors, "provider" -> "openai", "error_type" -> "rate_limit") shouldBe Some(1.0)
    meters(registry, Names.ImagesGenerated) shouldBe 0
  }

  it should "record an operation other than generate or edit as `other`" in {
    val (registry, metrics) = fresh()
    metrics.observeImageGeneration("openai", "dall-e-3", "variation", Outcome.Success, 1.second, 1)
    metrics.observeImageGeneration("openai", "dall-e-3", null, Outcome.Success, 1.second, 1)
    metrics.observeImageGeneration("openai", "dall-e-3", " EDIT ", Outcome.Success, 1.second, 1)
    counter(registry, Names.ImageGenerations, "operation" -> "other") shouldBe Some(2.0)
    counter(registry, Names.ImageGenerations, "operation" -> "edit") shouldBe Some(1.0)
  }

  it should "use the tags documented for each image meter" in {
    val (registry, metrics) = fresh()
    metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Error(ErrorKind.Network), 1.second, 1)
    metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 1.second, 1)
    tagKeys(registry, Names.ImageGenerations) shouldBe Set("provider", "model", "operation", "status")
    tagKeys(registry, Names.ImageGenerationDuration) shouldBe Set("provider", "model", "operation")
    tagKeys(registry, Names.ImagesGenerated) shouldBe Set("provider", "model")
    tagKeys(registry, Names.ImageGenerationErrors) shouldBe Set("provider", "model", "operation", "error_type")
  }

  "recordImageGenerationCost" should "add the cost and drop an invalid amount" in {
    val (registry, metrics) = fresh()
    metrics.recordImageGenerationCost("openai", "dall-e-3", 0.04, 1)
    metrics.recordImageGenerationCost("openai", "dall-e-3", 0.04, 1)
    metrics.recordImageGenerationCost("openai", "dall-e-3", -1.0, 1)
    metrics.recordImageGenerationCost("openai", "dall-e-3", Double.NaN, 1)
    counter(registry, Names.ImageGenerationCostUsd, "provider" -> "openai", "model" -> "dall-e-3") shouldBe Some(0.08)
  }

  // --- safety, cardinality, concurrency ---------------------------------------------------------

  "A collector" should "never throw when the registry rejects a meter" in {
    val registry = new SimpleMeterRegistry()
    registry
      .config()
      .meterFilter(new MeterFilter {
        override def map(id: Meter.Id): Meter.Id = throw new IllegalStateException("the filter refuses")
      })
    val metrics = new MicrometerMetrics(registry)

    noException should be thrownBy {
      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.Timeout), 1.second)
      metrics.addTokens("openai", "gpt-4o", 1L, 1L)
      metrics.recordCost("openai", "gpt-4o", 1.0)
      metrics.recordRetryAttempt("openai", 1)
      metrics.recordCircuitBreakerTransition("openai", "open")
      metrics.recordError(ErrorKind.Network, "openai")
      metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 1.second, 1)
      metrics.recordImageGenerationCost("openai", "dall-e-3", 1.0, 1)
    }
    registry.getMeters.size() shouldBe 0
  }

  it should "record nothing, and not throw, when a filter denies its meters" in {
    val registry = new SimpleMeterRegistry()
    registry.config().meterFilter(MeterFilter.deny())
    val metrics = new MicrometerMetrics(registry)
    noException should be thrownBy metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    registry.getMeters.size() shouldBe 0
  }

  it should "stay within a fixed set of meters however many distinct states and operations it is given" in {
    val (registry, metrics) = fresh()
    (1 to 1000).foreach { i =>
      metrics.recordCircuitBreakerTransition("openai", s"state-$i")
      metrics.observeImageGeneration("openai", "dall-e-3", s"operation-$i", Outcome.Success, 1.millis, 1)
    }
    Seq("open", "closed", "half-open").foreach(metrics.recordCircuitBreakerTransition("openai", _))
    meters(registry, Names.CircuitBreakerTransitions) shouldBe 4 // open, closed, half-open, other
    meters(registry, Names.ImageGenerations) shouldBe 1          // all `other`
    meters(registry, Names.ImageGenerationDuration) shouldBe 1
  }

  it should "add up exactly under concurrent use" in {
    val (registry, metrics) = fresh()
    given ExecutionContext  = ExecutionContext.global
    val threads             = 8
    val callsPerThread      = 5000
    val work = Future.traverse((1 to threads).toList) { _ =>
      Future {
        (1 to callsPerThread).foreach { _ =>
          metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.millis)
          metrics.addTokens("openai", "gpt-4o", 2L, 1L)
        }
      }
    }
    Await.result(work, 60.seconds)

    val total = (threads * callsPerThread).toDouble
    counter(registry, Names.Requests, "status" -> "success") shouldBe Some(total)
    timer(registry, Names.RequestDuration).value.count() shouldBe total.toLong
    counter(registry, Names.Tokens, "type" -> "input") shouldBe Some(total * 2)
    counter(registry, Names.Tokens, "type" -> "output") shouldBe Some(total)
  }

  it should "work next to another collector through MetricsCollector.compose" in {
    val (r1, m1) = fresh()
    val (r2, m2) = fresh()
    val combined = MetricsCollector.compose(m1, m2)
    combined.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    counter(r1, Names.Requests) shouldBe Some(1.0)
    counter(r2, Names.Requests) shouldBe Some(1.0)
  }

  // --- through the code every provider client uses ----------------------------------------------

  /** A client in the shape of a provider client: `MetricsRecording.withMetrics` is what they all call. */
  final private class ProbeClient(protected val metrics: MetricsCollector) extends MetricsRecording {
    def call[A](
      operation: => Result[A],
      usage: A => Option[TokenUsage] = (_: A) => None,
      cost: A => Option[Double] = (_: A) => None
    ): Result[A] = withMetrics("probe", "probe-model", operation, usage, cost)
  }

  "Through MetricsRecording" should "record a successful call with its tokens and cost" in {
    val (registry, metrics) = fresh()
    val client              = new ProbeClient(metrics)
    val result = client.call[String](
      Right("ok"),
      _ => Some(TokenUsage(promptTokens = 120, completionTokens = 30, totalTokens = 150)),
      _ => Some(0.02)
    )

    result shouldBe Right("ok")
    counter(
      registry,
      Names.Requests,
      "provider" -> "probe",
      "model"    -> "probe-model",
      "status"   -> "success"
    ) shouldBe Some(1.0)
    timer(registry, Names.RequestDuration, "provider" -> "probe").value.count() shouldBe 1L
    counter(registry, Names.Tokens, "provider" -> "probe", "type" -> "input") shouldBe Some(120.0)
    counter(registry, Names.Tokens, "provider" -> "probe", "type" -> "output") shouldBe Some(30.0)
    counter(registry, Names.CostUsd, "provider" -> "probe") shouldBe Some(0.02)
  }

  it should "classify each kind of LLMError the way ErrorKind.fromLLMError does" in {
    val failures = Seq(
      RateLimitError("probe", 1.second)                -> "rate_limit",
      TimeoutError("timed out", 1.second, "probe-op")  -> "timeout",
      AuthenticationError("probe", "bad key")          -> "authentication",
      NetworkError("connection failed", None, "probe") -> "network",
      ServiceError(500, "probe", "server error")       -> "service_error",
      CancelledError("probe-op")                       -> "cancelled"
    )
    failures.foreach { case (error, label) =>
      val (registry, metrics) = fresh()
      new ProbeClient(metrics).call[String](Left(error)) shouldBe Left(error)
      counter(registry, Names.Requests, "status" -> s"error_$label") shouldBe Some(1.0)
      counter(registry, Names.Errors, "error_type" -> label) shouldBe Some(1.0)
      meters(registry, Names.Tokens) shouldBe 0
    }
  }

  implicit private class OptionOps[A](private val option: Option[A]) {
    def value: A = option.getOrElse(fail("expected the meter to exist"))
  }
}
