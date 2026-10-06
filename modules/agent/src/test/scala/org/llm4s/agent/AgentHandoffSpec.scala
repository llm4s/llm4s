package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * `Handoff` with a builder target, and handoffs through a built [[Agent]]: the former `HandoffSpec`
 * and `HandoffIntegrationSpec`, ported.
 */
class AgentHandoffSpec extends AnyFlatSpec with Matchers {

  private val client = ScriptedLLMClient.of()

  private def target(id: String): AgentBuilder = Agent.builder(id, client)

  "Handoff" should "create with a target builder and default settings" in {
    val physics = target("physics")
    val handoff = Handoff("physics", Some(physics))

    handoff.id shouldBe "physics"
    handoff.target.get shouldBe theSameInstanceAs(physics)
    handoff.preserveContext shouldBe true
    handoff.transferReason shouldBe None
  }

  it should "derive a stable handoffId from the explicit id" in {
    Handoff.to("physics", target("physics")).handoffId shouldBe "handoff_to_physics"
  }

  it should "give equal handoffIds to the same id on different builders" in {
    Handoff.to("math", target("math")).handoffId shouldBe Handoff.to("math", target("math")).handoffId
  }

  it should "generate a human-readable name with and without a reason" in {
    Handoff.to("math", target("math"), "Math expertise").handoffName shouldBe "Handoff: Math expertise"
    Handoff.to("math", target("math")).handoffName shouldBe "Handoff to math"
  }

  "Handoff.of" should "refuse invalid ids and accept valid ones" in {
    val builder = target("x")
    Handoff.of("", builder).left.map(_.getClass.getSimpleName) shouldBe Left("ValidationError")
    Handoff.of("has space", builder) shouldBe a[Left[_, _]]
    Handoff.of("x" * 53, builder) shouldBe a[Left[_, _]]
    Handoff.of("x" * 52, builder) shouldBe a[Right[_, _]]
    Handoff.of("a_b-C9", builder, Some("why")).map(_.transferReason) shouldBe Right(Some("why"))
  }

  "Handoff.to" should "create with and without a reason" in {
    val math = target("math")
    Handoff.to("math", math).target.get shouldBe theSameInstanceAs(math)
    Handoff.to("math", math).transferReason shouldBe None
    Handoff.to("math", math, "Specialist needed").transferReason shouldBe Some("Specialist needed")
  }

  it should "take preserveContext, defaulting to true" in {
    val math = target("math")
    Handoff.to("math", math, "Specialist needed", preserveContext = false).preserveContext shouldBe false
    Handoff.to("math", math, "Specialist needed").preserveContext shouldBe true
    Handoff.of("math", math, Some("why"), preserveContext = false).map(_.preserveContext) shouldBe Right(false)
    Handoff.of("math", math).map(_.preserveContext) shouldBe Right(true)
  }

  it should "throw IllegalArgumentException for an invalid id" in {
    an[IllegalArgumentException] should be thrownBy Handoff.to("has space", target("x"))
    an[IllegalArgumentException] should be thrownBy Handoff.to("", target("x"), "r")
  }

  "Handoff.toId" should "name a target by agent id alone, with or without a reason" in {
    Handoff.toId("triage").target shouldBe None
    Handoff.toId("triage").transferReason shouldBe None
    Handoff.toId("triage", "Back to triage").transferReason shouldBe Some("Back to triage")
    Handoff.toId("triage", preserveContext = false).preserveContext shouldBe false
    an[IllegalArgumentException] should be thrownBy Handoff.toId("has space")
  }

  "AgentBuilder.build" should "refuse an id-only handoff to an agent no builder of the family defines" in {
    val error = Agent
      .builder("physics", client)
      .withHandoffs(Handoff.toId("triage"))
      .build()
      .fold(identity, _ => fail("expected a refusal"))

    error shouldBe ValidationError(
      "handoffs",
      List("agent 'physics' hands off to 'triage' by id, and no builder in the family defines it")
    )
  }

  it should "refuse a builder equal to one already reached whose own handoff target differs" in {
    val c      = Agent.builder("c", client).withSystemPrompt("C")
    val cPrime = Agent.builder("c", client).withSystemPrompt("C prime")
    val b      = Agent.builder("b", client).withHandoffs(Handoff.to("c", c))
    val bPrime = Agent.builder("b", client).withHandoffs(Handoff.to("c", cPrime))
    val x      = Agent.builder("x", client).withHandoffs(Handoff.to("b", bPrime))
    val error = Agent
      .builder("root", client)
      .withHandoffs(Handoff.to("b", b), Handoff.to("x", x))
      .build()
      .fold(identity, _ => fail("expected a refusal"))

    error shouldBe ValidationError("handoffs", "agent id 'c' is defined by two different builders")
  }

  it should "refuse two handoffs to one target, before any model call" in {
    val calls = ScriptedLLMClient.of()
    val other = Agent.builder("dup", calls)
    val error = Agent
      .builder("assistant", calls)
      .withHandoffs(Handoff.to("dup", other), Handoff.to("dup", other))
      .build()
      .fold(identity, _ => fail("expected a refusal"))

    error shouldBe a[ValidationError]
    error.message should include("more than one handoff to 'dup'")
    calls.callCount shouldBe 0
  }

  private def handoffCall(target: String): Completion =
    CompletionFixture.withMessage(
      AssistantMessage(
        Some("I'll hand this off to the specialist"),
        Seq(ToolCall("call_1", s"handoff_to_$target", ujson.Obj("reason" -> "Requires advanced math")))
      )
    )

