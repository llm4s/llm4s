package org.llm4s.zio

import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import org.llm4s.agent.{ Agent, AgentContext, AgentStatus }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.error.{ SimpleError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import upickle.default.*
import zio.ZIO
import zio.test.*

/** Proves that arguments given to `AgentZ` reach the underlying `Agent` unchanged. */
object AgentZFidelitySpec extends ZIOSpecDefault {

  final case class EchoResult(value: String)
  object EchoResult { implicit val rw: ReadWriter[EchoResult] = macroRW }

  private val echoTool = ToolBuilder[Map[String, Any], EchoResult](
    "echo",
    "echoes",
    Schema.`object`[Map[String, Any]]("params").withRequiredField("v", Schema.string("value"))
  ).withHandler(ex => ex.getString("v").map(EchoResult(_))).buildSafe() match {
    case Right(t) => t
    case Left(e)  => throw new IllegalStateException(s"could not build tool: $e")
  }

  private def tools: ToolRegistry = new ToolRegistry(Seq(echoTool))

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

  private def z(client: LLMClient) = AgentZ(new Agent(client))

  private val failed = AgentStatus.Failed("Maximum step limit reached")

  val spec =
    suite("AgentZ fidelity")(
      test("run forwards systemPromptAddition, completionOptions and tools to the model call") {
        val client = new Recording(_ => text("done"))
        val opts   = CompletionOptions().withTemperature(0.123).withMaxTokens(77)
        z(client)
          .run("q", tools, systemPromptAddition = Some("EXTRA-INSTRUCTIONS"), completionOptions = opts)
          .map { state =>
            val sent = client.conversations.get(0).messages
            assertTrue(state.status == AgentStatus.Complete) &&
            assertTrue(client.calls.get() == 1) &&
            assertTrue(sent.collect { case m: SystemMessage => m.content }.exists(_.contains("EXTRA-INSTRUCTIONS"))) &&
            assertTrue(sent.collect { case m: UserMessage => m.content } == Seq("q")) &&
            assertTrue(client.options.get(0).temperature == 0.123) &&
            assertTrue(client.options.get(0).maxTokens == Some(77)) &&
            assertTrue(client.options.get(0).tools.map(_.name) == Seq("echo"))
          }
      },
      test(
        "run honours maxSteps = Some(2) (a tool round trip costs two steps): one model call, then the step-limit failure"
      ) {
        val client = new Recording(toolCall)
        z(client).run("q", tools, maxSteps = Some(2)).map { state =>
          assertTrue(client.calls.get() == 1) && assertTrue(state.status == failed)
        }
      },
      test("run honours maxSteps = None as unlimited rather than the default cap") {
        val client = new Recording(i => if (i < 60) toolCall(i) else text("finally"))
        z(client).run("q", tools, maxSteps = None).map { state =>
          assertTrue(client.calls.get() == 61) && assertTrue(state.status == AgentStatus.Complete)
        }
      },
      test("run applies the Agent default step cap when maxSteps is not given") {
        val client = new Recording(toolCall)
        val direct = new Recording(toolCall)
        new Agent(direct).run("q", tools)
        z(client).run("q", tools).map { state =>
          assertTrue(client.calls.get() == direct.calls.get()) &&
          assertTrue(client.calls.get() == Agent.DefaultMaxSteps / 2) &&
          assertTrue(state.status == failed)
        }
      },
      test("run applies input guardrails before any model call and fails with the ValidationError") {
        val client = new Recording(_ => text("done"))
        z(client)
          .run("this query is far too long", ToolRegistry.empty, inputGuardrails = Seq(new LengthCheck(1, 5)))
          .flip
          .map(err => assertTrue(err.isInstanceOf[ValidationError]) && assertTrue(client.calls.get() == 0))
      },
      test("run applies output guardrails to the final state") {
        val client = new Recording(_ => text("ok"))
        z(client)
          .run("q", ToolRegistry.empty, outputGuardrails = Seq(new LengthCheck(10, 100)))
          .flip
          .map(err => assertTrue(err.isInstanceOf[ValidationError]) && assertTrue(client.calls.get() == 1))
      },
      test("run forwards the AgentContext (trace log path)") {
        val path = Files.createTempFile("agentz-trace", ".md")
        Files.delete(path)
        z(new Recording(_ => text("done")))
          .run("q", ToolRegistry.empty, context = AgentContext(traceLogPath = Some(path.toString)))
          .map { _ =>
            val exists = Files.exists(path)
            Files.deleteIfExists(path)
            assertTrue(exists)
          }
      },
      test("run turns a thrown non-LLM exception into a defect, unchanged") {
        val failure = new IllegalStateException("provider exploded")
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] = throw failure
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] =
            throw failure
          def getContextWindow(): Int     = 1
          def getReserveCompletion(): Int = 1
        }
        z(client).run("q", ToolRegistry.empty).exit.map { exit =>
          assertTrue(exit.causeOption.flatMap(_.dieOption).contains(failure))
        }
      },
      test("continueConversation forwards the previous state, the message and maxSteps") {
        val loop = new Recording(toolCall)
        for {
          first <- z(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty)
          next  <- z(loop).continueConversation(first.copy(tools = tools), "q2", maxSteps = Some(3))
        } yield assertTrue(loop.calls.get() == 2) && // maxSteps = 3: two steps per tool round trip
          assertTrue(next.status == failed) &&
          assertTrue(loop.conversations.get(0).messages.collect { case m: UserMessage => m.content } == Seq("q1", "q2"))
      },
      test("continueConversation defaults to unlimited steps like Agent.continueConversation") {
        val loop = new Recording(i => if (i < 60) toolCall(i) else text("end"))
        for {
          first <- z(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty)
          next  <- z(loop).continueConversation(first.copy(tools = tools), "q2")
        } yield assertTrue(loop.calls.get() == 61) && assertTrue(next.status == AgentStatus.Complete)
      },
      test("continueConversation applies input guardrails to the new message and does not call the model") {
        val client = new Recording(_ => text("x"))
        for {
          first <- z(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty)
          err <- z(client)
            .continueConversation(first, "far too long a follow up", inputGuardrails = Seq(new LengthCheck(1, 5)))
            .flip
        } yield assertTrue(err.isInstanceOf[ValidationError]) && assertTrue(client.calls.get() == 0)
      },
      test("continueConversation applies output guardrails") {
        for {
          first <- z(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty)
          err <- z(new Recording(_ => text("ok")))
            .continueConversation(first, "q2", outputGuardrails = Seq(new LengthCheck(10, 100)))
            .flip
        } yield assertTrue(err.isInstanceOf[ValidationError])
      },
      test("continueConversation refuses an incomplete state with a ValidationError") {
        val inProgress = new Agent(new Recording(_ => text("x"))).initializeSafe("q", ToolRegistry.empty)
        ZIO
          .fromEither(inProgress)
          .flatMap(s => z(new Recording(_ => text("x"))).continueConversation(s, "more"))
          .flip
          .map(err => assertTrue(err.isInstanceOf[ValidationError]))
      },
      test("continueConversation surfaces provider errors unchanged") {
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] = Left(SimpleError("nope"))
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] =
            Left(SimpleError("nope"))
          def getContextWindow(): Int     = 1
          def getReserveCompletion(): Int = 1
        }
        for {
          first <- z(new Recording(_ => text("first"))).run("q1", ToolRegistry.empty)
          err   <- z(client).continueConversation(first, "q2").flip
        } yield assertTrue(err == SimpleError("nope"))
      },
      test("agent() builds an AgentZ over the same underlying client") {
        val client = new Recording(_ => text("via-client"))
        LLMClientZ(client).agent().run("q", ToolRegistry.empty).map { state =>
          assertTrue(state.status == AgentStatus.Complete) && assertTrue(client.calls.get() == 1)
        }
      }
    ) @@ TestAspect.sequential
}
