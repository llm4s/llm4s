package org.llm4s.agent.harness

import org.llm4s.agent.{ AgentId, CompletionFixture, ScriptedLLMClient }
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.middleware.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.agent.graph.toolloop.ToolLoopFixtures.*
import org.llm4s.context.ConversationTokenCounter
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.*
import org.llm4s.media.{ MediaSource, MediaType }
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import scala.jdk.CollectionConverters.*

/** Stage 3 slice F4 (#1908, design §4.34): `SummarizationMiddleware` through the tool loop. */
class SummarizationMiddlewareSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {

  private val agentA = AgentId.unsafe("a")

  /** A model that answers `a<n>` to its n-th request (from 1), unless `play` says otherwise, recording each request. */
  final private class Model(
    play: PartialFunction[(Int, Vector[Message]), Result[AssistantMessage]] = PartialFunction.empty
  ) extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      seen.add(messages)
      val n = seen.size
      play.applyOrElse((n, messages), (_: (Int, Vector[Message])) => Right(AssistantMessage(s"a$n"))).map(completion(_))
    }
    def request(i: Int): Vector[Message] = seen.get(i)
    def last: Vector[Message]            = seen.get(seen.size - 1)
  }

  /** A compressor that records what it was given and answers `S<n>: <contents folded>`. */
  final private class Recording(answer: Option[String] = None) extends SummaryCompressor {
    val calls = new CopyOnWriteArrayList[(Option[String], Vector[Message])]()
    def compress(
      previous: Option[String],
      messages: Vector[Message],
      maxTokens: Int,
      context: ModelContext
    ): Result[String] = {
      calls.add(previous -> messages)
      Right(answer.getOrElse(s"S${calls.size}: " + messages.map(_.content).mkString(",")))
    }
    def call(i: Int): (Option[String], Vector[Message]) = calls.get(i)
  }

  private def loopOf(model: ModelStep, stack: Seq[AgentMiddleware], tools: Seq[AgentTool[?]] = Nil): ToolLoop =
    ToolLoop
      .build("summary", "1", agentA, Vector(LoopAgent(agentA, model, ToolSet.of(tools*).value).withMiddleware(stack)))
      .value

  private def summaryOf(request: Vector[Message]): Option[String] =
    request.collectFirst {
      case u: UserMessage if u.content.startsWith(SummarizationMiddleware.SummaryHeader) =>
        u.content.stripPrefix(SummarizationMiddleware.SummaryHeader + "\n")
    }

  private def contents(messages: Vector[Message]): Vector[String] = messages.map(_.content)

  /** Runs `queries` as successive turns of one thread; the last turn's state. */
  private def turns(loop: ToolLoop, queries: String*)(using
    store: InMemoryCheckpointer,
    thread: ThreadId
  ): ThreadState =
    queries.map(q => GraphRuntime(store).start(thread, loop.graph, AgentInput(q)).awaited.value.answered._1).last

  private val echo: AgentTool[Echo] =
    AgentTool(
      AgentToolSpec[Echo](
        "echo",
        "Echo",
        Schema.`object`[Echo]("echo").withRequiredField("text", Schema.string("text"))
      )
    )((a, _) => ToolOutcome.Success(ToolContent.Json(ujson.Str(a.text))))

  "SummarizationMiddleware" should "fold all but keepRecent messages into a summary sent in the same request (rule 3)" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("fold")
    val model                         = new Model()
    val compressor                    = new Recording()
    val mw    = new SummarizationMiddleware(SummaryTrigger.Messages(6), keepRecent = 2, compressor = compressor)
    val state = turns(loopOf(model, Seq(mw)), "u1", "u2", "u3", "u4")
    // the fourth request had 7 messages: the first five folded, the last two kept
    compressor.calls.size shouldBe 1
    compressor.call(0) shouldBe (None -> Vector(
      UserMessage("u1"),
      AssistantMessage("a1"),
      UserMessage("u2"),
      AssistantMessage("a2"),
      UserMessage("u3")
    ))
    val fourth = model.request(3)
    summaryOf(fourth).value shouldBe "S1: u1,a1,u2,a2,u3"
    contents(fourth).drop(1) shouldBe Vector("a3", "u4")
    // the summary is kept with the last folded message's stored id; the history is untouched
    val history = state.get(Messages.key).value
    contents(history.map(_.message)) shouldBe Vector("u1", "a1", "u2", "a2", "u3", "a3", "u4", "a4")
    state.get(SummarizationMiddleware.key).value.value shouldBe ConversationSummary("S1: u1,a1,u2,a2,u3", history(4).id)
  }

  it should "send the stored summary in place of what it covers, and extend it, never recompute it" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("extend")
    val model                         = new Model()
    val compressor                    = new Recording()
    val mw    = new SummarizationMiddleware(SummaryTrigger.Messages(6), keepRecent = 2, compressor = compressor)
    val state = turns(loopOf(model, Seq(mw)), "u1", "u2", "u3", "u4", "u5", "u6")
    // the fifth request is within the trigger with the summary applied
    contents(model.request(4)).drop(1) shouldBe Vector("a3", "u4", "a4", "u5")
    summaryOf(model.request(4)).value shouldBe "S1: u1,a1,u2,a2,u3"
    // the sixth fires again: the old summary and only the messages after it
    compressor.calls.size shouldBe 2
    compressor.call(1) shouldBe (Some("S1: u1,a1,u2,a2,u3") -> Vector(
      AssistantMessage("a3"),
      UserMessage("u4"),
      AssistantMessage("a4"),
      UserMessage("u5")
    ))
    contents(model.request(5)).drop(1) shouldBe Vector("a5", "u6")
    state.get(SummarizationMiddleware.key).value.value.throughMessageId shouldBe state.get(Messages.key).value(8).id
  }

  it should "never separate a tool call from its results" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("pairs")
    val model = new Model({ case (1, _) =>
      Right(
        AssistantMessage(
          None,
          Seq(ToolCall("c1", "echo", ujson.Obj("text" -> "r1")), ToolCall("c2", "echo", ujson.Obj("text" -> "r2")))
        )
      )
    })
    val compressor = new Recording()
    // the last two of [u1, call, r1, r2] would start at a result: the cut moves back to the call
    val mw = new SummarizationMiddleware(SummaryTrigger.Messages(3), keepRecent = 2, compressor = compressor)
    turns(loopOf(model, Seq(mw), Seq(echo)), "u1")
    compressor.call(0)._2 shouldBe Vector(UserMessage("u1"))
    val second = model.request(1)
    second.drop(1).head shouldBe a[AssistantMessage]
    second.drop(1).collect { case t: ToolMessage => t.content } shouldBe Vector("r1", "r2")
    ToolResultRule.check(second).isRight shouldBe true
  }

  it should "keep a message an outer wrapper adds, never counting it as summarised" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("annotated")
    val turn                          = new java.util.concurrent.atomic.AtomicInteger(0)
    // an outer wrapper that puts a fresh note before the history on every request
    val annotate = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("annotate")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        next(request.withMessages(UserMessage(s"note ${turn.incrementAndGet()}") +: request.messages))
    }
    val model      = new Model()
    val compressor = new Recording()
    val mw         = new SummarizationMiddleware(SummaryTrigger.Messages(6), keepRecent = 2, compressor = compressor)
    turns(loopOf(model, Seq(annotate, mw)), "u1", "u2", "u3", "u4")
    // the note is never folded, and is sent after the summary
    compressor.call(0)._2.map(_.content) should not contain "note 3"
    compressor.call(0)._2.map(_.content).filter(_.startsWith("note")) shouldBe empty
    contents(model.last).drop(1) should contain("note 4")
    // a later request keeps its new note while the stored summary replaces the history it covers
    turns(loopOf(model, Seq(annotate, mw)), "u5")
    contents(model.last).drop(1).head shouldBe "note 5"
  }

  it should "never fold across a gap in the history it was sent, so a summary covers only what it saw" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("gap")
    // an outer wrapper that never sends the first answer, as a pruning wrapper or a handoff's view leaves a gap
    val gap = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("gap")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] =
        next(request.withMessages(request.messages.filterNot(_.content == "a1")))
    }
    val model      = new Model()
    val compressor = new Recording()
    val mw         = new SummarizationMiddleware(SummaryTrigger.Messages(4), keepRecent = 1, compressor = compressor)
    val state      = turns(loopOf(model, Seq(gap, mw)), "u1", "u2", "u3", "u4")
    // only u1 could be folded without skipping a1, which the compressor never saw; after it, the gap blocks folding
    compressor.calls.asScala.map(_._2.map(_.content)).toVector shouldBe Vector(Vector("u1"))
    state.get(SummarizationMiddleware.key).value.value.throughMessageId shouldBe state.get(Messages.key).value.head.id
  }

  it should "send the request as it is when no cut is left" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("no-cut")
    val model                         = new Model()
    val compressor                    = new Recording()
    val mw = new SummarizationMiddleware(SummaryTrigger.Messages(1), keepRecent = 5, compressor = compressor)
    turns(loopOf(model, Seq(mw)), "u1", "u2")
    compressor.calls.size shouldBe 0
    contents(model.last) shouldBe Vector("u1", "a1", "u2")
  }

  it should "fire on the estimated tokens of the request, system prompt included" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("tokens")
    val model                         = new Model()
    val compressor                    = new Recording()
    val mw = new SummarizationMiddleware(
      SummaryTrigger.Tokens(100),
      keepRecent = 1,
      compressor = compressor,
      tokenCounter = m => if m.role == MessageRole.System then 90 else 5
    )
    val loop = ToolLoop
      .build(
        "summary",
        "1",
        agentA,
        Vector(LoopAgent(agentA, model, ToolSet.empty).withSystemPrompt(Some("system")).withMiddleware(Seq(mw)))
      )
      .value
    turns(loop, "u1", "u2")
    // 90 + 3 * 5 > 100: the second request fires, keeping its system prompt first
    compressor.calls.size shouldBe 1
    model.request(1).head shouldBe SystemMessage("system")
    summaryOf(model.request(1)).value should startWith("S1")
  }

  "The summary" should "be cut to maxSummaryTokens when stored and when a stored one is sent (rule 12)" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("bounded")
    val model                         = new Model()
    val compressor                    = new Recording(Some("x" * 10000))
    val mw = new SummarizationMiddleware(
      SummaryTrigger.Messages(2),
      keepRecent = 1,
      compressor = compressor,
      maxSummaryTokens = 10
    )
    val loop  = loopOf(model, Seq(mw))
    val state = turns(loop, "u1", "u2")
    state.get(SummarizationMiddleware.key).value.value.text shouldBe "x" * 40
    summaryOf(model.last).value shouldBe "x" * 40
    // a stored summary over the bound, written another way, is cut when it is sent
    val rt      = GraphRuntime(store)
    val through = state.get(Messages.key).value.last.id
    rt.updateState(
      thread,
      loop.graph,
      store.latest(thread).value.value.checkpoint.id,
      StateUpdate.update(SummarizationMiddleware.key, Some(ConversationSummary("y" * 5000, through)))
    ).isRight shouldBe true
    val again = new Model()
    turns(loopOf(again, Seq(new SummarizationMiddleware(SummaryTrigger.Messages(1000), 1, compressor, 10))), "u3")
    summaryOf(again.last).value shouldBe "y" * 40
  }

  it should "not be sent once its message is gone from the history" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("stale")
    val compressor                    = new Recording()
    val mw                            = new SummarizationMiddleware(SummaryTrigger.Messages(1000), 1, compressor)
    turns(loopOf(new Model(), Seq(mw)), "u1")
    val rt = GraphRuntime(store)
    rt.updateState(
      thread,
      loopOf(new Model(), Seq(mw)).graph,
      store.latest(thread).value.value.checkpoint.id,
      StateUpdate.update(SummarizationMiddleware.key, Some(ConversationSummary("old", "no-such-message")))
    ).isRight shouldBe true
    val model = new Model()
    turns(loopOf(model, Seq(mw)), "u2")
    summaryOf(model.last) shouldBe None
    contents(model.last) shouldBe Vector("u1", "a1", "u2")
  }

  "An output Block" should "restore the summary, so a blocked turn's summary is never kept (rule 5)" in {
    val guard = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("guard")
      override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
        if (answer.contains("secret")) Left(GuardrailBlocked("secrets", "no")) else Right(BoundaryResult(answer))
    }
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("blocked")
    val model                         = new Model({ case (2, _) => Right(AssistantMessage("the secret")) })
    val compressor                    = new Recording()
    val mw   = new SummarizationMiddleware(SummaryTrigger.Messages(2), keepRecent = 1, compressor = compressor)
    val loop = loopOf(model, Seq(guard, mw))
    val rt   = GraphRuntime(store)
    rt.start(thread, loop.graph, AgentInput("u1")).awaited.value.answered
    val (blocked, error) = rt.start(thread, loop.graph, AgentInput("u2")).awaited.value.failed
    error shouldBe a[GuardrailBlocked]
    compressor.calls.size shouldBe 1
    blocked.get(SummarizationMiddleware.key).value shouldBe None
  }

  "A model step that fails after compressing" should "commit no summary, and compress again on recover (rules 4, 10)" in {
    val broken                        = new AtomicBoolean(true)
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("recovered")
    val model = new Model({
      case (2, _) if broken.getAndSet(false) => Left(ValidationError("model", "down"))
    })
    val compressor = new Recording()
    val mw         = new SummarizationMiddleware(SummaryTrigger.Messages(2), keepRecent = 1, compressor = compressor)
    val loop       = loopOf(model, Seq(mw))
    val rt         = GraphRuntime(store)
    rt.start(thread, loop.graph, AgentInput("u1")).awaited.value.answered
    val (failed, _) = rt.start(thread, loop.graph, AgentInput("u2")).awaited.value.failed
    failed.get(SummarizationMiddleware.key).value shouldBe None
    val (state, _) = rt.recover(thread, loop.graph).awaited.value.answered
    compressor.calls.size shouldBe 2
    compressor.call(1) shouldBe compressor.call(0)
    state.get(SummarizationMiddleware.key).value.value.text shouldBe "S2: u1,a1"
  }

  "The model compressor" should "call through the accounted client, so the thread's usage counts it (rule 11)" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("accounted")
    val client                        = ScriptedLLMClient.of(CompletionFixture.withUsage("the summary", 100, 7))
    val mw    = new SummarizationMiddleware(SummaryTrigger.Messages(2), 1, SummaryCompressor.model(client))
    val model = new Model()
    val state = turns(loopOf(model, Seq(mw)), "u1", "u2")
    summaryOf(model.last).value shouldBe "the summary"
    val usage = state.get(LoopKeys.usage).value
    usage.byModel.keySet should contain("test-model")
    usage.byModel("test-model").inputTokens shouldBe 100L
    // it was sent the new messages, and asked for at most maxSummaryTokens
    val (conversation, options) = client.calls.head
    conversation.messages.last.content should (include("user: u1").and(include("assistant: a1")))
    options.maxTokens shouldBe Some(SummarizationMiddleware.DefaultMaxSummaryTokens)
  }

  it should "fold a transcript over maxInputTokens in chunks, each bounded (rule 12)" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("chunks")
    val client = ScriptedLLMClient.of(CompletionFixture.simple("first"), CompletionFixture.simple("second"))
    val mw = new SummarizationMiddleware(
      SummaryTrigger.Messages(4),
      1,
      SummaryCompressor.model(client, maxInputTokens = 20)
    )
    val long  = "z" * 400
    val model = new Model()
    turns(loopOf(model, Seq(mw)), long, "u2", "u3")
    client.callCount shouldBe 2
    // each call is sent at most a chunk of transcript, plus the summary so far and fixed text
    client.calls.foreach((conversation, _) => conversation.messages.last.content.length should be < (20 * 4 + 60))
    client.calls(1)._1.messages.last.content should include("first")
    client.calls.head._1.messages.last.content should include("[cut:")
  }

  it should "name media parts in the transcript by kind, never sending their bytes" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("s"))
    val image =
      ContentPart.image(MediaSource.Inline(IArray.fill[Byte](64)(7)), MediaType.Png).value
    val context = new ModelContext(testRunContext(), ThreadState.empty(Map.empty), agentA)
    SummaryCompressor
      .model(client)
      .compress(
        None,
        Vector(UserMessage(Vector(ContentPart.Text("look"), image)), AssistantMessage("ok")),
        100,
        context
      )
      .value shouldBe "s"
    val sent = client.calls.head._1.messages.last.content
    sent should include("user: look [image (inline")
    sent should include("image/png")
  }

  "The digest compressor" should "digest with no model call, extend by appending, and stay within the bound" in {
    given store: InMemoryCheckpointer = new InMemoryCheckpointer
    given thread: ThreadId            = ThreadId("digest")
    val counter                       = ConversationTokenCounter.openAI().value
    val model                         = new Model()
    val mw    = new SummarizationMiddleware(SummaryTrigger.Messages(2), 1, SummaryCompressor.digest(counter))
    val state = turns(loopOf(model, Seq(mw)), "use id: ABC123", "then id: XYZ789", "and more")
    val text  = state.get(SummarizationMiddleware.key).value.value.text
    text should (include("ABC123").and(include("XYZ789")))

    val small = new SummarizationMiddleware(
      SummaryTrigger.Messages(2),
      1,
      SummaryCompressor.digest(counter),
      maxSummaryTokens = 20
    )
    val bounded =
      turns(loopOf(new Model(), Seq(small)), (1 to 6).map(i => s"step id: K$i must be kept.")*)(using
        store,
        ThreadId("digest-small")
      )
    bounded.get(SummarizationMiddleware.key).value.value.text.length should be <= 80
  }

  it should "digest a block with media, naming each part, and never drop a message it was given" in {
    val counter = ConversationTokenCounter.openAI().value
    val image =
      ContentPart.image(MediaSource.Url(java.net.URI.create("https://example.com/c.png")), MediaType.Png).value
    val context = new ModelContext(testRunContext(), ThreadState.empty(Map.empty), agentA)
    val text = SummaryCompressor
      .digest(counter)
      .compress(
        None,
        Vector(
          UserMessage(Vector(ContentPart.Text("chart id: CH42"), image)),
          AssistantMessage("decided to use it."),
          UserMessage("[HISTORY_SUMMARY] looks like a digest, id: K9"),
          AssistantMessage("ok")
        ),
        500,
        context
      )
      .value
    text should include("CH42")
    text should include("K9")
  }

  "An agent without SummarizationMiddleware" should "register no summary key (rule 7)" in {
    val (state, _) = runInMemory(loopOf(new Model(), Seq.empty).graph, AgentInput("q")).answered
    state.get(SummarizationMiddleware.key).left.value shouldBe a[GraphError.UnknownStateKey]
  }

  "A stored summary" should "round-trip through the key's codec" in {
    val summary = Some(ConversationSummary("text", "m-1"))
    val json    = upickle.default.writeJs(summary)(using SummarizationMiddleware.key.stateCodec)
    upickle.default.read[Option[ConversationSummary]](json)(using
      SummarizationMiddleware.key.stateCodec
    ) shouldBe summary
  }

  "The limits" should "refuse values below 1" in {
    an[IllegalArgumentException] should be thrownBy SummaryTrigger.Messages(0)
    an[IllegalArgumentException] should be thrownBy SummaryCompressor.model(ScriptedLLMClient.of(), maxInputTokens = 15)
    an[IllegalArgumentException] should be thrownBy SummaryTrigger.Tokens(0)
    an[IllegalArgumentException] should be thrownBy new SummarizationMiddleware(
      SummaryTrigger.Messages(1),
      0,
      new Recording()
    )
    an[IllegalArgumentException] should be thrownBy new SummarizationMiddleware(
      SummaryTrigger.Messages(1),
      1,
      new Recording(),
      maxSummaryTokens = 0
    )
  }

  "Alignment" should "match a subsequence of the history from the end, leaving inserted messages unmatched" in {
    val history = Vector("u1", "a1", "u2", "a2").zipWithIndex.map((c, i) =>
      StoredMessage(s"m$i", if c.startsWith("u") then UserMessage(c) else AssistantMessage(c))
    )
    SummarizationMiddleware.align(
      Vector(UserMessage("u1"), UserMessage("note"), UserMessage("u2"), AssistantMessage("a2")),
      history
    ) shouldBe Vector(Some(0), None, Some(2), Some(3))
  }
}
