package org.llm4s.agent

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.llmconnect.model.{ Message, UserMessage }

/**
 * Tests for ContextWindowConfig and PruningStrategy types.
 */
class ContextWindowConfigSpec extends AnyFlatSpec with Matchers {

  // ==========================================================================
  // ContextWindowConfig Tests
  // ==========================================================================

  "ContextWindowConfig" should "have sensible defaults" in {
    val config = ContextWindowConfig()

    config.maxTokens shouldBe None
    config.maxMessages shouldBe None
    config.preserveSystemMessage shouldBe true
    config.minRecentTurns shouldBe 3
    config.pruningStrategy shouldBe PruningStrategy.OldestFirst
  }

  it should "accept custom values" in {
    val config = ContextWindowConfig(
      maxTokens = Some(4000),
      maxMessages = Some(50),
      preserveSystemMessage = false,
      minRecentTurns = 5,
      pruningStrategy = PruningStrategy.MiddleOut
    )

    config.maxTokens shouldBe Some(4000)
    config.maxMessages shouldBe Some(50)
    config.preserveSystemMessage shouldBe false
    config.minRecentTurns shouldBe 5
    config.pruningStrategy shouldBe PruningStrategy.MiddleOut
  }

  it should "support copy with modifications" in {
    val original = ContextWindowConfig(maxMessages = Some(100))
    val modified = original.copy(maxTokens = Some(8000))

    modified.maxMessages shouldBe Some(100)
    modified.maxTokens shouldBe Some(8000)
  }

  it should "support equality" in {
    val c1 = ContextWindowConfig(maxMessages = Some(50))
    val c2 = ContextWindowConfig(maxMessages = Some(50))
    val c3 = ContextWindowConfig(maxMessages = Some(100))

    c1 shouldBe c2
    c1 should not be c3
  }

  // ==========================================================================
  // PruningStrategy Tests
  // ==========================================================================

  "PruningStrategy.OldestFirst" should "be a singleton" in {
    PruningStrategy.OldestFirst shouldBe PruningStrategy.OldestFirst
  }

  "PruningStrategy.MiddleOut" should "be a singleton" in {
    PruningStrategy.MiddleOut shouldBe PruningStrategy.MiddleOut
  }

  "PruningStrategy.RecentTurnsOnly" should "store turn count" in {
    val strategy = PruningStrategy.RecentTurnsOnly(5)
    strategy.turns shouldBe 5
  }

  it should "support equality based on turns" in {
    val s1 = PruningStrategy.RecentTurnsOnly(3)
    val s2 = PruningStrategy.RecentTurnsOnly(3)
    val s3 = PruningStrategy.RecentTurnsOnly(5)

    s1 shouldBe s2
    s1 should not be s3
  }

  "PruningStrategy.Custom" should "store the function" in {
    val fn: Seq[Message] => Seq[Message] = _.take(5)
    val strategy                         = PruningStrategy.Custom(fn)

    strategy.fn shouldBe fn
  }

  it should "apply the custom function correctly" in {
    val fn: Seq[Message] => Seq[Message] = msgs => msgs.filter(_.content.contains("keep"))
    val strategy                         = PruningStrategy.Custom(fn)

    val messages = Seq(
      UserMessage("keep this"),
      UserMessage("remove this"),
      UserMessage("keep this too")
    )

    val result = strategy.fn(messages)
    result should have size 2
    result.forall(_.content.contains("keep")) shouldBe true
  }

  // ==========================================================================
  // PruningStrategy as Sealed Trait
  // ==========================================================================

  "PruningStrategy" should "be pattern matchable" in {
    def describe(strategy: PruningStrategy): String = strategy match {
      case PruningStrategy.OldestFirst          => "oldest-first"
      case PruningStrategy.MiddleOut            => "middle-out"
      case PruningStrategy.RecentTurnsOnly(n)   => s"recent-$n-turns"
      case PruningStrategy.Custom(_)            => "custom"
      case _: PruningStrategy.AdaptiveWindowing => "adaptive"
    }

    describe(PruningStrategy.OldestFirst) shouldBe "oldest-first"
    describe(PruningStrategy.MiddleOut) shouldBe "middle-out"
    describe(PruningStrategy.RecentTurnsOnly(3)) shouldBe "recent-3-turns"
    describe(PruningStrategy.Custom(_ => Seq.empty)) shouldBe "custom"
    describe(PruningStrategy.AdaptiveWindowing(100_000)) shouldBe "adaptive"
  }

  // ==========================================================================
  // Common Configuration Patterns
  // ==========================================================================

  "ContextWindowConfig" should "support token-based limiting" in {
    val config = ContextWindowConfig(
      maxTokens = Some(4096),
      pruningStrategy = PruningStrategy.OldestFirst
    )

    config.maxTokens shouldBe Some(4096)
    config.maxMessages shouldBe None
  }

  it should "support message-based limiting" in {
    val config = ContextWindowConfig(
      maxMessages = Some(20),
      pruningStrategy = PruningStrategy.RecentTurnsOnly(5)
    )

    config.maxMessages shouldBe Some(20)
    config.maxTokens shouldBe None
  }

  it should "support both limits together" in {
    val config = ContextWindowConfig(
      maxTokens = Some(8000),
      maxMessages = Some(100),
      pruningStrategy = PruningStrategy.MiddleOut
    )

    config.maxTokens shouldBe Some(8000)
    config.maxMessages shouldBe Some(100)
  }
}
