package org.llm4s.agent.graph.toolloop

import org.llm4s.agent.AgentId
import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.middleware.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.Schema
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import org.llm4s.agent.guardrails.{ CompositeGuardrail, LLMGuardrail }

import java.util.concurrent.{ CopyOnWriteArrayList, CountDownLatch, TimeUnit }
import scala.concurrent.duration.*
import scala.jdk.CollectionConverters.*

/**
 * The kernel hooks of Stage 3 slice C1 (#1902, design §4.22): hooks that read thread state and write their own
 * keys, prompt sections, usage the loop counts, and an output Block that restores every key the turn wrote.
 */
class KernelHooksSpec extends AnyFlatSpec with Matchers with EitherValues with OptionValues {
  import ToolLoopFixtures.*

  private val agentA = AgentId.unsafe("a")

  /** A middleware key: every update is appended. */
  private val notes: StateKey[Vector[String], String] = StateKey.appending[String]("notes")

  /** A tool key: each update replaces the value. */
  private val files: StateKey[Map[String, String], Map[String, String]] =
    StateKey.replace[Map[String, String]]("files", Map.empty)

  /** A model that plays `turns` in order and records every request it was sent. */
  final private class ScriptedModel(turns: (Vector[Message] => AssistantMessage)*) extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet, call: ModelCall): Result[Completion] = {
      val turn = seen.size
      seen.add(messages)
      turns
        .lift(turn)
        .map(play => Right(completion(play(messages))))
        .getOrElse(Left(ValidationError("model", "no turn")))
    }
    def calls: Int = seen.size
  }

  private def answer(text: String): Vector[Message] => AssistantMessage = _ => AssistantMessage(text)

  /** A tool writing its argument into `files`. */
  private val write = AgentTool(
    AgentToolSpec[Echo](
      "write",
      "writes a file",
      Schema.`object`[Echo]("args").withRequiredField("text", Schema.string("t"))
    ),
    writes = Set(files)
  ) { (args, context) =>
    val current = context.state.get(files).getOrElse(Map.empty)
    ToolOutcome.Success(
      ToolContent.Json(ujson.Str("ok")),
      StateUpdate.update(files, current + (args.text -> args.text))
    )
  }

  /** A model turn calling `write` with `name`. */
  private def callWrite(name: String): Vector[Message] => AssistantMessage =
    _ => AssistantMessage(None, Seq(ToolCall(s"c-$name", "write", ujson.Obj("text" -> name))))

  /** An output guardrail blocking any answer that mentions a secret. */
  private def secretGuard: AgentMiddleware = middleware(
    "guard",
    writing = Set.empty,
    after = (t, _) => if t.contains("secret") then Left(GuardrailBlocked("secrets", "no")) else Right(BoundaryResult(t))
  )

  private def systemOf(request: Vector[Message]): Option[String] =
    request.collectFirst { case s: SystemMessage => s.content }

  private def notesOf(state: ThreadState): Vector[String] = state.get(notes).value

  /** A middleware built from optional hooks, writing `writes`. */
  private def middleware(
    name: String,
    writing: Set[StateKey[?, ?]] = Set(notes),
    before: (String, ModelContext) => Result[BoundaryResult] = (t, _) => Right(BoundaryResult(t)),
    after: (String, ModelContext) => Result[BoundaryResult] = (t, _) => Right(BoundaryResult(t)),
    sections: ModelContext => Result[Vector[PromptSection]] = _ => Right(Vector.empty),
    model: (ModelRequest, ModelContext, ModelRequest => Result[ModelResponse]) => Result[ModelResponse] =
      (r, _, next) => next(r)
  ): AgentMiddleware = new AgentMiddleware {
    val id: MiddlewareId                                                                   = MiddlewareId(name)
    override def writes: Set[StateKey[?, ?]]                                               = writing
    override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] = before(input, context)
    override def afterAgent(text: String, context: ModelContext): Result[BoundaryResult]   = after(text, context)
    override def promptSections(context: ModelContext): Result[Vector[PromptSection]]      = sections(context)
    override def wrapModelCall(request: ModelRequest, context: ModelContext)(
      next: ModelRequest => Result[ModelResponse]
    ): Result[ModelResponse] = model(request, context, next)
  }

  private def loopOf(
    model: ModelStep,
    stack: Seq[AgentMiddleware],
    tools: Seq[AgentTool[?]] = Nil,
    configure: LoopAgent => LoopAgent = identity
  ): ToolLoop =
    ToolLoop
      .build(
        "hooks",
        "1",
        agentA,
        Vector(configure(LoopAgent(agentA, model, ToolSet.of(tools*).value).withMiddleware(stack)))
      )
      .value

  /** The run's failure, unwrapped from the `NodeFailed` its task failed with. */
  private def causeOf(result: RunResult[?]): org.llm4s.error.LLMError =
    result.failed._2 match {
      case GraphError.NodeFailed(_, _, cause) => cause
      case other                              => other
    }

  "Every hook" should "read the committed state and write its middleware's own keys" in {
    val sawAtModel = new CopyOnWriteArrayList[Vector[String]]()
    val sawAtAfter = new CopyOnWriteArrayList[Vector[String]]()
    val m = middleware(
      "notes",
      before =
        (t, c) => Right(BoundaryResult(t).addUpdate(StateUpdate.update(notes, s"before:${notesOf(c.state).size}"))),
      model = (r, c, next) =>
        sawAtModel.add(notesOf(c.state))
        next(r).map(_.addUpdate(StateUpdate.update(notes, "model")))
      ,
      after = (t, c) =>
        sawAtAfter.add(notesOf(c.state))
        Right(BoundaryResult(t).addUpdate(StateUpdate.update(notes, "after")))
    )
    val (state, text) = runInMemory(loopOf(ScriptedModel(answer("hi")), Seq(m)).graph, AgentInput("go")).answered
    text shouldBe "hi"
    // beforeAgent's update committed with the input task, so the first model call's snapshot holds it (rule 3)
    sawAtModel.asScala.toVector shouldBe Vector(Vector("before:0"))
    sawAtAfter.asScala.toVector shouldBe Vector(Vector("before:0", "model"))
    notesOf(state) shouldBe Vector("before:0", "model", "after")
  }

  it should "refuse a write to a key its middleware does not declare, failing the run" in {
    val undeclared = StateUpdate.update(notes, "x")
    val hooks: Seq[(String, AgentMiddleware)] = Seq(
      "beforeAgent" -> middleware("m", writing = Set.empty, before = (t, _) => Right(BoundaryResult(t, undeclared))),
      "wrapModelCall" -> middleware(
        "m",
        writing = Set.empty,
        model = (r, _, next) => next(r).map(_.addUpdate(undeclared))
      ),
      "afterAgent" -> middleware("m", writing = Set.empty, after = (t, _) => Right(BoundaryResult(t, undeclared)))
    )
    hooks.foreach { (hook, m) =>
      // another middleware declares the key, so the graph registers it; only the writer's own writes count
      val owner  = middleware("owner")
      val result = runInMemory(loopOf(ScriptedModel(answer("hi")), Seq(owner, m)).graph, AgentInput("go"))
      withClue(hook) {
        causeOf(result) match {
          case v: ValidationError => v.message should include("middleware 'm'")
          case other              => fail(s"$hook: not a ValidationError: $other")
        }
      }
    }
  }

  it should "fail the run on an undeclared write even when a later hook blocks, asks or discards it" in {
    val undeclared = StateUpdate.update(notes, "x")
    val sneaky = middleware(
      "sneaky",
      writing = Set.empty,
      before = (t, _) => Right(BoundaryResult(t, undeclared)),
      after = (t, _) => Right(BoundaryResult(t, undeclared)),
      model = (r, _, next) => next(r).map(_.addUpdate(undeclared))
    )
    val blockingIn  = middleware("blocking", before = (_, _) => Left(GuardrailBlocked("in", "no")))
    val blockingOut = middleware("blocking", after = (_, _) => Left(GuardrailBlocked("out", "no")))
    // outermost: discards the inner response, then fails the call
    val discarding =
      middleware("discarding", model = (r, _, next) => next(r).flatMap(_ => Left(ValidationError("x", "y"))))
    val cases: Seq[(String, Seq[AgentMiddleware])] = Seq(
      // beforeAgent runs in stack order: the sneaky write, then the block
      "beforeAgent then a Block" -> Seq(sneaky, blockingIn),
      // afterAgent runs in reverse: the sneaky write, then the block
      "afterAgent then a Block" -> Seq(
        blockingOut,
        middleware("sneaky", writing = Set.empty, after = (t, _) => Right(BoundaryResult(t, undeclared)))
      ),
      "a model wrapper's response discarded" -> Seq(
        discarding,
        middleware("sneaky", writing = Set.empty, model = (r, _, next) => next(r).map(_.addUpdate(undeclared)))
      )
    )
    cases.foreach { (what, stack) =>
      val result =
        runInMemory(loopOf(ScriptedModel(answer("hi")), middleware("owner") +: stack).graph, AgentInput("go"))
      withClue(what) {
        result match {
          case RunResult.Failed(_, GraphError.NodeFailed(_, _, v: ValidationError)) =>
            v.message should include("middleware 'sneaky'")
          case other => fail(s"$what: not an undeclared-write failure: $other")
        }
      }
    }
  }

  "A model wrapper that calls next twice" should "commit only the returned response's updates, and count both calls" in {
    val inner = middleware(
      "inner",
      model = (r, _, next) => next(r).map(_.addUpdate(StateUpdate.update(notes, "attempt")))
    )
    val retrying = middleware(
      "retry",
      writing = Set.empty,
      model = (r, _, next) =>
        for
          first <- next(r)
          _     <- next(r)
        yield first
    )
    val model         = ScriptedModel(answer("one"), answer("two"))
    val (state, text) = runInMemory(loopOf(model, Seq(retrying, inner)).graph, AgentInput("go")).answered
    text shouldBe "one"
    model.calls shouldBe 2
    // the discarded second response's update is never committed
    notesOf(state) shouldBe Vector("attempt")
    // both provider calls are counted (rule 11)
    val usage = state.get(LoopKeys.usage).value
    usage.requestCount shouldBe 2
    usage.inputTokens shouldBe 20
  }

  "ModelResponse.addUpdate" should "keep the inner wrappers' writes, applied innermost first" in {
    val outer =
      middleware("outer", model = (r, _, next) => next(r).map(_.addUpdate(StateUpdate.update(notes, "outer"))))
    val inner =
      middleware("inner", model = (r, _, next) => next(r).map(_.addUpdate(StateUpdate.update(notes, "inner"))))
    val (state, _) =
      runInMemory(loopOf(ScriptedModel(answer("hi")), Seq(outer, inner)).graph, AgentInput("go")).answered
    notesOf(state) shouldBe Vector("inner", "outer")
  }

  "A model wrapper answering without next" should "count no request" in {
    val cache = middleware(
      "cache",
      writing = Set.empty,
      model = (_, _, _) => Right(ModelResponse(completion(AssistantMessage("cached"))))
    )
    val model         = ScriptedModel(answer("never"))
    val (state, text) = runInMemory(loopOf(model, Seq(cache)).graph, AgentInput("go")).answered
    text shouldBe "cached"
    model.calls shouldBe 0
    state.get(LoopKeys.usage).value.requestCount shouldBe 0
  }

  it should "fail the run on an undeclared write even when a call of the step was refused at the token budget" in {
    val undeclared = StateUpdate.update(notes, "x")
    val judge = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Right(completion(AssistantMessage("ok"), prompt = 100, completion = 1))
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      def getContextWindow(): Int     = 1000
      def getReserveCompletion(): Int = 100
    }
    // its judge call uses the budget, so next is refused; it fabricates a response carrying an undeclared write
    val sneaky = middleware(
      "sneaky",
      writing = Set.empty,
      model = (r, c, next) =>
        c.accounted(judge).complete(Conversation(Seq(UserMessage("judge")))): Unit
        next(r).orElse(Right(ModelResponse(completion(AssistantMessage("fake"))))).map(_.addUpdate(undeclared))
    )
    val budget = RunConfig().withBudgets(RunBudgets.default.withMaxTokens(50))
    val result =
      runInMemory(
        loopOf(ScriptedModel(answer("never")), Seq(middleware("owner"), sneaky)).graph,
        AgentInput("go"),
        budget
      )
    result match {
      case RunResult.Failed(_, GraphError.NodeFailed(_, _, v: ValidationError)) =>
        v.message should include("middleware 'sneaky'")
      case other => fail(s"not an undeclared-write failure: $other")
    }
  }

  "ModelContext.accounted" should "count every call a hook makes through it, also when a hook makes it outside next" in {
    val judge = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Right(completion(AssistantMessage("ok"), prompt = 100, completion = 1))
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      def getContextWindow(): Int     = 1000
      def getReserveCompletion(): Int = 100
    }
    val judging = middleware(
      "judge",
      writing = Set.empty,
      before = (t, c) => c.accounted(judge).complete(Conversation(Seq(UserMessage(t)))).map(_ => BoundaryResult(t)),
      after = (t, c) =>
        c.recordUsage("embedder", TokenUsage(7, 0, 7))
        c.accounted(judge).complete(Conversation(Seq(UserMessage(t)))).map(_ => BoundaryResult(t))
    )
    val (state, _) = runInMemory(loopOf(ScriptedModel(answer("hi")), Seq(judging)).graph, AgentInput("go")).answered
    val usage      = state.get(LoopKeys.usage).value
    // the agent's call, the input judge's, the output judge's and the recorded usage
    usage.requestCount shouldBe 4
    usage.inputTokens shouldBe 10 + 100 + 100 + 7
    usage.byModel.keySet shouldBe Set("scripted", "embedder")
  }

  "Prompt sections" should "join the system prompt, cacheable ones first, each group in stack order, never stored" in {
    val a = middleware(
      "a",
      sections = _ => Right(Vector(PromptSection("a1", "A one"), PromptSection("a2", "A two", cacheable = true)))
    )
    val b          = middleware("b", sections = _ => Right(Vector(PromptSection("b1", "B one", cacheable = true))))
    val model      = ScriptedModel(answer("hi"))
    val loop       = loopOf(model, Seq(a, b), configure = _.withSystemPrompt(Some("You help.")))
    val (state, _) = runInMemory(loop.graph, AgentInput("go")).answered
    systemOf(model.seen.get(0)).value shouldBe "You help.\n\nA two\n\nB one\n\nA one"
    model.seen.get(0).count(_.role == MessageRole.System) shouldBe 1
    state.get(Messages.key).value.map(_.message.role) should not contain MessageRole.System
  }

  they should "make a system message of their own when the agent has no prompt, read from the snapshot" in {
    val m = middleware(
      "m",
      before = (t, _) => Right(BoundaryResult(t, StateUpdate.update(notes, "remembered"))),
      sections = c => c.state.get(notes).map(ns => ns.map(n => PromptSection(s"note-$n", n)))
    )
    val model = ScriptedModel(answer("hi"))
    runInMemory(loopOf(model, Seq(m)).graph, AgentInput("go")).answered
    systemOf(model.seen.get(0)).value shouldBe "remembered"
  }

  they should "fail the run on an id used twice in one request, naming it" in {
    val a      = middleware("a", sections = _ => Right(Vector(PromptSection("same", "A"))))
    val b      = middleware("b", sections = _ => Right(Vector(PromptSection("same", "B"))))
    val model  = ScriptedModel(answer("hi"))
    val result = runInMemory(loopOf(model, Seq(a, b)).graph, AgentInput("go"))
    causeOf(result).message should (include("'same'").and(include("'a'")).and(include("'b'")))
    model.calls shouldBe 0
  }

  they should "fail the run when together over the agent's budget, naming every section's size" in {
    val a     = middleware("a", sections = _ => Right(Vector(PromptSection("big", "x" * 400))))
    val b     = middleware("b", sections = _ => Right(Vector(PromptSection("small", "y" * 40))))
    val model = ScriptedModel(answer("hi"))
    val result =
      runInMemory(loopOf(model, Seq(a, b), configure = _.withMaxPromptSectionTokens(100)).graph, AgentInput("go"))
    causeOf(result).message should (include("a/big: 100")
      .and(include("b/small: 10"))
      .and(include("add 111 estimated tokens"))
      .and(
        include(
          "100"
        )
      ))
    model.calls shouldBe 0
  }

  "The tool loop" should "refuse a prompt section budget below 1" in {
    ToolLoop
      .build("hooks", "1", agentA, Vector(LoopAgent(agentA, ScriptedModel()).withMaxPromptSectionTokens(0)))
      .left
      .value
      .message should include("maxPromptSectionTokens")
  }

  "An output Block" should "restore every key the turn wrote, middleware and tool keys alike, and keep usage" in {
    // turn 1 completes, writing both keys; turn 2 writes both again, and its answer is blocked
    val scratch: StateKey[Option[String], Option[String]] = StateKey.replace[Option[String]]("scratch", None)
    val noting = middleware(
      "noting",
      writing = Set(notes, scratch),
      model = (r, c, next) => next(r).map(_.addUpdate(StateUpdate.update(notes, s"call-${notesOf(c.state).size}"))),
      before = (t, _) => Right(BoundaryResult(t, StateUpdate.update(scratch, Some(t))))
    )
    val guard = secretGuard
    val model = ScriptedModel(callWrite("one"), answer("fine"), callWrite("two"), answer("the secret"))
    val loop  = loopOf(model, Seq(guard, noting), tools = Seq(write))
    val store = new InMemoryCheckpointer
    val rt    = GraphRuntime(store)
    val t     = ThreadId("blocked-turn")

    val (after1, _) = rt.start(t, loop.graph, AgentInput("first")).awaited.value.answered
    after1.get(files).value shouldBe Map("one" -> "one")
    notesOf(after1) shouldBe Vector("call-0", "call-1")
    after1.get(scratch).value shouldBe Some("first")
    val usage1 = after1.get(LoopKeys.usage).value

    val (blocked, error) = rt.start(t, loop.graph, AgentInput("second")).awaited.value.failed
    error shouldBe a[GuardrailBlocked]
    // the closing checkpoint holds the state from before the blocked turn, but its usage
    blocked.get(files).value shouldBe Map("one" -> "one")
    notesOf(blocked) shouldBe Vector("call-0", "call-1")
    blocked.get(scratch).value shouldBe Some("first")
    blocked.get(Messages.key).value.map(_.message) shouldBe after1.get(Messages.key).value.map(_.message)
    blocked.get(LoopKeys.usage).value.requestCount shouldBe usage1.requestCount + 2
    blocked.get(LoopKeys.turn).value.priorValues shouldBe empty
    val latest = store.latest(t).value.value
    loop.graph.restore(latest.checkpoint.snapshot).value.state.get(files).value shouldBe Map("one" -> "one")
  }

  it should "restore a key a handoff source's tool wrote, when the target's answer is blocked" in {
    val agentB = AgentId.unsafe("b")
    val handoff: Vector[Message] => AssistantMessage =
      _ => AssistantMessage(None, Seq(ToolCall("h1", HandoffTools.toolName(agentB), ujson.Obj("reason" -> "over"))))
    val source = LoopAgent(agentA, ScriptedModel(callWrite("draft"), handoff), ToolSet.of(write).value)
      .withMiddleware(Seq(secretGuard))
      .withHandoffs(Vector(LoopHandoff(agentB, None, preserveContext = true)))
    val target         = LoopAgent(agentB, ScriptedModel(answer("the secret")))
    val loop           = ToolLoop.build("family", "1", agentA, Vector(source, target)).value
    val (state, error) = runInMemory(loop.graph, AgentInput("go")).failed
    error shouldBe a[GuardrailBlocked]
    state.isSet(files) shouldBe false
    state.get(LoopKeys.activeAgent).value shouldBe Some(agentA)
    state.get(Messages.key).value shouldBe empty
    state.get(LoopKeys.usage).value.requestCount shouldBe 3
  }

  it should "remove a key the turn set for the first time" in {
    val scratch: StateKey[Option[String], Option[String]] = StateKey.replace[Option[String]]("scratch", None)
    val m = middleware(
      "m",
      writing = Set(scratch),
      before = (t, _) => Right(BoundaryResult(t, StateUpdate.update(scratch, Some(t)))),
      after = (_, _) => Left(GuardrailBlocked("all", "no"))
    )
    val (state, _) = runInMemory(loopOf(ScriptedModel(answer("hi")), Seq(m)).graph, AgentInput("go")).failed
    state.isSet(scratch) shouldBe false
    state.get(Messages.key).value shouldBe empty
  }

  "A completed turn" should "not carry its prior values into its settled state" in {
    val m          = middleware("m", model = (r, _, next) => next(r).map(_.addUpdate(StateUpdate.update(notes, "n"))))
    val (state, _) = runInMemory(loopOf(ScriptedModel(answer("hi")), Seq(m)).graph, AgentInput("go")).answered
    state.get(LoopKeys.turn).value.priorValues shouldBe empty
  }

  "A stored TurnState without prior values" should "still decode (rule 7)" in {
    val old = upickle.default.writeJs(TurnState(1, None, Some(agentA)))
    old.obj.remove("priorValues")
    upickle.default.read[TurnState](old) shouldBe TurnState(1, None, Some(agentA))
  }

  "A failing model step" should "carry its provider calls' usage on the durable RunFailed (rule 10)" in {
    val failing = middleware(
      "failing",
      writing = Set.empty,
      model = (r, _, next) => next(r).flatMap(_ => Left(ValidationError("check", "refused after the call")))
    )
    val store = new InMemoryCheckpointer
    val t     = ThreadId("failed-usage")
    val loop  = loopOf(ScriptedModel(answer("hi"), answer("again")), Seq(failing))
    GraphRuntime(store).start(t, loop.graph, AgentInput("go")).awaited.value.failed
    val failed =
      store.eventsAfter(t, 0L, 1000).value.collect { case EventRecord(_, _, _, _, _, _, _, _, f: RunEvent.RunFailed) =>
        f
      }
    failed.size shouldBe 1
    val usage = failed.head.usage.value
    usage.requestCount shouldBe 1
    usage.inputTokens shouldBe 10
  }

  "A stored RunFailed without usage" should "still decode" in {
    val old = upickle.default.writeJs[RunEvent](RunEvent.RunFailed("boom"))
    old.obj.remove("usage")
    upickle.default.read[RunEvent](old) shouldBe RunEvent.RunFailed("boom")
  }

  // ---- round 2: bounds measured on what is sent, a Block undoing only the turn's own writes, usage of every attempt

  they should "count the separators between sections, so many empty sections cannot pass the budget" in {
    val many  = middleware("many", sections = _ => Right(Vector.tabulate(30)(i => PromptSection(s"s$i", ""))))
    val model = ScriptedModel(answer("hi"))
    val result =
      runInMemory(loopOf(model, Seq(many), configure = _.withMaxPromptSectionTokens(10)).graph, AgentInput("go"))
    // 29 separators of two characters: 58 characters, 15 estimated tokens, though every section's text is empty
    causeOf(result).message should include("add 15 estimated tokens")
    model.calls shouldBe 0
  }

  they should "be refused beyond the most one request may carry, whatever their size" in {
    val count = LoopAgent.MaxPromptSections + 1
    val many  = middleware("many", sections = _ => Right(Vector.tabulate(count)(i => PromptSection(s"s$i", "x"))))
    val model = ScriptedModel(answer("hi"))
    val result =
      runInMemory(loopOf(model, Seq(many), configure = _.withMaxPromptSectionTokens(100000)).graph, AgentInput("go"))
    causeOf(result).message should include(s"$count prompt sections, over the limit of ${LoopAgent.MaxPromptSections}")
    model.calls shouldBe 0
  }

  "An output Block after an updateState edit" should "keep the edit, restoring only keys that still hold the turn's writes" in {
    val scratch: StateKey[Option[String], Option[String]] = StateKey.replace[Option[String]]("scratch", None)
    val reviewing = new AgentMiddleware.Asking[String, Boolean] {
      val id: MiddlewareId                     = MiddlewareId("review")
      override def writes: Set[StateKey[?, ?]] = Set(notes, scratch)
      override def beforeAgent(input: String, context: ModelContext): Result[BoundaryResult] =
        Right(BoundaryResult(input, StateUpdate.update(scratch, Some(input))))
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] = next(request).map(_.addUpdate(StateUpdate.update(notes, "model")))
      override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
        answered(context).fold(ask("block?"))(_ => Left(GuardrailBlocked("review", "rejected")))
    }
    val loop  = loopOf(ScriptedModel(answer("hi")), Seq(reviewing))
    val store = new InMemoryCheckpointer
    val rt    = GraphRuntime(store)
    val t     = ThreadId("edited-while-reviewed")

    val parked = rt.start(t, loop.graph, AgentInput("go")).awaited.value.suspended
    parked.state.get(scratch).value shouldBe Some("go")
    // a reviewer corrects the scratch key while the turn waits for review
    val latest = store.latest(t).value.value.checkpoint.id
    rt.updateState(t, loop.graph, latest, StateUpdate.update(scratch, Some("edited"))).isRight shouldBe true
    val (asked, _) = loop.middlewareQuestions(parked).value.head
    val (state, error) =
      rt.resume(t, loop.graph, Map(asked -> ujson.Bool(true))).awaited.value.failed
    error shouldBe a[GuardrailBlocked]
    // the edit is newer than the turn's write: the Block keeps it
    state.get(scratch).value shouldBe Some("edited")
    // the turn's own write is undone
    state.isSet(notes) shouldBe false
  }

  "A cancelled run" should "carry the usage of the calls its interrupted task made on RunCancelled" in {
    val called = new CountDownLatch(1)
    val hanging = middleware(
      "hanging",
      writing = Set.empty,
      model = (r, _, next) =>
        next(r).flatMap { _ =>
          called.countDown()
          Thread.sleep(30000)
          Left(ValidationError("never", "reached"))
        }
    )
    val store = new InMemoryCheckpointer
    val t     = ThreadId("cancelled-usage")
    val handle =
      GraphRuntime(store).start(t, loopOf(ScriptedModel(answer("hi")), Seq(hanging)).graph, AgentInput("go")).value
    called.await(5, TimeUnit.SECONDS) shouldBe true
    handle.cancel()
    awaitResult(handle).value.failed
    val cancelled = store.eventsAfter(t, 0L, 1000).value.collect {
      case EventRecord(_, _, _, _, _, _, _, _, c: RunEvent.RunCancelled) => c
    }
    cancelled.map(_.usage.map(_.requestCount)) shouldBe Vector(Some(1L))
  }

  "A timed-out run" should "carry the usage of the calls its interrupted task made on RunTimedOut" in {
    val hanging = middleware(
      "hanging",
      writing = Set.empty,
      model = (r, _, next) =>
        next(r).flatMap { _ =>
          Thread.sleep(30000)
          Left(ValidationError("never", "reached"))
        }
    )
    val store  = new InMemoryCheckpointer
    val t      = ThreadId("timed-out-usage")
    val config = RunConfig().withBudgets(RunBudgets(timeout = Some(300.millis)))
    GraphRuntime(store)
      .start(t, loopOf(ScriptedModel(answer("hi")), Seq(hanging)).graph, AgentInput("go"), config)
      .awaited
      .value
      .failed
    val timedOut = store.eventsAfter(t, 0L, 1000).value.collect {
      case EventRecord(_, _, _, _, _, _, _, _, e: RunEvent.RunTimedOut) => e
    }
    timedOut.map(_.usage.map(_.requestCount)) shouldBe Vector(Some(1L))
  }

  "A stored RunCancelled or RunTimedOut from before usage" should "decode as one with none" in {
    upickle.default.read[RunEvent]("\"RunCancelled\"") shouldBe RunEvent.RunCancelled(None)
    upickle.default.read[RunEvent]("\"RunTimedOut\"") shouldBe RunEvent.RunTimedOut(None)
    upickle.default.read[RunEvent](
      upickle.default.write[RunEvent](RunEvent.RunCancelled(Some(UsageSummary())))
    ) shouldBe
      RunEvent.RunCancelled(Some(UsageSummary()))
  }

  "A wrapper that calls next several times, then fails" should "leave every completed call's usage on RunFailed" in {
    val retrying = middleware(
      "corrective",
      writing = Set.empty,
      model = (r, _, next) =>
        for
          _ <- next(r)
          _ <- next(r)
          _ <- Left(ValidationError("check", "still wrong"))
        yield fail("unreachable")
    )
    val store = new InMemoryCheckpointer
    val t     = ThreadId("corrective-usage")
    GraphRuntime(store)
      .start(t, loopOf(ScriptedModel(answer("one"), answer("two")), Seq(retrying)).graph, AgentInput("go"))
      .awaited
      .value
      .failed
    val failed =
      store.eventsAfter(t, 0L, 1000).value.collect { case EventRecord(_, _, _, _, _, _, _, _, f: RunEvent.RunFailed) =>
        f
      }
    failed.map(_.usage.map(_.requestCount)) shouldBe Vector(Some(2L))
  }

  "An LLM-judge guardrail in GuardrailMiddleware" should "have its judge call counted, also inside a composite" in {
    val judge = new LLMClient {
      def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] =
        Right(completion(AssistantMessage("0.9"), prompt = 50, completion = 1))
      def streamComplete(
        conversation: Conversation,
        options: CompletionOptions,
        onChunk: StreamedChunk => Unit
      ): Result[Completion] = complete(conversation, options)
      def getContextWindow(): Int     = 1000
      def getReserveCompletion(): Int = 100
    }
    val direct    = LLMGuardrail(judge, "is it fine?")
    val composite = CompositeGuardrail.all(Seq(LLMGuardrail(judge, "is it kind?", guardrailName = "kind")))
    val guard = new GuardrailMiddleware(
      Nil,
      Seq(
        direct,
        new org.llm4s.agent.guardrails.OutputGuardrail {
          val name: String                            = "composite"
          def validate(value: String): Result[String] = composite.validate(value)
          override def validate(value: String, context: ModelContext): Result[String] =
            composite.validate(value, context)
        }
      )
    )
    val (state, text) = runInMemory(loopOf(ScriptedModel(answer("hi")), Seq(guard)).graph, AgentInput("go")).answered
    text shouldBe "hi"
    val usage = state.get(LoopKeys.usage).value
    usage.requestCount shouldBe 3
    usage.inputTokens shouldBe 10 + 50 + 50
  }
}
