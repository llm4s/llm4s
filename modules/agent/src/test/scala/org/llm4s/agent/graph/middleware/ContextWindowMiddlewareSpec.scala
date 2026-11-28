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
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
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
    val strategy       = PruningStrategy.AdaptiveWindowing(contextWindowSize = 1000)
    val cfg            = ContextWindowConfig(pruningStrategy = strategy, minRecentTurns = 2)
    val (sent, stored) = turn(new ContextWindowMiddleware(cfg, sized))
    // window 1000 holds two 400-token messages; minRecentTurns = 2 keeps the last two turns whole past it
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

  private def call(id: String)       = ToolCall(ToolCallId(id), "t", ujson.Obj())
  private def toolResult(id: String) = ToolMessage(s"r$id", ToolCallId(id))

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

  it should "pair calls and results by position, so a reused tool-call id cannot keep a call without its result (#3249)" in {
    // a provider that returns call_0 on every turn: MiddleOut keeps [U1, A1] at the head and [U2, A2, T2] at the tail
    val reused = Seq(
      UserMessage("u1"),
      AssistantMessage(None, Seq(call("call_0"))),
      ToolMessage("r1", ToolCallId("call_0")),
      UserMessage("u2"),
      AssistantMessage(None, Seq(call("call_0"))),
      ToolMessage("r2", ToolCallId("call_0"))
    )
    val headAndTail = reused.patch(2, Nil, 1)
    val kept        = ContextPruning.keepToolPairs(headAndTail)
    org.llm4s.agent.graph.toolloop.ToolResultRule.check(kept) shouldBe Right(())
    kept shouldBe Seq(reused(0), reused(3), reused(4), reused(5))
    ContextPruning.keepToolPairs(reused) shouldBe reused
    // a result not straight after its call is dropped, though an earlier call has its id
    ContextPruning.keepToolPairs(Seq(reused(1), reused(2), UserMessage("x"), reused(5))) shouldBe
      Seq(reused(1), reused(2), UserMessage("x"))
  }

  it should "give MiddleOut a request ToolResultRule accepts with reused tool-call ids, at every limit (#3249)" in {
    val turns = (1 to 6).flatMap { n =>
      Seq(
        UserMessage(s"u$n"),
        AssistantMessage(None, Seq(call("call_0"))),
        ToolMessage(s"r$n", ToolCallId("call_0")),
        AssistantMessage(s"a$n")
      )
    } :+ UserMessage("now")
    (1 to turns.length).foreach { n =>
      Seq(PruningStrategy.MiddleOut, PruningStrategy.OldestFirst).foreach { strategy =>
        val pruned =
          ContextPruning.prune(turns, ContextWindowConfig(maxMessages = Some(n), pruningStrategy = strategy), sized)
        withClue(s"$strategy, $n: ") {
          org.llm4s.agent.graph.toolloop.ToolResultRule.check(pruned) shouldBe Right(())
        }
      }
    }
  }

  it should "give a ContextWindowMiddleware request that passes validateConversation" in {
    val mw   = new ContextWindowMiddleware(ContextWindowConfig(maxMessages = Some(4)))
    var sent = Vector.empty[Message]
    val result = mw.wrapModelCall(ModelRequest(toolHistory.toVector, ToolSet.of().value), testModelContext()) { req =>
      sent = req.messages
      Right(ModelResponse(completion(AssistantMessage("ok"))))
    }
    result.isRight shouldBe true
    sent.head shouldBe SystemMessage("sys")
    Message.validateConversation(sent.toList) shouldBe Right(())
  }

  // The counter's job is the tokens a provider is sent, so `maxTokens` keeps a request inside the window.
  "ContextPruning.defaultTokenCounter" should "count ten common words as about ten tokens, plus message framing" in {
    val n = ContextPruning.defaultTokenCounter(UserMessage("one two three four five six seven eight nine ten"))
    n should ((be >= 10).and(be <= 20))
  }

  /** A tool result as providers return it: minified JSON, no whitespace. */
  private lazy val minifiedJson: String =
    ujson
      .Arr((1 to 3000).map(i => ujson.Obj("id" -> i, "name" -> s"item-$i", "tags" -> ujson.Arr("alpha", "beta")))*)
      .render()

  it should "count minified JSON by its tokens, not by its spaces" in {
    minifiedJson.exists(_.isWhitespace) shouldBe false
    // any BPE tokenizer averages at most four characters a token on JSON
    ContextPruning.defaultTokenCounter(ToolMessage(minifiedJson, ToolCallId("c1"))) should be >= minifiedJson.length / 4
    ContextPruning.defaultTokenCounter(
      AssistantMessage(None, Seq(ToolCall(ToolCallId("c1"), "t", ujson.read(minifiedJson))))
    ) should be >= minifiedJson.length / 4
  }

  it should "count text with no spaces, such as a base64 blob or a long URL, by its length" in {
    val blob = java.util.Base64.getEncoder.encodeToString(Array.tabulate[Byte](30_000)(i => (i * 31).toByte))
    ContextPruning.defaultTokenCounter(UserMessage(blob)) should be >= blob.length / 4
  }

  // The default counter keeps counts by message; a count must follow the message, never another with the same text.
  it should "give each message its own count when messages share their text" in {
    val text  = minifiedJson.take(2_000)
    val plain = AssistantMessage(text)
    val withCalls =
      AssistantMessage(
        Some(text),
        Seq(ToolCall(ToolCallId("c1"), "search", ujson.Arr(ujson.read(minifiedJson).arr.take(100).toSeq*)))
      )
    val counted = ContextPruning.defaultTokenCounter(plain)
    ContextPruning.defaultTokenCounter(withCalls) should be > counted + 1_000
    ContextPruning.defaultTokenCounter(plain) shouldBe counted
    ContextPruning.defaultTokenCounter(UserMessage(text)) shouldBe counted // same text and framing, either role
  }

  it should "count a message with inline media bytes, media included (counting only; whether it is cached isn't visible)" in {
    val pixels = org.llm4s.media.MediaSource.Inline(IArray.fill[Byte](64 * 1024)(1))
    val image  = ContentPart.image(pixels, org.llm4s.media.MediaType.Png).value
    val asked  = UserMessage(Vector(ContentPart.Text("what is in this chart?"), image))
    ContextPruning.defaultTokenCounter(asked) should be > ContextPruning.defaultTokenCounter(
      UserMessage("what is in this chart?")
    )
    ContextPruning.defaultTokenCounter(asked) shouldBe ContextPruning.defaultTokenCounter(asked)
  }

  it should "count a message with an inline document, document included" in {
    val bytes = org.llm4s.media.MediaSource.Inline(IArray.fill[Byte](64 * 1024)(1))
    val pdf   = ContentPart.document(bytes, org.llm4s.media.MediaType.Pdf).value
    val asked = ToolMessage(Vector(ContentPart.Text("report"), pdf), ToolCallId("c1"))
    ContextPruning.defaultTokenCounter(asked) should be > ContextPruning.defaultTokenCounter(
      ToolMessage("report", ToolCallId("c1"))
    )
    ContextPruning.defaultTokenCounter(asked) shouldBe ContextPruning.defaultTokenCounter(asked)
  }

  "ContextPruning.tokenizerFrom" should "fall back to no tokenizer, so words are counted, when cl100k_base fails to load" in {
    ContextPruning.tokenizerFrom(Left(org.llm4s.error.TokenizerError.notAvailable("cl100k_base"))) shouldBe None
    val loaded = org.llm4s.context.ConversationTokenCounter.cl100k().value
    ContextPruning.tokenizerFrom(Right(loaded)) shouldBe Some(loaded)
  }

  "ContextPruning.countWith" should "count words times 1.3 when no tokenizer loaded" in {
    val tenWords = UserMessage("one two three four five six seven eight nine ten")
    ContextPruning.countWith(None)(tenWords) shouldBe 13
    ContextPruning.wordEstimate(tenWords) shouldBe 13
    ContextPruning.countWith(None)(
      ToolMessage("""{"a":1,"b":2}""", ToolCallId("c1"))
    ) shouldBe 1 // one "word": why cl100k is the default
  }

  it should "count a tool result containing special-token names instead of failing" in {
    val page = "a page about LLMs: <|endoftext|> ends a document, <|fim_prefix|> starts a fill-in"
    ContextPruning.defaultTokenCounter(ToolMessage(page, ToolCallId("c1"))) should be > 10
    ContextPruning
      .prune(
        Seq(UserMessage("q1"), AssistantMessage("a1"), UserMessage(page)),
        ContextWindowConfig(maxTokens = Some(8_000)),
        ContextPruning.defaultTokenCounter
      )
      .last shouldBe UserMessage(page)
  }

  it should "let pruning drop an old tool result that does not fit maxTokens" in {
    val old = Seq(
      UserMessage("q1"),
      AssistantMessage(None, Seq(ToolCall(ToolCallId("c1"), "search", ujson.Obj("q" -> "items")))),
      ToolMessage(minifiedJson, ToolCallId("c1")),
      AssistantMessage("a1")
    )
    val current = UserMessage("q2")
    val cfg     = ContextWindowConfig(maxTokens = Some(8_000))
    val sent    = ContextPruning.prune(old :+ current, cfg, ContextPruning.defaultTokenCounter)
    sent.collect { case t: ToolMessage => t } shouldBe empty
    sent.last shouldBe current
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
    val strategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 1000)
    val keep     = ContextPruning.prune(msgs, ContextWindowConfig(pruningStrategy = strategy), sized)
    keep.head shouldBe msgs.head
    val drop = ContextPruning.prune(
      msgs,
      ContextWindowConfig(pruningStrategy = strategy, preserveSystemMessage = false),
      sized
    )
    drop.head shouldBe a[UserMessage]
  }

  it should "keep at least minRecentTurns turns" in {
    val msgs = convo(20)
    val cfg = ContextWindowConfig(
      pruningStrategy = PruningStrategy.AdaptiveWindowing(contextWindowSize = 4_000),
      minRecentTurns = 5
    )
    ContextPruning.prune(msgs, cfg, sized).length should be >= 10
  }

  it should "take the smaller of its window and maxTokens, and maxMessages as well" in {
    val msgs = convo(20)
    // a 6,000-token window holds 15 messages, cut to 14 to start on a user message
    val adaptive = PruningStrategy.AdaptiveWindowing(contextWindowSize = 10_000)
    ContextPruning.prune(msgs, ContextWindowConfig(pruningStrategy = adaptive), sized).length shouldBe 14
    val tokens = ContextWindowConfig(maxTokens = Some(2_400), pruningStrategy = adaptive)
    ContextPruning.prune(msgs, tokens, sized).length shouldBe 6
    val count = ContextWindowConfig(maxMessages = Some(4), pruningStrategy = adaptive)
    ContextPruning.prune(msgs, count, sized).length shouldBe 4
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

  // ---- maxTokens and maxMessages together (#2729) ----

  private def bothStrategies = Seq(PruningStrategy.OldestFirst, PruningStrategy.MiddleOut)

  "ContextPruning.prune with both limits" should "prune a history over maxMessages but under maxTokens" in {
    val msgs = convo(10) // 20 messages, 8,000 tokens
    bothStrategies.foreach { st =>
      val cfg = ContextWindowConfig(maxTokens = Some(100_000), maxMessages = Some(6), pruningStrategy = st)
      withClue(st)(ContextPruning.prune(msgs, cfg, sized).length shouldBe 6)
    }
  }

  it should "keep within maxTokens when maxMessages alone would allow more" in {
    val msgs = convo(10)
    bothStrategies.foreach { st =>
      val cfg    = ContextWindowConfig(maxTokens = Some(2_400), maxMessages = Some(15), pruningStrategy = st)
      val pruned = ContextPruning.prune(msgs, cfg, sized)
      withClue(st)(pruned.length shouldBe 6)
    }
  }

  it should "keep within maxMessages when maxTokens alone would allow more" in {
    val msgs = convo(10)
    bothStrategies.foreach { st =>
      val cfg = ContextWindowConfig(maxTokens = Some(6_000), maxMessages = Some(4), pruningStrategy = st)
      withClue(st)(ContextPruning.prune(msgs, cfg, sized).length shouldBe 4)
    }
  }

  it should "leave a history within both limits untouched" in {
    val msgs = convo(3)
    val cfg  = ContextWindowConfig(maxTokens = Some(2_400), maxMessages = Some(6))
    ContextPruning.prune(msgs, cfg, sized) shouldBe msgs
  }

  it should "count a preserved system message against both limits" in {
    val msgs = SystemMessage("s") +: convo(10)
    val kept = ContextPruning.prune(msgs, ContextWindowConfig(maxTokens = Some(2_000), maxMessages = Some(9)), sized)
    kept shouldBe msgs.head +: msgs.takeRight(4) // 2,000 tokens: the system message and four more
    ContextPruning.prune(msgs, ContextWindowConfig(maxTokens = Some(9_000), maxMessages = Some(5)), sized) shouldBe kept
  }

  "ContextPruning.prune under MiddleOut" should "fit maxTokens, keeping the start and the end" in {
    val msgs   = convo(10)
    val cfg    = ContextWindowConfig(maxTokens = Some(2_400), pruningStrategy = PruningStrategy.MiddleOut)
    val pruned = ContextPruning.prune(msgs, cfg, sized)
    pruned.map(sized).sum should be <= 2_400
    pruned.head shouldBe msgs.head
    pruned.last shouldBe msgs.last
  }

  it should "send no message twice when the limit is larger than the earlier history" in {
    val msgs = convo(3)
    val cfg = ContextWindowConfig(
      maxTokens = Some(2_000),
      maxMessages = Some(50),
      pruningStrategy = PruningStrategy.MiddleOut
    )
    val pruned = ContextPruning.prune(msgs, cfg, sized)
    pruned.distinct shouldBe pruned
    pruned.map(sized).sum should be <= 2_000
  }

  // ---- minRecentTurns (#2728) ----

  "ContextPruning.prune with minRecentTurns" should "keep only the current turn past the limit by default" in {
    texts(ContextPruning.prune(convo(5), ContextWindowConfig(maxMessages = Some(1)), sized)) shouldBe
      texts(convo(5).takeRight(2))
  }

  it should "keep that many recent turns whole past either limit, under every strategy" in {
    val msgs = convo(6)
    val strategies = bothStrategies ++ Seq(
      PruningStrategy.RecentTurnsOnly(1),
      PruningStrategy.Custom(_ => Seq.empty),
      PruningStrategy.AdaptiveWindowing(contextWindowSize = 1000)
    )
    for {
      st  <- strategies
      cfg <- Seq(ContextWindowConfig(maxMessages = Some(2)), ContextWindowConfig(maxTokens = Some(800)))
    } withClue(s"$st $cfg") {
      ContextPruning.prune(msgs, cfg.withMinRecentTurns(3).withPruningStrategy(st), sized) shouldBe msgs.takeRight(6)
    }
  }

  it should "keep a protected turn's tool calls and results" in {
    val cfg     = ContextWindowConfig(maxMessages = Some(1), minRecentTurns = 2)
    val lastTwo = toolHistory.tail // q1 with its tool exchange, and q2
    ContextPruning.prune(Seq(UserMessage("q0"), AssistantMessage("a0")) ++ lastTwo, cfg, sized) shouldBe lastTwo
  }

  it should "spend what the protected turns leave on earlier history" in {
    val cfg = ContextWindowConfig(maxMessages = Some(8), minRecentTurns = 2)
    ContextPruning.prune(convo(6), cfg, sized) shouldBe convo(6).takeRight(8)
  }

  it should "count RecentTurnsOnly's turns from the end, the protected ones included" in {
    val cfg = ContextWindowConfig(
      maxMessages = Some(6),
      minRecentTurns = 2,
      pruningStrategy = PruningStrategy.RecentTurnsOnly(3)
    )
    ContextPruning.prune(convo(6), cfg, sized) shouldBe convo(6).takeRight(6)
  }

  "ContextPruning.prune with RecentTurnsOnly" should "keep that many turns when no other limit is set" in {
    val cfg = ContextWindowConfig(pruningStrategy = PruningStrategy.RecentTurnsOnly(2))
    ContextPruning.prune(convo(6), cfg, sized) shouldBe convo(6).takeRight(4)
    ContextPruning.prune(SystemMessage("s") +: convo(6), cfg, sized) shouldBe SystemMessage("s") +: convo(6).takeRight(
      4
    )
  }

  it should "keep that many turns under limits the history is within" in {
    val cfg = ContextWindowConfig(
      maxTokens = Some(100_000),
      maxMessages = Some(100),
      pruningStrategy = PruningStrategy.RecentTurnsOnly(2)
    )
    ContextPruning.prune(convo(6), cfg, sized) shouldBe convo(6).takeRight(4)
  }

  it should "leave a history of no more turns untouched" in {
    val cfg = ContextWindowConfig(pruningStrategy = PruningStrategy.RecentTurnsOnly(6))
    ContextPruning.prune(convo(6), cfg, sized) shouldBe convo(6)
  }

  it should "drop the oldest of its turns when they exceed maxTokens or maxMessages" in {
    val strategy = PruningStrategy.RecentTurnsOnly(4) // eight messages, 3,200 tokens
    ContextPruning.prune(
      convo(6),
      ContextWindowConfig(maxTokens = Some(1_600), pruningStrategy = strategy),
      sized
    ) shouldBe
      convo(6).takeRight(4)
    ContextPruning.prune(
      convo(6),
      ContextWindowConfig(maxMessages = Some(6), pruningStrategy = strategy),
      sized
    ) shouldBe
      convo(6).takeRight(6)
  }

  it should "keep minRecentTurns turns when its own count is lower, and the current turn at zero" in {
    val low = ContextWindowConfig(minRecentTurns = 3, pruningStrategy = PruningStrategy.RecentTurnsOnly(1))
    ContextPruning.prune(convo(6), low, sized) shouldBe convo(6).takeRight(6)
    val zero = ContextWindowConfig(pruningStrategy = PruningStrategy.RecentTurnsOnly(0))
    ContextPruning.prune(convo(6), zero, sized) shouldBe convo(6).takeRight(2)
  }

  it should "keep a kept turn's tool calls with their results" in {
    val cfg    = ContextWindowConfig(pruningStrategy = PruningStrategy.RecentTurnsOnly(1))
    val pruned = ContextPruning.prune(toolHistory :+ AssistantMessage("a2"), cfg, sized)
    texts(pruned) shouldBe Seq("sys", "q2", "a2")
    Message.validateConversation(pruned.toList) shouldBe Right(())
  }

  it should "keep the whole history when it has fewer turns, and treat a value below 1 as 1" in {
    val msgs = convo(2)
    ContextPruning.prune(msgs, ContextWindowConfig(maxMessages = Some(1), minRecentTurns = 9), sized) shouldBe msgs
    ContextPruning.prune(msgs, ContextWindowConfig(maxMessages = Some(1), minRecentTurns = 0), sized) shouldBe
      msgs.takeRight(2)
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
      ToolMessage("a long earlier tool result", ToolCallId("0")),
      AssistantMessage("a1"),
      UserMessage("q2"),
      AssistantMessage(None, Seq(call("1"))),
      ToolMessage("the current tool result", ToolCallId("1"))
    )
    // copies every assistant and tool message, truncating tool results: none is the same reference
    val copying = PruningStrategy.Custom(_.map {
      case t: ToolMessage      => ToolMessage(t.content.take(6), t.toolCallId)
      case a: AssistantMessage => AssistantMessage(a.text, a.toolCalls)
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
