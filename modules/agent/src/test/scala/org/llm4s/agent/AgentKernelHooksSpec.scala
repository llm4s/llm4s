package org.llm4s.agent

import org.llm4s.agent.AgentFixture.built
import org.llm4s.agent.graph.middleware.{ AgentMiddleware, MiddlewareId, ModelContext, ModelRequest, ModelResponse }
import org.llm4s.error.ValidationError
import org.llm4s.llmconnect.model.{ Completion, TokenUsage }
import org.llm4s.trace.{ TraceEvent, Tracing }
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.util.concurrent.ConcurrentLinkedQueue
import scala.jdk.CollectionConverters.*

/** The agent-level side of Stage 3 slice C1 (#1902, design §4.22). */
class AgentKernelHooksSpec extends AnyFlatSpec with Matchers {

  final private class Recording extends Tracing {
    private val events                              = new ConcurrentLinkedQueue[TraceEvent]()
    def traceEvent(event: TraceEvent): Result[Unit] = { events.add(event); Right(()) }
    def traceToolCall(toolName: String, input: String, output: String): Result[Unit]       = Right(())
    def traceError(error: Throwable, context: String): Result[Unit]                        = Right(())
    def traceCompletion(completion: Completion, model: String): Result[Unit]               = Right(())
    def traceTokenUsage(usage: TokenUsage, model: String, operation: String): Result[Unit] = Right(())
    def ended: Vector[TraceEvent.AgentRunEnded] =
      events.asScala.toVector.collect { case e: TraceEvent.AgentRunEnded => e }
  }

  "A traced run whose model step fails" should "trace the usage of the calls the failed step made" in {
    val refusing = new AgentMiddleware {
      val id: MiddlewareId = MiddlewareId("refusing")
      override def wrapModelCall(request: ModelRequest, context: ModelContext)(
        next: ModelRequest => Result[ModelResponse]
      ): Result[ModelResponse] = next(request).flatMap(_ => Left(ValidationError("check", "refused")))
    }
    val tracing = new Recording
    val client  = ScriptedLLMClient.of(CompletionFixture.simple("answer"))
    val agent   = built(Agent.builder("assistant", client).withMiddleware(refusing).withTracing(tracing))

    agent.run("q").isLeft shouldBe true
    client.callCount shouldBe 1
    tracing.ended.map(e => (e.status, e.usage.requestCount, e.usage.inputTokens)) shouldBe Vector(("failed", 1L, 10L))
  }

  "AgentBuilder.withMaxPromptSectionTokens" should "refuse a budget below 1" in {
    Agent
      .builder("assistant", ScriptedLLMClient.of())
      .withMaxPromptSectionTokens(0)
      .build()
      .left
      .toOption
      .map(_.message)
      .getOrElse(fail("built")) should include("maxPromptSectionTokens")
  }
}
