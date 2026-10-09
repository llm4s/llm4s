package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling, Text }
import org.llm4s.agent.events.GuardrailPhase
import org.llm4s.agent.events.{ AgentEvents, ModelCallCompleted }
import org.llm4s.agent.graph.{
  EventRecord,
  GraphError,
  GraphRuntime,
  InMemoryCheckpointer,
  InterruptId,
  RunContext,
  RunEvent,
  ThreadId
}
import org.llm4s.agent.graph.middleware._
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome }
import org.llm4s.agent.graph.toolloop.{
  AgentInput,
  ApprovalRequest,
  ApprovalSource,
  GivenAnswer,
  MiddlewareHook,
  MiddlewareQuestionRequest,
  ToolLoop,
  ToolQuestionRequest,
  ToolTask
}
import org.llm4s.agent.guardrails.OutputGuardrail
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters._

/** Typed questions from middleware (#1704): `AgentMiddleware.Asking` in every hook, and `GuardrailReviewMiddleware`. */
class AgentMiddlewareQuestionSpec extends AnyFlatSpec with Matchers {
  import AgentMiddlewareQuestionSpec._

  private val deployed = new CopyOnWriteArrayList[String]()
  private val deploy = SpecTools.tool("deploy") { (a, _) =>
    deployed.add(a.text)
    ToolOutcome.Success(ujson.Str(s"deployed ${a.text}"))
  }

  /** The durable `ModelCallCompleted` events of `threadId`, in order. */
  private def modelCallsCompleted(store: InMemoryCheckpointer, threadId: ThreadId): Vector[ModelCallCompleted] =
    store
      .eventsAfter(threadId, 0L, 1000)
      .fold(e => fail(e.message), identity)
      .collect {
        case EventRecord(_, _, _, _, _, _, _, RunEvent.Custom(AgentEvents.ModelCallCompleted.name, _, payload)) =>
          AgentEvents.ModelCallCompleted.decode(payload).getOrElse(fail(s"undecodable payload $payload"))
      }

  private def askedOf(result: AgentResult): Vector[(InterruptId, MiddlewareQuestionRequest)] = result.status match {
    case AgentStatus.Suspended(_, _, asked, _) => asked
    case other                                 => fail(s"expected Suspended, got $other")
  }

  "A beforeAgent question" should "suspend before anything of the turn is stored, and run the input again once answered" in {
    val client        = ScriptedLLMClient.of(CompletionFixture.simple("deployed to eu"))
    val region        = new Region
    val agent         = built(Agent.builder("assistant", client).withMiddleware(region))
    val parked        = agent.run("deploy").value
    val (id, request) = askedOf(parked).head
    request.hook shouldBe MiddlewareHook.BeforeAgent
    request.middleware shouldBe MiddlewareId("region")
    request.agent shouldBe AgentId.unsafe("assistant")
    ToolLoop.middlewareQuestion[Need](request) shouldBe Right(Need("region"))
    request.input.map(_.query) shouldBe Some("deploy")
    parked.messages shouldBe empty
    client.callCount shouldBe 0

    val done = agent.resume(parked.threadId, Map(parked.reply(id, Info("eu")))).value
    done.answer shouldBe Some("deployed to eu")
    client.sent.head.collect { case u: UserMessage => u.content } shouldBe Vector("deploy (region eu)")
    region.asks.get shouldBe 1
  }

