package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ InterruptId, RunConfig, RunStatus, ThreadId }
import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.tool.{ AgentTool, ToolSet }
import org.llm4s.agent.graph.toolloop.ApprovalDecision
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

/** A single turn of the graph-runtime [[Agent]]: answer, tools, usage, prompt, threads and building. */
class AgentRunSpec extends AnyFlatSpec with Matchers {

  final case class Echoed(text: String) derives ReadWriter

  /** A tool that echoes its `text` argument, as `{"text": ...}`. */
  private def echoTool(name: String = "echo"): ToolFunction[Map[String, Any], Echoed] =
    ToolBuilder[Map[String, Any], Echoed](
      name,
      "Echoes its text",
      Schema.`object`[Map[String, Any]]("Echo parameters").withRequiredField("text", Schema.string("Text to echo"))
    ).withHandler(extractor => extractor.getString("text").map(Echoed(_)))
      .buildSafe()
      .fold(e => fail(s"tool did not build: $e"), identity)

  private def toolCalls(calls: (String, String, ujson.Value)*): Completion = {
    val message = AssistantMessage(None, calls.map((id, name, args) => ToolCall(id, name, args)))
    CompletionFixture.withMessage(message)
  }

  "Agent.run" should "complete a single turn with the answer, storing the user and assistant messages" in {
    val result = plain(ScriptedLLMClient.of(CompletionFixture.simple("Hello there"))).run("Hi").value

    result.status shouldBe AgentStatus.Completed("Hello there")
    result.answer shouldBe Some("Hello there")
    result.messages shouldBe Vector(UserMessage("Hi"), AssistantMessage("Hello there"))
    result.activeAgent.value shouldBe "assistant"
  }

