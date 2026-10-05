package org.llm4s.agent

import org.llm4s.agent.graph.middleware.ApprovalMiddleware
import org.llm4s.agent.graph.toolloop.{ ApprovalDecision, ApprovalRequest }
import org.llm4s.agent.guardrails.builtin.LengthCheck
import org.llm4s.error.{ CancelledError, ValidationError }
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.toolapi.{ Schema, ToolBuilder, ToolFunction, ToolRegistry }
import org.llm4s.types.Result
import org.scalatest.{ EitherValues, OptionValues }
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.{ macroRW, writeJs, ReadWriter }

import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }
import scala.collection.mutable.ArrayBuffer

/**
 * What the agent loop on the graph runtime must keep doing (#1328): multi-turn input, guardrail outcomes, typed
 * suspension with partial resume, recover without re-running completed work, cancellation and the step limit - all
 * through `Agent`, the public front end.
 */
class AgentRuntimeCutoverSpec extends AnyFlatSpec with Matchers with OptionValues with EitherValues {

  private case class Out(v: String)
  private object Out { implicit val rw: ReadWriter[Out] = macroRW }

  private def counting(name: String, runs: AtomicInteger): ToolFunction[Map[String, Any], Out] =
    ToolBuilder[Map[String, Any], Out](
      name,
      s"$name tool",
      Schema.`object`[Map[String, Any]]("params").withRequiredField("x", Schema.string("x"))
    ).withHandler { extractor =>
      runs.incrementAndGet()
      extractor.getString("x").map(Out(_))
    }.buildSafe()
      .toOption
      .get

  private def text(s: String): Completion =
    Completion("id", 0L, s, "m", AssistantMessage(s), usage = Some(TokenUsage(10, 5, 15)))

  private def calls(cs: (String, String)*): Completion = {
    val tcs = cs.zipWithIndex.map { case ((name, x), i) =>
      ToolCall(s"call-$i-$name", name, ujson.Obj("x" -> x))
    }.toList
    Completion("id", 0L, "", "m", AssistantMessage("", tcs), toolCalls = tcs, usage = Some(TokenUsage(10, 5, 15)))
  }

