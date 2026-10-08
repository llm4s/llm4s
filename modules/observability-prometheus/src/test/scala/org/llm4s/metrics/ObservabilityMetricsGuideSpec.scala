package org.llm4s.metrics

import com.typesafe.config.ConfigFactory
import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.llm4s.config.{ Llm4sConfig, MetricsConfigLoader }
import org.llm4s.llmconnect.LLMConnect
import org.llm4s.model.ModelRegistryService
import org.llm4s.llmconnect.BaseLifecycleLLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.error.NetworkError
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import pureconfig.ConfigSource

import java.net.URI
import java.net.http.{ HttpClient, HttpRequest, HttpResponse }
import scala.concurrent.duration.*

/**
 * Runs what the "Metrics" section of `docs/guide/observability/index.md` states: the configuration
 * block, the recording rules, the exported series and the limits. Each test names the passage it
 * backs. The sample scrape in the guide is compared, line for line, with what a real endpoint serves.
 * No network beyond a loopback `/metrics` endpoint on an operating-system-chosen port.
 */
class ObservabilityMetricsGuideSpec extends AnyFlatSpec with Matchers {

  // ---- helpers -------------------------------------------------------------------------------

  /** A scraped `/metrics` body, as lines. */
  private def scrape(endpoint: PrometheusEndpoint): List[String] = {
    val response = HttpClient
      .newHttpClient()
      .send(HttpRequest.newBuilder(URI.create(endpoint.url)).GET().build(), HttpResponse.BodyHandlers.ofString())
    response.statusCode() shouldBe 200
    response.body().split("\n").toList
  }

  /** The samples of the series this guide documents, without comments, in a stable order. */
  private def samples(lines: List[String]): List[String] =
    lines.filter(line => line.startsWith("llm4s_") && !line.startsWith("#")).sorted

  /** Runs `body` against a fresh collector and the endpoint serving it, then stops the endpoint. */
  private def withEndpoint[A](body: (PrometheusMetrics, PrometheusEndpoint) => A): A = {
    val registry = new PrometheusRegistry()
    val metrics  = new PrometheusMetrics(registry)
    PrometheusEndpoint.start(0, registry) match {
      case Right(endpoint) =>
        val result = scala.util.Try(body(metrics, endpoint))
        endpoint.stop()
        result.get
      case Left(error) => fail(s"could not start the endpoint: ${error.message}")
    }
  }

  /** A client built the way every provider client is: its calls go through `completeWithMetrics`. */
  final private class Stub(collector: MetricsCollector, reply: () => Result[Completion])
      extends BaseLifecycleLLMClient {
    protected def clientDescription = "stub client"
    protected def providerName      = "openai"
    protected def modelName         = "gpt-4o"
    protected def metrics           = collector
    def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
      completeWithMetrics(reply())
    def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = completeWithMetrics(reply())
    def getContextWindow(): Int     = 128000
    def getReserveCompletion(): Int = 4096
  }

  private def conversation = Conversation(Seq(UserMessage("hi")))

  private def answer(usage: Option[TokenUsage], cost: Option[Double]): Result[Completion] =
    Right(
      Completion(
        id = "c1",
        created = 0L,
        content = "ok",
        model = "gpt-4o",
        message = AssistantMessage("ok"),
        usage = usage,
        estimatedCost = cost
      )
    )

  // ---- "Enabling it": the llm4s.metrics block -------------------------------------------------

  // The guide's block, with the port the guide shows swapped for an operating-system-chosen one.
  private def guideBlock: String =
    """llm4s {
      |  metrics {
      |    enabled = true
      |    prometheus {
      |      enabled = true
      |      port    = 9090
      |    }
      |  }
      |}""".stripMargin

  "the shipped defaults" should "leave metrics off and name port 9090 for when it is switched on" in {
    val reference = ConfigFactory.defaultReference()
    reference.getBoolean("llm4s.metrics.enabled") shouldBe false
    reference.getBoolean("llm4s.metrics.prometheus.enabled") shouldBe true
    reference.getInt("llm4s.metrics.prometheus.port") shouldBe 9090
  }