  it should "give each of two parallel tool calls exactly one result, then continue to the answer" in {
    val client = ScriptedLLMClient.of(
      toolCalls(("c1", "echo", ujson.Obj("text" -> "one")), ("c2", "echo", ujson.Obj("text" -> "two"))),
      CompletionFixture.simple("both echoed")
    )
    val agent  = built(Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(echoTool()))))
    val result = agent.run("echo twice").value

    result.answer shouldBe Some("both echoed")
    val results = result.messages.collect { case t: ToolMessage => t.toolCallId -> t.content }
    results.map(_._1) shouldBe Vector("c1", "c2")
    results.toMap.apply("c1") should include("one")
    results.toMap.apply("c2") should include("two")
    client.sent(1).collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1", "c2")
  }

  it should "send the tools to the model in the completion options, with the agent's other options" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("ok"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(new ToolRegistry(Seq(echoTool())))
        .withCompletionOptions(CompletionOptions().withTemperature(0.2).withMaxTokens(77))
    )
    agent.run("q").value

    val options = client.calls.head._2
    options.tools.map(_.name) shouldBe Seq("echo")
    options.temperature shouldBe 0.2
    options.maxTokens shouldBe Some(77)
  }

  it should "run a tool given as a ToolSet of agent tools" in {
    val client = ScriptedLLMClient.of(
      toolCalls(("c1", "echo", ujson.Obj("text" -> "set"))),
      CompletionFixture.simple("done")
    )
    val tools  = ToolSet.of(AgentTool.fromToolFunction(echoTool())).fold(e => fail(e.message), identity)
    val result = built(Agent.builder("assistant", client).withTools(tools)).run("q").value

    result.messages.collect { case t: ToolMessage => t.content }.head should include("set")
  }

  it should "give a failing tool's error to the model as its result, without failing the run" in {
    val client = ScriptedLLMClient.of(
      toolCalls(("c1", "echo", ujson.Obj("wrong" -> "argument"))),
      CompletionFixture.simple("handled")
    )
    val result = built(Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(echoTool()))))
      .run("q")
      .value

    result.answer shouldBe Some("handled")
    result.messages.collect { case t: ToolMessage => t.toolCallId } shouldBe Vector("c1")
  }

  it should "sum usage over every model call of the turn" in {
    val client = ScriptedLLMClient.of(
      toolCalls(("c1", "echo", ujson.Obj("text" -> "a"))),
      toolCalls(("c2", "echo", ujson.Obj("text" -> "b"))),
      CompletionFixture.withUsage("done", prompt = 100, completion = 7)
    )
    val result = built(Agent.builder("assistant", client).withTools(new ToolRegistry(Seq(echoTool()))))
      .run("q")
      .value

    result.usage.requestCount shouldBe 3
    result.usage.inputTokens shouldBe (10 + 10 + 100)
    result.usage.outputTokens shouldBe (20 + 20 + 7)
    result.usage.byModel.keySet shouldBe Set("test-model")
  }

  it should "send the system prompt first on every call, and never store it" in {
    val client = ScriptedLLMClient.of(
      toolCalls(("c1", "echo", ujson.Obj("text" -> "a"))),
      CompletionFixture.simple("done")
    )
    val agent = built(
      Agent
        .builder("assistant", client)
        .withSystemPrompt("You are terse.")
        .withTools(new ToolRegistry(Seq(echoTool())))
    )
    val result = agent.run("q").value

    client.sent.map(_.head) shouldBe Vector(SystemMessage("You are terse."), SystemMessage("You are terse."))
    result.messages.collect { case s: SystemMessage => s } shouldBe empty
  }

  it should "run on a new thread each time" in {
    val agent  = plain(ScriptedLLMClient.of(CompletionFixture.simple("one"), CompletionFixture.simple("two")))
    val first  = agent.run("first").value
    val second = agent.run("second").value

    first.threadId should not be second.threadId
    second.messages shouldBe Vector(UserMessage("second"), AssistantMessage("two"))
  }

  it should "end the turn StepLimitReached at maxSteps model calls, without calling the model again" in {
    val looping = new NTurnFakeLLMClient(CompletionFixture.withToolCall("missing_tool", ujson.Obj()))
    val result  = built(Agent.builder("assistant", looping).withMaxSteps(2)).run("loop").value

    result.status shouldBe AgentStatus.StepLimitReached
    result.answer shouldBe None
    result.usage.requestCount shouldBe 2
  }

  it should "complete when the answer comes on exactly the last allowed step" in {
    val client = ScriptedLLMClient.of(
      CompletionFixture.withToolCall("missing_tool", ujson.Obj()),
      CompletionFixture.simple("just in time")
    )
    built(Agent.builder("assistant", client).withMaxSteps(2)).run("q").value.answer shouldBe Some("just in time")
  }

  it should "offer each handoff to the model as a handoff_to_<id> tool" in {
    val client = ScriptedLLMClient.of(CompletionFixture.simple("no transfer needed"))
    val agent = built(
      Agent
        .builder("assistant", client)
        .withHandoffs(Handoff.to("physics", Agent.builder("physics", client), "Physics questions"))
    )
    agent.run("q").value

    client.calls.head._2.tools.map(_.name) shouldBe Seq("handoff_to_physics")
  }

  "Agent.start" should "return the running turn, whose await gives its result, every time it is called" in {
    val agent  = plain(ScriptedLLMClient.of(CompletionFixture.simple("ok"), CompletionFixture.simple("next")))
    val thread = ThreadId("started")
    val run    = agent.start(thread, "q").fold(e => fail(e.message), identity)
    val result = run.await().value

    run.threadId shouldBe thread
    run.status shouldBe RunStatus.Completed
    result.runId shouldBe run.runId
    result.answer shouldBe Some("ok")
    // a cancel after the turn ended is a no-op, and awaiting again gives the same result
    run.cancel()
    run.await().value shouldBe result
    agent.run(thread, "again", RunConfig()).value.messages should have size 4
  }

  "A tool call that needs approval" should "suspend the turn, and resume runs it once approved" in {
    val client = ScriptedLLMClient.of(
      toolCalls(("c1", "echo", ujson.Obj("text" -> "approved"))),
      CompletionFixture.simple("ran it")
    )
    val agent = built(
      Agent
        .builder("assistant", client)
        .withTools(new ToolRegistry(Seq(echoTool())))
        .withMiddleware(new ApprovalMiddleware(_ => Some("every call is reviewed")))
    )
    val parked = agent.run("q").value

    val approvals = parked.status match {
      case AgentStatus.Suspended(approvals, questions, _, _) =>
        questions shouldBe empty
        approvals
      case other => fail(s"expected Suspended, got $other")
    }
    approvals.map(_._2.call.id) shouldBe Vector("c1")
    approvals.head._2.reason shouldBe "every call is reviewed"
    parked.answer shouldBe None

    val resumed = agent.resume(parked.threadId, Map(parked.approve(approvals.head._1))).value
    resumed.answer shouldBe Some("ran it")
    resumed.messages.collect { case t: ToolMessage => t.content }.head should include("approved")
  }

  "AgentResult's answer helpers" should "encode each decision as the approval node reads it" in {
    val result = plain(ScriptedLLMClient.of(CompletionFixture.simple("ok"))).run("q").value
    val id     = InterruptId("i-1")

    result.approve(id) shouldBe (id      -> upickle.default.writeJs[ApprovalDecision](ApprovalDecision.Approve))
    result.reject(id, "no") shouldBe (id -> upickle.default.writeJs[ApprovalDecision](ApprovalDecision.Reject("no")))
    result.edit(id, ujson.Obj("text" -> "x")) shouldBe
      (id -> upickle.default.writeJs[ApprovalDecision](ApprovalDecision.Edit(ujson.Obj("text" -> "x"))))
    result.reply(id, Echoed("yes")) shouldBe (id -> ujson.Obj("text" -> "yes"))
  }

  "AgentBuilder.build" should "refuse two tools with one name" in {
    val error = Agent
      .builder("assistant", ScriptedLLMClient.of())
      .withTools(new ToolRegistry(Seq(echoTool(), echoTool())))
      .build()
      .fold(identity, _ => fail("expected a refusal"))

    error shouldBe a[ValidationError]
    error.message should include("duplicate tool name")
  }

  it should "refuse one id defined by two builders with different settings" in {
    val client = ScriptedLLMClient.of()
    val x1     = Agent.builder("x", client).withSystemPrompt("first")
    val x2     = Agent.builder("x", client).withSystemPrompt("second")
    val y      = Agent.builder("y", client).withHandoffs(Handoff.to("x", x2))
    val error = Agent
      .builder("root", client)
      .withHandoffs(Handoff.to("x", x1), Handoff.to("y", y))
      .build()
      .fold(identity, _ => fail("expected a refusal"))

    error shouldBe ValidationError("handoffs", "agent id 'x' is defined by two different builders")
  }

  it should "accept one id reached through the same builder twice, or through builders with equal settings" in {
    val client = ScriptedLLMClient.of()
    val x      = Agent.builder("x", client).withSystemPrompt("same")
    val y      = Agent.builder("y", client).withHandoffs(Handoff.to("x", x))
    val sameX  = Agent.builder("x", client).withSystemPrompt("same")
    val z      = Agent.builder("z", client).withHandoffs(Handoff.to("x", sameX))

    Agent.builder("root", client).withHandoffs(Handoff.to("x", x), Handoff.to("y", y)).build().isRight shouldBe true
    Agent.builder("root", client).withHandoffs(Handoff.to("x", x), Handoff.to("z", z)).build().isRight shouldBe true
  }

  it should "refuse maxSteps below 1" in {
    Agent.builder("assistant", ScriptedLLMClient.of()).withMaxSteps(0).build() shouldBe
      Left(ValidationError("maxSteps", "agent 'assistant': must be at least 1, was 0"))
  }

  it should "refuse an invalid agent id" in {
    val error = Agent.builder("not valid!", ScriptedLLMClient.of()).build().fold(identity, _ => fail("refused"))
    error shouldBe a[ValidationError]
    error.message should include("[a-zA-Z0-9_-]{1,52}")
  }

  it should "refuse a handoff whose id is not its target's, or is invalid" in {
    val client = ScriptedLLMClient.of()
    val target = Agent.builder("physics", client)

    val mismatched = Agent
      .builder("root", client)
      .withHandoffs(Handoff("science", Some(target)))
      .build()
      .fold(identity, _ => fail("expected a refusal"))
    mismatched shouldBe a[ValidationError]
    mismatched.message should include("must be its target's agent id 'physics'")

    val invalid = Agent
      .builder("root", client)
      .withHandoffs(Handoff("bad id", Some(target)))
      .build()
      .fold(identity, _ => fail("expected a refusal"))
    invalid.message should include("handoff id 'bad id' must match")
  }

  it should "version the graph by the family's settings, not by its client or options" in {
    val a                              = ScriptedLLMClient.of()
    val b                              = ScriptedLLMClient.of()
    def version(builder: AgentBuilder) = built(builder).loop.graph.version

    version(Agent.builder("assistant", a)) shouldBe version(
      Agent.builder("assistant", b).withCompletionOptions(CompletionOptions().withTemperature(0.1))
    )
    version(Agent.builder("assistant", a)) should not be version(Agent.builder("assistant", a).withSystemPrompt("p"))
    version(Agent.builder("assistant", a)) should not be version(Agent.builder("assistant", a).withMaxSteps(3))
    version(Agent.builder("assistant", a)) should not be version(
      Agent.builder("assistant", a).withTools(new ToolRegistry(Seq(echoTool())))
    )
    version(Agent.builder("assistant", a)).matches("[0-9a-f]{64}") shouldBe true
    built(Agent.builder("assistant", a)).loop.graph.id shouldBe "assistant"
  }
}
