package org.llm4s.agent

import org.llm4s.llmconnect.model.*
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * What [[ContextPruning.prune]] guarantees whatever the strategy: the `minRecentTurns` floor (#2728) and both
 * `maxTokens` and `maxMessages` as limits (#2729).
 */
class ContextPruningSpec extends AnyFlatSpec with Matchers {

  /** Every message counts 100 tokens. */
  private val hundred: Message => Int = _ => 100

  /** Five answered turns and a sixth just asked: q1, a1 ... q5, a5, q6 - eleven messages, 1,100 tokens. */
  private val conversation: Seq[Message] =
    (1 to 5).flatMap(i => Seq(UserMessage(s"q$i"), AssistantMessage(s"a$i"))) :+ UserMessage("q6")

  private def texts(ms: Seq[Message]): Seq[String] = ms.map(_.content)

  private def call(id: String) = ToolCall(id, "t", ujson.Obj())

  /** Window 1,000 tokens: the smallest `calculateOptimalWindow` gives. */
  private val adaptive = PruningStrategy.AdaptiveWindowing(contextWindowSize = 1000)

  private val strategies: Seq[(String, PruningStrategy)] = Seq(
    "OldestFirst"       -> PruningStrategy.OldestFirst,
    "MiddleOut"         -> PruningStrategy.MiddleOut,
    "RecentTurnsOnly"   -> PruningStrategy.RecentTurnsOnly(1),
    "AdaptiveWindowing" -> adaptive,
    "Custom"            -> PruningStrategy.Custom(_ => Seq.empty)
  )

  private val limits: Seq[(String, ContextWindowConfig)] = Seq(
    "maxTokens"   -> ContextWindowConfig(maxTokens = Some(100)),
    "maxMessages" -> ContextWindowConfig(maxMessages = Some(1)),
    "both limits" -> ContextWindowConfig(maxTokens = Some(100), maxMessages = Some(1))
  )

  // --- #2728: the minRecentTurns floor ---

  for {
    (strategyName, strategy) <- strategies
    (limitName, limit)       <- limits
  } "ContextPruning.prune" should s"keep minRecentTurns whole turns past $limitName under $strategyName" in {
    val cfg = limit.copy(minRecentTurns = 3, pruningStrategy = strategy)
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q4", "a4", "q5", "a5", "q6")
  }

  for ((strategyName, strategy) <- strategies)
    "ContextPruning.prune" should s"keep each floor turn's tool calls and results under $strategyName" in {
      val tools = Seq(
        UserMessage("q1"),
        AssistantMessage("a1"),
        UserMessage("q2"),
        AssistantMessage(None, Seq(call("1"), call("2"))),
        ToolMessage("r1", "1"),
        ToolMessage("r2", "2"),
        AssistantMessage("a2"),
        UserMessage("q3"),
        AssistantMessage(None, Seq(call("3"))),
        ToolMessage("r3", "3")
      )
      val cfg    = ContextWindowConfig(maxMessages = Some(1), minRecentTurns = 2, pruningStrategy = strategy)
      val pruned = ContextPruning.prune(tools, cfg, hundred)
      pruned shouldBe tools.drop(2)
      Message.validateConversation(pruned.toList) shouldBe Right(())
    }

  it should "keep the default of three recent turns" in {
    val pruned = ContextPruning.prune(conversation, ContextWindowConfig(maxMessages = Some(1)), hundred)
    texts(pruned) shouldBe Seq("q4", "a4", "q5", "a5", "q6")
  }

  it should "keep the whole conversation when it has no more turns than the floor" in {
    val cfg = ContextWindowConfig(maxTokens = Some(100), maxMessages = Some(1), minRecentTurns = 6)
    ContextPruning.prune(conversation, cfg, hundred) shouldBe conversation
    ContextPruning.prune(conversation, cfg.copy(minRecentTurns = 60), hundred) shouldBe conversation
  }

  it should "keep the current turn when minRecentTurns is below one" in {
    Seq(0, -4).foreach { n =>
      val cfg = ContextWindowConfig(maxMessages = Some(1), minRecentTurns = n)
      texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q6")
    }
  }

  it should "keep preserved system messages as well as a floor that is over the limit" in {
    val cfg    = ContextWindowConfig(maxMessages = Some(1), minRecentTurns = 2)
    val pruned = ContextPruning.prune(SystemMessage("sys") +: conversation, cfg, hundred)
    texts(pruned) shouldBe Seq("sys", "q5", "a5", "q6")
  }

  it should "spend only what the floor leaves of the limits on older history" in {
    // the floor is q5, a5, q6: 300 of 500 tokens, 3 of 6 messages
    val tokens = ContextWindowConfig(maxTokens = Some(500), minRecentTurns = 2)
    texts(ContextPruning.prune(conversation, tokens, hundred)) shouldBe Seq("q4", "a4", "q5", "a5", "q6")
    val messages = ContextWindowConfig(maxMessages = Some(6), minRecentTurns = 2)
    // a3 would lead the history, so it goes
    texts(ContextPruning.prune(conversation, messages, hundred)) shouldBe Seq("q4", "a4", "q5", "a5", "q6")
  }

  // --- #2729: both limits ---

  private def one(cfg: ContextWindowConfig): ContextWindowConfig = cfg.copy(minRecentTurns = 1)

  "ContextPruning.prune with both limits" should "prune when only maxMessages is exceeded" in {
    val cfg = one(ContextWindowConfig(maxTokens = Some(10_000), maxMessages = Some(5)))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q4", "a4", "q5", "a5", "q6")
  }

  it should "prune to maxTokens when maxMessages allows more" in {
    val cfg = one(ContextWindowConfig(maxTokens = Some(300), maxMessages = Some(8)))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q5", "a5", "q6")
  }

  it should "prune when only maxTokens is exceeded and maxMessages is not" in {
    val cfg = one(ContextWindowConfig(maxTokens = Some(500), maxMessages = Some(50)))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q4", "a4", "q5", "a5", "q6")
  }

  it should "leave a conversation within both untouched" in {
    val cfg = one(ContextWindowConfig(maxTokens = Some(1100), maxMessages = Some(11)))
    ContextPruning.prune(conversation, cfg, hundred) shouldBe conversation
  }

  for {
    (strategyName, strategy) <- strategies.filterNot(_._1 == "Custom")
    maxTokens                <- Seq(100, 300, 450, 700, 1000, 5000)
    maxMessages              <- Seq(1, 2, 3, 5, 8, 10, 50)
  } "ContextPruning.prune with both limits" should
    s"satisfy maxTokens = $maxTokens and maxMessages = $maxMessages under $strategyName" in {
      val cfg = one(
        ContextWindowConfig(maxTokens = Some(maxTokens), maxMessages = Some(maxMessages), pruningStrategy = strategy)
      )
      val pruned = ContextPruning.prune(conversation, cfg, hundred)
      pruned.last shouldBe UserMessage("q6")
      pruned.distinct shouldBe pruned
      pruned.length should be <= maxMessages
      pruned.map(hundred).sum should be <= maxTokens
      Message.validateConversation(pruned.toList) shouldBe Right(())
    }

  // --- MiddleOut ---

  "ContextPruning.prune with MiddleOut" should "keep the start and the end within maxMessages" in {
    val cfg = one(ContextWindowConfig(maxMessages = Some(5), pruningStrategy = PruningStrategy.MiddleOut))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q1", "a1", "q5", "a5", "q6")
  }

  it should "keep the start and the end within maxTokens" in {
    val cfg = one(ContextWindowConfig(maxTokens = Some(500), pruningStrategy = PruningStrategy.MiddleOut))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q1", "a1", "q5", "a5", "q6")
  }

  it should "keep the start and the end within both, without repeating a message" in {
    val tight = one(
      ContextWindowConfig(maxTokens = Some(500), maxMessages = Some(7), pruningStrategy = PruningStrategy.MiddleOut)
    )
    texts(ContextPruning.prune(conversation, tight, hundred)) shouldBe Seq("q1", "a1", "q5", "a5", "q6")
    // maxMessages above the conversation's length used to keep the start and the end twice over
    val loose = tight.copy(maxTokens = Some(900), maxMessages = Some(50))
    texts(ContextPruning.prune(conversation, loose, hundred)) shouldBe
      Seq("q1", "a1", "q2", "a2", "q4", "a4", "q5", "a5", "q6")
  }

  it should "fill the budget from one end when the other end's next message does not fit" in {
    val counter: Message => Int = m => if (m.content == "a1") 1000 else 100
    val cfg = one(ContextWindowConfig(maxTokens = Some(600), pruningStrategy = PruningStrategy.MiddleOut))
    texts(ContextPruning.prune(conversation, cfg, counter)) shouldBe Seq("q1", "q4", "a4", "q5", "a5", "q6")
  }

  // --- RecentTurnsOnly ---

  "ContextPruning.prune with RecentTurnsOnly" should "keep its turns with no other limit set" in {
    val cfg = one(ContextWindowConfig(pruningStrategy = PruningStrategy.RecentTurnsOnly(2)))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q5", "a5", "q6")
  }

  it should "keep fewer turns when a limit asks for fewer" in {
    val messages = one(ContextWindowConfig(maxMessages = Some(3), pruningStrategy = PruningStrategy.RecentTurnsOnly(4)))
    texts(ContextPruning.prune(conversation, messages, hundred)) shouldBe Seq("q5", "a5", "q6")
    val tokens = one(ContextWindowConfig(maxTokens = Some(300), pruningStrategy = PruningStrategy.RecentTurnsOnly(4)))
    texts(ContextPruning.prune(conversation, tokens, hundred)) shouldBe Seq("q5", "a5", "q6")
  }

  it should "keep minRecentTurns turns when that is more than its own" in {
    val cfg = ContextWindowConfig(minRecentTurns = 3, pruningStrategy = PruningStrategy.RecentTurnsOnly(1))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q4", "a4", "q5", "a5", "q6")
  }

  it should "leave a conversation of no more than its turns untouched" in {
    val cfg = one(ContextWindowConfig(pruningStrategy = PruningStrategy.RecentTurnsOnly(6)))
    ContextPruning.prune(conversation, cfg, hundred) shouldBe conversation
  }

  // --- AdaptiveWindowing ---

  "ContextPruning.prune with AdaptiveWindowing" should "prune to its window with no other limit set" in {
    val cfg = one(ContextWindowConfig(pruningStrategy = adaptive))
    // ten of the eleven fit 1,000 tokens; a1 would lead the history, so it goes
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe texts(conversation.drop(2))
  }

  it should "prune to its window when maxTokens allows more" in {
    val cfg = one(ContextWindowConfig(maxTokens = Some(5000), pruningStrategy = adaptive))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe texts(conversation.drop(2))
  }

  it should "prune to maxTokens when it is below the window" in {
    val cfg = one(ContextWindowConfig(maxTokens = Some(300), pruningStrategy = adaptive))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q5", "a5", "q6")
  }

  it should "prune to maxMessages" in {
    val cfg = one(ContextWindowConfig(maxMessages = Some(3), pruningStrategy = adaptive))
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q5", "a5", "q6")
  }

  it should "keep minRecentTurns whole turns past its window" in {
    val cfg = ContextWindowConfig(minRecentTurns = 4, pruningStrategy = adaptive)
    texts(ContextPruning.prune(conversation, cfg, _ => 1000)) shouldBe texts(conversation.drop(4))
  }

  // --- Custom ---

  "ContextPruning.prune with Custom" should "give the function only the history before the protected turns" in {
    var seen = Seq.empty[Message]
    val strategy = PruningStrategy.Custom { ms =>
      seen = ms; ms.take(2)
    }
    val cfg = ContextWindowConfig(maxMessages = Some(1), minRecentTurns = 2, pruningStrategy = strategy)
    texts(ContextPruning.prune(conversation, cfg, hundred)) shouldBe Seq("q1", "a1", "q5", "a5", "q6")
    texts(seen) shouldBe Seq("q1", "a1", "q2", "a2", "q3", "a3", "q4", "a4")
  }

  it should "run when either limit is exceeded, and not otherwise" in {
    val strategy = PruningStrategy.Custom(_ => Seq.empty)
    val over     = one(ContextWindowConfig(maxTokens = Some(10_000), maxMessages = Some(5), pruningStrategy = strategy))
    texts(ContextPruning.prune(conversation, over, hundred)) shouldBe Seq("q6")
    val within = over.copy(maxMessages = Some(11))
    ContextPruning.prune(conversation, within, hundred) shouldBe conversation
  }
}
