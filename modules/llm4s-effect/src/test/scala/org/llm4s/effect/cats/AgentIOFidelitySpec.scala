package org.llm4s.effect.cats

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.{ Agent, AgentContext, AgentState, AgentStatus }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.error.{ SimpleError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/** Proves that arguments given to `AgentIO` reach the underlying `Agent` unchanged. */
class AgentIOFidelitySpec extends AnyFlatSpec with Matchers {

  final case class EchoResult(value: String)
  object EchoResult { implicit val rw: ReadWriter[EchoResult] = macroRW }

  private val echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "echoes",
    Schema.`object`[Map[String, Any]]("params").withRequiredField("v", Schema.string("value"))
  ).withHandler(ex => ex.getString("v").map(EchoResult(_))).buildSafe() match {
    case Right(t) => t
    case Left(e)  => fail(s"could not build tool: $e")
  }

  /** Records every request; answers with `reply(callIndex)`. */
  final private class Recording(reply: Int => Completion) extends LLMClient {
    val conversations = new CopyOnWriteArrayList[Conversation]()
    val options       = new CopyOnWriteArrayList[CompletionOptions]()
    val calls         = new AtomicInteger(0)
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      val i = calls.getAndIncrement()
      conversations.add(c)
      options.add(o)
      Right(reply(i))
    }
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  private def text(s: String): Completion =
    Completion(id = "t", created = 0L, content = s, model = "m", message = AssistantMessage(Some(s)))

  private def toolCall(i: Int): Completion = {
    val calls = Seq(ToolCall(id = s"call-$i", name = "echo", arguments = ujson.Obj("v" -> "x")))
    Completion(
      id = s"t$i",
      created = 0L,
      content = "",
      model = "m",
      message = AssistantMessage("", calls),
      toolCalls = calls.toList
    )
  }

  private def io(client: LLMClient) = AgentIO[IO](new Agent(client))

  "AgentIO.run" should "forward systemPromptAddition, completionOptions and tools to the model call" in {
    val client = new Recording(_ => text("done"))
    val opts   = CompletionOptions().withTemperature(0.123).withMaxTokens(77)
    val state = io(client)
      .run(
        "q",
        new ToolRegistry(Seq(echoTool)),
        systemPromptAddition = Some("EXTRA-INSTRUCTIONS"),
        completionOptions = opts
      )
      .unsafeRunSync()
    state.status shouldBe AgentStatus.Complete
    client.calls.get() shouldBe 1
    val sent = client.conversations.get(0).messages
    sent.collect { case m: SystemMessage => m.content }.exists(_.contains("EXTRA-INSTRUCTIONS")) shouldBe true
    sent.collect { case m: UserMessage => m.content } shouldBe Seq("q")
    client.options.get(0).temperature shouldBe 0.123
    client.options.get(0).maxTokens shouldBe Some(77)
    client.options.get(0).tools.map(_.name) shouldBe Seq("echo")
  }

  it should "honour maxSteps (a tool round trip costs two steps, so Some(2) allows one model call) and report the step limit" in {
    val client = new Recording(toolCall)
    val state  = io(client).run("q", new ToolRegistry(Seq(echoTool)), maxSteps = Some(2)).unsafeRunSync()
    client.calls.get() shouldBe 1
    state.status shouldBe AgentStatus.Failed("Maximum step limit reached")
  }

  it should "honour maxSteps = None as unlimited rather than the default cap" in {
    val client = new Recording(i => if (i < 60) toolCall(i) else text("finally"))
    val state  = io(client).run("q", new ToolRegistry(Seq(echoTool)), maxSteps = None).unsafeRunSync()
    client.calls.get() shouldBe 61
    state.status shouldBe AgentStatus.Complete
  }

  it should "apply the Agent default step cap when maxSteps is not given" in {
    val client = new Recording(toolCall)
    val state  = io(client).run("q", new ToolRegistry(Seq(echoTool))).unsafeRunSync()
    val direct = new Recording(toolCall)
    new Agent(direct).run("q", new ToolRegistry(Seq(echoTool)))
    client.calls.get() shouldBe direct.calls.get()
    client.calls.get() shouldBe Agent.DefaultMaxSteps / 2
    state.status shouldBe AgentStatus.Failed("Maximum step limit reached")
  }

  it should "apply input guardrails before any model call and map the failure to LLMException" in {
    val client = new Recording(_ => text("done"))
    val result = io(client)
      .run("this query is far too long", ToolRegistry.empty, inputGuardrails = Seq(new LengthCheck(1, 5)))
      .attempt
      .unsafeRunSync()
    result.left.toOption.get shouldBe a[LLMException]
    result.left.toOption.get.asInstanceOf[LLMException].error shouldBe a[ValidationError]
    client.calls.get() shouldBe 0
  }

  it should "apply output guardrails to the final state" in {
    val client = new Recording(_ => text("ok"))
    val result = io(client)
      .run("q", ToolRegistry.empty, outputGuardrails = Seq(new LengthCheck(10, 100)))
      .attempt
      .unsafeRunSync()
    result.left.toOption.get shouldBe a[LLMException]
    client.calls.get() shouldBe 1
  }

  it should "forward the AgentContext (trace log path)" in {
    val path = Files.createTempFile("agentio-trace", ".md")
    Files.delete(path)
    val client = new Recording(_ => text("done"))
    io(client).run("q", ToolRegistry.empty, context = AgentContext(traceLogPath = Some(path.toString))).unsafeRunSync()
    Files.exists(path) shouldBe true
    Files.delete(path)
  }

  it should "map a thrown non-LLM exception to the raw throwable in the error channel" in {
    val failure = new IllegalStateException("provider exploded")
    val client = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] = throw failure
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        throw failure
      def getContextWindow(): Int     = 1
      def getReserveCompletion(): Int = 1
    }
    val outcome = io(client).run("q", ToolRegistry.empty).attempt.unsafeRunSync()
    (outcome.left.toOption.get should be).theSameInstanceAs(failure)
  }

  "AgentIO.continueConversation" should "forward the previous state, message, and maxSteps" in {
    val first = io(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty).unsafeRunSync()

    val loop  = new Recording(toolCall)
    val state = first.copy(tools = new ToolRegistry(Seq(echoTool)))
    val next  = io(loop).continueConversation(state, "q2", maxSteps = Some(3)).unsafeRunSync()
    loop.calls.get() shouldBe 2 // maxSteps = 3: two steps per tool round trip
    next.status shouldBe AgentStatus.Failed("Maximum step limit reached")
    val firstRequest = loop.conversations.get(0).messages.collect { case m: UserMessage => m.content }
    firstRequest shouldBe Seq("q1", "q2")
  }

  it should "default to unlimited steps like Agent.continueConversation" in {
    val first = io(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty).unsafeRunSync()
    val loop  = new Recording(i => if (i < 60) toolCall(i) else text("end"))
    val next = io(loop)
      .continueConversation(first.copy(tools = new ToolRegistry(Seq(echoTool))), "q2")
      .unsafeRunSync()
    loop.calls.get() shouldBe 61
    next.status shouldBe AgentStatus.Complete
  }

  it should "apply input guardrails to the new message and not call the model" in {
    val first  = io(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty).unsafeRunSync()
    val client = new Recording(_ => text("x"))
    val result = io(client)
      .continueConversation(first, "far too long a follow up", inputGuardrails = Seq(new LengthCheck(1, 5)))
      .attempt
      .unsafeRunSync()
    result.left.toOption.get shouldBe a[LLMException]
    client.calls.get() shouldBe 0
  }

  it should "apply output guardrails" in {
    val first  = io(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty).unsafeRunSync()
    val client = new Recording(_ => text("ok"))
    val result = io(client)
      .continueConversation(first, "q2", outputGuardrails = Seq(new LengthCheck(10, 100)))
      .attempt
      .unsafeRunSync()
    result.left.toOption.get shouldBe a[LLMException]
  }

  it should "refuse to continue an incomplete state with a ValidationError" in {
    val inProgress: AgentState = new Agent(new Recording(_ => text("x")))
      .initializeSafe("q", ToolRegistry.empty)
      .getOrElse(fail("init failed"))
    val result = io(new Recording(_ => text("x"))).continueConversation(inProgress, "more").attempt.unsafeRunSync()
    result.left.toOption.get.asInstanceOf[LLMException].error shouldBe a[ValidationError]
  }

  it should "surface provider errors with the original LLMError attached" in {
    val first = io(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty).unsafeRunSync()
    val client = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Left(SimpleError("nope"))
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        Left(SimpleError("nope"))
      def getContextWindow(): Int     = 1
      def getReserveCompletion(): Int = 1
    }
    val err = io(client).continueConversation(first, "q2").attempt.unsafeRunSync().left.toOption.get
    err.asInstanceOf[LLMException].error shouldBe SimpleError("nope")
  }

  "agent()" should "build an AgentIO over the same underlying client" in {
    val client = new Recording(_ => text("via-client"))
    val state  = LLMClientIO[IO](client).agent().run("q", ToolRegistry.empty).unsafeRunSync()
    state.status shouldBe AgentStatus.Complete
    client.calls.get() shouldBe 1
  }
}
