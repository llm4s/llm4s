package org.llm4s.zio

import org.llm4s.agent.{ Agent, ThreadStatus }
import org.llm4s.error.SimpleError
import org.llm4s.llmconnect.LLMClient
import org.llm4s.llmconnect.model.{ AssistantMessage, Completion, CompletionOptions, Conversation, StreamedChunk }
import org.llm4s.toolapi.ToolRegistry
import org.llm4s.types.Result
import zio.test.*

object AgentZSpec extends ZIOSpecDefault {

  private def completion(text: String) = Completion(
    id = "test-id",
    created = 0L,
    content = text,
    model = "test-model",
    message = AssistantMessage(contentOpt = Some(text))
  )

  private def successClient(text: String): LLMClient = new LLMClient {
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Right(completion(text))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      Right(completion(text))
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }

  private val failingClient: LLMClient = new LLMClient {
    def complete(c: Conversation, o: CompletionOptions): Result[Completion] =
      Left(SimpleError("agent-fail"))
    def streamComplete(c: Conversation, o: CompletionOptions, onChunk: StreamedChunk => Unit): Result[Completion] =
      Left(SimpleError("agent-fail"))
    def getContextWindow(): Int     = 4096
    def getReserveCompletion(): Int = 256
  }

  val spec = suite("AgentZ")(
    test("run returns AgentThread with Completed status when the agent finishes") {
      for {
        state <- AgentZ(new Agent(successClient("4"))).run("What is 2+2?", ToolRegistry.empty)
      } yield assertTrue(state.status == ThreadStatus.Completed)
    },
    test("run includes the query in the conversation history") {
      for {
        state <- AgentZ(new Agent(successClient("answer"))).run("my query", ToolRegistry.empty)
      } yield {
        val messages = state.messages.map(_.content)
        assertTrue(messages.contains("my query"))
      }
    },
    test("run propagates LLMError on failure") {
      AgentZ(new Agent(failingClient))
        .run("What is 2+2?", ToolRegistry.empty)
        .flip
        .map(err => assertTrue(err == SimpleError("agent-fail")))
    },
    test("continueConversation returns AgentThread with Completed status") {
      for {
        s1 <- AgentZ(new Agent(successClient("4"))).run("What is 2+2?", ToolRegistry.empty)
        s2 <- AgentZ(new Agent(successClient("6"))).continueConversation(s1, "And 3+3?", ToolRegistry.empty)
      } yield assertTrue(s2.status == ThreadStatus.Completed)
    },
    test("continueConversation appends follow-up to conversation history") {
      for {
        s1 <- AgentZ(new Agent(successClient("4"))).run("First question", ToolRegistry.empty)
        s2 <- AgentZ(new Agent(successClient("6"))).continueConversation(s1, "Second question", ToolRegistry.empty)
      } yield {
        val messages = s2.messages.map(_.content)
        assertTrue(messages.contains("Second question"))
      }
    },
    test("continueConversation propagates LLMError on failure") {
      for {
        s1  <- AgentZ(new Agent(successClient("4"))).run("First", ToolRegistry.empty)
        err <- AgentZ(new Agent(failingClient)).continueConversation(s1, "Follow-up", ToolRegistry.empty).flip
      } yield assertTrue(err == SimpleError("agent-fail"))
    }
  )
}