  it should "keep a new thread's imported history for the turn that runs once answered" in {
    val client  = ScriptedLLMClient.of(CompletionFixture.simple("ok"))
    val agent   = built(Agent.builder("assistant", client).withMiddleware(new Region))
    val history = Vector(UserMessage("earlier"), AssistantMessage("noted", Seq.empty))
    val parked  = agent.run(ThreadId("imported"), "deploy", org.llm4s.agent.graph.RunConfig(), history).value
    parked.messages shouldBe empty
    val done = agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, Info("us")))).value
    done.messages.take(2) shouldBe history
  }

  it should "refuse an answer that does not decode as the middleware's type, the question still pending" in {
    val agent =
      built(Agent.builder("assistant", ScriptedLLMClient.of(CompletionFixture.simple("ok"))).withMiddleware(new Region))
    val parked = agent.run("deploy").value
    val id     = askedOf(parked).head._1
    agent.resume(parked.threadId, Map(id -> ujson.Num(3))).error shouldBe a[GraphError.InvalidResume]
    agent.resume(parked.threadId, Map(parked.reply(id, Info("eu")))).value.answer shouldBe Some("ok")
  }

  "An afterAgent question" should "hold the stored answer for review, and let the reviewer keep or edit it" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("draft"), CompletionFixture.simple("second draft"))
    val agent  = built(Agent.builder("assistant", client).withMiddleware(new Review))
    val parked = agent.run("write").value
    val (id, request) = askedOf(parked).head
    request.hook shouldBe MiddlewareHook.AfterAgent
    ToolLoop.middlewareQuestion[Need](request) shouldBe Right(Need("review: draft"))
    parked.answer shouldBe None
    parked.messages.last.content shouldBe "draft"

    agent.resume(parked.threadId, Map(parked.reply(id, Info("ok")))).value.answer shouldBe Some("draft")

    val next   = agent.run(parked.threadId, "again").value
    val edited = agent.resume(parked.threadId, Map(next.reply(askedOf(next).head._1, Info("final text")))).value
    edited.answer shouldBe Some("final text")
    edited.messages.last.content shouldBe "final text"
    client.callCount shouldBe 2
  }

  it should "ask about a handoff target's answer when the root's middleware asks, and finish as the target" in {
    val client = ScriptedLLMClient.of(calling(call("h1", "handoff_to_expert")), CompletionFixture.simple("expert says"))
    val expert = Agent.builder("expert", client)
    val agent =
      built(Agent.builder("assistant", client).withHandoffs(Handoff.to("expert", expert)).withMiddleware(new Review))
    val parked = agent.run("hard").value
    askedOf(parked).head._2.agent shouldBe AgentId.unsafe("assistant")
    val done = agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, Info("ok")))).value
    done.answer shouldBe Some("expert says")
    done.activeAgent shouldBe AgentId.unsafe("expert")
  }

  "A model wrapper's question" should "come before the model is called, and the call go ahead once answered" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("answer"))
    val gate = new AgentMiddleware.Asking[Need, Boolean] {
      val id: MiddlewareId = MiddlewareId("spend")
      override def wrapModelCall(request: ModelRequest, context: RunContext)(
        next: ModelRequest => Result[Completion]
      ): Result[Completion] =
        answered(context) match {
          case Some(resumed) if resumed.answer => next(request)
          case Some(_)                         => Left(ValidationError("spend", "refused"))
          case None                            => ask(Need("spend tokens?"))
        }
    }
    val agent  = built(Agent.builder("assistant", client).withMiddleware(gate))
    val parked = agent.run("q").value
    askedOf(parked).head._2.hook shouldBe MiddlewareHook.WrapModelCall
    client.callCount shouldBe 0
    parked.usage.requestCount shouldBe 0

    val done = agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, true))).value
    done.answer shouldBe Some("answer")
    client.callCount shouldBe 1
  }

  it should "review the model's output, returning the reviewed completion without calling the model again" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("raw output"))
    val store  = new InMemoryCheckpointer
    val agent =
      built(Agent.builder("assistant", client).withMiddleware(new OutputReview).withRuntime(new GraphRuntime(store)))
    val parked = agent.run(ThreadId("reviewed"), "q").value
    ToolLoop.middlewareQuestion[Need](askedOf(parked).head._2) shouldBe Right(Need("raw output"))
    parked.messages.collect { case a: AssistantMessage => a } shouldBe empty
    // the model call made before the question is counted while it waits, and announced once
    parked.usage.requestCount shouldBe 1
    (parked.usage.inputTokens, parked.usage.outputTokens) shouldBe ((10L, 20L))
    modelCallsCompleted(store, parked.threadId).map(_.usage.map(_.promptTokens)) shouldBe Vector(Some(10))

    val done = agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, Info("reviewed output")))).value
    done.answer shouldBe Some("reviewed output")
    client.callCount shouldBe 1
    // the reviewed completion carries no usage: the thread's usage is the one real call's, counted once
    done.usage.requestCount shouldBe 2
    (done.usage.inputTokens, done.usage.outputTokens) shouldBe ((10L, 20L))
    modelCallsCompleted(store, parked.threadId).map(_.usage.map(_.promptTokens)) shouldBe Vector(Some(10), None)
  }

  it should "count every model call a wrapper made before asking, each announced with its attempt" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("first"), CompletionFixture.simple("second"))
    val store  = new InMemoryCheckpointer
    val twice = new AgentMiddleware.Asking[Need, Info] {
      val id: MiddlewareId = MiddlewareId("twice")
      override def wrapModelCall(request: ModelRequest, context: RunContext)(
        next: ModelRequest => Result[Completion]
      ): Result[Completion] =
        answered(context) match {
          case Some(resumed) => Right(CompletionFixture.simple(resumed.answer.value).withUsage(None))
          case None          => next(request).flatMap(_ => next(request)).flatMap(c => ask(Need(c.message.content)))
        }
    }
    val agent  = built(Agent.builder("assistant", client).withMiddleware(twice).withRuntime(new GraphRuntime(store)))
    val parked = agent.run(ThreadId("twice"), "q").value
    parked.usage.requestCount shouldBe 2
    parked.usage.inputTokens shouldBe 20L
    modelCallsCompleted(store, parked.threadId).map(_.attempts) shouldBe Vector(1, 2)
  }

  "A tool wrapper's questions" should "be pending one per call, and be answered one at a time" in {
    deployed.clear()
    val ran = new CopyOnWriteArrayList[String]()
    val client = ScriptedLLMClient.of(
      calling(call("c1", "deploy", "one"), call("c2", "deploy", "two")),
      CompletionFixture.simple("both")
    )
    val agent =
      built(Agent.builder("assistant", client).withTools(SpecTools.set(deploy)).withMiddleware(new Environment(ran)))
    val parked = agent.run("deploy").value
    val asked  = askedOf(parked)
    asked.map(_._2.hook) shouldBe Vector(MiddlewareHook.WrapToolCall, MiddlewareHook.WrapToolCall)
    asked.map(_._2.call.map(_.call.id)) shouldBe Vector(Some("c1"), Some("c2"))
    deployed.size shouldBe 0

    val partly = agent.resume(parked.threadId, Map(parked.reply(asked(1)._1, Info("prod")))).value
    askedOf(partly).map(_._1) shouldBe Vector(asked.head._1)
    deployed.asScala.toVector shouldBe Vector("two")
    client.callCount shouldBe 1

    val done = agent.resume(parked.threadId, Map(partly.reply(asked.head._1, Info("staging")))).value
    done.answer shouldBe Some("both")
    ran.asScala.toVector shouldBe Vector("c2@prod", "c1@staging")
  }

  it should "carry its answer through a later approval, so it is not asked twice" in {
    deployed.clear()
    val ran     = new CopyOnWriteArrayList[String]()
    val env     = new Environment(ran)
    val client  = ScriptedLLMClient.of(calling(call("c1", "deploy", "one")), CompletionFixture.simple("done"))
    val approve = new ApprovalMiddleware(_ => Some("check"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(deploy)).withMiddleware(env, approve))
    val parked = agent.run("deploy").value
    val asked  = agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, Info("prod")))).value
    val approval = asked.status match {
      case AgentStatus.Suspended(Vector((id, request)), _, Vector(), _) =>
        request.answered.map(_.middleware) shouldBe Vector(MiddlewareId("environment"))
        id
      case other => fail(s"expected an approval, got $other")
    }
    agent.resume(parked.threadId, Map(asked.approve(approval))).value.answer shouldBe Some("done")
    env.asks.get shouldBe 1
    deployed.asScala.toVector shouldBe Vector("one")
  }

  it should "carry its answer through the tool's own question" in {
    final case class Confirm(prompt: String) derives ReadWriter
    val confirm = new AgentTool.Asking[Text, Confirm, Boolean](SpecTools.spec("confirm")) {
      def execute(args: Text, context: ToolContext): ToolOutcome = ask(Confirm("sure?"))
      def resume(args: Text, question: Confirm, answer: Boolean, context: ToolContext): ToolOutcome =
        ToolOutcome.Success(ujson.Str(s"confirmed $answer"))
    }
    val env    = new Environment(new CopyOnWriteArrayList[String]())
    val client = ScriptedLLMClient.of(calling(call("c1", "confirm")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(confirm)).withMiddleware(env))
    val parked = agent.run("go").value
    val asked  = agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, Info("prod")))).value
    val question = asked.status match {
      case AgentStatus.Suspended(Vector(), Vector((id, request)), Vector(), _) =>
        request.answered.map(_.answer) shouldBe Vector(upickle.default.writeJs(Info("prod")))
        id
      case other => fail(s"expected a tool question, got $other")
    }
    agent.resume(parked.threadId, Map(asked.reply(question, true))).value.answer shouldBe Some("done")
    env.asks.get shouldBe 1
  }

  it should "be the call's error result when asked while the tool continues after its own question" in {
    final case class Confirm(prompt: String) derives ReadWriter
    val confirm = new AgentTool.Asking[Text, Confirm, Boolean](SpecTools.spec("confirm")) {
      def execute(args: Text, context: ToolContext): ToolOutcome = ask(Confirm("sure?"))
      def resume(args: Text, question: Confirm, answer: Boolean, context: ToolContext): ToolOutcome =
        ToolOutcome.Success(ujson.Str("never"))
    }
    val calls = new AtomicInteger(0)
    // passes the first invocation through, so the tool asks; asks itself on the second, the tool's resume
    val late = new AgentMiddleware.Asking[Need, Info] {
      val id: MiddlewareId = MiddlewareId("late")
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
        if calls.incrementAndGet() == 1 then next() else askAbout(Need("late"))
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "confirm")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(confirm)).withMiddleware(late))
    val parked = agent.run("go").value
    val id = parked.status match {
      case AgentStatus.Suspended(_, Vector((id, _)), _, _) => id
      case other                                           => fail(s"expected a tool question, got $other")
    }
    val done = agent.resume(parked.threadId, Map(parked.reply(id, true))).value
    done.answer shouldBe Some("done")
    done.messages.collect { case t: ToolMessage => t.content }.head should include(
      "Middleware 'late' asked a question after a tool question"
    )
  }

  it should "fail the run when a middleware that declares no question asks one" in {
    val rogue = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("rogue")
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
        ToolOutcome.Ask("anything")
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(deploy)).withMiddleware(rogue))
    cause(agent.run("go").error) match {
      case GraphError.ToolFailed(_, _, cause) => cause.message should include("asked a question it does not declare")
      case other                              => fail(s"expected ToolFailed, got $other")
    }
  }

  it should "fail the run when its question is not of its declared type" in {
    val wrong = new AgentMiddleware.Asking[Need, Info] {
      val id: MiddlewareId = MiddlewareId("wrong")
      override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
        ToolOutcome.Ask(42)
    }
    val client = ScriptedLLMClient.of(calling(call("c1", "deploy")), CompletionFixture.simple("done"))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(deploy)).withMiddleware(wrong))
    cause(agent.run("go").error) match {
      case GraphError.ToolFailed(_, _, cause) => cause.message should include("asked a question of another type")
      case other                              => fail(s"expected ToolFailed, got $other")
    }
  }

  "Two asking middleware in one stack" should "each ask once, the first answer kept while the second is asked" in {
    val outer = new Region
    val inner = new AgentMiddleware.Asking[Need, Info] {
      val id: MiddlewareId = MiddlewareId("ticket")
      override def beforeAgent(input: String, context: RunContext): Result[String] =
        answered(context).fold(ask(Need("ticket")))(r => Right(s"$input [${r.answer.value}]"))
    }
    val client = ScriptedLLMClient.of(CompletionFixture.simple("ok"))
    val agent  = built(Agent.builder("assistant", client).withMiddleware(outer, inner))
    val parked = agent.run("deploy").value
    askedOf(parked).head._2.middleware shouldBe MiddlewareId("region")
    val second        = agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, Info("eu")))).value
    val (id, request) = askedOf(second).head
    request.middleware shouldBe MiddlewareId("ticket")
    request.answered shouldBe Vector(
      GivenAnswer(
        AgentId.unsafe("assistant"),
        MiddlewareId("region"),
        upickle.default.writeJs(Need("region")),
        upickle.default.writeJs(Info("eu"))
      )
    )
    agent.resume(parked.threadId, Map(second.reply(id, Info("T-1")))).value.answer shouldBe Some("ok")
    client.sent.head.collect { case u: UserMessage => u.content } shouldBe Vector("deploy (region eu) [T-1]")
    outer.asks.get shouldBe 1
  }

  "A question asked through a middleware the stack does not hold" should "fail the run" in {
    final class Exposed extends AgentMiddleware.Asking[Need, Info] {
      val id: MiddlewareId         = MiddlewareId("elsewhere")
      def raise(): Result[Nothing] = ask(Need("x"))
    }
    val elsewhere = new Exposed
    val proxy = new AgentMiddleware {
      val id: MiddlewareId                                                         = MiddlewareId("proxy")
      override def beforeAgent(input: String, context: RunContext): Result[String] = elsewhere.raise()
    }
    val agent = built(Agent.builder("assistant", ScriptedLLMClient.of()).withMiddleware(proxy))
    cause(agent.run("go").error).message should include("has no asking middleware of that id")
  }

  "A failure after an answered question" should "be recovered with the answer, without asking again" in {
    val client = new ScriptedLLMClient(Left(ValidationError("provider", "down")), Right(CompletionFixture.simple("ok")))
    val region = new Region
    val agent  = built(Agent.builder("assistant", client).withMiddleware(region))
    val parked = agent.run(ThreadId("recovering"), "deploy").value
    agent.resume(parked.threadId, Map(parked.reply(askedOf(parked).head._1, Info("eu")))).error shouldBe a[GraphError]
    agent.recover(parked.threadId).value.answer shouldBe Some("ok")
    region.asks.get shouldBe 1
    client.sent.last.collect { case u: UserMessage => u.content } shouldBe Vector("deploy (region eu)")
  }

  "MiddlewareAsked" should "describe the question as an error, for a caller outside the agent loop" in {
    new Region().beforeAgent("deploy", org.llm4s.agent.graph.GraphTestSupport.testRunContext()) match {
      case Left(asked: MiddlewareAsked) =>
        asked.middleware shouldBe MiddlewareId("region")
        asked.message shouldBe "Middleware 'region' asked a question"
      case other => fail(s"expected a question, got $other")
    }
  }

  "GuardrailReviewMiddleware" should "ask a reviewer about a refused answer instead of blocking, and allow it" in {
    val tooLong: OutputGuardrail = new LengthCheck(1, 5)
    val client                   = ScriptedLLMClient.of(CompletionFixture.simple("far too long"))
    val agent =
      built(Agent.builder("assistant", client).withMiddleware(new GuardrailReviewMiddleware(Nil, Seq(tooLong))))
    val parked        = agent.run("q").value
    val (id, request) = askedOf(parked).head
    request.middleware shouldBe MiddlewareId("guardrail-review")
    val review = ToolLoop.middlewareQuestion[GuardrailReview](request).fold(e => fail(e.message), identity)
    review.phase shouldBe GuardrailPhase.Output
    review.guardrail shouldBe tooLong.name
    review.text shouldBe "far too long"
    agent.resume(parked.threadId, Map(parked.reply[GuardrailVerdict](id, GuardrailVerdict.Allow))).value.answer shouldBe
      Some("far too long")
  }

  it should "use the reviewer's edit, or block as GuardrailMiddleware would" in {
    val tooLong: OutputGuardrail = new LengthCheck(1, 5)
    def agentOver(client: ScriptedLLMClient) =
      built(Agent.builder("assistant", client).withMiddleware(new GuardrailReviewMiddleware(Nil, Seq(tooLong))))

    val editing = agentOver(ScriptedLLMClient.of(CompletionFixture.simple("far too long")))
    val parked  = editing.run("q").value
    editing
      .resume(
        parked.threadId,
        Map(parked.reply[GuardrailVerdict](askedOf(parked).head._1, GuardrailVerdict.Edit("short")))
      )
      .value
      .answer shouldBe Some("short")

    val blocking = agentOver(ScriptedLLMClient.of(CompletionFixture.simple("far too long")))
    val held     = blocking.run("q").value
    val blocked = blocking
      .resume(held.threadId, Map(held.reply[GuardrailVerdict](askedOf(held).head._1, GuardrailVerdict.Block)))
      .value
    blocked.status shouldBe a[AgentStatus.Blocked]
    blocked.messages shouldBe empty
  }

  it should "review a refused query before anything is stored, and pass text the guardrails accept" in {
    val short  = new LengthCheck(1, 5)
    val client = ScriptedLLMClient.of(CompletionFixture.simple("ok"), CompletionFixture.simple("fine"))
    val agent = built(Agent.builder("assistant", client).withMiddleware(new GuardrailReviewMiddleware(Seq(short), Nil)))
    val parked        = agent.run("a very long query").value
    val (id, request) = askedOf(parked).head
    ToolLoop.middlewareQuestion[GuardrailReview](request).map(_.phase) shouldBe Right(GuardrailPhase.Input)
    client.callCount shouldBe 0
    agent
      .resume(parked.threadId, Map(parked.reply[GuardrailVerdict](id, GuardrailVerdict.Allow)))
      .value
      .answer shouldBe Some("ok")
    agent.run(parked.threadId, "hi").value.answer shouldBe Some("fine")
  }

  it should "end the review with the verdict when the guardrail's reason varies, without calling it again" in {
    val calls = new AtomicInteger
    // an LLM judge: refuses every time, worded differently each time
    val judge: OutputGuardrail = new OutputGuardrail {
      def validate(value: String): Result[String] =
        Left(ValidationError("judge", s"unsafe (score ${calls.incrementAndGet()})"))
      val name: String = "judge"
    }
    val agentOver = (verdict: GuardrailVerdict) => {
      calls.set(0)
      val client = ScriptedLLMClient.of(CompletionFixture.simple("answer"))
      val agent =
        built(Agent.builder("assistant", client).withMiddleware(new GuardrailReviewMiddleware(Nil, Seq(judge))))
      val parked        = agent.run("q").value
      val (id, request) = askedOf(parked).head
      ToolLoop.middlewareQuestion[GuardrailReview](request).map(_.reason.contains("score 1")) shouldBe Right(true)
      agent.resume(parked.threadId, Map(parked.reply[GuardrailVerdict](id, verdict))).value
    }

    agentOver(GuardrailVerdict.Allow).answer shouldBe Some("answer")
    calls.get shouldBe 1
    agentOver(GuardrailVerdict.Edit("edited")).answer shouldBe Some("edited")
    calls.get shouldBe 1
    agentOver(GuardrailVerdict.Block).status match {
      case AgentStatus.Blocked(guardrail, reason) =>
        guardrail shouldBe "judge"
        reason should include("score 1")
      case other => fail(s"expected Blocked, got $other")
    }
    calls.get shouldBe 1
  }

  it should "run the guardrails again, and ask again, when the hook runs on different text" in {
    val tooLong: OutputGuardrail = new LengthCheck(1, 5)
    val review                   = new GuardrailReviewMiddleware(Nil, Seq(tooLong))
    val verdict                  = upickle.default.writeJs[GuardrailVerdict](GuardrailVerdict.Allow)
    val asked = upickle.default.writeJs(GuardrailReview(GuardrailPhase.Output, tooLong.name, "r", "far too long"))
    val context = org.llm4s.agent.graph.GraphTestSupport
      .testRunContext()
      .withAnswers(Map(review.id.value -> (asked, verdict)))
    review.afterAgent("far too long", context) shouldBe Right("far too long")
    review.afterAgent("also far too long", context) match {
      case Left(again: MiddlewareAsked) =>
        upickle.default.read[GuardrailReview](again.question).text shouldBe "also far too long"
      case other => fail(s"expected a new question, got $other")
    }
    review.beforeAgent("far too long", context) shouldBe Right("far too long") // no input guardrails
  }

  "The interrupt request types" should "be built with apply and changed with their with* setters" in {
    val c      = call("c1", "deploy", "one")
    val agent  = AgentId.unsafe("a")
    val mw     = MiddlewareId("m")
    val given1 = GivenAnswer(agent, mw, ujson.Str("q"), ujson.Str("a"))
    given1.withAgent(AgentId.unsafe("b")).agent shouldBe AgentId.unsafe("b")
    given1.withMiddleware(MiddlewareId("n")).middleware shouldBe MiddlewareId("n")
    given1.withQuestion(ujson.Num(1)).question shouldBe ujson.Num(1)
    given1.withAnswer(ujson.Num(2)).answer shouldBe ujson.Num(2)

    val approval = ApprovalRequest("m1", c, "why", ApprovalSource.Tool)
    approval.answered shouldBe empty
    approval.withAssistantMessageId("m2").assistantMessageId shouldBe "m2"
    approval.withCall(call("c2", "deploy")).call.id shouldBe "c2"
    approval.withReason("because").reason shouldBe "because"
    approval.withSource(ApprovalSource.Middleware(mw)).source shouldBe ApprovalSource.Middleware(mw)
    approval.withAnswered(Vector(given1)).answered shouldBe Vector(given1)

    val question = ToolQuestionRequest("m1", c, ujson.Str("q"))
    (question.approved, question.answered) shouldBe ((false, Vector.empty))
    question.withAssistantMessageId("m2").assistantMessageId shouldBe "m2"
    question.withCall(call("c2", "deploy")).call.id shouldBe "c2"
    question.withQuestion(ujson.Num(3)).question shouldBe ujson.Num(3)
    question.withApproved(true).approved shouldBe true
    question.withAnswered(Vector(given1)).answered shouldBe Vector(given1)

    val asked = MiddlewareQuestionRequest(agent, mw, MiddlewareHook.BeforeAgent, ujson.Str("q"))
    (asked.input, asked.call, asked.approved, asked.answered) shouldBe ((None, None, false, Vector.empty))
    asked.withAgent(AgentId.unsafe("b")).agent shouldBe AgentId.unsafe("b")
    asked.withMiddleware(MiddlewareId("n")).middleware shouldBe MiddlewareId("n")
    asked.withHook(MiddlewareHook.AfterAgent).hook shouldBe MiddlewareHook.AfterAgent
    asked.withQuestion(ujson.Num(4)).question shouldBe ujson.Num(4)
    asked.withInput(AgentInput("go")).input.map(_.query) shouldBe Some("go")
    asked.withInput(AgentInput("go")).withInput(None).input shouldBe None
    asked.withCall(ToolTask("m1", c)).call shouldBe Some(ToolTask("m1", c))
    asked.withCall(ToolTask("m1", c)).withCall(None).call shouldBe None
    asked.withApproved(true).approved shouldBe true
    asked.withAnswered(Vector(given1)).answered shouldBe Vector(given1)
    upickle.default.read[MiddlewareQuestionRequest](upickle.default.write(asked)) shouldBe asked
  }

  "GuardrailReview" should "be built with apply and changed with its with* setters" in {
    val review = GuardrailReview(GuardrailPhase.Input, "g", "r", "t")
    review.withPhase(GuardrailPhase.Output).phase shouldBe GuardrailPhase.Output
    review.withGuardrail("h").guardrail shouldBe "h"
    review.withReason("s").reason shouldBe "s"
    review.withText("u").text shouldBe "u"
    upickle.default.read[GuardrailReview](upickle.default.write(review)) shouldBe review
  }
}

