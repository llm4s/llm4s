package org.llm4s.metrics

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.lang.reflect.Modifier
import scala.collection.mutable
import scala.concurrent.duration._

/**
 * `MetricsCollector.compose` fans every call out to every collector it was given (#1609).
 *
 * A composed collector that keeps a trait default silently drops that metric for every
 * child, so the last test pins the contract for the whole trait: every public method
 * `MetricsCollector` declares must be overridden by the collector `compose` returns.
 */
class MetricsCollectorComposeSpec extends AnyFlatSpec with Matchers {

  /** Records the name of every method called on it; overrides every method of the trait. */
  final private class Recording extends MetricsCollector {
    val calls: mutable.Buffer[String] = mutable.Buffer.empty

    override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
      calls += "observeRequest"
    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit =
      calls += "addTokens"
    override def recordCost(provider: String, model: String, costUsd: Double): Unit =
      calls += "recordCost"
    override def recordRetryAttempt(provider: String, attemptNumber: Int): Unit =
      calls += "recordRetryAttempt"
    override def recordCircuitBreakerTransition(provider: String, newState: String): Unit =
      calls += "recordCircuitBreakerTransition"
    override def recordError(errorKind: ErrorKind, provider: String): Unit =
      calls += "recordError"
    override def observeImageGeneration(
      provider: String,
      model: String,
      operation: String,
      outcome: Outcome,
      duration: FiniteDuration,
      imageCount: Int
    ): Unit = calls += "observeImageGeneration"
    override def recordImageGenerationCost(provider: String, model: String, costUsd: Double, imageCount: Int): Unit =
      calls += "recordImageGenerationCost"
  }

  /** A collector that throws from every method; `compose` must not let it stop the others. */
  final private class Throwing extends MetricsCollector {
    private def boom: Nothing = throw new RuntimeException("collector failure")
    override def observeRequest(provider: String, model: String, outcome: Outcome, duration: FiniteDuration): Unit =
      boom
    override def addTokens(provider: String, model: String, inputTokens: Long, outputTokens: Long): Unit = boom
    override def recordCost(provider: String, model: String, costUsd: Double): Unit                      = boom
    override def observeImageGeneration(
      provider: String,
      model: String,
      operation: String,
      outcome: Outcome,
      duration: FiniteDuration,
      imageCount: Int
    ): Unit = boom
    override def recordImageGenerationCost(provider: String, model: String, costUsd: Double, imageCount: Int): Unit =
      boom
  }

  /** `name(paramType, ...)` for the public, non-static, non-synthetic methods a class declares. */
  private def publicMethodSignatures(cls: Class[?]): Set[String] =
    cls.getDeclaredMethods.toList
      .filter(m => Modifier.isPublic(m.getModifiers) && !Modifier.isStatic(m.getModifiers))
      .filter(m => !m.isSynthetic && !m.isBridge && !m.getName.contains("$"))
      .map(m => s"${m.getName}(${m.getParameterTypes.map(_.getSimpleName).mkString(", ")})")
      .toSet

  "MetricsCollector.compose" should "forward observeImageGeneration to every collector" in {
    val a        = new Recording
    val b        = new Recording
    val composed = MetricsCollector.compose(a, b)

    composed.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 2.seconds, 1)

    a.calls.toList shouldBe List("observeImageGeneration")
    b.calls.toList shouldBe List("observeImageGeneration")
  }

  it should "forward recordImageGenerationCost to every collector" in {
    val a        = new Recording
    val b        = new Recording
    val composed = MetricsCollector.compose(a, b)

    composed.recordImageGenerationCost("openai", "dall-e-3", 0.04, 1)

    a.calls.toList shouldBe List("recordImageGenerationCost")
    b.calls.toList shouldBe List("recordImageGenerationCost")
  }

  it should "forward every method of the trait to every collector, in order" in {
    val a        = new Recording
    val b        = new Recording
    val composed = MetricsCollector.compose(a, b)

    composed.observeRequest("openai", "gpt-4o", Outcome.Success, 1.second)
    composed.addTokens("openai", "gpt-4o", 10L, 20L)
    composed.recordCost("openai", "gpt-4o", 0.01)
    composed.recordRetryAttempt("openai", 1)
    composed.recordCircuitBreakerTransition("openai", "open")
    composed.recordError(ErrorKind.RateLimit, "openai")
    composed.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 2.seconds, 1)
    composed.recordImageGenerationCost("openai", "dall-e-3", 0.04, 1)

    val expected = List(
      "observeRequest",
      "addTokens",
      "recordCost",
      "recordRetryAttempt",
      "recordCircuitBreakerTransition",
      "recordError",
      "observeImageGeneration",
      "recordImageGenerationCost"
    )
    a.calls.toList shouldBe expected
    b.calls.toList shouldBe expected
    // the list above is the whole trait: a new method must be added here and to `compose`
    expected.toSet shouldBe publicMethodSignatures(classOf[MetricsCollector]).map(_.takeWhile(_ != '('))
  }

  it should "keep forwarding the image-generation methods when an earlier collector throws" in {
    val recording = new Recording
    val composed  = MetricsCollector.compose(new Throwing, recording)

    composed.observeImageGeneration("openai", "dall-e-3", "generate", Outcome.Success, 2.seconds, 1)
    composed.recordImageGenerationCost("openai", "dall-e-3", 0.04, 1)

    recording.calls.toList shouldBe List("observeImageGeneration", "recordImageGenerationCost")
  }

  it should "override every public method MetricsCollector declares, so no trait default is left in place" in {
    val declared   = publicMethodSignatures(classOf[MetricsCollector])
    val overridden = publicMethodSignatures(MetricsCollector.compose().getClass)

    declared should not be empty
    withClue("methods of MetricsCollector that compose does not forward: ") {
      (declared -- overridden) shouldBe empty
    }
  }
}
