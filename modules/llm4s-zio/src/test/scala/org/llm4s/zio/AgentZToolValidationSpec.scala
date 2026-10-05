package org.llm4s.zio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import org.llm4s.agent.{ Agent, ThreadStatus }
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import upickle.default.*
import zio.test.*

/**
 * Tool calls with invalid arguments, driven through `AgentZ`.
 *
 * `Agent` (the legacy loop the wrapper delegates to) validates a call's arguments in the tool itself and
 * reports a failure to the model as a structured tool result; it does not fail the run. The wrapper
 * must neither swallow that result nor hang on it, and a run that never recovers must end at the step limit.
 */
object AgentZToolValidationSpec extends ZIOSpecDefault {

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
    case Left(e)  => throw new IllegalStateException(s"could not build tool: $e")
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

  private def z(client: LLMClient) = AgentZ(new Agent(client))

  private def invalidThenText(args: ujson.Value)(i: Int): Completion =
    if (i == 0) callWith(i, args) else text("recovered")

  val spec =
    suite("AgentZ tool-argument validation")(
      test("run hands a missing required argument back to the model as a structured error tool result") {
        executed.set(0)
        val client = new Recording(invalidThenText(ujson.Obj()))
        z(client).run("q", tools).map { state =>
          val seen = toolResults(client.conversations.get(1))
          val json = ujson.read(seen.head.content)
          assertTrue(client.calls.get() == 2) &&
          assertTrue(state.status == ThreadStatus.Completed) &&
          assertTrue(executed.get() == 0) &&
          assertTrue(seen.map(_.toolCallId) == Seq("call-0")) &&
          assertTrue(json("isError").bool) &&
          assertTrue(json("errorType").str == "handler_error") &&
          assertTrue(json("toolName").str == "echo") &&
          assertTrue(json("message").str.contains("v"))
        }
      },
      test("run hands an argument of the wrong type back to the model and never runs the tool") {
        executed.set(0)
        val client = new Recording(invalidThenText(ujson.Obj("v" -> 42)))
        z(client).run("q", tools).map { state =>
          assertTrue(state.status == ThreadStatus.Completed) &&
          assertTrue(executed.get() == 0) &&
          assertTrue(
            ujson.read(toolResults(client.conversations.get(1)).head.content)("errorType").str == "handler_error"
          )
        }
      },
      test("run executes the tool normally for a valid call (control)") {
        executed.set(0)
        val client = new Recording(invalidThenText(ujson.Obj("v" -> "ok")))
        z(client).run("q", tools).map { state =>
          assertTrue(state.status == ThreadStatus.Completed) &&
          assertTrue(executed.get() == 1) &&
          assertTrue(!ujson.read(toolResults(client.conversations.get(1)).head.content).obj.contains("isError"))
        }
      },
      test("run ends at the step limit, not hang, when the model never fixes its arguments") {
        executed.set(0)
        val client = new Recording(i => if (i < 40) callWith(i, ujson.Obj()) else text("late"))
        z(client).run("q", tools, maxSteps = Some(2)).map { state =>
          assertTrue(client.calls.get() == 2) &&
          assertTrue(state.status == ThreadStatus.Failed("Maximum step limit reached")) &&
          assertTrue(executed.get() == 0)
        }
      },
      test("continueConversation hands an invalid tool call back to the model too") {
        executed.set(0)
        val client = new Recording(invalidThenText(ujson.Obj()))
        for {
          first <- z(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty)
          next  <- z(client).continueConversation(first, "q2", tools)
        } yield assertTrue(next.status == ThreadStatus.Completed) &&
          assertTrue(client.calls.get() == 2) &&
          assertTrue(executed.get() == 0) &&
          assertTrue(
            ujson.read(toolResults(client.conversations.get(1)).head.content)("errorType").str == "handler_error"
          )
      },
      test("continueConversation still fails a provider error after an invalid call in the error channel") {
        val client = new LLMClient {
          val n = new AtomicInteger(0)
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
            if (n.getAndIncrement() == 0) Right(callWith(0, ujson.Obj())) else Left(SimpleError("provider down"))
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] = complete(c, o)
          def getContextWindow(): Int     = 1
          def getReserveCompletion(): Int = 1
        }
        for {
          first <- z(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty)
          err   <- z(client).continueConversation(first, "q2", tools).flip
        } yield assertTrue(err == SimpleError("provider down"))
      }
    ) @@ TestAspect.sequential
}
