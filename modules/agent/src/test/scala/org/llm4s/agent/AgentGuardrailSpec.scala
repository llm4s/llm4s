package org.llm4s.agent

import org.llm4s.testutil.TestTimeLimit

import org.llm4s.agent.graph.middleware.{ BoundaryResult, ModelContext, ModelResponse }
import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.graph.{ Checkpoint, GraphError, GraphRuntime }
import org.llm4s.agent.graph.middleware.{
  AgentMiddleware,
  GuardrailBlocked,
  GuardrailMiddleware,
  MiddlewareId,
  ModelRequest
}
import org.llm4s.agent.guardrails.{ InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.builtin.{ JSONValidator, LengthCheck, PIIMasker, ProfanityFilter }
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
class AgentGuardrailSpec extends AnyFlatSpec with TestTimeLimit with Matchers {

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
    val result = guarded(client, input = Seq(LengthCheck(min = 10, max = 100))).run("Short").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "LengthCheck"
    result.status.asInstanceOf[AgentStatus.Blocked].reason.getOrElse("") should include("too short")
    result.answer shouldBe None
    result.messages shouldBe empty
    result.usage.requestCount shouldBe 0
    client.callCount shouldBe 0
  }

  it should "leave the thread usable, so the next turn works and the blocked query is not in its history" in {
    val client = answers("first answer", "second answer")
    val agent  = guarded(client, input = Seq(LengthCheck(min = 10, max = 100)))

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
      input = Seq(LengthCheck(min = 1, max = 100), ProfanityFilter())
    ).run("This contains badword").value

    result.status shouldBe a[AgentStatus.Blocked]
    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "ProfanityFilter"
  }

  it should "stop the turn before the output guardrails or the model" in {
    val client = answers("""{"result": "success"}""")
    val result = guarded(client, input = Seq(LengthCheck(1, 5)), output = Seq(JSONValidator()))
      .run("This query is too long")
      .value

    result.status.asInstanceOf[AgentStatus.Blocked].reason.getOrElse("") should include("too long")
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
    val result = guarded(client, output = Seq(JSONValidator())).run("Generate JSON").value

    val blocked = result.status.asInstanceOf[AgentStatus.Blocked]
    blocked.guardrail shouldBe "JSONValidator"
    blocked.reason.getOrElse("") should include("not valid JSON")
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

  it should "leave nothing of the blocked turn in the thread's checkpoint history (design 9, 4.17)" in {
    val runtime = GraphRuntime.inMemory()
    val client  = answers("first", "leaks the secret", "a safe answer")
    val agent = built(
      Agent
        .builder("assistant", client)
        .withMiddleware(new GuardrailMiddleware(Seq(LengthCheck(min = 3, max = 100)), Seq(secretFree)))
        .withRuntime(runtime)
    )
    val first   = agent.run("Hello").value
    val blocked = agent.continueConversation(first, "Tell me").value
    agent.continueConversation(blocked, "No").value.status shouldBe a[AgentStatus.Blocked]
    agent.continueConversation(blocked, "Try again").value.answer shouldBe Some("a safe answer")

    val history = runtime.history(first.threadId, limit = 1000).fold(e => fail(e.message), identity)
    val stored  = history.map(c => Checkpoint.toJson(c).toString)
    stored.exists(_.contains("leaks the secret")) shouldBe false
    stored.exists(_.contains("Tell me")) shouldBe false
    stored.exists(_.contains("\"No\"")) shouldBe false
    stored.exists(_.contains("a safe answer")) shouldBe true
    history.map(_.id).distinct shouldBe history.map(_.id)
  }

  it should "apply after a successful tool round, having called the model twice" in {
    val client = ScriptedLLMClient.of(
      CompletionFixture.withToolCall("missing_tool", ujson.Obj()),
      CompletionFixture.simple("leaks the secret")
    )
    val result = guarded(client, output = Seq(secretFree)).run("q").value

    result.status.asInstanceOf[AgentStatus.Blocked].reason.getOrElse("") should include(
      "response must not contain secrets"
    )
    client.callCount shouldBe 2
  }

  it should "check each turn's answer on a continued conversation" in {
    val client = answers("""{"turn": 1}""", "not json")
    val agent  = guarded(client, output = Seq(JSONValidator()))

    val first  = agent.run("First query").value
    val second = agent.continueConversation(first, "Generate JSON please").value

    first.status shouldBe AgentStatus.Completed("""{"turn": 1}""")
    second.status shouldBe a[AgentStatus.Blocked]
  }

  "Passing guardrails" should "complete the turn with the answer, in single and multi-turn conversations" in {
    val client = answers("""{"response": "ok"}""", """{"response": "ok"}""", """{"response": "ok"}""")
    val agent = guarded(
      client,
      input = Seq(LengthCheck(1, 100), ProfanityFilter()),
      output = Seq(JSONValidator(), LengthCheck(1, 1000))
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
    override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] =
      if (calls.incrementAndGet() <= failures) Left(ValidationError("audit", "audit store unavailable"))
      else Right(BoundaryResult(answer))
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

  /** Fails its first model call with a `GuardrailBlocked`, as a model wrapper (not a boundary hook). */
  final private class BlockingModelWrapper extends AgentMiddleware {
    val calls            = new AtomicInteger(0)
    val id: MiddlewareId = MiddlewareId("model-guard")
    override def wrapModelCall(request: ModelRequest, context: ModelContext)(
      next: ModelRequest => Result[ModelResponse]
    ): Result[ModelResponse] =
      if (calls.incrementAndGet() == 1) Left(GuardrailBlocked("ModelGuard", Some("not now"))) else next(request)
  }

  "A GuardrailBlocked from a model wrapper" should "fail the run as Left, leaving the thread for recover" in {
    val client  = answers("the answer")
    val wrapper = new BlockingModelWrapper
    val agent   = built(Agent.builder("assistant", client).withMiddleware(wrapper))
    val thread  = org.llm4s.agent.graph.ThreadId("wrapper-guardrail")

    val error = agent.run(thread, "q").error
    cause(error) shouldBe GuardrailBlocked("ModelGuard", Some("not now"))
    // not a Block: the thread is Running, so a new turn is refused and recover continues it
    agent.run(thread, "another").error shouldBe a[GraphError.IncompleteRun]
    val recovered = agent.recover(thread).value
    recovered.answer shouldBe Some("the answer")
    client.callCount shouldBe 1
  }

  // --- root boundary hooks apply to the whole family ---

  private def handoffTo(target: String): Completion =
    CompletionFixture.withMessage(
      AssistantMessage(
        None,
        Seq(ToolCall(ToolCallId("call_h"), s"handoff_to_$target", ujson.Obj("reason" -> "specialist")))
      )
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
    override def beforeAgent(text: String, context: ModelContext): Result[BoundaryResult] = {
      log.add(s"$tag:before:$text"); Right(BoundaryResult(s"$text+$tag"))
    }
    override def afterAgent(answer: String, context: ModelContext): Result[BoundaryResult] = {
      log.add(s"$tag:after:$answer"); Right(BoundaryResult(s"$answer+$tag"))
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

  "A handoff target's input guardrail" should "check the query of the turn that hands off to it (#3245)" in {
    val root       = ScriptedLLMClient.of(handoffTo("specialist"))
    val specialist = answers("never sent")
    val agent = built(
      Agent
        .builder("triage", root)
        .withHandoffs(Handoff.to("specialist", Agent.builder("specialist", specialist).withInputGuardrails(rejectNo)))
    )

    val result = agent.run("no card for you").value

    result.status.asInstanceOf[AgentStatus.Blocked].guardrail shouldBe "RejectNo"
    result.messages shouldBe empty
    // the handoff was part of the retracted turn: the thread is with the root again
    result.activeAgent.value shouldBe "triage"
    specialist.callCount shouldBe 0
  }

  it should "end the turn TokenLimitReached, not Blocked, when the handoff call used the budget its judge needed (#3245)" in {
    val root       = ScriptedLLMClient.of(handoffTo("specialist"))
    val specialist = answers("never sent")
    val judge      = answers("0.95")
    val agent = built(
      Agent
        .builder("triage", root)
        .withHandoffs(
          Handoff.to(
            "specialist",
            Agent
              .builder("specialist", specialist)
              .withInputGuardrails(
                InputGuardrail.from(org.llm4s.agent.guardrails.builtin.LLMSafetyGuardrail(judge))
              )
          )
        )
    )

    val result = agent
      .run(
        org.llm4s.agent.graph.ThreadId("budget-at-handoff"),
        "hello",
        org.llm4s.agent.graph
          .RunConfig()
          .withBudgets(org.llm4s.agent.graph.RunBudgets.default.withMaxTokens(1L))
      )
      .value

    result.status shouldBe AgentStatus.TokenLimitReached
    judge.callCount shouldBe 0
    specialist.callCount shouldBe 0
    result.activeAgent.value shouldBe "triage"
  }

  private def judgedHandoff(judge: ScriptedLLMClient, specialist: ScriptedLLMClient) = built(
    Agent
      .builder("triage", ScriptedLLMClient.of(handoffTo("specialist")))
      .withHandoffs(
        Handoff.to(
          "specialist",
          Agent
            .builder("specialist", specialist)
            .withInputGuardrails(InputGuardrail.from(org.llm4s.agent.guardrails.builtin.LLMSafetyGuardrail(judge)))
        )
      )
  )

  it should "count the target judge's call in the run's usage (#3245)" in {
    val judge      = answers("0.95")
    val specialist = answers("done")
    val result     = judgedHandoff(judge, specialist).run("hello").value

    result.answer shouldBe Some("done")
    judge.callCount shouldBe 1
    // the handoff call, the judge's and the specialist's, 30 tokens each
    result.usage.requestCount shouldBe 3L
    (result.usage.inputTokens + result.usage.outputTokens) shouldBe 90L
  }

  it should "end the turn TokenLimitReached before the target's model when its judge used the budget (#3245)" in {
    val judge      = answers("0.95")
    val specialist = answers("never sent")
    val result = judgedHandoff(judge, specialist)
      .run(
        org.llm4s.agent.graph.ThreadId("judge-uses-budget"),
        "hello",
        org.llm4s.agent.graph
          .RunConfig()
          .withBudgets(org.llm4s.agent.graph.RunBudgets.default.withMaxTokens(60L))
      )
      .value

    result.status shouldBe AgentStatus.TokenLimitReached
    judge.callCount shouldBe 1
    specialist.callCount shouldBe 0
    (result.usage.inputTokens + result.usage.outputTokens) shouldBe 60L
  }

  it should "give the target's model the query as its admission rewrote it (#3245)" in {
    val root       = ScriptedLLMClient.of(handoffTo("specialist"))
    val specialist = answers("done")
    val agent = built(
      Agent
        .builder("triage", root)
        .withHandoffs(
          Handoff.to("specialist", Agent.builder("specialist", specialist).withInputGuardrails(new PIIMasker()))
        )
    )

    val result = agent.run("my ssn is 123-45-6789").value

    result.answer shouldBe Some("done")
    specialist.sent.head.collect { case u: UserMessage => u.content } shouldBe Vector("my ssn is [REDACTED_SSN]")
    result.messages.collect { case u: UserMessage => u.content } shouldBe Seq("my ssn is [REDACTED_SSN]")
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

  // #3235: a refused import stores nothing - the history was not admitted - so it can be made again
  "An input block on a new thread" should "import nothing and create no thread, so the import can be made again" in {
    val client  = answers("next answer")
    val agent   = guarded(client, input = Seq(rejectNo))
    val thread  = org.llm4s.agent.graph.ThreadId("blocked-with-history")
    val history = Seq(UserMessage("earlier question"), AssistantMessage("earlier answer"))

    val blocked = agent.run(thread, "no", org.llm4s.agent.graph.RunConfig(), history).value
    blocked.status shouldBe a[AgentStatus.Blocked]
    blocked.threadId shouldBe thread
    blocked.messages shouldBe empty
    agent.runtime.history(thread) shouldBe Right(Vector.empty)

    val next = agent.run(thread, "yes", org.llm4s.agent.graph.RunConfig(), history).value
    next.answer shouldBe Some("next answer")
    next.messages shouldBe (history.toVector ++ Vector(UserMessage("yes"), AssistantMessage("next answer")))
    client.sent.head.collect { case u: UserMessage => u.content } shouldBe Vector("earlier question", "yes")
  }

  it should "admit each imported user message, and refuse the import when one is refused (#3235)" in {
    val client = answers("fine")
    val agent  = guarded(client, input = Seq(rejectNo))
    val thread = org.llm4s.agent.graph.ThreadId("blocked-history-message")

    val blocked =
      agent.run(thread, "yes", org.llm4s.agent.graph.RunConfig(), Seq(UserMessage("no"), AssistantMessage("ok"))).value
    blocked.status shouldBe a[AgentStatus.Blocked]
    agent.runtime.history(thread) shouldBe Right(Vector.empty)
    client.callCount shouldBe 0
  }

  it should "still refuse an import onto a thread that exists" in {
    val agent  = guarded(answers("fine", "again"), input = Seq(rejectNo))
    val thread = org.llm4s.agent.graph.ThreadId("blocked-history-existing")
    agent.run(thread, "yes").value.answer shouldBe Some("fine")
    agent.run(thread, "no", org.llm4s.agent.graph.RunConfig(), Seq(UserMessage("earlier"))).isLeft shouldBe true
  }

  // --- a blank query (review of #1369) ---

  "A blank query" should "be refused before any thread is claimed, and the thread stays usable" in {
    val client = answers("fine")
    val agent  = built(Agent.builder("assistant", client))
    val thread = org.llm4s.agent.graph.ThreadId("blank-query")

    agent.run(thread, "   ").error shouldBe ValidationError("query", "the query is blank")
    agent.run("").error shouldBe ValidationError("query", "the query is blank")
    client.callCount shouldBe 0

    // nothing was created, so history can still be imported into the thread
    val history = Seq(UserMessage("earlier"), AssistantMessage("before"))
    val next    = agent.run(thread, "hello", org.llm4s.agent.graph.RunConfig(), history).value
    next.answer shouldBe Some("fine")
    next.messages shouldBe history.toVector ++ Vector(UserMessage("hello"), AssistantMessage("fine"))
  }

  /** Turns the query `blank me` blank; passes any other. */
  final private class Blanking extends AgentMiddleware {
    val id: MiddlewareId = MiddlewareId("blanking")
    override def beforeAgent(text: String, context: ModelContext): Result[BoundaryResult] =
      Right(BoundaryResult(if (text == "blank me") "  " else text))
  }

  "A query a beforeAgent hook turns blank" should "be Left, store nothing, and leave the thread usable" in {
    val client = answers("first", "second")
    val agent  = built(Agent.builder("assistant", client).withMiddleware(new Blanking))
    val thread = org.llm4s.agent.graph.ThreadId("hook-blank")

    val first = agent.run(thread, "hi").value
    agent.run(thread, "blank me").error shouldBe ValidationError("query", "beforeAgent returned a blank query")
    client.callCount shouldBe 1
    // the thread is finished, not left Running with an invalid user message
    agent.recover(thread).error shouldBe GraphError.NothingToRecover(thread.value)

    val next = agent.run(thread, "again").value
    next.answer shouldBe Some("second")
    next.messages shouldBe first.messages ++ Vector(UserMessage("again"), AssistantMessage("second"))
  }
}
