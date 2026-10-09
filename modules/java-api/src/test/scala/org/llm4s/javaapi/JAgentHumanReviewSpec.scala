package org.llm4s.javaapi

import org.llm4s.agent.{ AgentNode, AgentStatus }
import org.llm4s.agent.graph.{ BreakpointPhase => GraphPhase, InterruptId, NodeId, RunContext }
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ToolCallRequest }
import org.llm4s.agent.graph.tool.{ AgentTool, ToolContext, ToolOutcome, ToolSet }
import org.llm4s.agent.graph.toolloop.{
  ApprovalRequest,
  ApprovalSource,
  BreakpointRequest,
  MiddlewareHook,
  MiddlewareQuestionRequest,
  ToolQuestionRequest,
  ToolTask
}
import org.llm4s.agent.AgentId
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import upickle.default.ReadWriter

import java.util.Optional
import java.util.concurrent.CopyOnWriteArrayList
import scala.jdk.CollectionConverters.*

import StreamFixtures.*
import SuspensionFixtures.{ call, calling, scripted }

/** Breakpoints and middleware questions (#1704) through the Java facade: `PendingInterrupt` and `Answer.proceed`. */
class JAgentHumanReviewSpec extends AnyFlatSpec with Matchers {
  import JAgentHumanReviewSpec.*

  private val ran = new CopyOnWriteArrayList[String]()

  private val deploy = AgentTool(SuspensionFixtures.spec("deploy")) { (args, _) =>
    ran.add(args.text)
    ToolOutcome.Success(ujson.Str(s"deployed ${args.text}"))
  }

  private def tools: ToolSet = ToolSet.of(deploy).fold(e => throw new AssertionError(e.message), identity)

  private def pendingOf(result: JAgentResult): List[PendingInterrupt] = result.status.pending.asScala.toList

  "A breakpoint" should "be a BREAKPOINT PendingInterrupt naming its node, phase and held call, continued with Answer.proceed" in {
    ran.clear()
    val client =
      scripted(Right(calling(call("c1", "deploy", "one"), call("c2", "deploy", "two"))), Right(completion("done")))
    val agent = jAgentOf(client)(_.withTools(tools).withInterruptBefore(AgentNode.Tool))
    val first = agent.run("deploy").get()
    first.status.kind shouldBe AgentStatusKind.SUSPENDED
    val List(p1, p2) = pendingOf(first): @unchecked
    p1.kind shouldBe InterruptKind.BREAKPOINT
    p1.node() shouldBe Optional.of("test/call-tool")
    p1.phase() shouldBe Optional.of(BreakpointPhase.BEFORE)
    p1.toolName() shouldBe Optional.of("deploy")
    p1.argumentsJson() shouldBe Optional.of("""{"text":"one"}""")
    (p1.reason(), p1.questionJson(), p1.middleware()) shouldBe ((Optional.empty(), Optional.empty(), Optional.empty()))
    p1.toString shouldBe s"""PendingInterrupt(BREAKPOINT ${p1.id}: BEFORE test/call-tool deploy {"text":"one"})"""
    ran.size shouldBe 0

    // one at a time: the other stays pending
    val partly = agent.resume(first.threadId, java.util.List.of(Answer.proceed(p2.id))).get()
    pendingOf(partly).map(_.id) shouldBe List(p1.id)
    ran.asScala.toList shouldBe List("two")
    agent.resume(first.threadId, java.util.List.of(Answer.proceed(p1.id))).get().answer() shouldBe Optional.of("done")
  }

  it should "name no call when it holds a model call after it ran" in {
    val agent   = jAgentOf(answering("hi"))(_.withInterruptAfter(AgentNode.Model))
    val first   = agent.run("hello").get()
    val List(p) = pendingOf(first): @unchecked
    p.phase() shouldBe Optional.of(BreakpointPhase.AFTER)
    p.node() shouldBe Optional.of("test/model")
    (p.toolName(), p.argumentsJson()) shouldBe ((Optional.empty(), Optional.empty()))
    p.toString shouldBe s"PendingInterrupt(BREAKPOINT ${p.id}: AFTER test/model)"
    agent.resume(first.threadId, java.util.List.of(Answer.proceed(p.id))).get().answer() shouldBe Optional.of("hi")
  }

  it should "refuse an answer other than proceed" in {
    val agent = jAgentOf(answering("hi"))(_.withInterruptBefore(AgentNode.Model))
    val first = agent.run("hello").get()
    val id    = pendingOf(first).head.id
    agent.resume(first.threadId, java.util.List.of(Answer.approve(id))).isFailure shouldBe true
    agent.resume(first.threadId, java.util.List.of(Answer.proceed(id))).isSuccess shouldBe true
  }