  /** Answers from `script` in order (repeating the last), recording every conversation it is given. */
  final private class Scripted(script: Seq[Completion]) extends LLMClient {
    val seen: ArrayBuffer[Seq[Message]] = ArrayBuffer.empty
    def callCount: Int                  = seen.size
    override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
      val reply = script(seen.size.min(script.size - 1))
      seen += c.messages
      Right(reply)
    }
    override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit): Result[Completion] =
      complete(c, o)
    override def getContextWindow(): Int     = 8192
    override def getReserveCompletion(): Int = 1024
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Multi-turn input
  // ------------------------------------------------------------------------------------------------------------------

  "Multi-turn conversations" should "give the model every earlier message, in order, on each turn" in {
    val client = new Scripted(Seq(text("a1"), text("a2"), text("a3")))
    val agent  = new Agent(client)
    val tools  = ToolRegistry.empty

    val result = for {
      t1 <- agent.run("q1", tools)
      t2 <- agent.continueConversation(t1, "q2", tools)
      t3 <- agent.continueConversation(t2, "q3", tools)
    } yield t3

    val thread = result.fold(e => fail(e.formatted), identity)
    thread.messages.map(_.content) shouldBe Seq("q1", "a1", "q2", "a2", "q3", "a3")
    client.seen.map(_.filterNot(_.role == MessageRole.System).map(_.content)) shouldBe Seq(
      Seq("q1"),
      Seq("q1", "a1", "q2"),
      Seq("q1", "a1", "q2", "a2", "q3")
    )
    thread.usage.requestCount shouldBe 3L
  }

  it should "run each query of runMultiTurn on the conversation so far" in {
    val client = new Scripted(Seq(text("a1"), text("a2")))
    val result = new Agent(client).runMultiTurn("q1", Seq("q2"), ToolRegistry.empty)

    result.fold(e => fail(e.formatted), _.messages.map(_.content)) shouldBe Seq("q1", "a1", "q2", "a2")
  }

  it should "continue from a thread that went through JSON, in a different Agent" in {
    val first = new Agent(new Scripted(Seq(text("a1")))).run("q1", ToolRegistry.empty).toOption.value
    val saved = AgentThread.fromJson(AgentThread.toJson(first)).toOption.value

    val client = new Scripted(Seq(text("a2")))
    val next   = new Agent(client).continueConversation(saved, "q2", ToolRegistry.empty).toOption.value

    next.messages.map(_.content) shouldBe Seq("q1", "a1", "q2", "a2")
    client.seen.head.filterNot(_.role == MessageRole.System).map(_.content) shouldBe Seq("q1", "a1", "q2")
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Guardrails
  // ------------------------------------------------------------------------------------------------------------------

  "A blocking input guardrail" should "return its error without a model call and leave the thread usable" in {
    val client = new Scripted(Seq(text("a1"), text("a2")))
    val agent  = new Agent(client)
    val first  = agent.run("a long enough first question", ToolRegistry.empty).toOption.value

    val blocked = agent.continueConversation(
      first,
      "no",
      ToolRegistry.empty,
      inputGuardrails = Seq(new LengthCheck(min = 5, max = 100))
    )

    blocked.left.value shouldBe a[ValidationError]
    client.callCount shouldBe 1
    val again = agent.continueConversation(first, "a long enough follow-up", ToolRegistry.empty).toOption.value
    again.messages.map(_.content) shouldBe Seq("a long enough first question", "a1", "a long enough follow-up", "a2")
  }

  "A failing output guardrail" should "return its error and store nothing of the blocked answer" in {
    val client = new Scripted(Seq(text("ok"), text("this answer is far too long for the guardrail")))
    val agent  = new Agent(client)
    val first  = agent.run("q1", ToolRegistry.empty).toOption.value

    val blocked =
      agent.continueConversation(first, "q2", ToolRegistry.empty, outputGuardrails = Seq(new LengthCheck(1, 10)))

    blocked.left.value shouldBe a[ValidationError]
    client.callCount shouldBe 2
    // the next turn starts from the history before the blocked one
    val next = agent.continueConversation(first, "q3", ToolRegistry.empty).toOption.value
    next.messages.map(_.content).take(3) shouldBe Seq("q1", "ok", "q3")
  }

  it should "not run on a thread that asks for a tool: only the final answer is checked" in {
    val runs   = new AtomicInteger(0)
    val client = new Scripted(Seq(calls("echo" -> "hello"), text("done")))
    val result = new Agent(client).run(
      "q",
      new ToolRegistry(Seq(counting("echo", runs))),
      outputGuardrails = Seq(new LengthCheck(1, 10))
    )

    result.map(_.answer) shouldBe Right(Some("done"))
    runs.get() shouldBe 1
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Typed suspension and partial resume
  // ------------------------------------------------------------------------------------------------------------------

  "A turn that needs approvals" should "suspend on all of them, resume partially, and finish when the last is answered" in {
    val runs   = new AtomicInteger(0)
    val client = new Scripted(Seq(calls("danger" -> "one", "danger" -> "two"), text("all done")))
    val agent  = new Agent(client)
    val tools  = new ToolRegistry(Seq(counting("danger", runs)))
    val gate   = new ApprovalMiddleware(request => Some(s"approve ${request.call.id}"))

    val suspended = agent.run("go", tools, middleware = Seq(gate)).toOption.value
    suspended.status shouldBe a[ThreadStatus.Suspended]
    runs.get() shouldBe 0
    val approvals = suspended.approvals.toOption.value
    approvals.map(_._2).map { case r: ApprovalRequest => r.call.name }.toSet shouldBe Set("danger")
    approvals should have size 2

    // typed answer to the first interrupt only
    val (firstId, _) = approvals.head
    val partial = agent
      .resume(
        suspended,
        Map(firstId -> writeJs[ApprovalDecision](ApprovalDecision.Approve)),
        tools,
        middleware = Seq(gate)
      )
      .toOption
      .value
    partial.status shouldBe a[ThreadStatus.Suspended]
    partial.approvals.toOption.value should have size 1
    runs.get() shouldBe 1
    client.callCount shouldBe 1 // the model waits for the whole batch

    val (secondId, _) = partial.approvals.toOption.value.head
    val done = agent
      .resume(
        partial,
        Map(secondId -> writeJs[ApprovalDecision](ApprovalDecision.Approve)),
        tools,
        middleware = Seq(gate)
      )
      .toOption
      .value
    done.status shouldBe ThreadStatus.Completed
    done.answer shouldBe Some("all done")
    runs.get() shouldBe 2 // each call ran exactly once
    client.callCount shouldBe 2
  }

  it should "give a rejected call an error result and run the others" in {
    val runs   = new AtomicInteger(0)
    val client = new Scripted(Seq(calls("danger" -> "one"), text("ok, not done")))
    val agent  = new Agent(client)
    val tools  = new ToolRegistry(Seq(counting("danger", runs)))
    val gate   = new ApprovalMiddleware(_ => Some("needs a human"))

    val suspended = agent.run("go", tools, middleware = Seq(gate)).toOption.value
    val (id, _)   = suspended.approvals.toOption.value.head
    val done = agent
      .resume(
        suspended,
        Map(id -> writeJs[ApprovalDecision](ApprovalDecision.Reject("no thanks"))),
        tools,
        middleware = Seq(gate)
      )
      .toOption
      .value

    done.status shouldBe ThreadStatus.Completed
    runs.get() shouldBe 0
    done.messages.collect { case m: ToolMessage => m.content }.head should include("no thanks")
  }

  it should "refuse to resume a thread that is not suspended" in {
    val done = new Agent(new Scripted(Seq(text("x")))).run("q", ToolRegistry.empty).toOption.value

    new Agent(new Scripted(Seq(text("x")))).resume(done, Map.empty, ToolRegistry.empty).isLeft shouldBe true
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Step limit and recover
  // ------------------------------------------------------------------------------------------------------------------

  "The step limit" should "count model calls, and recover continues without re-running completed tool calls" in {
    val runsA  = new AtomicInteger(0)
    val runsB  = new AtomicInteger(0)
    val client = new Scripted(Seq(calls("a" -> "1", "b" -> "2"), text("both done")))
    val agent  = new Agent(client)
    val tools  = new ToolRegistry(Seq(counting("a", runsA), counting("b", runsB)))

    val limited = agent.run("go", tools, maxSteps = Some(1)).toOption.value

    limited.status shouldBe ThreadStatus.Failed(Agent.StepLimitMessage)
    client.callCount shouldBe 1
    (runsA.get(), runsB.get()) shouldBe ((1, 1))

    val finished = agent.recover(limited, tools, maxSteps = Some(1)).toOption.value
    finished.status shouldBe ThreadStatus.Completed
    finished.answer shouldBe Some("both done")
    client.callCount shouldBe 2
    (runsA.get(), runsB.get()) shouldBe ((1, 1)) // not run again
    finished.messages.collect { case m: ToolMessage => m.toolCallId }.size shouldBe 2
  }

  it should "end an endless tool loop at exactly maxSteps model calls" in {
    val runs   = new AtomicInteger(0)
    val client = new Scripted(Seq(calls("loop" -> "x")))
    val result = new Agent(client).run("go", new ToolRegistry(Seq(counting("loop", runs))), maxSteps = Some(4))

    result.map(_.status) shouldBe Right(ThreadStatus.Failed(Agent.StepLimitMessage))
    client.callCount shouldBe 4
    runs.get() shouldBe 4
  }

  it should "refuse recover on a thread that finished" in {
    val agent = new Agent(new Scripted(Seq(text("x"))))
    val done  = agent.run("q", ToolRegistry.empty).toOption.value

    agent.recover(done, ToolRegistry.empty).isLeft shouldBe true
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Cancellation
  // ------------------------------------------------------------------------------------------------------------------

  "An interrupted caller" should "interrupt the model call in flight and return once the run has stopped" in {
    val started = new CountDownLatch(1)
    val sawStop = new AtomicBoolean(false)
    val parked = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        started.countDown()
        try {
          Thread.sleep(30000)
          Right(text("late"))
        } catch {
          case _: InterruptedException =>
            sawStop.set(true)
            Thread.currentThread().interrupt()
            Left(CancelledError("test"))
        }
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, f: StreamedChunk => Unit) = complete(c, o)
      override def getContextWindow(): Int                                                         = 4096
      override def getReserveCompletion(): Int                                                     = 512
    }
    val outcome = new java.util.concurrent.atomic.AtomicReference[Result[AgentThread]]()
    val caller  = new Thread(() => outcome.set(new Agent(parked).run("q", ToolRegistry.empty)))

    caller.start()
    started.await(10, TimeUnit.SECONDS) shouldBe true
    caller.interrupt()
    caller.join(10000)

    caller.isAlive shouldBe false
    sawStop.get() shouldBe true
    outcome.get().isLeft shouldBe true
  }

  // ------------------------------------------------------------------------------------------------------------------
  // Handoffs by id
  // ------------------------------------------------------------------------------------------------------------------

  "A handoff" should "reach the target agent by id, with the history, and return the target's thread" in {
    val source = new Scripted(
      Seq(
        Completion(
          "id",
          0L,
          "",
          "m",
          AssistantMessage("", List(ToolCall("h1", "handoff_to_physics", ujson.Obj("reason" -> "physics")))),
          toolCalls = List(ToolCall("h1", "handoff_to_physics", ujson.Obj("reason" -> "physics"))),
          usage = Some(TokenUsage(10, 5, 15))
        )
      )
    )
    val target = new Scripted(Seq(text("E = mc^2")))
    val result = new Agent(source).run(
      "explain energy",
      ToolRegistry.empty,
      handoffs = Seq(Handoff.to("physics", new Agent(target), "physics expertise"))
    )

    val thread = result.toOption.value
    thread.answer shouldBe Some("E = mc^2")
    source.callCount shouldBe 1
    thread.usage.requestCount shouldBe 2L // the source's call and the target's
    thread.usage.inputTokens shouldBe 20
    target.seen.head.exists(_.content == "explain energy") shouldBe true
  }

  it should "be rejected before any model call when two handoffs share an id" in {
    val client = new Scripted(Seq(text("x")))
    val same   = new Agent(client)
    val result = same.run("q", ToolRegistry.empty, handoffs = Seq(Handoff.to("dup", same), Handoff.to("dup", same)))

    result.left.value shouldBe a[ValidationError]
    client.callCount shouldBe 0
  }
}
