package org.llm4s.agent.graph.middleware

import org.llm4s.agent.{ AgentId, ContextPruning, ContextWindowConfig, PruningStrategy }
import org.llm4s.agent.graph.toolloop.ToolLoopFixtures.{ answered, completion }
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList

class ContextWindowMiddlewareSpec extends AnyFlatSpec with Matchers with EitherValues {

  final private class ScriptedModel extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet): Result[Completion] = {
      seen.add(messages)
      Right(completion(AssistantMessage("ok")))
    }
  }

  private def agentA = AgentId.unsafe("a")

  /** Three earlier exchanges, the oldest first. */
  private def history: Vector[Message] = Vector(
    UserMessage("q1"),
    AssistantMessage("a1"),
    UserMessage("q2"),
    AssistantMessage("a2"),
    UserMessage("q3"),
    AssistantMessage("a3")
  )

  private def sized: Message => Int = _ => 400

  /** Runs one turn over `history` and returns what the model saw and how many messages were stored. */
  private def turn(mw: ContextWindowMiddleware, system: Option[String] = Some("be brief")): (Vector[Message], Int) = {
    val model = new ScriptedModel
    val l = ToolLoop
      .build(
        "assistant",
        "v1",
        agentA,
        Vector(
          LoopAgent(agentA, model, ToolSet.of().value).withSystemPrompt(system).withMiddleware(Seq(mw))
        )
      )
      .value
    val (state, _) = runInMemory(l.graph, AgentInput("q4", history)).answered
    (model.seen.get(0), state.get(Messages.key).value.length)
  }

  private def texts(ms: Seq[Message]): Seq[String] = ms.map(_.content)

  "ContextWindowMiddleware" should "trim what the model sees under OldestFirst, keeping the system prompt first" in {
    val (sent, stored) = turn(new ContextWindowMiddleware(ContextWindowConfig(maxMessages = Some(3))))
    texts(sent) shouldBe Seq("be brief", "q3", "a3", "q4")
    sent.head shouldBe a[SystemMessage]
    stored shouldBe 8 // seven in, one answer
  }

  it should "trim under MiddleOut" in {
    val cfg            = ContextWindowConfig(maxMessages = Some(4), pruningStrategy = PruningStrategy.MiddleOut)
    val (sent, stored) = turn(new ContextWindowMiddleware(cfg))
    // the current turn (q4) takes one of the four; the earlier six keep one from the start and two from the end
    texts(sent) shouldBe Seq("be brief", "q1", "q3", "a3", "q4")
    stored shouldBe 8
  }

  it should "trim under RecentTurnsOnly" in {
    val cfg = ContextWindowConfig(maxMessages = Some(1), pruningStrategy = PruningStrategy.RecentTurnsOnly(1))
    val (sent, stored) = turn(new ContextWindowMiddleware(cfg))
    texts(sent) shouldBe Seq("be brief", "q4")
    stored shouldBe 8
  }

  it should "trim under AdaptiveWindowing" in {
    val strategy       = PruningStrategy.AdaptiveWindowing(contextWindowSize = 1000, preserveMinTurns = 2)
    val (sent, stored) = turn(new ContextWindowMiddleware(ContextWindowConfig(pruningStrategy = strategy), sized))
    // window 1000 holds two 400-token messages; preserveMinTurns = 2 floors it at four, a3 first is cut to start on a user
    texts(sent) shouldBe Seq("be brief", "q3", "a3", "q4")
    sent.head shouldBe a[SystemMessage]
    stored shouldBe 8
  }

  it should "trim under a Custom strategy" in {
    val cfg = ContextWindowConfig(maxMessages = Some(1), pruningStrategy = PruningStrategy.Custom(_.takeRight(3)))
    val (sent, stored) = turn(new ContextWindowMiddleware(cfg))
    texts(sent) shouldBe Seq("be brief", "q3", "a3", "q4")
    stored shouldBe 8
  }

  it should "send everything when within the limits" in {
    val (sent, _) = turn(new ContextWindowMiddleware(ContextWindowConfig(maxMessages = Some(50))))
    sent.length shouldBe 8
  }

  it should "work with no system prompt" in {
    val (sent, _) = turn(new ContextWindowMiddleware(ContextWindowConfig(maxMessages = Some(2))), system = None)
    texts(sent) shouldBe Seq("q4") // a3 would lead the history: it starts on a user message
  }

  // --- tool pairs ---

  private def call(id: String)       = ToolCall(id, "t", ujson.Obj())
  private def toolResult(id: String) = ToolMessage(s"r$id", id)

  private def toolHistory: Seq[Message] = Seq(
    SystemMessage("sys"),
    UserMessage("q1"),
    AssistantMessage(None, Seq(call("1"), call("2"))),
    toolResult("1"),
    toolResult("2"),
    AssistantMessage("done"),
    UserMessage("q2")
  )

  "ContextPruning.prune" should "never cut between a tool call and its result (every cut point)" in {
    (1 to toolHistory.length).foreach { n =>
      val cfg    = ContextWindowConfig(maxMessages = Some(n))
      val pruned = ContextPruning.prune(toolHistory, cfg, sized)
      Message.validateConversation(pruned.toList) shouldBe Right(())
      val ids = pruned.collect { case t: ToolMessage => t.toolCallId }.toSet
      pruned.collect { case m: AssistantMessage => m.toolCalls.map(_.id) }.flatten.toSet shouldBe ids
    }
  }

  it should "drop results whose call was pruned" in {
    val pruned = ContextPruning.prune(toolHistory, ContextWindowConfig(maxMessages = Some(4)), sized)
    texts(pruned) shouldBe Seq("sys", "q2") // "done" would lead the history, so it goes too
  }

  it should "keep a multi-call assistant message only with all of its results" in {
    ContextPruning.keepToolPairs(toolHistory.patch(4, Nil, 1)).collect { case m: ToolMessage => m } shouldBe Seq.empty
    ContextPruning.keepToolPairs(toolHistory) shouldBe toolHistory
  }

  it should "give a ContextWindowMiddleware request that passes validateConversation" in {
    val mw   = new ContextWindowMiddleware(ContextWindowConfig(maxMessages = Some(4)))
    var sent = Vector.empty[Message]
    val result = mw.wrapModelCall(ModelRequest(toolHistory.toVector, ToolSet.of().value), testRunContext()) { req =>
      sent = req.messages
      Right(completion(AssistantMessage("ok")))
    }
    result.isRight shouldBe true
    sent.head shouldBe SystemMessage("sys")
    Message.validateConversation(sent.toList) shouldBe Right(())
  }

  "ContextPruning.defaultTokenCounter" should "estimate words times 1.3" in {
    ContextPruning.defaultTokenCounter(UserMessage("one two three four five six seven eight nine ten")) shouldBe 13
  }

  it should "leave a conversation under the limit untouched" in {
    val cfg = ContextWindowConfig(maxTokens = Some(1000))
    ContextPruning.prune(history, cfg, ContextPruning.defaultTokenCounter) shouldBe history
  }

  // --- current turn, user first, system outside the budget ---

  /** A history whose current turn is a tool exchange: user, assistant with calls, results. */
  private def midTurn: Seq[Message] = Seq(
    SystemMessage("sys"),
    UserMessage("q1"),
    AssistantMessage("a1"),
    UserMessage("q2"),
    AssistantMessage(None, Seq(call("1"), call("2"))),
    toolResult("1"),
    toolResult("2")
  )

  it should "keep the whole current turn under a tiny window" in {
    val pruned = ContextPruning.prune(midTurn, ContextWindowConfig(maxMessages = Some(1)), sized)
    texts(pruned) shouldBe Seq("sys", "q2", "", "r1", "r2")
    Message.validateConversation(pruned.toList) shouldBe Right(())
  }

  it should "keep all of [user, assistant call, result] under maxMessages = 1" in {
    val three  = Seq(UserMessage("q"), AssistantMessage(None, Seq(call("1"))), toolResult("1"))
    val pruned = ContextPruning.prune(three, ContextWindowConfig(maxMessages = Some(1)), sized)
    pruned shouldBe three
    val more = UserMessage("old") +: AssistantMessage("older") +: three
    ContextPruning.prune(more, ContextWindowConfig(maxMessages = Some(1)), sized) shouldBe three
  }

  it should "start with a user message at every cut point of a history ending in a tool exchange" in {
    (1 to midTurn.length).foreach { n =>
      Seq(PruningStrategy.OldestFirst, PruningStrategy.MiddleOut, PruningStrategy.RecentTurnsOnly(1)).foreach { st =>
        val pruned =
          ContextPruning.prune(midTurn, ContextWindowConfig(maxMessages = Some(n), pruningStrategy = st), sized)
        val body = pruned.dropWhile(_.role == MessageRole.System)
        body.head shouldBe a[UserMessage]
        body.lastIndexWhere(_.isInstanceOf[UserMessage]) should be >= 0
        pruned.takeRight(4).exists(_ == UserMessage("q2")) shouldBe true
        Message.validateConversation(pruned.toList) shouldBe Right(())
      }
    }
  }

  it should "drop leading non-user messages the strategy leaves" in {
    val msgs =
      Seq(UserMessage("q1"), AssistantMessage("a1"), UserMessage("q2"), AssistantMessage("a2"), UserMessage("q3"))
    val pruned = ContextPruning.prune(msgs, ContextWindowConfig(maxMessages = Some(4)), sized)
    texts(pruned) shouldBe Seq("q2", "a2", "q3")
  }

  "ContextWindowMiddleware" should "not count the system prompt against maxTokens" in {
    // a budget of 1200 fits three 400-token messages; counting the system message would make it two
    val cfg            = ContextWindowConfig(maxTokens = Some(1200))
    val (sent, stored) = turn(new ContextWindowMiddleware(cfg, sized))
    texts(sent) shouldBe Seq("be brief", "q3", "a3", "q4")
    stored shouldBe 8
  }

  // --- ported from AdaptiveWindowingSpec / ContextWindowConfigSpec ---

  private def convo(n: Int): Seq[Message] =
    (1 to n).flatMap(i => Seq(UserMessage(s"Question $i"), AssistantMessage(s"Answer $i")))

  "ContextPruning.prune with AdaptiveWindowing" should "prune a small model's history, keeping the system message" in {
    val msgs = SystemMessage("You are helpful") +: convo(5)
    val cfg  = ContextWindowConfig(pruningStrategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 8_000))
    ContextPruning.prune(msgs, cfg, ContextPruning.defaultTokenCounter) shouldBe msgs // well under 4,800 tokens
    val heavy = ContextPruning.prune(msgs, cfg, _ => 1000)
    heavy.head shouldBe msgs.head
    heavy.length should be < msgs.length
    heavy.drop(1).head shouldBe a[UserMessage]
  }

  it should "run the adaptive path when no limits are set" in {
    val msgs = SystemMessage("System prompt") +: convo(3)
    val cfg  = ContextWindowConfig(pruningStrategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 50_000))
    ContextPruning.prune(msgs, cfg, sized) should not be empty
  }

  it should "respect preserveSystemMessage" in {
    val msgs     = SystemMessage("Critical") +: convo(5)
    val strategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 1000, preserveMinTurns = 1)
    val keep     = ContextPruning.prune(msgs, ContextWindowConfig(pruningStrategy = strategy), sized)
    keep.head shouldBe msgs.head
    val drop = ContextPruning.prune(
      msgs,
      ContextWindowConfig(pruningStrategy = strategy, preserveSystemMessage = false),
      sized
    )
    drop.head shouldBe a[UserMessage]
  }

  it should "keep at least preserveMinTurns turns" in {
    val msgs = convo(20)
    val cfg = ContextWindowConfig(
      pruningStrategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 4_000, preserveMinTurns = 5)
    )
    ContextPruning.prune(msgs, cfg, sized).length should be >= 10
  }

  it should "prune an expensive-input model at least as hard as a cheap-input one" in {
    val msgs = convo(25)
    def cfg(in: Double, out: Double) = ContextWindowConfig(
      pruningStrategy = PruningStrategy.AdaptiveWindowing(
        contextWindowSize = 100_000,
        inputCostPerToken = Some(in),
        outputCostPerToken = Some(out),
        costSensitivity = 1.0
      )
    )
    val counter: Message => Int = _ => 1500
    val expensive               = ContextPruning.prune(msgs, cfg(0.00001, 0.000001), counter)
    val cheap                   = ContextPruning.prune(msgs, cfg(0.000001, 0.00001), counter)
    expensive.length should be <= cheap.length
    expensive.length should be < msgs.length
  }

  "ContextPruning.prune with Custom" should "apply the custom function" in {
    val msgs = Seq(UserMessage("keep this"), UserMessage("remove this"), UserMessage("keep this too"))
    val cfg = ContextWindowConfig(
      maxMessages = Some(1),
      pruningStrategy = PruningStrategy.Custom(_.filter(_.content.contains("keep")))
    )
    texts(ContextPruning.prune(msgs, cfg, sized)) shouldBe Seq("keep this", "keep this too")
  }

  it should "not duplicate the current turn when a Custom strategy returns copies of its messages" in {
    val msgs = Seq(
      SystemMessage("sys"),
      UserMessage("q1"),
      AssistantMessage(None, Seq(call("0"))),
      ToolMessage("a long earlier tool result", "0"),
      AssistantMessage("a1"),
      UserMessage("q2"),
      AssistantMessage(None, Seq(call("1"))),
      ToolMessage("the current tool result", "1")
    )
    // copies every assistant and tool message, truncating tool results: none is the same reference
    val copying = PruningStrategy.Custom(_.map {
      case t: ToolMessage      => ToolMessage(t.content.take(6), t.toolCallId)
      case a: AssistantMessage => AssistantMessage(a.contentOpt, a.toolCalls)
      case other               => other
    })
    val pruned =
      ContextPruning.prune(msgs, ContextWindowConfig(maxMessages = Some(1), pruningStrategy = copying), sized)

    Message.validateConversation(pruned.toList) shouldBe Right(())
    val callIds = pruned.collect { case a: AssistantMessage => a.toolCalls.map(_.id) }.flatten
    callIds shouldBe Seq("0", "1")
    pruned.collect { case t: ToolMessage => t.toolCallId } shouldBe Seq("0", "1")
    // the strategy ran on the earlier history only: the current turn is sent as it is
    texts(pruned) shouldBe Seq("sys", "q1", "", "a long", "a1", "q2", "", "the current tool result")
  }
}
