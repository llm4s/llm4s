package org.llm4s.effect.cats

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.llm4s.agent.{ Agent, AgentState, AgentStatus }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/**
 * Tool calls with invalid arguments, driven through `AgentIO`.
 *
 * `Agent` (the legacy loop the wrapper delegates to) validates a call's arguments in the tool itself and
 * reports a failure to the model as a structured tool result; it does not fail the run. The wrapper
 * must neither swallow that result nor hang on it, and a run that never recovers must end at the step limit.
 */
class AgentIOToolValidationSpec extends AnyFlatSpec with Matchers {

  final case class EchoResult(value: String)
  object EchoResult { implicit val rw: ReadWriter[EchoResult] = macroRW }

  /** Counts executions that got past argument extraction, i.e. the tool's real body. */
  private val executed = new AtomicInteger(0)

  private val echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "echoes",
    Schema.`object`[Map[String, Any]]("params").withRequiredField("v", Schema.string("value"))
  ).withHandler(ex =>
    ex.getString("v").map { v =>
      executed.incrementAndGet()
      EchoResult(v)
    }
  ).buildSafe() match {
    case Right(t) => t
    case Left(e)  => fail(s"could not build tool: $e")
  }

  private def tools: ToolRegistry = new ToolRegistry(Seq(echoTool))

  final private class Recording(reply: Int => Completion) extends LLMClient {
    val conversations = new CopyOnWriteArrayList[Conversation]()
    val calls         = new AtomicInteger(0)
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      val i = calls.getAndIncrement()
      conversations.add(c)
      Right(reply(i))
    }
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    def getContextWindow(): Int     = 8192
    def getReserveCompletion(): Int = 512
  }

  private def text(s: String): Completion =
    Completion(id = "t", created = 0L, content = s, model = "m", message = AssistantMessage(Some(s)))

  private def callWith(i: Int, args: ujson.Value): Completion = {
    val calls = Seq(ToolCall(id = s"call-$i", name = "echo", arguments = args))
    Completion(
      id = s"t$i",
      created = 0L,
      content = "",
      model = "m",
      message = AssistantMessage("", calls),
      toolCalls = calls.toList
    )
  }

  private def toolResults(c: Conversation): Seq[ToolMessage] = c.messages.collect { case m: ToolMessage => m }

  private def io(client: LLMClient) = AgentIO[IO](new Agent(client))

  private def invalidThenText(args: ujson.Value)(i: Int): Completion =
    if (i == 0) callWith(i, args) else text("recovered")

  "AgentIO.run" should "hand a missing required argument back to the model as a structured error tool result" in {
    executed.set(0)
    val client = new Recording(invalidThenText(ujson.Obj()))
    val state  = io(client).run("q", tools).unsafeRunSync()
    client.calls.get() shouldBe 2
    state.status shouldBe AgentStatus.Complete
    executed.get() shouldBe 0
    val seen = toolResults(client.conversations.get(1))
    seen.map(_.toolCallId) shouldBe Seq("call-0")
    val json = ujson.read(seen.head.content)
    json("isError").bool shouldBe true
    json("errorType").str shouldBe "handler_error"
    json("toolName").str shouldBe "echo"
    json("message").str should include("v")
  }

  it should "hand an argument of the wrong type back to the model and never run the tool" in {
    executed.set(0)
    val client = new Recording(invalidThenText(ujson.Obj("v" -> 42)))
    val state  = io(client).run("q", tools).unsafeRunSync()
    state.status shouldBe AgentStatus.Complete
    executed.get() shouldBe 0
    ujson.read(toolResults(client.conversations.get(1)).head.content)("errorType").str shouldBe "handler_error"
  }

  it should "run the tool normally for a valid call (control)" in {
    executed.set(0)
    val client = new Recording(invalidThenText(ujson.Obj("v" -> "ok")))
    val state  = io(client).run("q", tools).unsafeRunSync()
    state.status shouldBe AgentStatus.Complete
    executed.get() shouldBe 1
    ujson.read(toolResults(client.conversations.get(1)).head.content).obj.contains("isError") shouldBe false
  }

  it should "end at the step limit, not hang, when the model never fixes its arguments" in {
    executed.set(0)
    val client = new Recording(i => if (i < 40) callWith(i, ujson.Obj()) else text("late"))
    val state  = io(client).run("q", tools, maxSteps = Some(4)).unsafeRunSync()
    client.calls.get() shouldBe 2
    state.status shouldBe AgentStatus.Failed("Maximum step limit reached")
    executed.get() shouldBe 0
  }

  "AgentIO.continueConversation" should "hand an invalid tool call back to the model too" in {
    executed.set(0)
    val first: AgentState = io(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty).unsafeRunSync()
    val client            = new Recording(invalidThenText(ujson.Obj()))
    val next              = io(client).continueConversation(first.copy(tools = tools), "q2").unsafeRunSync()
    next.status shouldBe AgentStatus.Complete
    client.calls.get() shouldBe 2
    executed.get() shouldBe 0
    ujson.read(toolResults(client.conversations.get(1)).head.content)("errorType").str shouldBe "handler_error"
  }

  it should "still raise a provider failure after an invalid call in the error channel" in {
    val first = io(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty).unsafeRunSync()
    val client = new LLMClient {
      val n = new AtomicInteger(0)
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        if (n.getAndIncrement() == 0) Right(callWith(0, ujson.Obj()))
        else Left(org.llm4s.error.SimpleError("provider down"))
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def getContextWindow(): Int     = 1
      def getReserveCompletion(): Int = 1
    }
    val err =
      io(client).continueConversation(first.copy(tools = tools), "q2").attempt.unsafeRunSync().left.toOption.get
    err.asInstanceOf[LLMException].error shouldBe org.llm4s.error.SimpleError("provider down")
  }
}