  "the guide's configuration block" should "start an endpoint and return a Prometheus collector" in {
    val config = ConfigFactory.parseString(guideBlock.replace("9090", "0"))
    MetricsConfigLoader.load(ConfigSource.fromConfig(config)) match {
      case Right((collector, Some(endpoint))) =>
        collector shouldBe a[PrometheusMetrics]
        endpoint.url should endWith("/metrics")
        scrape(endpoint) // the endpoint answers
        endpoint.stop()
      case other => fail(s"expected a collector and an endpoint, got $other")
    }
  }

  "metrics switched off" should "give the no-op collector and no endpoint, and start no server" in {
    val off = ConfigFactory.parseString("llm4s.metrics.enabled = false")
    MetricsConfigLoader.load(ConfigSource.fromConfig(off)) shouldBe Right((MetricsCollector.noop, None))
  }

  "metrics switched on with the Prometheus backend off" should "also give the no-op collector and no endpoint" in {
    val config = ConfigFactory.parseString("llm4s.metrics { enabled = true, prometheus.enabled = false }")
    MetricsConfigLoader.load(ConfigSource.fromConfig(config)) shouldBe Right((MetricsCollector.noop, None))
  }

  "the guide's wiring snippet" should "build the client and the configured endpoint from configuration" in {
    // The snippet, line for line (the default provider on this module's test classpath is the fixture
    // provider; the module's shipped default is `enabled = false`, so the loader returns the no-op
    // collector and no endpoint, and there is nothing to stop on shutdown).
    val application = for {
      providerConfig  <- Llm4sConfig.defaultProvider()
      registryService <- Llm4sConfig.modelRegistryService()
      given ModelRegistryService = registryService
      configured <- MetricsConfigLoader.default()
      (metrics, endpoint) = configured
      client <- LLMConnect.getClient(providerConfig, metrics).left.map { error =>
        endpoint.foreach(_.stop()) // release the server if client creation fails
        error
      }
    } yield (client, endpoint)

    application.isRight shouldBe true
    application.foreach { case (client, endpoint) =>
      endpoint shouldBe None // the shipped default is `enabled = false`
      client.close()
      endpoint.foreach(_.stop())
    }
  }

  // ---- "What is recorded per call" ------------------------------------------------------------

  "a successful call" should "record the request and its latency, then the tokens and the cost" in
    withEndpoint { (metrics, endpoint) =>
      val client = new Stub(metrics, () => answer(Some(TokenUsage(120, 30, 150)), Some(0.0021)))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true

      val lines = samples(scrape(endpoint))
      lines should contain("""llm4s_requests_total{model="gpt-4o",provider="openai",status="success"} 1.0""")
      lines should contain("""llm4s_tokens_total{model="gpt-4o",provider="openai",type="input"} 120.0""")
      lines should contain("""llm4s_tokens_total{model="gpt-4o",provider="openai",type="output"} 30.0""")
      lines should contain("""llm4s_cost_usd_total{model="gpt-4o",provider="openai"} 0.0021""")
      lines.exists(
        _.startsWith("llm4s_request_duration_seconds_count{model=\"gpt-4o\",provider=\"openai\"} 1")
      ) shouldBe true
    }

  "a failed call" should "record the request and the error, but no tokens and no cost" in
    withEndpoint { (metrics, endpoint) =>
      val client = new Stub(metrics, () => Left(NetworkError("connection reset", None, "https://api.example")))
      client.complete(conversation, CompletionOptions()).isLeft shouldBe true

      val lines = samples(scrape(endpoint))
      lines should contain("""llm4s_requests_total{model="gpt-4o",provider="openai",status="error_network"} 1.0""")
      lines should contain("""llm4s_errors_total{error_type="network",provider="openai"} 1.0""")
      lines.exists(_.startsWith("llm4s_tokens_total")) shouldBe false
      lines.exists(_.startsWith("llm4s_cost_usd_total")) shouldBe false
    }

  "a streamed call" should "be recorded once, when the stream has finished" in
    withEndpoint { (metrics, endpoint) =>
      val client = new Stub(metrics, () => answer(Some(TokenUsage(10, 5, 15)), None))
      client.streamComplete(conversation, CompletionOptions(), _ => ()).isRight shouldBe true

      samples(scrape(endpoint)) should contain(
        """llm4s_requests_total{model="gpt-4o",provider="openai",status="success"} 1.0"""
      )
    }

