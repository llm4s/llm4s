package org.llm4s.zio

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import org.llm4s.agent.{ Agent, AgentBuilder, AgentStatus }
import org.llm4s.agent.graph.GraphError
import org.llm4s.agent.graph.middleware.{ ApprovalMiddleware, GuardrailMiddleware }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.agent.testkit.Fixtures
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolRegistry }
import org.llm4s.types.Result
import upickle.default.*
import zio.test.*

/** Proves that what `AgentZ` is given reaches the underlying `Agent` unchanged, and what the agent reports comes back. */
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

  private def z(client: LLMClient)(configure: AgentBuilder => AgentBuilder = identity) =
    AgentZ(Fixtures.agentOf(client)(configure))

  val spec =
    suite("AgentZ fidelity")(
      test("run forwards the system prompt, completion options and tools to the model call") {
        val client = new Recording(_ => text("done"))
        val opts   = CompletionOptions().withTemperature(0.123).withMaxTokens(77)
        z(client)(_.withTools(tools).withSystemPrompt("EXTRA-INSTRUCTIONS").withCompletionOptions(opts))
          .run("q")
          .map { result =>
            val sent = client.conversations.get(0).messages
            assertTrue(result.status == AgentStatus.Completed("done")) &&
            assertTrue(client.calls.get() == 1) &&
            assertTrue(sent.collect { case m: SystemMessage => m.content }.exists(_.contains("EXTRA-INSTRUCTIONS"))) &&
            assertTrue(sent.collect { case m: UserMessage => m.content } == Seq("q")) &&
            assertTrue(client.options.get(0).temperature == 0.123) &&
            assertTrue(client.options.get(0).maxTokens == Some(77)) &&
            assertTrue(client.options.get(0).tools.map(_.name) == Seq("echo"))
          }
      },
      test("run honours withMaxSteps (model calls per turn) and reports the step limit") {
        val client = new Recording(toolCall)
        z(client)(_.withTools(tools).withMaxSteps(1)).run("q").map { result =>
          assertTrue(client.calls.get() == 1) && assertTrue(result.status == AgentStatus.StepLimitReached)
        }
      },
      test("run applies the Agent default step cap when withMaxSteps is not given") {
        val client = new Recording(toolCall)
        val direct = new Recording(toolCall)
        Fixtures.agentOf(direct)(_.withTools(tools)).run("q")
        z(client)(_.withTools(tools)).run("q").map { result =>
          assertTrue(client.calls.get() == direct.calls.get()) &&
          assertTrue(client.calls.get() == Agent.DefaultMaxSteps) &&
          assertTrue(result.status == AgentStatus.StepLimitReached)
        }
      },
      test("run applies input guardrails before any model call and reports the turn Blocked") {
        val client = new Recording(_ => text("done"))
        z(client)(_.withMiddleware(new GuardrailMiddleware(Seq(new LengthCheck(1, 5)), Nil)))
          .run("this query is far too long")
          .map(result =>
            assertTrue(result.status.isInstanceOf[AgentStatus.Blocked]) && assertTrue(client.calls.get() == 0)
          )
      },
      test("run applies output guardrails to the answer") {
        val client = new Recording(_ => text("ok"))
        z(client)(_.withMiddleware(new GuardrailMiddleware(Nil, Seq(new LengthCheck(10, 100)))))
          .run("q")
          .map(result =>
            assertTrue(result.status.isInstanceOf[AgentStatus.Blocked]) && assertTrue(client.calls.get() == 1)
          )
      },
      test("run fails with a NodeFailed whose cause carries the thrown exception instance") {
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
        z(client)().run("q").flip.map { err =>
          assertTrue(err.isInstanceOf[GraphError.NodeFailed]) &&
          assertTrue(Fixtures.thrownOf(err).exists(_ eq failure))
        }
      },
      test("continueConversation continues the previous result's thread and honours withMaxSteps") {
        val client = new Recording(i => if (i == 0) text("first") else toolCall(i))
        val agent  = z(client)(_.withTools(tools).withMaxSteps(2))
        for {
          first <- agent.run("q1")
          next  <- agent.continueConversation(first, "q2")
        } yield assertTrue(next.threadId == first.threadId) &&
          assertTrue(client.calls.get() == 3) && // one for q1, then two (the step limit) for q2
          assertTrue(next.status == AgentStatus.StepLimitReached) &&
          assertTrue(client.conversations.get(2).messages.collect { case m: UserMessage =>
            m.content
          } == Seq("q1", "q2"))
      },
      test("continueConversation applies input guardrails to the new message and does not call the model") {
        val client = new Recording(_ => text("first"))
        val agent  = z(client)(_.withMiddleware(new GuardrailMiddleware(Seq(new LengthCheck(1, 20)), Nil)))
        for {
          first  <- agent.run("q1")
          result <- agent.continueConversation(first, "far too long a follow up for the guardrail")
        } yield assertTrue(result.status.isInstanceOf[AgentStatus.Blocked]) && assertTrue(client.calls.get() == 1)
      },
      test("continueConversation applies output guardrails to the new answer") {
        val client = new Recording(i => if (i == 0) text("a long enough first answer") else text("ok"))
        val agent  = z(client)(_.withMiddleware(new GuardrailMiddleware(Nil, Seq(new LengthCheck(10, 100)))))
        for {
          first <- agent.run("q1")
          next  <- agent.continueConversation(first, "q2")
        } yield assertTrue(first.status == AgentStatus.Completed("a long enough first answer")) &&
          assertTrue(next.status.isInstanceOf[AgentStatus.Blocked]) &&
          // the blocked turn is removed: the thread is as the first turn left it
          assertTrue(next.messages == first.messages)
      },
      test("continueConversation surfaces provider errors with the original LLMError attached") {
        val calls = new AtomicInteger(0)
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
            if (calls.getAndIncrement() == 0) Right(text("first")) else Left(SimpleError("nope"))
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] = complete(c, o)
          def getContextWindow(): Int     = 1
          def getReserveCompletion(): Int = 1
        }
        val agent = z(client)()
        for {
          first <- agent.run("q1")
          err   <- agent.continueConversation(first, "q2").flip
        } yield assertTrue(Fixtures.causeOf(err) == SimpleError("nope"))
      },
      test("resume completes a turn parked on an approval once it is approved") {
        val client = new Recording(i => if (i == 0) toolCall(0) else text("shipped"))
        val agent =
          z(client)(_.withTools(tools).withMiddleware(new ApprovalMiddleware(r => Some(s"review ${r.call.id}"))))
        for {
          parked <- agent.run("go")
          ids = parked.status match {
            case AgentStatus.Suspended(approvals, _) => approvals.map(_._1)
            case _                                   => Vector.empty
          }
          done <- agent.resume(parked.threadId, Map(parked.approve(ids.head)))
        } yield assertTrue(ids.size == 1) && assertTrue(done.status == AgentStatus.Completed("shipped"))
      },
      test("recover completes a run that failed with a provider error, without any cancellation") {
        val calls = new AtomicInteger(0)
        val client = new LLMClient {
          def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
            calls.getAndIncrement() match {
              case 0 => Right(toolCall(0))
              case 1 => Left(SimpleError("provider down"))
              case _ => Right(text("recovered"))
            }
          def streamComplete(
            c: Conversation,
            o: CompletionOptions,
            onChunk: StreamedChunk => Unit
          ): Result[Completion] = complete(c, o)
          def getContextWindow(): Int     = 8192
          def getReserveCompletion(): Int = 512
        }
        val (capture, thread) = Fixtures.threadCapture()
        val agent             = z(client)(_.withTools(tools).withMiddleware(capture))
        for {
          err       <- agent.run("go").flip
          recovered <- agent.recover(thread.get().get)
        } yield assertTrue(Fixtures.causeOf(err) == SimpleError("provider down")) &&
          assertTrue(recovered.status == AgentStatus.Completed("recovered"))
      },
      test("agent(id) builds an AgentZ over the same underlying client") {
        val client = new Recording(_ => text("via-client"))
        LLMClientZ(client).agent("assistant")().flatMap(_.run("q")).map { result =>
          assertTrue(result.status == AgentStatus.Completed("via-client")) && assertTrue(client.calls.get() == 1)
        }
      },
      test("agent(id) applies the configuration to the agent") {
        val client = new Recording(_ => text("x"))
        LLMClientZ(client).agent("assistant")(_.withSystemPrompt("CONFIGURED")).flatMap(_.run("q")).map { _ =>
          assertTrue(
            client.conversations
              .get(0)
              .messages
              .collect { case m: SystemMessage => m.content }
              .head
              .contains("CONFIGURED")
          )
        }
      },
      test("agent(id) fails with the LLMError of a builder that does not build") {
        LLMClientZ(new Recording(_ => text("x"))).agent("not a valid id!")().either.map(r => assertTrue(r.isLeft))
      }
    ) @@ TestAspect.sequential
}
