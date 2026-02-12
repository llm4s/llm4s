package org.llm4s.sc3

import org.llm4s.agent.{Agent, AgentStatus}
import org.llm4s.error.ServiceError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model._
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class RobustnessCrossTest extends AnyFlatSpec with Matchers {

  "ServiceError" should "mark server (5xx) errors as recoverable" in {
    val error503 = ServiceError(503, "provider", "Service unavailable")
    // Using canonical API as requested
    error503.isRecoverableStatus shouldBe true
  }

  it should "treat rate limit (429) as recoverable" in {
    ServiceError(429, "provider", "Rate limit exceeded").isRecoverableStatus shouldBe true
  }

  "Agent" should "handle malformed JSON responses gracefully" in {
    // Definining helper inside
    def mockCompletion(content: String): Completion = Completion(
      id = "test-id",
      created = System.currentTimeMillis(),
      content = content,
      model = "test-model",
      message = AssistantMessage(contentOpt = Some(content), toolCalls = Nil)
    )

    val malformedJsonClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        val badToolCall = ToolCall("call_1", "real_tool", ujson.Str("{invalid_json}"))
        // Calling mockCompletion here makes the definition "Used"
        val base = mockCompletion("") 
        Right(base.copy(
          message = AssistantMessage(contentOpt = Some(""), toolCalls = List(badToolCall)),
          toolCalls = List(badToolCall)
        ))
      }
      override def streamComplete(c: Conversation, o: CompletionOptions, cb: StreamedChunk => Unit): Result[Completion] = 
        complete(c, o)
      override def getContextWindow(): Int = 4000
      override def getReserveCompletion(): Int = 1000
    }

    val agent = new Agent(malformedJsonClient)
    val state = agent.initialize("test", new ToolRegistry(Nil))
    
    noException should be thrownBy agent.runStep(state)
  }

  it should "handle hallucinated tool calls" in {
    val hallucinatedToolClient = new LLMClient {
      override def complete(c: Conversation, o: CompletionOptions): Result[Completion] = {
        val ghostToolCall = ToolCall("call_123", "ghost_tool", ujson.Obj())
        val completion = Completion(
          id = "test-hallucinated",
          created = System.currentTimeMillis(),
          content = "",
          model = "test-model",
          // List and contentOpt for cross-version parity
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
}