object AgentMiddlewareQuestionSpec {

  /** Reviews the model's output: asks about it, then returns the reviewer's text as a completion without usage. */
  final class OutputReview extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("output-review")
    override def wrapModelCall(request: ModelRequest, context: RunContext)(
      next: ModelRequest => Result[Completion]
    ): Result[Completion] =
      answered(context) match {
        case Some(resumed) => Right(CompletionFixture.simple(resumed.answer.value).withUsage(None))
        case None          => next(request).flatMap(c => ask(Need(c.message.content)))
      }
  }
  final case class Need(what: String) derives ReadWriter
  final case class Info(value: String) derives ReadWriter

  /** Asks for the region when the query names none, and appends the answer to it. */
  final class Region(val asks: AtomicInteger = new AtomicInteger) extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("region")
    override def beforeAgent(input: String, context: RunContext): Result[String] =
      if input.contains("region") then Right(input)
      else
        answered(context) match {
          case Some(resumed) => Right(s"$input (region ${resumed.answer.value})")
          case None =>
            asks.incrementAndGet()
            ask(Need("region"))
        }
  }

  /** Reviews the final answer: `Info("ok")` keeps it, any other value replaces it. */
  final class Review extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("review")
    override def afterAgent(answer: String, context: RunContext): Result[String] =
      answered(context) match {
        case Some(resumed) if resumed.answer.value == "ok" => Right(answer)
        case Some(resumed)                                 => Right(resumed.answer.value)
        case None                                          => ask(Need(s"review: $answer"))
      }
  }

  /** Asks which environment each call is for, unless the call names one; the call then runs with the answer. */
  final class Environment(ran: CopyOnWriteArrayList[String], val asks: AtomicInteger = new AtomicInteger)
      extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("environment")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      answered(context) match {
        case Some(resumed) =>
          ran.add(s"${request.call.id}@${resumed.answer.value}")
          next()
        case None =>
          asks.incrementAndGet()
          askAbout(Need(s"environment for ${request.call.id}"))
      }
  }

}
