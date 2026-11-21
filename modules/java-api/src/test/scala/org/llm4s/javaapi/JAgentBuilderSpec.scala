package org.llm4s.javaapi

import org.llm4s.agent.graph.{ InMemoryCheckpointer, ThreadId }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.testutil.TestTimeLimit
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

/** `Llm4s.agentBuilder` from Java (#2476): each option reaches the built agent. */
class JAgentBuilderSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

  /** One model call as the model saw it. */
  final private case class Call(messages: Seq[Message], tools: Seq[String], streamed: Boolean) {
    def system: Seq[String] = messages.collect { case m: SystemMessage => m.content }
    def users: Seq[String]  = messages.collect { case m: UserMessage => m.content }
  }

  /** A model that answers `answer` to every call and records what each call was sent. */
  final private class Recording(answer: String) extends LLMClient {
    val calls = new CopyOnWriteArrayList[Call]()
    private def reply(c: Conversation, o: CompletionOptions, streamed: Boolean): Result[Completion] = {
      calls.add(Call(c.messages.toSeq, o.tools.map(_.name), streamed))
      Right(Completion("id", Instant.EPOCH, answer, "m", AssistantMessage(answer)))
    }
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = reply(c, o, streamed = false)
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] = {
      f(StreamedChunk(id = "c", content = Some(answer)))
      reply(c, o, streamed = true)
    }
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
    def last: Call                           = calls.asScala.last
  }

  private def client(model: LLMClient): JLlmClient = Llm4s.wrapClient(model)

  "Llm4s.agentBuilder" should "give the built agent its system prompt, tools, guardrails, window and streaming" in {
    val model  = new Recording("""{"sum": 5}""")
    val result = AgentBuilderCheck.everything(client(model)).get().run("What is 2 + 3?").get()
    result.status().kind() shouldBe AgentStatusKind.COMPLETED
    result.answer().orElseThrow() shouldBe """{"sum": 5}"""
    model.last.system shouldBe Seq("You are a support assistant.")
    model.last.tools shouldBe Seq("add")
    model.last.streamed shouldBe true
  }

  it should "build the zero-option agent createAgentWithTools builds: no prompt, no tools, complete not stream" in {
    val model = new Recording("hi")
    Llm4s.agentBuilder("plain", client(model)).build().get().run("hello").get().answer().orElseThrow() shouldBe "hi"
    model.last shouldBe Call(model.last.messages, Nil, streamed = false)
    model.last.system shouldBe empty
  }

  it should "let a later setting replace an earlier one" in {
    val model = new Recording("ok")
    AgentBuilderCheck.replaced(client(model)).get().run("go").get()
    model.last.system shouldBe Seq("second")
    model.last.tools shouldBe empty
    model.last.streamed shouldBe false
  }

  it should "leave a builder unchanged by a setter: each returns a new builder" in {
    val model = new Recording("ok")
    val base  = Llm4s.agentBuilder("base", client(model))
    base.systemPrompt("ignored")
    base.build().get().run("go").get()
    model.last.system shouldBe empty
  }

  "an input guardrail" should "end a turn BLOCKED before the model is called" in {
    val model  = new Recording("never")
    val agent  = AgentBuilderCheck.guardingInput(client(model), java.util.List.of(JGuardrails.length(1, 5)))
    val result = agent.run("far too long a query").get()
    result.status().kind() shouldBe AgentStatusKind.BLOCKED
    result.status().guardrail().orElseThrow() shouldBe "LengthCheck"
    result.status().reason().orElseThrow() should include("too long")
    model.calls shouldBe empty
    // the conversation stays usable
    agent.run("ok").get().status().kind() shouldBe AgentStatusKind.COMPLETED
  }

  it should "block on the profanity list and on a pattern, with the pattern's own message" in {
    val profane =
      AgentBuilderCheck.guardingInput(client(new Recording("x")), java.util.List.of(JGuardrails.profanity()))
    profane.run("this is a badword").get().status().guardrail().orElseThrow() shouldBe "ProfanityFilter"
    profane.run("this is fine").get().status().kind() shouldBe AgentStatusKind.COMPLETED

    val digits = AgentBuilderCheck.guardingInput(
      client(new Recording("x")),
      java.util.List.of(JGuardrails.regex("^\\d+$"), JGuardrails.regex("^1", "must start with 1"))
    )
    digits.run("123").get().status().kind() shouldBe AgentStatusKind.COMPLETED
    val blocked = digits.run("abc").get().status()
    blocked.guardrail().orElseThrow() shouldBe "RegexValidator"
    blocked.reason().orElseThrow() should include("must start with 1")
  }

  "an output guardrail" should "end a turn BLOCKED when the answer fails it, and pass one that does not" in {
    val prose =
      AgentBuilderCheck.guardingOutput(client(new Recording("not json")), java.util.List.of(JGuardrails.json()))
    val result = prose.run("Answer in JSON").get()
    result.status().kind() shouldBe AgentStatusKind.BLOCKED
    result.status().guardrail().orElseThrow() shouldBe "JSONValidator"
    result.answer().isPresent shouldBe false

    val json = AgentBuilderCheck.guardingOutput(client(new Recording("[1, 2]")), java.util.List.of(JGuardrails.json()))
    json.run("Answer in JSON").get().answer().orElseThrow() shouldBe "[1, 2]"
  }

  "a context window" should "prune what the model is sent, not what the conversation keeps" in {
    def thirdTurn(messages: Int): (Recording, JAgentResult) = {
      val model = new Recording("ok")
      val agent = AgentBuilderCheck.windowed(client(model), messages)
      val first = agent.run("one").get()
      val third = agent.continueConversation(agent.continueConversation(first, "two").get(), "three").get()
      (model, third)
    }
    val (whole, _) = thirdTurn(0)
    whole.last.users shouldBe Seq("one", "two", "three")

    val (windowed, result) = thirdTurn(3)
    windowed.last.users shouldBe Seq("two", "three")
    windowed.last.messages.size shouldBe 3
    (result.messages().asScala.map(_.content) should contain).allOf("one", "two", "three")
  }

  it should "keep the system prompt out of the count and first" in {
    val model = new Recording("ok")
    val agent = Llm4s
      .agentBuilder("w", client(model))
      .systemPrompt("Be brief.")
      .contextWindow(JContextWindow.lastMessages(1))
      .build()
      .get()
    agent.continueConversation(agent.run("one").get(), "two").get()
    model.last.system shouldBe Seq("Be brief.")
    model.last.messages.head shouldBe a[SystemMessage]
    model.last.users shouldBe Seq("two")
  }

  "build()" should "refuse each null, invalid or clashing setting as a failed result naming it, never throwing" in {
    val messages = AgentBuilderCheck.refusals(client(new Recording("x"))).asScala.toList.map(_.toLowerCase)
    val expected = List(
      "id",
      "",
      "client",
      "systemprompt",
      "tools",
      "a tool is null",
      "two tools are named 'add'",
      "inputguardrails",
      "a guardrail is null",
      "outputguardrails",
      "contextwindow",
      "min",
      "max",
      "pattern",
      "pattern",
      "errormessage",
      "messages"
    )
    messages should have size expected.size.toLong
    messages.foreach(_ should not be "built")
    messages.zip(expected).foreach((message, part) => message should include(part))
  }

  "the facade's guardrails and window" should "name themselves, and describe an invalid one" in {
    JGuardrails.length(1, 2).name() shouldBe "LengthCheck"
    JGuardrails.json().name() shouldBe "JSONValidator"
    JGuardrails.length(-1, 2).name() shouldBe ""
    JGuardrails.profanity().toString shouldBe "JGuardrail(ProfanityFilter)"
    JGuardrails.json().toString shouldBe "JOutputGuardrail(JSONValidator)"
    JGuardrails.length(-1, 2).toString should startWith("JGuardrail(invalid: ")
    JContextWindow.lastMessages(20).toString shouldBe "JContextWindow(lastMessages=20)"
    JContextWindow.lastMessages(0).toString should startWith("JContextWindow(invalid: ")
  }

  "the Java guide's agent-builder block" should "complete with a JSON answer and report a blocked one" in {
    val add = AgentBuilderCheck.add()
    GuideSnippets.agentBuilder(client(new Recording("""{"answer": 5}""")), add).status().kind() shouldBe
      AgentStatusKind.COMPLETED
    val blocked = GuideSnippets.agentBuilder(client(new Recording("five")), add)
    blocked.status().guardrail().orElseThrow() shouldBe "JSONValidator"
  }

  "the migration guide's windowed agent" should "send the system prompt and at most the last 20 messages" in {
    val model = new Recording("ok")
    val agent = MigrationSnippets.windowed(client(model))
    val last =
      (1 to 15).foldLeft(agent.run("turn 0").get())((prev, i) => agent.continueConversation(prev, s"turn $i").get())
    last.messages().size() should be > 21
    model.last.system shouldBe Seq("You are a helpful assistant.")
    model.last.messages.count(m => !m.isInstanceOf[SystemMessage]) should be <= 20
  }

  /** A model that calls `add` on every call, so a turn only ends at its step limit. */
  final private class Looping extends LLMClient {
    val calls = new java.util.concurrent.atomic.AtomicInteger(0)
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      val n    = calls.incrementAndGet()
      val call = ToolCall(s"c$n", "add", ujson.Obj("a" -> 1, "b" -> 2))
      Right(Completion("id", Instant.EPOCH, "", "m", AssistantMessage(None, Seq(call)), toolCalls = List(call)))
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 512
  }

  /** A tracer that keeps the events it is given. */
  final private class Recorder extends Tracing {
    val events                                               = new CopyOnWriteArrayList[TraceEvent]()
    override def traceEvent(event: TraceEvent): Result[Unit] = { events.add(event); Right(()) }
    override def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    override def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    override def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    override def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
  }

  "maxSteps" should "end a turn STEP_LIMIT_REACHED after that many steps, and refuse one below 1" in {
    val looping = new Looping
    val agent =
      Llm4s.agentBuilder("loop", client(looping)).tools(java.util.List.of(AgentBuilderCheck.add())).maxSteps(2)
    agent.build().get().run("go").get().status().kind() shouldBe AgentStatusKind.STEP_LIMIT_REACHED
    looping.calls.get() should be <= 2

    val refused = Llm4s.agentBuilder("loop", client(looping)).maxSteps(0).build()
    refused.isFailure shouldBe true
    refused.getError().getMessage should include("maxSteps")
  }

  "checkpointer" should "keep the agent's threads in the store it is given, and refuse null" in {
    val store = new InMemoryCheckpointer()
    val result =
      Llm4s.agentBuilder("kept", client(new Recording("ok"))).checkpointer(store).build().get().run("hi").get()
    store.latest(ThreadId(result.threadId())).map(_.isDefined) shouldBe Right(true)
    Llm4s.agentBuilder("kept", client(new Recording("ok"))).checkpointer(null).build().getError().getMessage should
      include("checkpointer")
  }

  "tracing" should "report the agent's runs to the tracer it is given, and refuse null" in {
    val tracer = new Recorder
    Llm4s.agentBuilder("traced", client(new Recording("ok"))).tracing(tracer).build().get().run("hi").get()
    tracer.events.asScala.collect { case e: TraceEvent.AgentRunEnded => e } should not be empty
    Llm4s.agentBuilder("traced", client(new Recording("ok"))).tracing(null).build().getError().getMessage should
      include("tracing")
  }
}