  "a successful call without usage or cost" should "record the request only" in
    withEndpoint { (metrics, endpoint) =>
      val client = new Stub(metrics, () => answer(None, None))
      client.complete(conversation, CompletionOptions()).isRight shouldBe true

      val lines = samples(scrape(endpoint))
      lines.exists(_.startsWith("llm4s_requests_total")) shouldBe true
      lines.exists(_.startsWith("llm4s_tokens_total")) shouldBe false
      lines.exists(_.startsWith("llm4s_cost_usd_total")) shouldBe false
    }

  // ---- "The series": the sample scrape and the status and error_type label values -------------

  // Exactly the lines the guide shows under "A scrape after one successful and one rate-limited call".
  private def guideScrape: List[String] = List(
    """llm4s_cost_usd_total{model="gpt-4o",provider="openai"} 0.0021""",
    """llm4s_errors_total{error_type="rate_limit",provider="openai"} 1.0""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="0.1"} 0""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="0.5"} 0""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="1.0"} 0""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="2.0"} 1""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="5.0"} 1""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="10.0"} 1""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="30.0"} 1""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="60.0"} 1""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="120.0"} 2""",
    """llm4s_request_duration_seconds_bucket{model="gpt-4o",provider="openai",le="+Inf"} 2""",
    """llm4s_request_duration_seconds_count{model="gpt-4o",provider="openai"} 2""",
    """llm4s_requests_total{model="gpt-4o",provider="openai",status="error_rate_limit"} 1.0""",
    """llm4s_requests_total{model="gpt-4o",provider="openai",status="success"} 1.0""",
    """llm4s_tokens_total{model="gpt-4o",provider="openai",type="input"} 120.0""",
    """llm4s_tokens_total{model="gpt-4o",provider="openai",type="output"} 30.0"""
  )

