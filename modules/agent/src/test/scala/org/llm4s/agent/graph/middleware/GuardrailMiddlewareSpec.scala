package org.llm4s.agent.graph.middleware

import org.llm4s.agent.graph.*
import org.llm4s.agent.graph.GraphTestSupport.*
import org.llm4s.agent.graph.tool.*
import org.llm4s.agent.graph.toolloop.*
import org.llm4s.agent.guardrails.{ GuardrailAction, InputGuardrail, OutputGuardrail }
import org.llm4s.agent.guardrails.builtin.*
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.*
import org.llm4s.types.Result
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

class GuardrailMiddlewareSpec extends AnyFlatSpec with Matchers with EitherValues {

  final private class ScriptedModel(answer: String) extends ModelStep {
    val seen = new CopyOnWriteArrayList[Vector[Message]]()
    def next(messages: Vector[Message], tools: ToolSet): Result[AssistantMessage] = {
      seen.add(messages)
      Right(AssistantMessage(answer))
    }
  }

  private def build(model: ModelStep, mw: GuardrailMiddleware) =
    ToolLoop.build("assistant", "v1", model, ToolSet.of().value, Seq(mw)).value

  private def nodeFailure(result: RunResult[?]): (NodeId, org.llm4s.error.LLMError) =
    result.failed._2 match {
      case GraphError.NodeFailed(node, _, cause) => node -> cause
      case other                                 => fail(s"not a node failure: $other")
    }

  private class Upper extends InputGuardrail {
    def validate(value: String): Result[String] = Right(value.toUpperCase)
    val name                                    = "Upper"
  }

  private class Mark(suffix: String) extends OutputGuardrail {
    def validate(value: String): Result[String] = Right(value + suffix)
    val name                                    = "Mark"
  }

  private class Reject(label: String) extends InputGuardrail with OutputGuardrail {
    def validate(value: String): Result[String] = Left(ValidationError.invalid("input", s"$label rejected"))
    val name                                    = label
    override def transform(input: String)       = input
  }

  /** A guardrail that records what it saw, then passes. */
  private class Spy(seen: CopyOnWriteArrayList[String]) extends InputGuardrail {
    def validate(value: String): Result[String] = { seen.add(value); Right(value) }
    val name                                    = "Spy"
  }

  private val none = Seq.empty[InputGuardrail]

  "GuardrailMiddleware" should "fail the run before any model call when an input guardrail blocks" in {
    val model         = ScriptedModel("ok")
    val l             = build(model, new GuardrailMiddleware(Seq(LengthCheck(1, 5)), Nil))
    val (node, cause) = nodeFailure(runInMemory(l.graph, "much too long"))
    node shouldBe NodeId("input")
    cause.message should include("Input too long")
    model.seen.size shouldBe 0
  }

  it should "give the model the value an input guardrail returned" in {
    val model = ScriptedModel("ok")
    val l     = build(model, new GuardrailMiddleware(Seq(new Upper), Nil))
    runInMemory(l.graph, "hello").completed
    model.seen.get(0).collect { case u: UserMessage => u.content } shouldBe Vector("HELLO")
  }

  it should "show a guardrail the value the previous one returned" in {
    val seen = new CopyOnWriteArrayList[String]()
    val l    = build(ScriptedModel("ok"), new GuardrailMiddleware(Seq(new Upper, new Spy(seen)), Nil))
    runInMemory(l.graph, "hello").completed
    seen.get(0) shouldBe "HELLO"
  }

  it should "collect every failure into one aggregated error" in {
    val l             = build(ScriptedModel("ok"), new GuardrailMiddleware(Seq(new Reject("A"), new Reject("B")), Nil))
    val (node, cause) = nodeFailure(runInMemory(l.graph, "hi"))
    node shouldBe NodeId("input")
    cause.message should include("Multiple validation failures")
    cause.message should include("A rejected")
    cause.message should include("B rejected")
  }

  it should "block an answer an output guardrail rejects" in {
    val l             = build(ScriptedModel("this is badword"), new GuardrailMiddleware(none, Seq(ProfanityFilter())))
    val (node, cause) = nodeFailure(runInMemory(l.graph, "hi"))
    node shouldBe NodeId("finish")
    cause.message should include("inappropriate")
    val l2 = build(ScriptedModel("fine"), new GuardrailMiddleware(none, Seq(new Reject("Z"))))
    nodeFailure(runInMemory(l2.graph, "hi"))._2.message should include("Z rejected")
  }

  it should "change the run's output when an output guardrail fixes it" in {
    val masked = build(ScriptedModel("ssn 123-45-6789"), new GuardrailMiddleware(none, Seq(PIIMasker())))
    val (_, a) = runInMemory(masked.graph, "hi").completed
    (a should not).include("123-45-6789")
    val marked = build(ScriptedModel("ok"), new GuardrailMiddleware(none, Seq(new Mark("!"), new Mark("?"))))
    runInMemory(marked.graph, "hi").completed._2 shouldBe "ok!?"
  }

  it should "pass the answer unchanged on Warn" in {
    val warn = PIIDetector(onFail = GuardrailAction.Warn)
    val l    = build(ScriptedModel("ssn 123-45-6789"), new GuardrailMiddleware(none, Seq(warn)))
    runInMemory(l.graph, "hi").completed._2 shouldBe "ssn 123-45-6789"
  }

  /** A judge that answers each score in turn. */
  final private class Judge(scores: String*) extends LLMClient {
    private val n = new AtomicInteger()
    override def complete(conversation: Conversation, options: CompletionOptions): Result[Completion] = {
      val s = scores(n.getAndIncrement())
      Right(Completion("id", 0L, s, "test", AssistantMessage(s)))
    }
    override def streamComplete(
      conversation: Conversation,
      options: CompletionOptions,
      onChunk: StreamedChunk => Unit
    ): Result[Completion] = complete(conversation, options)
    override def getContextWindow(): Int     = 4096
    override def getReserveCompletion(): Int = 1024
  }

  it should "run an LLM-as-judge output guardrail: pass, then block" in {
    val judge = LLMSafetyGuardrail(new Judge("0.95", "0.1"))
    val mw    = new GuardrailMiddleware(none, Seq(judge))
    runInMemory(build(ScriptedModel("safe"), mw).graph, "hi", thread = "a").completed._2 shouldBe "safe"
    val (node, _) = nodeFailure(runInMemory(build(ScriptedModel("unsafe"), mw).graph, "hi", thread = "b"))
    node shouldBe NodeId("finish")
  }
}
