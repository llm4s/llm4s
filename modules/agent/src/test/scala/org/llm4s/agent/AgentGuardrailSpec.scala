package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ GraphError, RunContext }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, GuardrailMiddleware, MiddlewareId }
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.builtin.{ JSONValidator, LengthCheck, ProfanityFilter }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.atomic.AtomicInteger

/**
 * Guardrails on the agent: a block is the kernel's Block (design 4.13), which `Agent` reports as the
 * turn's `Blocked` outcome, not a `Left`. An input block stores nothing of the turn, an output block
 * removes it, and the next turn runs normally. Another middleware's boundary failure is also a
 * Block, returned as `Left`, the thread usable. Ports the behaviour of the former
 * `AgentGuardrailsIntegrationSpec`.
 */
class AgentGuardrailSpec extends AnyFlatSpec with Matchers {

  private def guarded(
    client: ScriptedLLMClient,
    input: Seq[InputGuardrail] = Nil,
    output: Seq[OutputGuardrail] = Nil
  ): Agent =
    built(Agent.builder("assistant", client).withMiddleware(new GuardrailMiddleware(input, output)))

  private def answers(texts: String*): ScriptedLLMClient =
    ScriptedLLMClient.of(texts.map(CompletionFixture.simple)*)

  private val secretFree: OutputGuardrail = new OutputGuardrail {
    val name: String = "NoSecrets"
    def validate(value: String): Result[String] =
      if (value.contains("secret")) Left(ValidationError.invalid("output", "response must not contain secrets"))
      else Right(value)
  }

  "An input block" should "end the turn Blocked, store nothing, change no usage, and never call the model" in {
    val client = answers("never sent")
    val result = guarded(client, input = Seq(new LengthCheck(min = 10, max = 100))).run("Short").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "LengthCheck"
    result.status.asInstanceOf[AgentStatus.Blocked].reason should include("too short")
    result.answer shouldBe None
    result.messages shouldBe empty
    result.usage.requestCount shouldBe 0
    client.callCount shouldBe 0
  }

  it should "leave the thread usable, so the next turn works and the blocked query is not in its history" in {
    val client = answers("first answer", "second answer")
    val agent  = guarded(client, input = Seq(new LengthCheck(min = 10, max = 100)))

    val first   = agent.run("A long enough first query").value
    val blocked = agent.continueConversation(first, "Short").value
    val next    = agent.continueConversation(blocked, "A long enough third query").value

    blocked.status shouldBe a[AgentStatus.Blocked]
    blocked.messages shouldBe first.messages
    blocked.usage shouldBe first.usage
    next.answer shouldBe Some("second answer")
    next.messages.collect { case u: UserMessage => u.content } shouldBe Vector(
      "A long enough first query",
      "A long enough third query"
    )
  }

  it should "come from the first failing guardrail of several" in {
    val result = guarded(
      answers("never sent"),
      input = Seq(new LengthCheck(min = 1, max = 100), new ProfanityFilter())
    ).run("This contains badword").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "ProfanityFilter"
  }

  it should "stop the turn before the output guardrails or the model" in {
    val client = answers("""{"result": "success"}""")
    val result = guarded(client, input = Seq(new LengthCheck(1, 5)), output = Seq(new JSONValidator()))
      .run("This query is too long")
      .value

    result.status.asInstanceOf[AgentStatus.Blocked].reason should include("too long")
    client.callCount shouldBe 0
  }

  it should "block every entry point - run, continueConversation and runMultiTurn - before the model" in {
    val reject = new InputGuardrail {
      val name: String = "Reject"
      def validate(value: String): Result[String] =
        if (value.startsWith("no")) Left(ValidationError.invalid("input", "not allowed")) else Right(value)
    }
    val client = answers("fine")
    val agent  = guarded(client, input = Seq(reject))
    val first  = agent.run("yes").value

    agent.run("no thanks").value.status shouldBe a[AgentStatus.Blocked]
    agent.continueConversation(first, "no more").value.status shouldBe a[AgentStatus.Blocked]
    agent.runMultiTurn("no start", Seq("yes")).value.status shouldBe a[AgentStatus.Blocked]
    client.callCount shouldBe 1
  }

  "An output block" should "remove the blocked turn and end it Blocked, keeping the turn's usage" in {
    val client = answers("not json")
    val result = guarded(client, output = Seq(new JSONValidator())).run("Generate JSON").value

    val blocked = result.status.asInstanceOf[AgentStatus.Blocked]
    blocked.guardrail shouldBe "JSONValidator"
    blocked.reason should include("not valid JSON")
    result.answer shouldBe None
    result.messages shouldBe empty
    result.usage.requestCount shouldBe 1
  }

