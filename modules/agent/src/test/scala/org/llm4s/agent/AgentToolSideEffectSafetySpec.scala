package org.llm4s.agent

import org.llm4s.agent.AgentFixture._
import org.llm4s.agent.SpecTools.{ call, calling }
import org.llm4s.agent.graph._
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelRequest }
import org.llm4s.agent.graph.tool.ToolOutcome
import org.llm4s.agent.graph.toolloop.ToolResultRule
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model._
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters._

/**
 * Tool side-effect safety (#1703): idempotency keys, and the rule that no model request holds a
 * tool call without exactly one result straight after it. `AgentToolResultContractSpec` runs the
 * same through the OpenAI and Anthropic clients.
 */
class AgentToolSideEffectSafetySpec extends AnyFlatSpec with Matchers {

  private val thread = ThreadId("t")
  private val callId = ToolCallId("call_0")

  "IdempotencyKey.derive" should "be 64 lowercase hex characters, the same for the same call" in {
    val key = IdempotencyKey.derive(thread, "run/1", callId)
    (key.value should fullyMatch).regex("[0-9a-f]{64}")
    IdempotencyKey.derive(thread, "run/1", callId) shouldBe key
  }

  it should "differ when the thread, the checkpoint or the call differs" in {
    val key = IdempotencyKey.derive(thread, "run/1", callId)
    IdempotencyKey.derive(ThreadId("u"), "run/1", callId) should not be key
    IdempotencyKey.derive(thread, "run/2", callId) should not be key
    IdempotencyKey.derive(thread, "run/1", ToolCallId("call_1")) should not be key
  }

  it should "not confuse triples whose parts concatenate alike" in {
    IdempotencyKey.derive(ThreadId("ab"), "c", callId) should not be IdempotencyKey.derive(ThreadId("a"), "bc", callId)
  }

  it should "encode as a plain JSON string" in {
    val key = IdempotencyKey("k-1")
    upickle.default.write(key) shouldBe "\"k-1\""
    upickle.default.read[IdempotencyKey]("\"k-1\"") shouldBe key
  }

  "The key a tool sees" should "be derived from the thread, the model call's checkpoint and the call id" in {
    val seen = new ConcurrentLinkedQueue[(String, IdempotencyKey)]()
    val record = SpecTools.tool("record") { (_, context) =>
      seen.add(context.run.position.checkpointId -> context.idempotencyKey)
      ToolOutcome.Success(ujson.Str("ok"))
    }
    val runtime = GraphRuntime.inMemory()
    val client  = ScriptedLLMClient.of(calling(call("c1", "record")), CompletionFixture.simple("done"))
    val agent   = built(Agent.builder("assistant", client).withTools(SpecTools.set(record)).withRuntime(runtime))
    val result  = agent.run(thread, "go").value
    result.answer shouldBe Some("done")

    val (toolCheckpoint, key) = seen.asScala.toVector match {
      case Vector(one) => one
      case other       => fail(s"expected one run, got $other")
    }
    // checkpoints are numbered per run: the claim (input runs at it), then each superstep's; the model ran
    // at the one before the tool's
    val modelCheckpoint = s"${result.runId.value}/2"
    toolCheckpoint shouldBe s"${result.runId.value}/3"
    key shouldBe IdempotencyKey.derive(thread, modelCheckpoint, ToolCallId("c1"))
  }

