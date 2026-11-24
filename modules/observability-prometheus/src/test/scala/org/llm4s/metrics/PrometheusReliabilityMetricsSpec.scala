package org.llm4s.metrics

import io.prometheus.metrics.expositionformats.PrometheusTextFormatWriter
import org.llm4s.error.TimeoutError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.reliability.{ CircuitBreakerConfig, ReliabilityConfig, ReliableClient, RetryPolicy }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.time.Instant
import scala.concurrent.duration.*

/**
 * What `ReliableClient` reports - retries, circuit-breaker transitions and the errors it ends on - reaches
 * `/metrics` (#2801). They were methods of `MetricsCollector` with no-op defaults that `PrometheusMetrics` never
 * overrode, so Prometheus dropped all three.
 */
class PrometheusReliabilityMetricsSpec extends AnyFlatSpec with Matchers {

  private def scrape(metrics: PrometheusMetrics): Vector[String] = {
    val out = new ByteArrayOutputStream()
    PrometheusTextFormatWriter.create().write(out, metrics.registry.scrape())
    new String(out.toByteArray, StandardCharsets.UTF_8).linesIterator.filterNot(_.startsWith("#")).toVector
  }

  private val completion =
    Completion("id", Instant.EPOCH, "ok", "test-model", AssistantMessage(content = "ok", toolCalls = List.empty))

  /** Fails its first `failures` calls with a retryable timeout, then answers. */
  private def client(failures: Int): LLMClient = new LLMClient {
    private var calls = 0
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      calls += 1
      if (calls <= failures) Left(TimeoutError("timeout", 1.second, "test")) else Right(completion)
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  private val conversation = Conversation(List(UserMessage("hi")))

  private def breaker(failureThreshold: Int) =
    CircuitBreakerConfig(failureThreshold = failureThreshold, recoveryTimeout = 1.minute, successThreshold = 1)

  "PrometheusMetrics" should "count ReliableClient's retries" in {
    val metrics = PrometheusMetrics.create()
    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 3, delay = 1.millis),
      circuitBreaker = breaker(10),
      deadline = None
    )

    ReliableClient(client(failures = 2), "acme", config).withMetrics(metrics).complete(conversation) shouldBe
      Right(completion)

    scrape(metrics) should contain("llm4s_retries_total{provider=\"acme\"} 2.0")
  }

  it should "count the circuit breaker opening, and the error the call ended on" in {
    val metrics = PrometheusMetrics.create()
    // ReliableClient reports the error a call ends on only once it has retried
    val config = ReliabilityConfig(
      retryPolicy = RetryPolicy.fixedDelay(maxAttempts = 2, delay = 1.millis),
      circuitBreaker = breaker(1),
      deadline = None
    )

    ReliableClient(client(failures = 2), "acme", config)
      .withMetrics(metrics)
      .complete(conversation)
      .isLeft shouldBe true

    val lines = scrape(metrics)
    lines should contain("llm4s_circuit_breaker_transitions_total{provider=\"acme\",state=\"open\"} 1.0")
    withClue(lines.mkString("\n")) {
      exactly(1, lines) should (startWith("llm4s_reliability_errors_total{")
        .and(include("error_type=\"timeout\""))
        .and(include("provider=\"acme\""))
        .and(endWith(" 1.0")))
    }
  }
}