  "A middleware question" should "be a MIDDLEWARE_QUESTION naming its middleware, answered with Answer.reply" in {
    val client  = scripted(Right(completion("deployed to eu")))
    val agent   = jAgentOf(client)(_.withMiddleware(new Region))
    val first   = agent.run("deploy").get()
    val List(p) = pendingOf(first): @unchecked
    p.kind shouldBe InterruptKind.MIDDLEWARE_QUESTION
    p.middleware() shouldBe Optional.of("region")
    p.questionJson() shouldBe Optional.of("""{"what":"region"}""")
    (p.toolName(), p.reason(), p.node(), p.phase()) shouldBe
      ((Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty()))
    p.toString shouldBe s"PendingInterrupt(MIDDLEWARE_QUESTION ${p.id}: middleware region)"

    agent.resume(first.threadId, java.util.List.of(Answer.reply(p.id, """{"value":"eu"}"""))).get().answer() shouldBe
      Optional.of("deployed to eu")
  }

  it should "name the tool call it asks about, one question per call" in {
    ran.clear()
    val client =
      scripted(Right(calling(call("c1", "deploy", "one"), call("c2", "deploy", "two"))), Right(completion("ok")))
    val agent        = jAgentOf(client)(_.withTools(tools).withMiddleware(new Environment))
    val first        = agent.run("deploy").get()
    val List(p1, p2) = pendingOf(first): @unchecked
    (p1.kind, p1.toolName(), p1.argumentsJson()) shouldBe
      ((InterruptKind.MIDDLEWARE_QUESTION, Optional.of("deploy"), Optional.of("""{"text":"one"}""")))
    val partly = agent.resume(first.threadId, java.util.List.of(Answer.reply(p1.id, """{"value":"prod"}"""))).get()
    pendingOf(partly).map(_.id) shouldBe List(p2.id)
    agent.resume(first.threadId, java.util.List.of(Answer.reply(p2.id, """{"value":"dev"}"""))).get().answer() shouldBe
      Optional.of("ok")
    ran.asScala.toList shouldBe List("one", "two")
  }

  "PendingInterrupt.of" should "list approvals, then tool questions, middleware questions and breakpoints" in {
    val c = call("c1", "deploy", "x")
    val status = AgentStatus.Suspended(
      Vector(InterruptId("a") -> ApprovalRequest("m", c, "why", ApprovalSource.Tool)),
      Vector(InterruptId("q") -> ToolQuestionRequest("m", c, ujson.Obj("q" -> 1))),
      Vector(
        InterruptId("m") -> MiddlewareQuestionRequest(
          AgentId.unsafe("assistant"),
          MiddlewareId("mw"),
          MiddlewareHook.WrapToolCall,
          ujson.Null,
          call = Some(ToolTask("m", c))
        )
      ),
      Vector(InterruptId("b") -> BreakpointRequest(NodeId("assistant/finish"), GraphPhase.Before, None))
    )
    val pending = PendingInterrupt.of(status).asScala.toList
    pending.map(p => p.id -> p.kind) shouldBe List(
      "a" -> InterruptKind.APPROVAL,
      "q" -> InterruptKind.QUESTION,
      "m" -> InterruptKind.MIDDLEWARE_QUESTION,
      "b" -> InterruptKind.BREAKPOINT
    )
    pending(2).questionJson() shouldBe Optional.of("null")
    pending(2).toolName() shouldBe Optional.of("deploy")
    pending(3).node() shouldBe Optional.of("assistant/finish")
    pending.distinct should have size 4
    pending(3) should not be PendingInterrupt.of(status).asScala.toList(2)
  }

  "BreakpointPhase" should "be a Java enum" in {
    classOf[BreakpointPhase].isEnum shouldBe true
    BreakpointPhase.values.toList shouldBe List(BreakpointPhase.BEFORE, BreakpointPhase.AFTER)
  }

  "Answer.proceed" should "answer with null, and refuse a null id when used" in {
    Answer.proceed("i").underlying shouldBe Right(InterruptId("i") -> ujson.Null)
    Answer.proceed(null).underlying.isLeft shouldBe true
  }
}

object JAgentHumanReviewSpec {
  final case class Need(what: String) derives ReadWriter
  final case class Info(value: String) derives ReadWriter

  /** Asks for the region when the query names none. */
  final class Region extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("region")
    override def beforeAgent(input: String, context: RunContext): Result[String] =
      answered(context).fold(ask(Need("region")))(r => Right(s"$input in ${r.answer.value}"))
  }

  /** Asks which environment each tool call is for. */
  final class Environment extends AgentMiddleware.Asking[Need, Info] {
    val id: MiddlewareId = MiddlewareId("environment")
    override def wrapToolCall(request: ToolCallRequest, context: ToolContext)(next: () => ToolOutcome): ToolOutcome =
      answered(context).fold(askAbout(Need(s"environment for ${request.call.id}")))(_ => next())
  }
}