  "A model message whose tool call ids repeat" should "be refused before it is stored; recover asks again" in {
    val ran = new java.util.concurrent.atomic.AtomicInteger()
    val lookup = SpecTools.tool("lookup") { (_, _) =>
      ran.incrementAndGet(); ToolOutcome.Success(ujson.Str("ok"))
    }
    val client = new ScriptedLLMClient(
      Right(calling(call("c1", "lookup"), call("c1", "lookup"))),
      Right(calling(call("c1", "lookup"), call("c2", "lookup"))),
      Right(CompletionFixture.simple("done"))
    )
    val agent = built(Agent.builder("assistant", client).withTools(SpecTools.set(lookup)))

    cause(agent.run(thread, "go").error) match {
      case e: ValidationError => e.message should include("tool call id 'c1' is used more than once")
      case other              => fail(s"expected a ValidationError, got $other")
    }
    ran.get shouldBe 0

    val done = agent.recover(thread).value
    done.answer shouldBe Some("done")
    ran.get shouldBe 2
    done.messages.collect { case a: AssistantMessage if a.toolCalls.nonEmpty => a.toolCalls.map(_.id) } shouldBe
      Vector(Seq("c1", "c2"))
  }

  "A model message with a blank tool call id" should "be refused before it is stored" in {
    val lookup = SpecTools.tool("lookup")((_, _) => ToolOutcome.Success(ujson.Str("ok")))
    val client = ScriptedLLMClient.of(calling(call(" ", "lookup")))
    val agent  = built(Agent.builder("assistant", client).withTools(SpecTools.set(lookup)))
    cause(agent.run(thread, "go").error).message should include("a tool call has a blank id")
  }

  "A model wrapper that drops a tool result" should "never reach the model" in {
    val dropping = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("drop-results")
      override def wrapModelCall(request: ModelRequest, context: RunContext)(
        next: ModelRequest => Result[Completion]
      ): Result[Completion] =
        next(request.withMessages(request.messages.filterNot(_.isInstanceOf[ToolMessage])))
    }
    val lookup = SpecTools.tool("lookup")((_, _) => ToolOutcome.Success(ujson.Str("ok")))
    val client = ScriptedLLMClient.of(calling(call("c1", "lookup")), CompletionFixture.simple("never"))
    val agent =
      built(Agent.builder("assistant", client).withTools(SpecTools.set(lookup)).withMiddleware(dropping))

    cause(agent.run(thread, "go").error).message should include("tool call 'c1' has no result straight after it")
    client.callCount shouldBe 1 // the first call had nothing to drop; the second was refused
  }

  "Imported history" should "be refused when a reused call id hides a call with no result" in {
    val history = Vector(
      UserMessage("one"),
      AssistantMessage(None, Seq(call("call_0", "lookup"))),
      ToolMessage("first", "call_0"),
      AssistantMessage("answer"),
      UserMessage("two"),
      AssistantMessage(None, Seq(call("call_0", "lookup")))
    )
    // the id-based check is satisfied by the first turn's result
    Message.validateConversation(history.toList) shouldBe Right(())
    val client = ScriptedLLMClient.of(CompletionFixture.simple("never"))
    val agent  = built(Agent.builder("assistant", client))
    agent.run(ThreadId("imported"), "three", RunConfig(), history).error.message should include(
      "tool call 'call_0' has no result straight after it"
    )
    client.callCount shouldBe 0
  }

  "ToolResultRule" should "accept results in any order within their run" in {
    val messages = Seq(
      UserMessage("go"),
      AssistantMessage(None, Seq(call("a", "x"), call("b", "x"))),
      ToolMessage("b", "b"),
      ToolMessage("a", "a")
    )
    ToolResultRule.check(messages) shouldBe Right(())
  }

  it should "report a second result, a stray result and an orphan tool message" in {
    val messages = Seq(
      UserMessage("go"),
      AssistantMessage(None, Seq(call("a", "x"))),
      ToolMessage("1", "a"),
      ToolMessage("2", "a"),
      ToolMessage("3", "q"),
      UserMessage("more"),
      ToolMessage("4", "a")
    )
    ToolResultRule.violations(messages) shouldBe Vector(
      "message 1: tool call 'a' has 2 results",
      "message 1: a tool result for 'q' answers none of its calls",
      "message 6: a tool result follows no assistant message with tool calls"
    )
    ToolResultRule.check(messages).isLeft shouldBe true
  }
}
