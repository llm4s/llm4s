package org.llm4s.effect.cats

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

import cats.effect.IO
import cats.effect.unsafe.implicits.global
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
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.*

/** Proves that what `AgentIO` is given reaches the underlying `Agent` unchanged, and what the agent reports comes back. */
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

  private def tools: ToolRegistry = new ToolRegistry(Seq(echoTool))

  private def io(client: LLMClient)(configure: AgentBuilder => AgentBuilder = identity) =
    AgentIO[IO](Fixtures.agentOf(client)(configure))

  "AgentIO.run" should "forward the system prompt, completion options and tools to the model call" in {
    val client = new Recording(_ => text("done"))
    val opts   = CompletionOptions().withTemperature(0.123).withMaxTokens(77)
    val result = io(client)(_.withTools(tools).withSystemPrompt("EXTRA-INSTRUCTIONS").withCompletionOptions(opts))
      .run("q")
      .unsafeRunSync()
    result.status shouldBe AgentStatus.Completed("done")
    client.calls.get() shouldBe 1
    val sent = client.conversations.get(0).messages
    sent.collect { case m: SystemMessage => m.content }.exists(_.contains("EXTRA-INSTRUCTIONS")) shouldBe true
    sent.collect { case m: UserMessage => m.content } shouldBe Seq("q")
    client.options.get(0).temperature shouldBe 0.123
    client.options.get(0).maxTokens shouldBe Some(77)
    client.options.get(0).tools.map(_.name) shouldBe Seq("echo")
  }

  it should "honour withMaxSteps (model calls per turn) and report the step limit" in {
    val client = new Recording(toolCall)
    val result = io(client)(_.withTools(tools).withMaxSteps(1)).run("q").unsafeRunSync()
    client.calls.get() shouldBe 1
    result.status shouldBe AgentStatus.StepLimitReached
  }

  it should "apply the Agent default step cap when withMaxSteps is not given" in {
    val client = new Recording(toolCall)
    val result = io(client)(_.withTools(tools)).run("q").unsafeRunSync()
    val direct = new Recording(toolCall)
    Fixtures.agentOf(direct)(_.withTools(tools)).run("q")
    client.calls.get() shouldBe direct.calls.get()
    client.calls.get() shouldBe Agent.DefaultMaxSteps
    result.status shouldBe AgentStatus.StepLimitReached
  }

  it should "apply input guardrails before any model call and report the turn Blocked" in {
    val client = new Recording(_ => text("done"))
    val result = io(client)(_.withMiddleware(new GuardrailMiddleware(Seq(new LengthCheck(1, 5)), Nil)))
      .run("this query is far too long")
      .unsafeRunSync()
    result.status shouldBe a[AgentStatus.Blocked]
    client.calls.get() shouldBe 0
  }

  it should "apply output guardrails to the answer" in {
    val client = new Recording(_ => text("ok"))
    val result = io(client)(_.withMiddleware(new GuardrailMiddleware(Nil, Seq(new LengthCheck(10, 100)))))
      .run("q")
      .unsafeRunSync()
    result.status shouldBe a[AgentStatus.Blocked]
    client.calls.get() shouldBe 1
  }

  it should "raise a thrown non-LLM exception as an LLMException whose NodeFailed cause carries that instance" in {
    val failure = new IllegalStateException("provider exploded")
    val client = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] = throw failure
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        throw failure
      def getContextWindow(): Int     = 1
      def getReserveCompletion(): Int = 1
    }
    val raised = io(client)().run("q").attempt.unsafeRunSync().left.toOption.get
    raised shouldBe a[LLMException]
    raised.asInstanceOf[LLMException].error shouldBe a[GraphError.NodeFailed]
    (Fixtures.thrownOf(raised.asInstanceOf[LLMException].error).get should be).theSameInstanceAs(failure)
  }

  "AgentIO.continueConversation" should "continue the previous result's thread and honour withMaxSteps" in {
    val client = new Recording(i => if (i == 0) text("first") else toolCall(i))
    val agent  = io(client)(_.withTools(tools).withMaxSteps(2))
    val first  = agent.run("q1").unsafeRunSync()
    val next   = agent.continueConversation(first, "q2").unsafeRunSync()
    next.threadId shouldBe first.threadId
    client.calls.get() shouldBe 3 // one for q1, then two (the step limit) for q2
    next.status shouldBe AgentStatus.StepLimitReached
    val lastRequest = client.conversations.get(2).messages.collect { case m: UserMessage => m.content }
    lastRequest shouldBe Seq("q1", "q2")
  }

  it should "apply input guardrails to the new message and not call the model" in {
    val client = new Recording(_ => text("first"))
    val agent  = io(client)(_.withMiddleware(new GuardrailMiddleware(Seq(new LengthCheck(1, 20)), Nil)))
    val first  = agent.run("q1").unsafeRunSync()
    val result = agent.continueConversation(first, "far too long a follow up for the guardrail").unsafeRunSync()
    result.status shouldBe a[AgentStatus.Blocked]
    client.calls.get() shouldBe 1
  }

  it should "apply output guardrails to the new answer" in {
    val client = new Recording(i => if (i == 0) text("a long enough first answer") else text("ok"))
    val agent  = io(client)(_.withMiddleware(new GuardrailMiddleware(Nil, Seq(new LengthCheck(10, 100)))))
    val first  = agent.run("q1").unsafeRunSync()
    first.status shouldBe AgentStatus.Completed("a long enough first answer")
    val next = agent.continueConversation(first, "q2").unsafeRunSync()
    next.status shouldBe a[AgentStatus.Blocked]
    // the blocked turn is removed: the thread is as the first turn left it
    next.messages shouldBe first.messages
  }

  it should "surface provider errors with the original LLMError attached" in {
    val calls = new AtomicInteger(0)
    val client = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        if (calls.getAndIncrement() == 0) Right(text("first")) else Left(SimpleError("nope"))
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def getContextWindow(): Int     = 1
      def getReserveCompletion(): Int = 1
    }
    val agent = io(client)()
    val first = agent.run("q1").unsafeRunSync()
    val err   = agent.continueConversation(first, "q2").attempt.unsafeRunSync().left.toOption.get
    Fixtures.causeOf(err.asInstanceOf[LLMException].error) shouldBe SimpleError("nope")
  }

  "agent(id)" should "build an AgentIO over the same underlying client" in {
    val client = new Recording(_ => text("via-client"))
    val result = LLMClientIO[IO](client)
      .agent("assistant")()
      .flatMap(_.run("q"))
      .unsafeRunSync()
    result.status shouldBe AgentStatus.Completed("via-client")
    client.calls.get() shouldBe 1
  }

  it should "apply the configuration to the agent" in {
    val client = new Recording(_ => text("x"))
    val result = LLMClientIO[IO](client)
      .agent("assistant")(_.withSystemPrompt("CONFIGURED"))
      .flatMap(_.run("q"))
      .unsafeRunSync()
    result.status shouldBe AgentStatus.Completed("x")
    client.conversations.get(0).messages.collect { case m: SystemMessage => m.content }.head should include(
      "CONFIGURED"
    )
  }

  it should "raise a builder that does not build as LLMException" in {
    val err = LLMClientIO[IO](new Recording(_ => text("x")))
      .agent("not a valid id!")()
      .attempt
      .unsafeRunSync()
    err.left.toOption.get shouldBe a[LLMException]
  }

  "AgentIO.resume" should "complete a turn parked on an approval once it is approved" in {
    val client = new Recording(i => if (i == 0) toolCall(0) else text("shipped"))
    val agent = io(client)(_.withTools(tools).withMiddleware(new ApprovalMiddleware(r => Some(s"review ${r.call.id}"))))
    val parked = agent.run("go").unsafeRunSync()
    val ids = parked.status match {
      case AgentStatus.Suspended(approvals, _) => approvals.map(_._1)
      case other                               => fail(s"expected Suspended, got $other")
    }
    ids should have size 1
    val done = agent.resume(parked.threadId, Map(parked.approve(ids.head))).unsafeRunSync()
    done.status shouldBe AgentStatus.Completed("shipped")
  }

  "AgentIO.recover" should "complete a run that failed with a provider error, without any cancellation" in {
    val calls = new AtomicInteger(0)
    val client = new LLMClient {
      def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
        calls.getAndIncrement() match {
          case 0 => Right(toolCall(0))
          case 1 => Left(SimpleError("provider down"))
          case _ => Right(text("recovered"))
        }
      def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
        complete(c, o)
      def getContextWindow(): Int     = 8192
      def getReserveCompletion(): Int = 512
    }
    val (capture, thread) = Fixtures.threadCapture()
    val agent             = io(client)(_.withTools(tools).withMiddleware(capture))
    val failed            = agent.run("go").attempt.unsafeRunSync()
    Fixtures.causeOf(failed.left.toOption.get.asInstanceOf[LLMException].error) shouldBe SimpleError("provider down")
    agent.recover(thread.get().get).unsafeRunSync().status shouldBe AgentStatus.Completed("recovered")
  }
}
