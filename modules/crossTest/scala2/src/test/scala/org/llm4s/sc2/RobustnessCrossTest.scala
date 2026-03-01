package org.llm4s.sc2

import org.llm4s.agent.{ Agent, AgentStatus }
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk, ToolCall }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * Comprehensive cross-version robustness test suite for the LLM4S Agent framework.
 * 
 * Ensures that the Agent can gracefully recover from LLM failures, hallucinations, and network-related issues.
 * 
 * Test Coverage:
 * 1. **ServiceError Handling**: Validates cross-version status code handling for HTTP errors.
 *    Included here to verify that ServiceError.isRecoverableStatus behaves identically across Scala 2.13 and 3.x.
 *    - Recoverable: 408 (Timeout), 5xx (Server Error), 429 (Rate Limit)
 *    - Non-Recoverable: 400 (Bad Request), 401 (Unauthorized), 404 (Not Found)
 * 
 * 2. **Malformed JSON Response**: Protects against LLM providers returning invalid JSON in tool call arguments.
 *    Verifies the Agent handles corrupted data packets without crashing.
 * 
 * 3. **Hallucinated Tool Names**: Prevents crashes when the LLM attempts to call non-existent tools.
 *    Confirms the Agent transitions to WaitingForTools state and captures the hallucination in conversation history.
 * 
 * 4. **Agent Lifecycle Robustness**: Ensures the Agent remains stable even if the initial LLM connection fails.
 *    Validates that errors are correctly propagated through runStep as Left(ServiceError).
 * 
 * 5. **Cross-Version Compatibility**: Logic is 100% identical between Scala 2 and Scala 3 modules,
 *    using consistent List types and canonical helpers for identical behavior across environments.
 * 
 * Run with:
 * sbt "++2.13.16 crossTestScala2/testOnly org.llm4s.sc2.RobustnessCrossTest"
 */
class RobustnessCrossTest extends AnyFlatSpec with Matchers {

  // ServiceError tests: Verified here for cross-version consistency in HTTP status handling
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
    val state = agent.initializeSafe("hi", new ToolRegistry(Nil))
      .fold(e => fail(s"initializeSafe failed: ${e.message}"), s => s)
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
    val state = agent.initializeSafe("test", new ToolRegistry(Nil))
      .fold(e => fail(s"initializeSafe failed: ${e.message}"), s => s)

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
          message = AssistantMessage(contentOpt = None, toolCalls = List(ghostToolCall)),
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
    val state = agent.initializeSafe("test", new ToolRegistry(Nil))
      .fold(e => fail(s"initializeSafe failed: ${e.message}"), s => s)

    agent.runStep(state) match {
      case Right(newState) =>
        newState.status shouldBe AgentStatus.WaitingForTools
        val assistantMsgs = newState.conversation.messages.collect { case am: AssistantMessage => am }
        assistantMsgs.lastOption
          .flatMap(_.toolCalls.headOption)
          .map(_.name) shouldBe Some("ghost_tool")
      case Left(error) =>
        fail(s"Should handle hallucination, but got error: ${error.message}")
    }
  }

  it should "handle malformed JSON responses gracefully" in {
    val malformedJsonClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        val badToolCall = ToolCall("id", "tool", ujson.Str("{invalid_json_here}"))
        Right(Completion(
          id = "test-id",
          created = System.currentTimeMillis(),
          content = "",
          model = "test-model",
          message = AssistantMessage(contentOpt = None, toolCalls = List(badToolCall)),
          toolCalls = List(badToolCall)
        ))
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, cb: StreamedChunk => Unit): Result[Completion] = complete(c, o)
      override def getContextWindow() = 4000
      override def getReserveCompletion() = 1000
    }

    val agent = new Agent(malformedJsonClient)
    val state = agent.initializeSafe("test", new ToolRegistry(Nil))
      .fold(e => fail(s"initializeSafe failed: ${e.message}"), s => s)

    agent.runStep(state) match {
      case Right(newState) =>
        newState.status shouldBe AgentStatus.WaitingForTools
      case Left(error) =>
        fail(s"Expected successful handling of malformed JSON, got error: ${error.message}")
    }
  }
}