  "A handoff call" should "move the turn to the target, which answers and stays active" in {
    val root       = ScriptedLLMClient.of(handoffCall("specialist"))
    val specialist = ScriptedLLMClient.of(CompletionFixture.simple("2x"), CompletionFixture.simple("still me"))
    val agent = built(
      Agent
        .builder("general", root)
        .withHandoffs(Handoff.to("specialist", Agent.builder("specialist", specialist), "Math specialist"))
    )

    val result = agent.run("What is the derivative of x^2?").value

    result.answer shouldBe Some("2x")
    result.activeAgent.value shouldBe "specialist"
    result.messages.collect { case t: ToolMessage => t.content } shouldBe Vector("Transferred to specialist")

    val next = agent.continueConversation(result, "And of x^3?").value
    next.answer shouldBe Some("still me")
    root.callCount shouldBe 1
  }

  it should "run a cycle: the specialist hands back, by id, to the agent that handed to it, within one turn" in {
    val triageClient = ScriptedLLMClient.of(handoffCall("physics"), CompletionFixture.simple("back with triage"))
    val physicsClient = ScriptedLLMClient.of(
      CompletionFixture.withMessage(
        AssistantMessage(None, Seq(ToolCall("call_2", "handoff_to_triage", ujson.Obj("reason" -> "not physics"))))
      )
    )
    val physics = Agent.builder("physics", physicsClient).withHandoffs(Handoff.toId("triage", "Not a physics question"))
    val agent   = built(Agent.builder("triage", triageClient).withHandoffs(Handoff.to("physics", physics)))

    val result = agent.run("What is for lunch?").value

    result.answer shouldBe Some("back with triage")
    result.activeAgent.value shouldBe "triage"
    result.messages.collect { case t: ToolMessage => t.content } shouldBe
      Vector("Transferred to physics", "Transferred to triage")
    physicsClient.calls.head._2.tools.map(_.name) shouldBe Seq("handoff_to_triage")
    triageClient.callCount shouldBe 2
  }

  it should "send the target the whole conversation when it preserves context" in {
    val root       = ScriptedLLMClient.of(CompletionFixture.simple("Answer 1"), handoffCall("specialist"))
    val specialist = ScriptedLLMClient.of(CompletionFixture.simple("specialist answer"))
    val agent = built(
      Agent.builder("general", root).withHandoffs(Handoff.to("specialist", Agent.builder("specialist", specialist)))
    )

    agent.runMultiTurn("Question 1", Seq("Question 2")).value

    specialist.sent.head.collect { case u: UserMessage => u.content } shouldBe Vector("Question 1", "Question 2")
  }

  it should "send the target only the last question and the transfer when it does not preserve context" in {
    val root       = ScriptedLLMClient.of(CompletionFixture.simple("Answer 1"), handoffCall("specialist"))
    val specialist = ScriptedLLMClient.of(CompletionFixture.simple("specialist answer"))
    val agent = built(
      Agent
        .builder("general", root)
        .withHandoffs(Handoff("specialist", Some(Agent.builder("specialist", specialist)), preserveContext = false))
    )

    val result = agent.runMultiTurn("Question 1", Seq("Question 2")).value

    specialist.sent.head.head shouldBe UserMessage("Question 2")
    specialist.sent.head.collect { case u: UserMessage => u.content } shouldBe Vector("Question 2")
    result.messages.collect { case u: UserMessage => u.content } shouldBe Vector("Question 1", "Question 2")
  }

  it should "run the target with its own prompt and tools, not the caller's" in {
    val root = ScriptedLLMClient.of(handoffCall("physics"))
    val physicsClient = ScriptedLLMClient.of(
      SpecTools.calling(SpecTools.call("p1", "gravity", "g")),
      CompletionFixture.simple("9.8")
    )
    val gravity = SpecTools.tool("gravity")((_, _) => ToolOutcome.Success(ujson.Str("9.8 m/s2")))
    val physics =
      Agent
        .builder("physics", physicsClient)
        .withSystemPrompt("You are a physicist")
        .withTools(SpecTools.set(gravity))
    val agent = built(
      Agent.builder("triage", root).withSystemPrompt("You are triage").withHandoffs(Handoff.to("physics", physics))
    )

    val result = agent.run("How strong is gravity?").value

    result.answer shouldBe Some("9.8")
    physicsClient.calls.head._2.tools.map(_.name) shouldBe Seq("gravity")
    physicsClient.sent.head.collect { case s: SystemMessage => s.content } shouldBe Vector("You are a physicist")
    root.sent.head.collect { case s: SystemMessage => s.content } shouldBe Vector("You are triage")
    root.calls.head._2.tools.map(_.name) shouldBe Seq("handoff_to_physics")
  }

  it should "send the next turn to the target, and keep the handoff from the root's model" in {
    val root     = ScriptedLLMClient.of(handoffCall("physics"))
    val physicsC = ScriptedLLMClient.of(CompletionFixture.simple("first"), CompletionFixture.simple("second"))
    val agent =
      built(Agent.builder("triage", root).withHandoffs(Handoff.to("physics", Agent.builder("physics", physicsC))))
    val first  = agent.run("q1").value
    val second = agent.continueConversation(first, "q2").value

    second.activeAgent.value shouldBe "physics"
    second.answer shouldBe Some("second")
    root.callCount shouldBe 1
  }

  "A handoff whose id differs from its target's agent id" should "be refused at build" in {
    val error = Agent
      .builder("triage", client)
      .withHandoffs(Handoff("physics", Some(Agent.builder("chemistry", client))))
      .build()
      .fold(identity, _ => fail("expected a refusal"))

    error shouldBe ValidationError(
      "handoffs",
      "agent 'triage': handoff id 'physics' must be its target's agent id 'chemistry'"
    )
  }
}
