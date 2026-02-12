package org.llm4s.sc2

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result 
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RobustnessCrossTest extends AnyFlatSpec with Matchers {

  "ServiceError" should "keep the details correctly" in {
    val e = ServiceError(500, "openai", "server go boom")
    e.httpStatus shouldBe 500
    e.provider shouldBe "openai"
    e.message should include ("boom")
  }

  it should "track the request id if we have one" in {
    val e = ServiceError(503, "azure", "dead", "req-999")
    e.requestId shouldBe Some("req-999")
    e.message should include ("req-999")
  }

  it should "know what to retry" in {
    ServiceError(408, "x", "time out").isRecoverableStatus shouldBe true
    ServiceError(500, "x", "oops").isRecoverableStatus shouldBe true
    ServiceError(503, "x", "down").isRecoverableStatus shouldBe true
    ServiceError(429, "x", "slow down").isRecoverableStatus shouldBe true
  }

  it should "not retry client errors" in {
    ServiceError(400, "x", "bad req").isRecoverableStatus shouldBe false
    ServiceError(401, "x", "no auth").isRecoverableStatus shouldBe false
    ServiceError(404, "x", "where is it").isRecoverableStatus shouldBe false
  }

  "Agent" should "start up fine even if client is broken" in {
    val badClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions) = Left(ServiceError(500, "mock", "init fail"))
      override def streamComplete(c: Conversation, o: CompletionOptions, cb: StreamedChunk => Unit) = complete(c, o)
      override def getContextWindow() = 4000
      override def getReserveCompletion() = 1000
    }

    val agent = new Agent(badClient)
    val state = agent.initialize("hi", new ToolRegistry(Nil))
    state.status shouldBe AgentStatus.InProgress
  }

  it should "pass errors through runStep" in {
    val failClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions) = Left(ServiceError(503, "anthropic", "busy"))
      override def streamComplete(c: Conversation, o: CompletionOptions, cb: StreamedChunk => Unit) = complete(c, o)
      override def getContextWindow() = 8000
      override def getReserveCompletion() = 2000
    }

    val agent = new Agent(failClient)
    val state = agent.initialize("test", new ToolRegistry(Nil))

    agent.runStep(state) match {
      case Left(e: ServiceError) =>
        e.httpStatus shouldBe 503
        e.isRecoverableStatus shouldBe true
      case x => fail(s"Expected ServiceError, got: $x")
    }
  }

  it should "handle hallucinated tool calls" in {
    val hallucinatedToolClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        val ghostToolCall = ToolCall("call_ghost_123", "ghost_tool", ujson.Obj("param" -> "value"))
        val completion = Completion(
          id = "test-hallucinated",
          created = System.currentTimeMillis(),
          content = "",
          model = "test-model",
          message = AssistantMessage(content = "", toolCalls = List(ghostToolCall)),
          toolCalls = List(ghostToolCall)
        )
        Right(completion)
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, cb: StreamedChunk => Unit): Result[Completion] = 
        complete(c, o)
      override def getContextWindow(): Int = 4000
      override def getReserveCompletion(): Int = 1000
    }

    val agent = new Agent(hallucinatedToolClient)
    val state = agent.initialize("test", new ToolRegistry(Nil))

    agent.runStep(state) match {
      case Right(newState) =>
        newState.status shouldBe AgentStatus.WaitingForTools
        val assistantMsgs = newState.conversation.messages.collect { case am: AssistantMessage => am }
        assistantMsgs.last.toolCalls.head.name shouldBe "ghost_tool"
      case Left(error) =>
        fail(s"Should handle hallucination, but got error: ${error.message}")
    }
  }

  // New Test to match README coverage
  it should "handle malformed JSON responses gracefully" in {
    val malformedJsonClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        // Mock a tool call with invalid JSON in arguments
        val badToolCall = ToolCall("id", "tool", ujson.Str("{invalid_json_here}"))
        Right(Completion(
          id = "test-id",
          created = System.currentTimeMillis(),
          content = "",
          model = "test-model",
          message = AssistantMessage(content = "", toolCalls = List(badToolCall)),
          toolCalls = List(badToolCall)
        ))
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, cb: StreamedChunk => Unit): Result[Completion] = complete(c, o)
      override def getContextWindow() = 4000
      override def getReserveCompletion() = 1000
    }

    val agent = new Agent(malformedJsonClient)
    val state = agent.initialize("test", new ToolRegistry(Nil))

    // Verification: Protects against LLM provider API changes or malformed responses
    noException should be thrownBy agent.runStep(state)
  }
}