  it should "keep the history valid: a follow-up turn succeeds, and the model sees nothing of the blocked turn" in {
    val client = answers("first", "leaks the secret", "a safe answer")
    val agent  = guarded(client, output = Seq(secretFree))

    val first    = agent.run("Hello").value
    val blocked  = agent.continueConversation(first, "Tell me").value
    val followUp = agent.continueConversation(blocked, "Try again").value

    blocked.status shouldBe a[AgentStatus.Blocked]
    blocked.messages shouldBe first.messages
    followUp.answer shouldBe Some("a safe answer")
    followUp.threadId shouldBe blocked.threadId
    client.sent(2) shouldBe Vector(UserMessage("Hello"), AssistantMessage("first"), UserMessage("Try again"))
    Message.validateConversation(followUp.messages.toList) shouldBe Right(())
  }

  it should "apply after a successful tool round, having called the model twice" in {
    val client = ScriptedLLMClient.of(
      CompletionFixture.withToolCall("missing_tool", ujson.Obj()),
      CompletionFixture.simple("leaks the secret")
    )
    val result = guarded(client, output = Seq(secretFree)).run("q").value

    result.status.asInstanceOf[AgentStatus.Blocked].reason should include("response must not contain secrets")
    client.callCount shouldBe 2
  }

  it should "check each turn's answer on a continued conversation" in {
    val client = answers("""{"turn": 1}""", "not json")
    val agent  = guarded(client, output = Seq(new JSONValidator()))

    val first  = agent.run("First query").value
    val second = agent.continueConversation(first, "Generate JSON please").value

    first.status shouldBe AgentStatus.Completed("""{"turn": 1}""")
    second.status shouldBe a[AgentStatus.Blocked]
  }

  "Passing guardrails" should "complete the turn with the answer, in single and multi-turn conversations" in {
    val client = answers("""{"response": "ok"}""", """{"response": "ok"}""", """{"response": "ok"}""")
    val agent = guarded(
      client,
      input = Seq(new LengthCheck(1, 100), new ProfanityFilter()),
      output = Seq(new JSONValidator(), new LengthCheck(1, 1000))
    )

    val result = agent.runMultiTurn("First query", Seq("Second query", "Third query")).value

    result.status shouldBe AgentStatus.Completed("""{"response": "ok"}""")
    result.messages should have size 6
  }

  they should "not interfere when the lists are empty" in {
    guarded(answers("Normal response")).run("Query").value.answer shouldBe Some("Normal response")
  }

  /** Fails `afterAgent` with a non-guardrail error the first `failures` times. */
  final private class FlakyAfterAgent(failures: Int) extends AgentMiddleware {
    val calls            = new AtomicInteger(0)
    val id: MiddlewareId = MiddlewareId("flaky")
    override def afterAgent(answer: String, context: RunContext): Result[String] =
      if (calls.incrementAndGet() <= failures) Left(ValidationError("audit", "audit store unavailable"))
      else Right(answer)
  }

  "A non-guardrail boundary failure" should "end the run as Left with its error, the turn removed and the thread usable" in {
    val client = answers("the answer", "the next answer")
    val flaky  = new FlakyAfterAgent(failures = 1)
    val agent  = built(Agent.builder("assistant", client).withMiddleware(flaky))
    val thread = org.llm4s.agent.graph.ThreadId("boundary-failure")

    agent.run(thread, "q").error shouldBe ValidationError("audit", "audit store unavailable")
    // a Block is finished, not interrupted: there is nothing to recover
    agent.recover(thread).error shouldBe GraphError.NothingToRecover(thread.value)

    val next = agent.run(thread, "q again").value
    next.answer shouldBe Some("the next answer")
    next.messages shouldBe Vector(UserMessage("q again"), AssistantMessage("the next answer"))
    client.callCount shouldBe 2
    flaky.calls.get() shouldBe 2
  }

  // --- root boundary hooks apply to the whole family ---

  private def handoffTo(target: String): Completion =
    CompletionFixture.withMessage(
      AssistantMessage(None, Seq(ToolCall("call_h", s"handoff_to_$target", ujson.Obj("reason" -> "specialist"))))
    )

  private def rejectNo: InputGuardrail = new InputGuardrail {
    val name: String = "RejectNo"
    def validate(value: String): Result[String] =
      if (value.startsWith("no")) Left(ValidationError.invalid("input", "not allowed")) else Right(value)
  }