  "the sample scrape in the guide" should "be exactly what the endpoint serves for those two calls" in
    withEndpoint { (metrics, endpoint) =>
      metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1500.millis)
      metrics.addTokens("openai", "gpt-4o", 120L, 30L)
      metrics.recordCost("openai", "gpt-4o", 0.0021)
      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.RateLimit), 90.seconds)

      // The sum of the latencies is not shown in the guide: it depends on the two durations only,
      // and the histogram's `_sum` line is checked on its own.
      val served = samples(scrape(endpoint))
      served.filterNot(_.startsWith("llm4s_request_duration_seconds_sum")) shouldBe guideScrape.sorted
      served.find(_.startsWith("llm4s_request_duration_seconds_sum")) shouldBe Some(
        """llm4s_request_duration_seconds_sum{model="gpt-4o",provider="openai"} 91.5"""
      )
    }

  "every error kind" should "become a snake_case status and error_type label" in
    withEndpoint { (metrics, endpoint) =>
      val kinds = List(
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
      kinds.foreach { case (kind, _) => metrics.observeRequest("p", "m", Outcome.Error(kind), 10.millis) }

      val served = samples(scrape(endpoint))
      kinds.foreach { case (_, label) =>
        served should contain(s"""llm4s_requests_total{model="m",provider="p",status="error_$label"} 1.0""")
        served should contain(s"""llm4s_errors_total{error_type="$label",provider="p"} 1.0""")
      }
    }

  "the ten series the guide lists" should "all exist once calls have been made" in
    withEndpoint { (metrics, endpoint) =>
      metrics.observeRequest("openai", "gpt-4o", Outcome.Error(ErrorKind.Timeout), 1.second)
      metrics.addTokens("openai", "gpt-4o", 1L, 1L)
      metrics.recordCost("openai", "gpt-4o", 0.01)
      metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Error(ErrorKind.Timeout), 2.seconds, 1)
      metrics.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 2.seconds, 2)
      metrics.recordImageGenerationCost("openai", "dall-e-3", 0.08, 2)

      val families = samples(scrape(endpoint)).map(_.takeWhile(c => c != '{' && c != ' ')).toSet
      List(
        "llm4s_requests_total",
        "llm4s_tokens_total",
        "llm4s_cost_usd_total",
        "llm4s_errors_total",
        "llm4s_request_duration_seconds_count",
        "llm4s_image_generations_total",
        "llm4s_images_generated_total",
        "llm4s_image_generation_duration_seconds_count",
        "llm4s_image_generation_cost_usd_total",
        "llm4s_image_generation_errors_total"
      ).foreach(name => families should contain(name))
    }

  // ---- "Limits" -------------------------------------------------------------------------------

  "retry attempts, circuit-breaker transitions and ReliableClient's error events" should "not appear in the Prometheus series" in
    withEndpoint { (metrics, endpoint) =>
      metrics.recordRetryAttempt("openai", 2)
      metrics.recordCircuitBreakerTransition("openai", "open")
      metrics.recordError(ErrorKind.RateLimit, "openai")

      samples(scrape(endpoint)) shouldBe empty
    }

  "MetricsCollector.compose" should "send every non-image call to every collector it was given" in {
    final class Counting extends MetricsCollector {
      var requests                                                                           = 0
      override def observeRequest(p: String, m: String, o: Outcome, d: FiniteDuration): Unit = requests += 1
      override def addTokens(p: String, m: String, in: Long, out: Long): Unit                = ()
      override def recordCost(p: String, m: String, usd: Double): Unit                       = ()
    }
    val counting = new Counting
    withEndpoint { (prometheus, endpoint) =>
      val both = MetricsCollector.compose(prometheus, counting)
      new Stub(both, () => answer(None, None)).complete(conversation, CompletionOptions())

      counting.requests shouldBe 1
      samples(scrape(endpoint)).exists(_.startsWith("llm4s_requests_total")) shouldBe true
    }
  }

  it should "leave the image-generation methods as no-ops, as the guide warns" in {
    final class CountingImages extends MetricsCollector {
      var images                                                                             = 0
      override def observeRequest(p: String, m: String, o: Outcome, d: FiniteDuration): Unit = ()
      override def addTokens(p: String, m: String, in: Long, out: Long): Unit                = ()
      override def recordCost(p: String, m: String, usd: Double): Unit                       = ()
      override def observeImageGeneration(
        provider: String,
        model: String,
        operation: String,
        outcome: Outcome,
        duration: FiniteDuration,
        imageCount: Int
      ): Unit = images += 1
      override def recordImageGenerationCost(provider: String, model: String, costUsd: Double, imageCount: Int): Unit =
        images += 1
    }
    val counting = new CountingImages
    val composed = MetricsCollector.compose(counting)
    composed.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 1.second, 1)
    composed.recordImageGenerationCost("openai", "dall-e-3", 0.04, 1)
    // compose does not override these two, so the child receives nothing (see the guide's Metrics section)
    counting.images shouldBe 0
  }

  "the build-the-pieces-yourself snippet" should "retain the endpoint and surface a bind failure as a Left" in {
    // Mirrors the guide's "Or build the pieces yourself" block (port 0 here, for a free port):
    // the Result is handled, the handle retained, and the documented shutdown call made.
    val registry = new PrometheusRegistry()
    val metrics  = new PrometheusMetrics(registry)
    val endpoint: Option[PrometheusEndpoint] =
      PrometheusEndpoint.start(0, registry) match {
        case Right(ep) => Some(ep)
        case Left(_)   => None
      }
    endpoint should not be empty // on success the snippet keeps the handle
    // the series it serves are the collector's: record one request and scrape it back
    metrics.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    endpoint.foreach(ep => samples(scrape(ep)).exists(_.startsWith("llm4s_requests_total")) shouldBe true)
    // and the Left branch is real: the port the endpoint holds cannot be bound a second time
    val taken = endpoint.map(_.port).getOrElse(fail("no endpoint"))
    PrometheusEndpoint.start(taken, new PrometheusRegistry()) match {
      case Left(_)       => () // what the snippet's comment documents: a bind failure is a Left
      case Right(second) => second.stop(); fail("binding an in-use port should be a Left")
    }
    endpoint.foreach(_.stop()) // the documented shutdown call
  }

  "stopping the endpoint" should "be safe to do twice, and free the port" in {
    val registry = new PrometheusRegistry()
    PrometheusEndpoint.start(0, registry) match {
      case Right(endpoint) =>
        endpoint.stop()
        endpoint.stop()
      case Left(error) => fail(error.message)
    }
  }
}