  /** Records each boundary hook it runs, tagged with `tag`. */
  final private class Recording(tag: String, log: java.util.concurrent.CopyOnWriteArrayList[String])
      extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId(s"rec-$tag")
    override def beforeAgent(text: String, context: RunContext): Result[String] = {
      log.add(s"$tag:before:$text"); Right(s"$text+$tag")
    }
    override def afterAgent(answer: String, context: RunContext): Result[String] = {
      log.add(s"$tag:after:$answer"); Right(s"$answer+$tag")
    }
  }

  "A root input guardrail" should "block a later turn's query while a handoff target is active" in {
    val root       = ScriptedLLMClient.of(handoffTo("specialist"))
    val specialist = answers("specialist answer", "never sent")
    val agent = built(
      Agent
        .builder("triage", root)
        .withMiddleware(new GuardrailMiddleware(Seq(rejectNo), Nil))
        .withHandoffs(Handoff.to("specialist", Agent.builder("specialist", specialist)))
    )

    val first   = agent.run("yes please").value
    val blocked = agent.continueConversation(first, "no thanks").value

    first.activeAgent.value shouldBe "specialist"
    blocked.status shouldBe AgentStatus.Blocked("RejectNo", blocked.status.asInstanceOf[AgentStatus.Blocked].reason)
    blocked.messages shouldBe first.messages
    blocked.activeAgent.value shouldBe "specialist"
    specialist.callCount shouldBe 1
  }

  "A root output guardrail" should "block a handoff target's answer, removing the turn and its handoff" in {
    val root       = ScriptedLLMClient.of(handoffTo("specialist"))
    val specialist = answers("here is the secret")
    val agent = built(
      Agent
        .builder("triage", root)
        .withMiddleware(new GuardrailMiddleware(Nil, Seq(secretFree)))
        .withHandoffs(Handoff.to("specialist", Agent.builder("specialist", specialist)))
    )

    val result = agent.run("tell me").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "NoSecrets"
    result.messages shouldBe empty
    // the handoff was part of the removed turn: the thread is with the root again
    result.activeAgent.value shouldBe "triage"
  }

  "A handoff target's own guardrail" should "still apply under a root guardrail" in {
    val root       = ScriptedLLMClient.of(handoffTo("specialist"))
    val specialist = answers("here is the secret")
    val agent = built(
      Agent
        .builder("triage", root)
        .withMiddleware(new GuardrailMiddleware(Seq(rejectNo), Nil))
        .withHandoffs(
          Handoff.to(
            "specialist",
            Agent
              .builder("specialist", specialist)
              .withMiddleware(new GuardrailMiddleware(Nil, Seq(secretFree)))
          )
        )
    )

    val result = agent.run("tell me").value

    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "NoSecrets"
    result.messages shouldBe empty
  }

  "Boundary middleware across a handoff" should "run the root's then the target's beforeAgent, and the target's then the root's afterAgent" in {
    val log        = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val root       = ScriptedLLMClient.of(handoffTo("specialist"))
    val specialist = answers("one", "two")
    val agent = built(
      Agent
        .builder("triage", root)
        .withMiddleware(new Recording("root", log))
        .withHandoffs(
          Handoff.to("specialist", Agent.builder("specialist", specialist).withMiddleware(new Recording("spec", log)))
        )
    )

    val first = agent.run("q1").value
    // the first turn started with the root active: only the root's beforeAgent ran on the query
    first.messages.head shouldBe UserMessage("q1+root")
    first.answer shouldBe Some("one+spec+root")

    log.clear()
    val second = agent.continueConversation(first, "q2").value
    second.messages.collect { case u: UserMessage => u.content }.last shouldBe "q2+root+spec"
    second.answer shouldBe Some("two+spec+root")
    import scala.jdk.CollectionConverters._
    log.asScala.toVector shouldBe Vector(
      "root:before:q2",
      "spec:before:q2+root",
      "spec:after:two",
      "root:after:two+spec"
    )
  }

  it should "run the root's hooks once a turn when no handoff happens" in {
    val log   = new java.util.concurrent.CopyOnWriteArrayList[String]()
    val agent = built(Agent.builder("assistant", answers("a")).withMiddleware(new Recording("root", log)))

    agent.run("q").value.answer shouldBe Some("a+root")
    import scala.jdk.CollectionConverters._
    log.asScala.toVector shouldBe Vector("root:before:q", "root:after:a")
  }

  // --- an input block on a new thread ---

  "An input block on a new thread" should "still import the history, so the next turn continues it" in {
    val client  = answers("next answer")
    val agent   = guarded(client, input = Seq(rejectNo))
    val thread  = org.llm4s.agent.graph.ThreadId("blocked-with-history")
    val history = Seq(UserMessage("earlier question"), AssistantMessage("earlier answer"))

    val blocked = agent.run(thread, "no", org.llm4s.agent.graph.RunConfig(), history).value
    blocked.status shouldBe a[AgentStatus.Blocked]
    blocked.messages shouldBe history.toVector
    blocked.activeAgent.value shouldBe "assistant"

    val next = agent.run(thread, "yes").value
    next.answer shouldBe Some("next answer")
    next.messages shouldBe (history.toVector ++ Vector(UserMessage("yes"), AssistantMessage("next answer")))
    client.sent.head.collect { case u: UserMessage => u.content } shouldBe Vector("earlier question", "yes")
  }
}
